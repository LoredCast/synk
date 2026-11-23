#define _POSIX_C_SOURCE 200809L

#include "synk.h"

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static char *synk_strdup(const char *s) {
    if (!s) return NULL;
    size_t n = strlen(s);
    char *copy = (char *)malloc(n + 1);
    if (copy) memcpy(copy, s, n + 1);
    return copy;
}

void synk_entry_list_init(SynkEntryList *list) {
    if (!list) return;
    list->items = NULL;
    list->count = 0;
    list->capacity = 0;
}

int synk_entry_list_add(SynkEntryList *list, const char *rel_path, int is_dir,
                        int64_t size, int64_t mtime_ms,
                        const char *hash, const char *etag) {
    if (!list || !rel_path) return -1;
    if (list->count == list->capacity) {
        size_t new_cap = list->capacity == 0 ? 64 : list->capacity * 2;
        SynkFileEntry *grown = (SynkFileEntry *)realloc(list->items, new_cap * sizeof(SynkFileEntry));
        if (!grown) return -1;
        list->items = grown;
        list->capacity = new_cap;
    }
    SynkFileEntry *e = &list->items[list->count];
    memset(e, 0, sizeof(*e));
    e->rel_path = synk_strdup(rel_path);
    if (!e->rel_path) return -1;
    e->is_dir = is_dir ? 1 : 0;
    e->size = is_dir ? 0 : size;
    e->mtime_ms = mtime_ms;
    if (hash && hash[0]) {
        strncpy(e->hash, hash, SYNK_SHA256_HEX_LEN - 1);
        e->hash[SYNK_SHA256_HEX_LEN - 1] = '\0';
    }
    if (etag && etag[0]) {
        strncpy(e->etag, etag, SYNK_ETAG_MAX_LEN - 1);
        e->etag[SYNK_ETAG_MAX_LEN - 1] = '\0';
    }
    list->count++;
    return 0;
}

static int cmp_entry_path(const void *a, const void *b) {
    const SynkFileEntry *ea = (const SynkFileEntry *)a;
    const SynkFileEntry *eb = (const SynkFileEntry *)b;
    return strcmp(ea->rel_path, eb->rel_path);
}

void synk_entry_list_sort(SynkEntryList *list) {
    if (!list || list->count <= 1) return;
    qsort(list->items, list->count, sizeof(SynkFileEntry), cmp_entry_path);
}

void synk_entry_list_free(SynkEntryList *list) {
    if (!list) return;
    for (size_t i = 0; i < list->count; ++i) {
        free(list->items[i].rel_path);
    }
    free(list->items);
    list->items = NULL;
    list->count = 0;
    list->capacity = 0;
}

/* Glob matcher supporting '*', '?', and '**' across path segments */
static int glob_match_impl(const char *p, const char *s) {
    while (*p) {
        if (p[0] == '*' && p[1] == '*') {
            while (p[0] == '*') p++;
            if (*p == '/') p++;
            if (*p == '\0') return 1;
            const char *t = s;
            for (;;) {
                if (glob_match_impl(p, t)) return 1;
                if (*t == '\0') break;
                t++;
            }
            return 0;
        } else if (*p == '*') {
            p++;
            const char *t = s;
            for (;;) {
                if (glob_match_impl(p, t)) return 1;
                if (*t == '\0' || *t == '/') break;
                t++;
            }
            return 0;
        } else if (*p == '?') {
            if (*s == '\0' || *s == '/') return 0;
            p++;
            s++;
        } else {
            if (*p != *s) return 0;
            p++;
            s++;
        }
    }
    return *s == '\0';
}

int synk_glob_match(const char *pattern, const char *rel_path, int is_dir) {
    if (!pattern || !rel_path) return 0;
    while (*pattern == ' ' || *pattern == '\t') pattern++;
    if (*pattern == '\0' || *pattern == '#') return 0;

    size_t plen = strlen(pattern);
    while (plen > 0 && (pattern[plen - 1] == '\r' || pattern[plen - 1] == '\n' ||
                        pattern[plen - 1] == ' ' || pattern[plen - 1] == '\t')) {
        plen--;
    }
    if (plen == 0) return 0;

    int dir_only = 0;
    if (pattern[plen - 1] == '/') {
        dir_only = 1;
        plen--;
    }
    if (plen == 0) return 0;

    int anchored = 0;
    if (pattern[0] == '/') {
        anchored = 1;
        pattern++;
        plen--;
    }
    if (plen == 0) return 0;

    char pat_buf[512];
    if (plen >= sizeof(pat_buf)) plen = sizeof(pat_buf) - 1;
    memcpy(pat_buf, pattern, plen);
    pat_buf[plen] = '\0';

    if (dir_only && !is_dir) {
        /* Also ignore files inside an ignored directory prefix */
        const char *slash = strchr(rel_path, '/');
        if (!slash) return 0;
        /* Check each directory ancestor */
        const char *cur = rel_path;
        while ((slash = strchr(cur, '/')) != NULL) {
            size_t prefix_len = (size_t)(slash - rel_path);
            char ancestor[1024];
            if (prefix_len < sizeof(ancestor)) {
                memcpy(ancestor, rel_path, prefix_len);
                ancestor[prefix_len] = '\0';
                if (synk_glob_match(pattern, ancestor, 1)) return 1;
            }
            cur = slash + 1;
        }
        return 0;
    }

    int has_slash = (strchr(pat_buf, '/') != NULL);
    if (anchored || has_slash) {
        return glob_match_impl(pat_buf, rel_path);
    }

    /* Match against basename OR any path segment */
    const char *base = strrchr(rel_path, '/');
    base = base ? base + 1 : rel_path;
    if (glob_match_impl(pat_buf, base)) return 1;

    /* Check ancestor segments as well (e.g., pattern ".git" ignores ".git/config") */
    const char *seg = rel_path;
    while (*seg) {
        const char *slash = strchr(seg, '/');
        if (!slash) break;
        size_t slen = (size_t)(slash - seg);
        char sbuf[256];
        if (slen < sizeof(sbuf)) {
            memcpy(sbuf, seg, slen);
            sbuf[slen] = '\0';
            if (glob_match_impl(pat_buf, sbuf)) return 1;
        }
        seg = slash + 1;
    }
    return 0;
}

int synk_is_ignored(const char *rel_path, int is_dir,
                    const char **patterns, size_t pattern_count) {
    if (!rel_path || !rel_path[0]) return 1;

    /* Always ignore internal Synk temporary files */
    const char *base = strrchr(rel_path, '/');
    base = base ? base + 1 : rel_path;
    if (strncmp(base, ".synk.tmp.", 10) == 0) return 1;
    if (strcmp(base, ".synk_base_cache") == 0) return 1;

    for (size_t i = 0; i < pattern_count; ++i) {
        if (patterns[i] && synk_glob_match(patterns[i], rel_path, is_dir)) {
            return 1;
        }
    }
    return 0;
}

static int scan_recursive(const char *abs_dir, const char *rel_prefix,
                          const char **ignore_patterns, size_t ignore_count,
                          int compute_hashes, SynkEntryList *out_list, int depth) {
    if (depth > 64) return 0; /* safety guard */

    DIR *dir = opendir(abs_dir);
    if (!dir) return -1;

    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        const char *name = ent->d_name;
        if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) continue;

        size_t abs_len = strlen(abs_dir) + 1 + strlen(name) + 1;
        char *abs_child = (char *)malloc(abs_len);
        if (!abs_child) {
            closedir(dir);
            return -1;
        }
        snprintf(abs_child, abs_len, "%s/%s", abs_dir, name);

        size_t rel_len = (rel_prefix && rel_prefix[0] ? strlen(rel_prefix) + 1 : 0) + strlen(name) + 1;
        char *rel_child = (char *)malloc(rel_len);
        if (!rel_child) {
            free(abs_child);
            closedir(dir);
            return -1;
        }
        if (rel_prefix && rel_prefix[0]) {
            snprintf(rel_child, rel_len, "%s/%s", rel_prefix, name);
        } else {
            snprintf(rel_child, rel_len, "%s", name);
        }

        struct stat st;
        if (lstat(abs_child, &st) != 0) {
            free(abs_child);
            free(rel_child);
            continue;
        }

        /* Skip symlinks and special devices */
        if (S_ISLNK(st.st_mode) || (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode))) {
            free(abs_child);
            free(rel_child);
            continue;
        }

        int is_dir = S_ISDIR(st.st_mode) ? 1 : 0;
        if (synk_is_ignored(rel_child, is_dir, ignore_patterns, ignore_count)) {
            free(abs_child);
            free(rel_child);
            continue;
        }

        int64_t mtime_ms = (int64_t)st.st_mtim.tv_sec * 1000LL + (int64_t)(st.st_mtim.tv_nsec / 1000000LL);
        char hash[SYNK_SHA256_HEX_LEN];
        hash[0] = '\0';

        if (!is_dir && compute_hashes) {
            synk_sha256_file(abs_child, hash);
        }

        if (synk_entry_list_add(out_list, rel_child, is_dir,
                                is_dir ? 0 : (int64_t)st.st_size,
                                mtime_ms, hash, "") != 0) {
            free(abs_child);
            free(rel_child);
            closedir(dir);
            return -1;
        }

        if (is_dir) {
            int rc = scan_recursive(abs_child, rel_child, ignore_patterns, ignore_count,
                                    compute_hashes, out_list, depth + 1);
            if (rc != 0) {
                /* Keep scanning siblings even if a subdir is unreadable */
            }
        }

        free(abs_child);
        free(rel_child);
    }

    closedir(dir);
    return 0;
}

int synk_scan_dir(const char *root_dir,
                  const char **ignore_patterns, size_t ignore_count,
                  int compute_hashes,
                  SynkEntryList *out_list) {
    if (!root_dir || !out_list) return -1;
    synk_entry_list_init(out_list);

    struct stat st;
    if (stat(root_dir, &st) != 0 || !S_ISDIR(st.st_mode)) {
        return -1;
    }

    int rc = scan_recursive(root_dir, "", ignore_patterns, ignore_count,
                            compute_hashes, out_list, 0);
    if (rc != 0) {
        synk_entry_list_free(out_list);
        return -1;
    }
    synk_entry_list_sort(out_list);
    return 0;
}
