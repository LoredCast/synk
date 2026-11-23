#include "synk.h"

#include <stdlib.h>
#include <string.h>

typedef enum {
    CH_NONE     = 0,
    CH_CREATED  = 1,
    CH_MODIFIED = 2,
    CH_DELETED  = 3
} ChangeKind;

static const int64_t MTIME_TOLERANCE_MS = 2000;

static int64_t abs_i64(int64_t v) {
    return v < 0 ? -v : v;
}

static char *plan_strdup(const char *s) {
    if (!s) return NULL;
    size_t n = strlen(s);
    char *p = (char *)malloc(n + 1);
    if (p) memcpy(p, s, n + 1);
    return p;
}

static int path_depth(const char *p) {
    int d = 0;
    if (!p) return 0;
    while (*p) {
        if (*p == '/') d++;
        p++;
    }
    return d;
}

static int action_phase(SynkActionType a) {
    switch (a) {
        case SYNK_ACT_MKDIR_LOCAL:
        case SYNK_ACT_MKDIR_REMOTE:
            return 0;
        case SYNK_ACT_DELETE_LOCAL:
        case SYNK_ACT_DELETE_REMOTE:
        case SYNK_ACT_REMOVE_BASE:
            return 2;
        default:
            return 1;
    }
}

static int cmp_plan_action(const void *pa, const void *pb) {
    const SynkPlanAction *a = (const SynkPlanAction *)pa;
    const SynkPlanAction *b = (const SynkPlanAction *)pb;
    int ph_a = action_phase(a->action);
    int ph_b = action_phase(b->action);
    if (ph_a != ph_b) return ph_a < ph_b ? -1 : 1;

    int da = path_depth(a->rel_path);
    int db = path_depth(b->rel_path);
    if (ph_a == 0) {
        /* MKDIR: parents first */
        if (da != db) return da < db ? -1 : 1;
    } else if (ph_a == 2) {
        /* DELETE: deepest children first, files before dirs at same depth */
        if (da != db) return da > db ? -1 : 1;
        if (a->is_dir != b->is_dir) return a->is_dir ? 1 : -1;
    }
    return strcmp(a->rel_path, b->rel_path);
}

static int plan_push(SynkPlan *plan, const char *rel_path, int is_dir,
                     SynkActionType action, SynkConflictKind ckind,
                     const SynkFileEntry *local, const SynkFileEntry *remote) {
    if (action == SYNK_ACT_NONE) return 0;
    if (plan->count == plan->capacity) {
        size_t new_cap = plan->capacity == 0 ? 64 : plan->capacity * 2;
        SynkPlanAction *grown = (SynkPlanAction *)realloc(plan->items, new_cap * sizeof(SynkPlanAction));
        if (!grown) return -1;
        plan->items = grown;
        plan->capacity = new_cap;
    }
    SynkPlanAction *act = &plan->items[plan->count];
    memset(act, 0, sizeof(*act));
    act->rel_path = plan_strdup(rel_path);
    if (!act->rel_path) return -1;
    act->is_dir = is_dir ? 1 : 0;
    act->action = action;
    act->conflict_kind = ckind;
    if (local) {
        act->local_size = local->size;
        act->local_mtime_ms = local->mtime_ms;
        memcpy(act->local_hash, local->hash, SYNK_SHA256_HEX_LEN);
        act->local_hash[SYNK_SHA256_HEX_LEN - 1] = '\0';
    }
    if (remote) {
        act->remote_size = remote->size;
        act->remote_mtime_ms = remote->mtime_ms;
        memcpy(act->remote_etag, remote->etag, SYNK_ETAG_MAX_LEN);
        act->remote_etag[SYNK_ETAG_MAX_LEN - 1] = '\0';
    }
    plan->count++;
    return 0;
}

static ChangeKind detect_local_change(const SynkFileEntry *base, const SynkFileEntry *local) {
    if (!base && !local) return CH_NONE;
    if (!base && local)  return CH_CREATED;
    if (base && !local)  return CH_DELETED;
    if (base->is_dir != local->is_dir) return CH_MODIFIED;
    if (local->is_dir) return CH_NONE;
    if (base->hash[0] && local->hash[0]) {
        return strcmp(base->hash, local->hash) == 0 ? CH_NONE : CH_MODIFIED;
    }
    if (base->size != local->size) return CH_MODIFIED;
    if (abs_i64(base->mtime_ms - local->mtime_ms) > MTIME_TOLERANCE_MS) return CH_MODIFIED;
    return CH_NONE;
}

static ChangeKind detect_remote_change(const SynkFileEntry *base, const SynkFileEntry *remote) {
    if (!base && !remote) return CH_NONE;
    if (!base && remote)  return CH_CREATED;
    if (base && !remote)  return CH_DELETED;
    if (base->is_dir != remote->is_dir) return CH_MODIFIED;
    if (remote->is_dir) return CH_NONE;
    if (base->etag[0] && remote->etag[0]) {
        return strcmp(base->etag, remote->etag) == 0 ? CH_NONE : CH_MODIFIED;
    }
    if (base->hash[0] && remote->hash[0]) {
        return strcmp(base->hash, remote->hash) == 0 ? CH_NONE : CH_MODIFIED;
    }
    if (base->size != remote->size) return CH_MODIFIED;
    if (base->mtime_ms > 0 && remote->mtime_ms > 0 &&
        abs_i64(base->mtime_ms - remote->mtime_ms) > MTIME_TOLERANCE_MS) {
        return CH_MODIFIED;
    }
    return CH_NONE;
}

static int files_known_identical(const SynkFileEntry *local, const SynkFileEntry *remote) {
    if (!local || !remote || local->is_dir || remote->is_dir) return 0;
    if (local->hash[0] && remote->hash[0]) {
        return strcmp(local->hash, remote->hash) == 0;
    }
    return 0;
}

static void decide_two_way(const char *path,
                           const SynkFileEntry *base,
                           const SynkFileEntry *local,
                           const SynkFileEntry *remote,
                           SynkConflictPolicy policy,
                           SynkPlan *plan) {
    ChangeKind lc = detect_local_change(base, local);
    ChangeKind rc = detect_remote_change(base, remote);
    int is_dir = local ? local->is_dir : (remote ? remote->is_dir : (base ? base->is_dir : 0));

    /* Both exist and are directories */
    if (local && remote && local->is_dir && remote->is_dir) {
        if (!base) {
            plan_push(plan, path, 1, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
        }
        return;
    }

    /* Type mismatch (file vs dir) when both exist */
    if (local && remote && local->is_dir != remote->is_dir) {
        plan_push(plan, path, is_dir, SYNK_ACT_CONFLICT_ASK, SYNK_CKIND_TYPE_MISMATCH, local, remote);
        return;
    }

    /* No changes on either side */
    if (lc == CH_NONE && rc == CH_NONE) {
        if (!base && local && remote) {
            plan_push(plan, path, is_dir, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
        }
        return;
    }

    /* Only local changed */
    if (lc != CH_NONE && rc == CH_NONE) {
        if (lc == CH_CREATED || lc == CH_MODIFIED) {
            plan_push(plan, path, is_dir,
                      is_dir ? SYNK_ACT_MKDIR_REMOTE : SYNK_ACT_UPLOAD,
                      SYNK_CKIND_NONE, local, remote);
        } else if (lc == CH_DELETED) {
            if (remote) {
                plan_push(plan, path, is_dir, SYNK_ACT_DELETE_REMOTE, SYNK_CKIND_NONE, local, remote);
            } else {
                plan_push(plan, path, is_dir, SYNK_ACT_REMOVE_BASE, SYNK_CKIND_NONE, local, remote);
            }
        }
        return;
    }

    /* Only remote changed */
    if (lc == CH_NONE && rc != CH_NONE) {
        if (rc == CH_CREATED || rc == CH_MODIFIED) {
            plan_push(plan, path, is_dir,
                      is_dir ? SYNK_ACT_MKDIR_LOCAL : SYNK_ACT_DOWNLOAD,
                      SYNK_CKIND_NONE, local, remote);
        } else if (rc == CH_DELETED) {
            if (local) {
                plan_push(plan, path, is_dir, SYNK_ACT_DELETE_LOCAL, SYNK_CKIND_NONE, local, remote);
            } else {
                plan_push(plan, path, is_dir, SYNK_ACT_REMOVE_BASE, SYNK_CKIND_NONE, local, remote);
            }
        }
        return;
    }

    /* Both sides changed */
    if (lc == CH_DELETED && rc == CH_DELETED) {
        plan_push(plan, path, is_dir, SYNK_ACT_REMOVE_BASE, SYNK_CKIND_NONE, local, remote);
        return;
    }

    if ((lc == CH_CREATED || lc == CH_MODIFIED) && (rc == CH_CREATED || rc == CH_MODIFIED)) {
        if (files_known_identical(local, remote)) {
            plan_push(plan, path, 0, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            return;
        }
        SynkConflictKind ck = base ? SYNK_CKIND_BOTH_MODIFIED : SYNK_CKIND_BOTH_CREATED;
        switch (policy) {
            case SYNK_CONFLICT_LOCAL_WINS:
                plan_push(plan, path, 0, SYNK_ACT_UPLOAD, ck, local, remote);
                break;
            case SYNK_CONFLICT_REMOTE_WINS:
                plan_push(plan, path, 0, SYNK_ACT_DOWNLOAD, ck, local, remote);
                break;
            case SYNK_CONFLICT_NEWEST_WINS:
                if (local->mtime_ms >= remote->mtime_ms) {
                    plan_push(plan, path, 0, SYNK_ACT_UPLOAD, ck, local, remote);
                } else {
                    plan_push(plan, path, 0, SYNK_ACT_DOWNLOAD, ck, local, remote);
                }
                break;
            case SYNK_CONFLICT_KEEP_BOTH:
                plan_push(plan, path, 0, SYNK_ACT_CONFLICT_KEEP_BOTH, ck, local, remote);
                break;
            case SYNK_CONFLICT_AUTO_MERGE:
                plan_push(plan, path, 0, SYNK_ACT_TRY_MERGE, ck, local, remote);
                break;
            case SYNK_CONFLICT_ASK:
            default:
                plan_push(plan, path, 0, SYNK_ACT_CONFLICT_ASK, ck, local, remote);
                break;
        }
        return;
    }

    if (lc == CH_MODIFIED && rc == CH_DELETED) {
        SynkConflictKind ck = SYNK_CKIND_LOCAL_MOD_REMOTE_DEL;
        if (policy == SYNK_CONFLICT_LOCAL_WINS || policy == SYNK_CONFLICT_KEEP_BOTH ||
            policy == SYNK_CONFLICT_NEWEST_WINS) {
            plan_push(plan, path, is_dir,
                      is_dir ? SYNK_ACT_MKDIR_REMOTE : SYNK_ACT_UPLOAD, ck, local, remote);
        } else if (policy == SYNK_CONFLICT_REMOTE_WINS) {
            plan_push(plan, path, is_dir, SYNK_ACT_DELETE_LOCAL, ck, local, remote);
        } else {
            plan_push(plan, path, is_dir, SYNK_ACT_CONFLICT_ASK, ck, local, remote);
        }
        return;
    }

    if (lc == CH_DELETED && rc == CH_MODIFIED) {
        SynkConflictKind ck = SYNK_CKIND_LOCAL_DEL_REMOTE_MOD;
        if (policy == SYNK_CONFLICT_LOCAL_WINS) {
            plan_push(plan, path, is_dir, SYNK_ACT_DELETE_REMOTE, ck, local, remote);
        } else if (policy == SYNK_CONFLICT_REMOTE_WINS || policy == SYNK_CONFLICT_KEEP_BOTH ||
                   policy == SYNK_CONFLICT_NEWEST_WINS) {
            plan_push(plan, path, is_dir,
                      is_dir ? SYNK_ACT_MKDIR_LOCAL : SYNK_ACT_DOWNLOAD, ck, local, remote);
        } else {
            plan_push(plan, path, is_dir, SYNK_ACT_CONFLICT_ASK, ck, local, remote);
        }
        return;
    }
}

static void decide_one_way(const char *path,
                           const SynkFileEntry *base,
                           const SynkFileEntry *local,
                           const SynkFileEntry *remote,
                           SynkMode mode,
                           SynkPlan *plan) {
    ChangeKind lc = detect_local_change(base, local);
    ChangeKind rc = detect_remote_change(base, remote);

    if (!local && !remote && base) {
        plan_push(plan, path, base->is_dir, SYNK_ACT_REMOVE_BASE, SYNK_CKIND_NONE, local, remote);
        return;
    }

    if (mode == SYNK_MODE_UPLOAD_ONLY || mode == SYNK_MODE_BACKUP_APPEND) {
        if (!local) return;
        if (local->is_dir) {
            if (!remote) {
                plan_push(plan, path, 1, SYNK_ACT_MKDIR_REMOTE, SYNK_CKIND_NONE, local, remote);
            } else if (!base) {
                plan_push(plan, path, 1, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            }
            return;
        }
        if (!remote) {
            plan_push(plan, path, 0, SYNK_ACT_UPLOAD, SYNK_CKIND_NONE, local, remote);
        } else if (lc == CH_MODIFIED || lc == CH_CREATED) {
            if (files_known_identical(local, remote)) {
                plan_push(plan, path, 0, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            } else {
                plan_push(plan, path, 0, SYNK_ACT_UPLOAD, SYNK_CKIND_NONE, local, remote);
            }
        }
        return;
    }

    if (mode == SYNK_MODE_UPLOAD_MIRROR) {
        if (local && !remote) {
            plan_push(plan, path, local->is_dir,
                      local->is_dir ? SYNK_ACT_MKDIR_REMOTE : SYNK_ACT_UPLOAD,
                      SYNK_CKIND_NONE, local, remote);
        } else if (!local && remote) {
            plan_push(plan, path, remote->is_dir, SYNK_ACT_DELETE_REMOTE, SYNK_CKIND_NONE, local, remote);
        } else if (local && remote) {
            if (local->is_dir && remote->is_dir) {
                if (!base) plan_push(plan, path, 1, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            } else if (local->is_dir != remote->is_dir) {
                plan_push(plan, path, local->is_dir, SYNK_ACT_CONFLICT_ASK, SYNK_CKIND_TYPE_MISMATCH, local, remote);
            } else if (lc != CH_NONE || rc != CH_NONE) {
                if (files_known_identical(local, remote)) {
                    plan_push(plan, path, 0, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
                } else {
                    plan_push(plan, path, 0, SYNK_ACT_UPLOAD, SYNK_CKIND_NONE, local, remote);
                }
            }
        }
        return;
    }

    if (mode == SYNK_MODE_DOWNLOAD_ONLY) {
        if (!remote) return;
        if (remote->is_dir) {
            if (!local) {
                plan_push(plan, path, 1, SYNK_ACT_MKDIR_LOCAL, SYNK_CKIND_NONE, local, remote);
            } else if (!base) {
                plan_push(plan, path, 1, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            }
            return;
        }
        if (!local) {
            plan_push(plan, path, 0, SYNK_ACT_DOWNLOAD, SYNK_CKIND_NONE, local, remote);
        } else if (rc == CH_MODIFIED || rc == CH_CREATED) {
            if (files_known_identical(local, remote)) {
                plan_push(plan, path, 0, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            } else {
                plan_push(plan, path, 0, SYNK_ACT_DOWNLOAD, SYNK_CKIND_NONE, local, remote);
            }
        }
        return;
    }

    if (mode == SYNK_MODE_DOWNLOAD_MIRROR) {
        if (remote && !local) {
            plan_push(plan, path, remote->is_dir,
                      remote->is_dir ? SYNK_ACT_MKDIR_LOCAL : SYNK_ACT_DOWNLOAD,
                      SYNK_CKIND_NONE, local, remote);
        } else if (!remote && local) {
            plan_push(plan, path, local->is_dir, SYNK_ACT_DELETE_LOCAL, SYNK_CKIND_NONE, local, remote);
        } else if (local && remote) {
            if (local->is_dir && remote->is_dir) {
                if (!base) plan_push(plan, path, 1, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
            } else if (local->is_dir != remote->is_dir) {
                plan_push(plan, path, remote->is_dir, SYNK_ACT_CONFLICT_ASK, SYNK_CKIND_TYPE_MISMATCH, local, remote);
            } else if (lc != CH_NONE || rc != CH_NONE) {
                if (files_known_identical(local, remote)) {
                    plan_push(plan, path, 0, SYNK_ACT_UPDATE_BASE, SYNK_CKIND_NONE, local, remote);
                } else {
                    plan_push(plan, path, 0, SYNK_ACT_DOWNLOAD, SYNK_CKIND_NONE, local, remote);
                }
            }
        }
        return;
    }
}

int synk_plan_sync(const SynkEntryList *base,
                   const SynkEntryList *local,
                   const SynkEntryList *remote,
                   SynkMode mode,
                   SynkConflictPolicy policy,
                   SynkPlan *out_plan) {
    if (!out_plan) return -1;
    out_plan->items = NULL;
    out_plan->count = 0;
    out_plan->capacity = 0;

    size_t nb = base ? base->count : 0;
    size_t nl = local ? local->count : 0;
    size_t nr = remote ? remote->count : 0;

    /* Copy sorted shallow views so callers don't need pre-sorted lists */
    SynkEntryList sb = {0}, sl = {0}, sr = {0};
    for (size_t i = 0; i < nb; ++i) {
        const SynkFileEntry *e = &base->items[i];
        synk_entry_list_add(&sb, e->rel_path, e->is_dir, e->size, e->mtime_ms, e->hash, e->etag);
    }
    for (size_t i = 0; i < nl; ++i) {
        const SynkFileEntry *e = &local->items[i];
        synk_entry_list_add(&sl, e->rel_path, e->is_dir, e->size, e->mtime_ms, e->hash, e->etag);
    }
    for (size_t i = 0; i < nr; ++i) {
        const SynkFileEntry *e = &remote->items[i];
        synk_entry_list_add(&sr, e->rel_path, e->is_dir, e->size, e->mtime_ms, e->hash, e->etag);
    }
    synk_entry_list_sort(&sb);
    synk_entry_list_sort(&sl);
    synk_entry_list_sort(&sr);

    /* 3-way sorted merge walk */
    size_t ib = 0, il = 0, ir = 0;
    while (ib < sb.count || il < sl.count || ir < sr.count) {
        const char *min_path = NULL;
        if (ib < sb.count) min_path = sb.items[ib].rel_path;
        if (il < sl.count && (!min_path || strcmp(sl.items[il].rel_path, min_path) < 0)) {
            min_path = sl.items[il].rel_path;
        }
        if (ir < sr.count && (!min_path || strcmp(sr.items[ir].rel_path, min_path) < 0)) {
            min_path = sr.items[ir].rel_path;
        }

        const SynkFileEntry *eb = (ib < sb.count && strcmp(sb.items[ib].rel_path, min_path) == 0)
                                  ? &sb.items[ib++] : NULL;
        const SynkFileEntry *el = (il < sl.count && strcmp(sl.items[il].rel_path, min_path) == 0)
                                  ? &sl.items[il++] : NULL;
        const SynkFileEntry *er = (ir < sr.count && strcmp(sr.items[ir].rel_path, min_path) == 0)
                                  ? &sr.items[ir++] : NULL;

        if (mode == SYNK_MODE_TWO_WAY) {
            decide_two_way(min_path, eb, el, er, policy, out_plan);
        } else {
            decide_one_way(min_path, eb, el, er, mode, out_plan);
        }
    }

    if (out_plan->count > 1) {
        qsort(out_plan->items, out_plan->count, sizeof(SynkPlanAction), cmp_plan_action);
    }

    synk_entry_list_free(&sb);
    synk_entry_list_free(&sl);
    synk_entry_list_free(&sr);
    return 0;
}

void synk_plan_free(SynkPlan *plan) {
    if (!plan) return;
    for (size_t i = 0; i < plan->count; ++i) {
        free(plan->items[i].rel_path);
    }
    free(plan->items);
    plan->items = NULL;
    plan->count = 0;
    plan->capacity = 0;
}
