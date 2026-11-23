#define _POSIX_C_SOURCE 200809L

#include "synk.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define WATCH_MASK (IN_CLOSE_WRITE | IN_MOVED_TO | IN_MOVED_FROM | IN_DELETE | IN_CREATE | IN_ATTRIB)

typedef struct {
    int   wd;
    char *rel_path; /* "" for root_dir, otherwise "sub/dir" */
} WatchEntry;

struct SynkWatcher {
    char          *root_dir;
    char         **ignore_patterns;
    size_t         ignore_count;
    int            debounce_ms;
    synk_watch_cb  cb;
    void          *userdata;

    int            inotify_fd;
    int            stop_pipe[2];
    pthread_t      thread;
    int            thread_started;

    WatchEntry    *watches;
    size_t         watch_count;
    size_t         watch_cap;
};

static char *w_strdup(const char *s) {
    if (!s) return NULL;
    size_t n = strlen(s);
    char *p = (char *)malloc(n + 1);
    if (p) memcpy(p, s, n + 1);
    return p;
}

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000LL);
}

static void set_nonblock_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    int fdflags = fcntl(fd, F_GETFD, 0);
    if (fdflags >= 0) fcntl(fd, F_SETFD, fdflags | FD_CLOEXEC);
}

static int watch_table_put(SynkWatcher *w, int wd, const char *rel_path) {
    for (size_t i = 0; i < w->watch_count; ++i) {
        if (w->watches[i].wd == wd) {
            free(w->watches[i].rel_path);
            w->watches[i].rel_path = w_strdup(rel_path);
            return 0;
        }
    }
    if (w->watch_count == w->watch_cap) {
        size_t new_cap = w->watch_cap == 0 ? 32 : w->watch_cap * 2;
        WatchEntry *grown = (WatchEntry *)realloc(w->watches, new_cap * sizeof(WatchEntry));
        if (!grown) return -1;
        w->watches = grown;
        w->watch_cap = new_cap;
    }
    w->watches[w->watch_count].wd = wd;
    w->watches[w->watch_count].rel_path = w_strdup(rel_path);
    if (!w->watches[w->watch_count].rel_path) return -1;
    w->watch_count++;
    return 0;
}

static const char *watch_table_get(SynkWatcher *w, int wd) {
    for (size_t i = 0; i < w->watch_count; ++i) {
        if (w->watches[i].wd == wd) return w->watches[i].rel_path;
    }
    return NULL;
}

static void watch_table_remove(SynkWatcher *w, int wd) {
    for (size_t i = 0; i < w->watch_count; ++i) {
        if (w->watches[i].wd == wd) {
            free(w->watches[i].rel_path);
            w->watches[i] = w->watches[w->watch_count - 1];
            w->watch_count--;
            return;
        }
    }
}

static void add_watch_recursive(SynkWatcher *w, const char *abs_dir, const char *rel_dir, int depth) {
    if (depth > 48) return;
    int wd = inotify_add_watch(w->inotify_fd, abs_dir, WATCH_MASK);
    if (wd < 0) return;
    watch_table_put(w, wd, rel_dir);

    DIR *dir = opendir(abs_dir);
    if (!dir) return;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        const char *name = ent->d_name;
        if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) continue;

        size_t abs_len = strlen(abs_dir) + 1 + strlen(name) + 1;
        char *abs_child = (char *)malloc(abs_len);
        size_t rel_len = (rel_dir[0] ? strlen(rel_dir) + 1 : 0) + strlen(name) + 1;
        char *rel_child = (char *)malloc(rel_len);
        if (!abs_child || !rel_child) {
            free(abs_child);
            free(rel_child);
            continue;
        }
        snprintf(abs_child, abs_len, "%s/%s", abs_dir, name);
        if (rel_dir[0]) snprintf(rel_child, rel_len, "%s/%s", rel_dir, name);
        else snprintf(rel_child, rel_len, "%s", name);

        struct stat st;
        if (lstat(abs_child, &st) == 0 && S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode)) {
            if (!synk_is_ignored(rel_child, 1, (const char **)w->ignore_patterns, w->ignore_count)) {
                add_watch_recursive(w, abs_child, rel_child, depth + 1);
            }
        }
        free(abs_child);
        free(rel_child);
    }
    closedir(dir);
}

static void *watcher_thread_main(void *arg) {
    SynkWatcher *w = (SynkWatcher *)arg;
    add_watch_recursive(w, w->root_dir, "", 0);

    struct pollfd pfds[2];
    pfds[0].fd = w->stop_pipe[0];
    pfds[0].events = POLLIN;
    pfds[1].fd = w->inotify_fd;
    pfds[1].events = POLLIN;

    int pending_events = 0;
    int64_t deadline_ms = 0;
    char last_path[1024];
    last_path[0] = '\0';

    uint8_t evbuf[8192] __attribute__((aligned(__alignof__(struct inotify_event))));

    for (;;) {
        int timeout_ms = -1;
        if (pending_events > 0) {
            int64_t now = monotonic_ms();
            int64_t rem = deadline_ms - now;
            timeout_ms = rem > 0 ? (int)rem : 0;
        }

        int rc = poll(pfds, 2, timeout_ms);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (pfds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            break;
        }

        if (rc == 0 && pending_events > 0) {
            int count = pending_events;
            pending_events = 0;
            if (w->cb) {
                w->cb(w->userdata, last_path, count);
            }
            continue;
        }

        if (pfds[1].revents & POLLIN) {
            for (;;) {
                ssize_t n = read(w->inotify_fd, evbuf, sizeof(evbuf));
                if (n <= 0) break;

                size_t offset = 0;
                while (offset < (size_t)n) {
                    const struct inotify_event *ev = (const struct inotify_event *)(evbuf + offset);
                    offset += sizeof(struct inotify_event) + ev->len;

                    if (ev->mask & IN_IGNORED) {
                        watch_table_remove(w, ev->wd);
                        continue;
                    }
                    if (ev->mask & IN_Q_OVERFLOW) {
                        pending_events++;
                        strncpy(last_path, "*", sizeof(last_path) - 1);
                        deadline_ms = monotonic_ms() + w->debounce_ms;
                        continue;
                    }

                    const char *dir_rel = watch_table_get(w, ev->wd);
                    if (!dir_rel || ev->len == 0) continue;

                    char rel_path[1024];
                    if (dir_rel[0]) {
                        snprintf(rel_path, sizeof(rel_path), "%s/%s", dir_rel, ev->name);
                    } else {
                        snprintf(rel_path, sizeof(rel_path), "%s", ev->name);
                    }

                    int is_dir = (ev->mask & IN_ISDIR) ? 1 : 0;
                    if (synk_is_ignored(rel_path, is_dir,
                                        (const char **)w->ignore_patterns, w->ignore_count)) {
                        continue;
                    }

                    if (is_dir && (ev->mask & (IN_CREATE | IN_MOVED_TO))) {
                        size_t abs_len = strlen(w->root_dir) + 1 + strlen(rel_path) + 1;
                        char *abs_sub = (char *)malloc(abs_len);
                        if (abs_sub) {
                            snprintf(abs_sub, abs_len, "%s/%s", w->root_dir, rel_path);
                            add_watch_recursive(w, abs_sub, rel_path, 1);
                            free(abs_sub);
                        }
                    }

                    pending_events++;
                    strncpy(last_path, rel_path, sizeof(last_path) - 1);
                    last_path[sizeof(last_path) - 1] = '\0';
                    deadline_ms = monotonic_ms() + w->debounce_ms;
                }
            }
        }
    }

    return NULL;
}

SynkWatcher *synk_watcher_start(const char *root_dir,
                                const char **ignore_patterns, size_t ignore_count,
                                int debounce_ms,
                                synk_watch_cb cb, void *userdata) {
    if (!root_dir || !cb) return NULL;
    struct stat st;
    if (stat(root_dir, &st) != 0 || !S_ISDIR(st.st_mode)) return NULL;

    SynkWatcher *w = (SynkWatcher *)calloc(1, sizeof(SynkWatcher));
    if (!w) return NULL;
    w->inotify_fd = -1;
    w->stop_pipe[0] = -1;
    w->stop_pipe[1] = -1;

    w->root_dir = w_strdup(root_dir);
    w->debounce_ms = debounce_ms > 50 ? debounce_ms : 50;
    w->cb = cb;
    w->userdata = userdata;

    if (ignore_count > 0 && ignore_patterns) {
        w->ignore_patterns = (char **)calloc(ignore_count, sizeof(char *));
        if (!w->ignore_patterns) goto fail;
        w->ignore_count = ignore_count;
        for (size_t i = 0; i < ignore_count; ++i) {
            if (ignore_patterns[i]) {
                w->ignore_patterns[i] = w_strdup(ignore_patterns[i]);
            }
        }
    }

    w->inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (w->inotify_fd < 0) goto fail;

    if (pipe(w->stop_pipe) != 0) goto fail;
    set_nonblock_cloexec(w->stop_pipe[0]);
    set_nonblock_cloexec(w->stop_pipe[1]);

    if (pthread_create(&w->thread, NULL, watcher_thread_main, w) != 0) goto fail;
    w->thread_started = 1;
    return w;

fail:
    synk_watcher_stop(w);
    return NULL;
}

void synk_watcher_stop(SynkWatcher *w) {
    if (!w) return;
    if (w->thread_started) {
        char b = 1;
        ssize_t ignored = write(w->stop_pipe[1], &b, 1);
        (void)ignored;
        pthread_join(w->thread, NULL);
        w->thread_started = 0;
    }
    if (w->inotify_fd >= 0) close(w->inotify_fd);
    if (w->stop_pipe[0] >= 0) close(w->stop_pipe[0]);
    if (w->stop_pipe[1] >= 0) close(w->stop_pipe[1]);
    for (size_t i = 0; i < w->watch_count; ++i) {
        free(w->watches[i].rel_path);
    }
    free(w->watches);
    for (size_t i = 0; i < w->ignore_count; ++i) {
        free(w->ignore_patterns[i]);
    }
    free(w->ignore_patterns);
    free(w->root_dir);
    free(w);
}
