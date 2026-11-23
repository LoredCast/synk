#define _POSIX_C_SOURCE 200809L

#include "synk.h"

#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int g_passed = 0;

#define RUN_TEST(fn) do { \
    printf("  [TEST] %s ... ", #fn); \
    fn(); \
    g_passed++; \
    printf("OK\n"); \
} while (0)

static void write_file_str(const char *path, const char *content) {
    FILE *f = fopen(path, "wb");
    assert(f != NULL);
    fwrite(content, 1, strlen(content), f);
    fclose(f);
}

static void test_sha256(void) {
    char hex[SYNK_SHA256_HEX_LEN];

    synk_sha256_bytes((const uint8_t *)"", 0, hex);
    assert(strcmp(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") == 0);

    synk_sha256_bytes((const uint8_t *)"abc", 3, hex);
    assert(strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0);

    const char *msg = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    synk_sha256_bytes((const uint8_t *)msg, strlen(msg), hex);
    assert(strcmp(hex, "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1") == 0);

    char tmpl[] = "/tmp/synk_sha_XXXXXX";
    int fd = mkstemp(tmpl);
    assert(fd >= 0);
    assert(write(fd, "abc", 3) == 3);
    close(fd);

    assert(synk_sha256_file(tmpl, hex) == 0);
    assert(strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0);
    unlink(tmpl);
}

static void test_glob_and_scanner(void) {
    assert(synk_glob_match("*.tmp", "notes/draft.tmp", 0) == 1);
    assert(synk_glob_match("*.tmp", "notes/draft.txt", 0) == 0);
    assert(synk_glob_match(".git/**", ".git/objects/ab/cd", 0) == 1);
    assert(synk_glob_match("/root.log", "root.log", 0) == 1);
    assert(synk_glob_match("/root.log", "sub/root.log", 0) == 0);
    assert(synk_glob_match("cache/", "cache", 1) == 1);
    assert(synk_glob_match("cache/", "cache/a.bin", 0) == 1);
    assert(synk_glob_match("cache/", "cache", 0) == 0);

    char dir_tmpl[] = "/tmp/synk_scan_XXXXXX";
    assert(mkdtemp(dir_tmpl) != NULL);

    char p1[512], p2[512], p3[512], p4[512], p5[512];
    snprintf(p1, sizeof(p1), "%s/alpha.txt", dir_tmpl);
    snprintf(p2, sizeof(p2), "%s/sub", dir_tmpl);
    snprintf(p3, sizeof(p3), "%s/sub/beta.md", dir_tmpl);
    snprintf(p4, sizeof(p4), "%s/ignore.tmp", dir_tmpl);
    snprintf(p5, sizeof(p5), "%s/.synk.tmp.123", dir_tmpl);

    write_file_str(p1, "hello");
    assert(mkdir(p2, 0755) == 0);
    write_file_str(p3, "world!");
    write_file_str(p4, "ignored");
    write_file_str(p5, "partial");

    const char *ignores[] = { "*.tmp" };
    SynkEntryList list;
    assert(synk_scan_dir(dir_tmpl, ignores, 1, 1, &list) == 0);
    assert(list.count == 3);
    assert(strcmp(list.items[0].rel_path, "alpha.txt") == 0);
    assert(list.items[0].is_dir == 0 && list.items[0].size == 5);
    assert(strlen(list.items[0].hash) == 64);
    assert(strcmp(list.items[1].rel_path, "sub") == 0);
    assert(list.items[1].is_dir == 1);
    assert(strcmp(list.items[2].rel_path, "sub/beta.md") == 0);
    assert(list.items[2].size == 6);

    synk_entry_list_free(&list);
    unlink(p5);
    unlink(p4);
    unlink(p3);
    rmdir(p2);
    unlink(p1);
    rmdir(dir_tmpl);
}

static void test_diff3_merge(void) {
    /* Non-overlapping edits -> clean merge */
    const char *base   = "line1\nline2\nline3\nline4\n";
    const char *local  = "LINE1_LOCAL\nline2\nline3\nline4\n";
    const char *remote = "line1\nline2\nline3\nLINE4_REMOTE\n";

    SynkMergeResult res;
    assert(synk_diff3_merge(base, strlen(base), local, strlen(local),
                            remote, strlen(remote), "PHONE", "SERVER", &res) == 0);
    assert(res.is_binary == 0);
    assert(res.conflict_count == 0);
    assert(strcmp(res.merged, "LINE1_LOCAL\nline2\nline3\nLINE4_REMOTE\n") == 0);
    synk_merge_result_free(&res);

    /* Overlapping edits -> 1 conflict with markers */
    const char *local2  = "line1\nlocal_edit\nline3\nline4\n";
    const char *remote2 = "line1\nremote_edit\nline3\nline4\n";
    assert(synk_diff3_merge(base, strlen(base), local2, strlen(local2),
                            remote2, strlen(remote2), "PHONE", "SERVER", &res) == 0);
    assert(res.is_binary == 0);
    assert(res.conflict_count == 1);
    assert(strstr(res.merged, "<<<<<<< PHONE\nlocal_edit\n=======\nremote_edit\n>>>>>>> SERVER\n") != NULL);
    synk_merge_result_free(&res);

    /* Identical edits on both sides -> clean merge */
    assert(synk_diff3_merge(base, strlen(base), local2, strlen(local2),
                            local2, strlen(local2), "PHONE", "SERVER", &res) == 0);
    assert(res.conflict_count == 0);
    assert(strcmp(res.merged, local2) == 0);
    synk_merge_result_free(&res);

    /* Binary detection */
    const char bin[] = { 'P', 'N', 'G', '\0', 1, 2, 3 };
    assert(synk_diff3_merge(base, strlen(base), bin, sizeof(bin),
                            remote, strlen(remote), "L", "R", &res) == 0);
    assert(res.is_binary == 1);
    synk_merge_result_free(&res);
}

static void test_planner_two_way_and_modes(void) {
    SynkEntryList base, local, remote;
    synk_entry_list_init(&base);
    synk_entry_list_init(&local);
    synk_entry_list_init(&remote);

    /* 1. unchanged.txt: identical everywhere */
    synk_entry_list_add(&base,   "unchanged.txt", 0, 10, 1000, "h1", "e1");
    synk_entry_list_add(&local,  "unchanged.txt", 0, 10, 1000, "h1", "");
    synk_entry_list_add(&remote, "unchanged.txt", 0, 10, 1000, "", "e1");

    /* 2. local_edit.txt: local modified */
    synk_entry_list_add(&base,   "local_edit.txt", 0, 10, 1000, "h1", "e1");
    synk_entry_list_add(&local,  "local_edit.txt", 0, 15, 5000, "h2", "");
    synk_entry_list_add(&remote, "local_edit.txt", 0, 10, 1000, "", "e1");

    /* 3. remote_edit.txt: remote modified */
    synk_entry_list_add(&base,   "remote_edit.txt", 0, 10, 1000, "h1", "e1");
    synk_entry_list_add(&local,  "remote_edit.txt", 0, 10, 1000, "h1", "");
    synk_entry_list_add(&remote, "remote_edit.txt", 0, 20, 6000, "", "e2");

    /* 4. both_edit.txt: offline conflict */
    synk_entry_list_add(&base,   "both_edit.txt", 0, 10, 1000, "h1", "e1");
    synk_entry_list_add(&local,  "both_edit.txt", 0, 12, 7000, "hL", "");
    synk_entry_list_add(&remote, "both_edit.txt", 0, 14, 9000, "", "eR");

    /* 5. mod_vs_del.txt: local modified while remote deleted */
    synk_entry_list_add(&base,   "mod_vs_del.txt", 0, 10, 1000, "h1", "e1");
    synk_entry_list_add(&local,  "mod_vs_del.txt", 0, 18, 8000, "hL2", "");

    /* 6. nested dir creation + deletion ordering */
    synk_entry_list_add(&local,  "newdir", 1, 0, 2000, "", "");
    synk_entry_list_add(&local,  "newdir/sub", 1, 0, 2000, "", "");
    synk_entry_list_add(&local,  "newdir/sub/f.txt", 0, 5, 2000, "hf", "");

    synk_entry_list_add(&base,   "olddir", 1, 0, 1000, "", "");
    synk_entry_list_add(&base,   "olddir/child.txt", 0, 5, 1000, "hc", "ec");
    synk_entry_list_add(&remote, "olddir", 1, 0, 1000, "", "");
    synk_entry_list_add(&remote, "olddir/child.txt", 0, 5, 1000, "", "ec");

    SynkPlan plan;
    assert(synk_plan_sync(&base, &local, &remote, SYNK_MODE_TWO_WAY, SYNK_CONFLICT_AUTO_MERGE, &plan) == 0);

    /* Verify phase ordering: MKDIR first (parent before child), DELETE last (child before parent) */
    assert(plan.count >= 8);
    assert(plan.items[0].action == SYNK_ACT_MKDIR_REMOTE && strcmp(plan.items[0].rel_path, "newdir") == 0);
    assert(plan.items[1].action == SYNK_ACT_MKDIR_REMOTE && strcmp(plan.items[1].rel_path, "newdir/sub") == 0);
    assert(plan.items[plan.count - 2].action == SYNK_ACT_DELETE_REMOTE &&
           strcmp(plan.items[plan.count - 2].rel_path, "olddir/child.txt") == 0);
    assert(plan.items[plan.count - 1].action == SYNK_ACT_DELETE_REMOTE &&
           strcmp(plan.items[plan.count - 1].rel_path, "olddir") == 0);

    int saw_try_merge = 0, saw_mod_del = 0, saw_upload = 0, saw_download = 0;
    for (size_t i = 0; i < plan.count; ++i) {
        const SynkPlanAction *a = &plan.items[i];
        assert(strcmp(a->rel_path, "unchanged.txt") != 0);
        if (strcmp(a->rel_path, "both_edit.txt") == 0) {
            assert(a->action == SYNK_ACT_TRY_MERGE && a->conflict_kind == SYNK_CKIND_BOTH_MODIFIED);
            saw_try_merge = 1;
        } else if (strcmp(a->rel_path, "mod_vs_del.txt") == 0) {
            assert(a->action == SYNK_ACT_CONFLICT_ASK && a->conflict_kind == SYNK_CKIND_LOCAL_MOD_REMOTE_DEL);
            saw_mod_del = 1;
        } else if (strcmp(a->rel_path, "local_edit.txt") == 0) {
            assert(a->action == SYNK_ACT_UPLOAD);
            saw_upload = 1;
        } else if (strcmp(a->rel_path, "remote_edit.txt") == 0) {
            assert(a->action == SYNK_ACT_DOWNLOAD);
            saw_download = 1;
        }
    }
    assert(saw_try_merge && saw_mod_del && saw_upload && saw_download);
    synk_plan_free(&plan);

    /* Test NEWEST_WINS on both_edit.txt (remote mtime 9000 > local 7000 -> DOWNLOAD) */
    assert(synk_plan_sync(&base, &local, &remote, SYNK_MODE_TWO_WAY, SYNK_CONFLICT_NEWEST_WINS, &plan) == 0);
    int found_newest = 0;
    for (size_t i = 0; i < plan.count; ++i) {
        if (strcmp(plan.items[i].rel_path, "both_edit.txt") == 0) {
            assert(plan.items[i].action == SYNK_ACT_DOWNLOAD);
            found_newest = 1;
        }
    }
    assert(found_newest);
    synk_plan_free(&plan);

    /* Test BACKUP_APPEND never deletes remote files */
    assert(synk_plan_sync(&base, &local, &remote, SYNK_MODE_BACKUP_APPEND, SYNK_CONFLICT_ASK, &plan) == 0);
    for (size_t i = 0; i < plan.count; ++i) {
        assert(plan.items[i].action != SYNK_ACT_DELETE_REMOTE);
        assert(plan.items[i].action != SYNK_ACT_DELETE_LOCAL);
        assert(plan.items[i].action != SYNK_ACT_DOWNLOAD);
    }
    synk_plan_free(&plan);

    /* Test DOWNLOAD_MIRROR deletes local-only newdir and downloads remote-only olddir */
    assert(synk_plan_sync(&base, &local, &remote, SYNK_MODE_DOWNLOAD_MIRROR, SYNK_CONFLICT_ASK, &plan) == 0);
    int del_newdir = 0, dl_old = 0;
    for (size_t i = 0; i < plan.count; ++i) {
        if (strcmp(plan.items[i].rel_path, "newdir") == 0 && plan.items[i].action == SYNK_ACT_DELETE_LOCAL) del_newdir = 1;
        if (strcmp(plan.items[i].rel_path, "olddir/child.txt") == 0 && plan.items[i].action == SYNK_ACT_DOWNLOAD) dl_old = 1;
    }
    assert(del_newdir && dl_old);
    synk_plan_free(&plan);

    synk_entry_list_free(&base);
    synk_entry_list_free(&local);
    synk_entry_list_free(&remote);
}

typedef struct {
    pthread_mutex_t mu;
    pthread_cond_t  cv;
    int             calls;
    int             events;
    char            last_path[512];
} WatchTestState;

static void on_watch_event(void *ud, const char *last_rel_path, int event_count) {
    WatchTestState *st = (WatchTestState *)ud;
    pthread_mutex_lock(&st->mu);
    st->calls++;
    st->events += event_count;
    strncpy(st->last_path, last_rel_path ? last_rel_path : "", sizeof(st->last_path) - 1);
    pthread_cond_broadcast(&st->cv);
    pthread_mutex_unlock(&st->mu);
}

static void test_inotify_watcher(void) {
    char dir_tmpl[] = "/tmp/synk_watch_XXXXXX";
    assert(mkdtemp(dir_tmpl) != NULL);

    WatchTestState st;
    memset(&st, 0, sizeof(st));
    pthread_mutex_init(&st.mu, NULL);
    pthread_cond_init(&st.cv, NULL);

    const char *ignores[] = { "*.tmp" };
    SynkWatcher *w = synk_watcher_start(dir_tmpl, ignores, 1, 120, on_watch_event, &st);
    assert(w != NULL);

    /* Give watcher thread a tiny moment to attach root watch */
    struct timespec short_sleep = { 0, 50 * 1000 * 1000 };
    nanosleep(&short_sleep, NULL);

    /* Ignored file should not trigger */
    char ignored_file[512];
    snprintf(ignored_file, sizeof(ignored_file), "%s/skip.tmp", dir_tmpl);
    write_file_str(ignored_file, "x");

    /* Create nested directory & file inside it */
    char subdir[512], nested[512];
    snprintf(subdir, sizeof(subdir), "%s/docs", dir_tmpl);
    snprintf(nested, sizeof(nested), "%s/docs/note.txt", dir_tmpl);
    assert(mkdir(subdir, 0755) == 0);
    nanosleep(&short_sleep, NULL);
    write_file_str(nested, "changed!");

    /* Wait up to 2s for debounced callback */
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 2;

    pthread_mutex_lock(&st.mu);
    while (st.calls == 0 || strstr(st.last_path, "note.txt") == NULL) {
        if (pthread_cond_timedwait(&st.cv, &st.mu, &deadline) != 0) break;
    }
    int calls = st.calls;
    char observed[512];
    strncpy(observed, st.last_path, sizeof(observed));
    pthread_mutex_unlock(&st.mu);

    synk_watcher_stop(w);
    pthread_cond_destroy(&st.cv);
    pthread_mutex_destroy(&st.mu);

    assert(calls >= 1);
    assert(strcmp(observed, "docs/note.txt") == 0);

    unlink(nested);
    rmdir(subdir);
    unlink(ignored_file);
    rmdir(dir_tmpl);
}

int main(void) {
    printf("Running Synk C Native Engine Unit Tests...\n");
    RUN_TEST(test_sha256);
    RUN_TEST(test_glob_and_scanner);
    RUN_TEST(test_diff3_merge);
    RUN_TEST(test_planner_two_way_and_modes);
    RUN_TEST(test_inotify_watcher);
    printf("All %d C native tests passed!\n", g_passed);
    return 0;
}
