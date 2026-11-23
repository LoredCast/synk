#ifndef SYNK_H
#define SYNK_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SYNK_SHA256_HEX_LEN 65
#define SYNK_ETAG_MAX_LEN   128

/* Sync modes */
typedef enum {
    SYNK_MODE_TWO_WAY         = 0,
    SYNK_MODE_UPLOAD_ONLY     = 1,
    SYNK_MODE_UPLOAD_MIRROR   = 2,
    SYNK_MODE_DOWNLOAD_ONLY   = 3,
    SYNK_MODE_DOWNLOAD_MIRROR = 4,
    SYNK_MODE_BACKUP_APPEND   = 5
} SynkMode;

/* Conflict resolution policies */
typedef enum {
    SYNK_CONFLICT_ASK         = 0,
    SYNK_CONFLICT_AUTO_MERGE  = 1,
    SYNK_CONFLICT_KEEP_BOTH   = 2,
    SYNK_CONFLICT_LOCAL_WINS  = 3,
    SYNK_CONFLICT_REMOTE_WINS = 4,
    SYNK_CONFLICT_NEWEST_WINS = 5
} SynkConflictPolicy;

/* Planned sync actions */
typedef enum {
    SYNK_ACT_NONE               = 0,
    SYNK_ACT_MKDIR_LOCAL        = 1,
    SYNK_ACT_MKDIR_REMOTE       = 2,
    SYNK_ACT_UPLOAD             = 3,
    SYNK_ACT_DOWNLOAD           = 4,
    SYNK_ACT_DELETE_LOCAL       = 5,
    SYNK_ACT_DELETE_REMOTE      = 6,
    SYNK_ACT_UPDATE_BASE        = 7,
    SYNK_ACT_REMOVE_BASE        = 8,
    SYNK_ACT_TRY_MERGE          = 9,
    SYNK_ACT_CONFLICT_ASK       = 10,
    SYNK_ACT_CONFLICT_KEEP_BOTH = 11
} SynkActionType;

/* Conflict kinds */
typedef enum {
    SYNK_CKIND_NONE                = 0,
    SYNK_CKIND_BOTH_MODIFIED       = 1,
    SYNK_CKIND_BOTH_CREATED        = 2,
    SYNK_CKIND_LOCAL_MOD_REMOTE_DEL = 3,
    SYNK_CKIND_LOCAL_DEL_REMOTE_MOD = 4,
    SYNK_CKIND_TYPE_MISMATCH       = 5
} SynkConflictKind;

/* File/directory metadata entry */
typedef struct {
    char   *rel_path;
    int     is_dir;
    int64_t size;
    int64_t mtime_ms;
    char    hash[SYNK_SHA256_HEX_LEN];
    char    etag[SYNK_ETAG_MAX_LEN];
} SynkFileEntry;

typedef struct {
    SynkFileEntry *items;
    size_t         count;
    size_t         capacity;
} SynkEntryList;

typedef struct {
    char            *rel_path;
    int              is_dir;
    SynkActionType   action;
    SynkConflictKind conflict_kind;
    int64_t          local_size;
    int64_t          local_mtime_ms;
    int64_t          remote_size;
    int64_t          remote_mtime_ms;
    char             local_hash[SYNK_SHA256_HEX_LEN];
    char             remote_etag[SYNK_ETAG_MAX_LEN];
} SynkPlanAction;

typedef struct {
    SynkPlanAction *items;
    size_t          count;
    size_t          capacity;
} SynkPlan;

typedef struct {
    char  *merged;
    size_t merged_len;
    int    conflict_count;
    int    is_binary;
} SynkMergeResult;

/* ---- SHA-256 ---- */
void synk_sha256_bytes(const uint8_t *data, size_t len, char out_hex[SYNK_SHA256_HEX_LEN]);
int  synk_sha256_file(const char *path, char out_hex[SYNK_SHA256_HEX_LEN]);

/* ---- Entry List helpers ---- */
void synk_entry_list_init(SynkEntryList *list);
int  synk_entry_list_add(SynkEntryList *list, const char *rel_path, int is_dir,
                         int64_t size, int64_t mtime_ms,
                         const char *hash, const char *etag);
void synk_entry_list_sort(SynkEntryList *list);
void synk_entry_list_free(SynkEntryList *list);

/* ---- Ignore / Glob matching & Directory Scanner ---- */
int  synk_glob_match(const char *pattern, const char *rel_path, int is_dir);
int  synk_is_ignored(const char *rel_path, int is_dir,
                     const char **patterns, size_t pattern_count);
int  synk_scan_dir(const char *root_dir,
                   const char **ignore_patterns, size_t ignore_count,
                   int compute_hashes,
                   SynkEntryList *out_list);

/* ---- 3-Way Diff3 Text Merge ---- */
int  synk_diff3_merge(const char *base, size_t base_len,
                      const char *local, size_t local_len,
                      const char *remote, size_t remote_len,
                      const char *local_label, const char *remote_label,
                      SynkMergeResult *out);
void synk_merge_result_free(SynkMergeResult *res);

/* ---- 3-Way Sync Planner ---- */
int  synk_plan_sync(const SynkEntryList *base,
                    const SynkEntryList *local,
                    const SynkEntryList *remote,
                    SynkMode mode,
                    SynkConflictPolicy policy,
                    SynkPlan *out_plan);
void synk_plan_free(SynkPlan *plan);

/* ---- Recursive Linux inotify Watcher ---- */
typedef struct SynkWatcher SynkWatcher;
typedef void (*synk_watch_cb)(void *userdata, const char *last_rel_path, int event_count);

SynkWatcher *synk_watcher_start(const char *root_dir,
                                const char **ignore_patterns, size_t ignore_count,
                                int debounce_ms,
                                synk_watch_cb cb, void *userdata);
void synk_watcher_stop(SynkWatcher *watcher);

#ifdef __cplusplus
}
#endif

#endif /* SYNK_H */
