#include "synk.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const char *ptr;
    size_t      len;
    uint64_t    hash;
} Line;

typedef struct {
    Line  *items;
    size_t count;
} LineList;

typedef struct {
    char  *data;
    size_t len;
    size_t cap;
} StrBuf;

static int sb_reserve(StrBuf *sb, size_t extra) {
    if (sb->len + extra + 1 <= sb->cap) return 0;
    size_t new_cap = sb->cap ? sb->cap * 2 : 256;
    while (new_cap < sb->len + extra + 1) new_cap *= 2;
    char *p = (char *)realloc(sb->data, new_cap);
    if (!p) return -1;
    sb->data = p;
    sb->cap = new_cap;
    return 0;
}

static int sb_append(StrBuf *sb, const char *s, size_t n) {
    if (n == 0) return 0;
    if (sb_reserve(sb, n) != 0) return -1;
    memcpy(sb->data + sb->len, s, n);
    sb->len += n;
    sb->data[sb->len] = '\0';
    return 0;
}

static int sb_append_cstr(StrBuf *sb, const char *s) {
    return sb_append(sb, s, strlen(s));
}

static uint64_t fnv1a_64(const char *s, size_t len) {
    uint64_t h = 14695981039346656037ULL;
    for (size_t i = 0; i < len; ++i) {
        h ^= (uint8_t)s[i];
        h *= 1099511628211ULL;
    }
    return h;
}

static int looks_binary(const char *buf, size_t len) {
    if (!buf || len == 0) return 0;
    size_t check = len < 8192 ? len : 8192;
    for (size_t i = 0; i < check; ++i) {
        if (buf[i] == '\0') return 1;
    }
    return 0;
}

static int split_lines(const char *text, size_t len, LineList *out) {
    out->items = NULL;
    out->count = 0;
    if (!text || len == 0) return 0;

    size_t count = 0;
    for (size_t i = 0; i < len; ++i) {
        if (text[i] == '\n') count++;
    }
    if (text[len - 1] != '\n') count++;

    Line *arr = (Line *)malloc(count * sizeof(Line));
    if (!arr) return -1;

    size_t idx = 0;
    size_t start = 0;
    for (size_t i = 0; i < len; ++i) {
        if (text[i] == '\n') {
            size_t llen = i - start + 1;
            arr[idx].ptr = text + start;
            arr[idx].len = llen;
            arr[idx].hash = fnv1a_64(text + start, llen);
            idx++;
            start = i + 1;
        }
    }
    if (start < len) {
        size_t llen = len - start;
        arr[idx].ptr = text + start;
        arr[idx].len = llen;
        arr[idx].hash = fnv1a_64(text + start, llen);
        idx++;
    }
    out->items = arr;
    out->count = idx;
    return 0;
}

static inline int lines_eq(const Line *a, const Line *b) {
    return a->hash == b->hash && a->len == b->len &&
           (a->len == 0 || memcmp(a->ptr, b->ptr, a->len) == 0);
}

static int slices_eq(const LineList *a, size_t a0, size_t a1,
                     const LineList *b, size_t b0, size_t b1) {
    if ((a1 - a0) != (b1 - b0)) return 0;
    size_t n = a1 - a0;
    for (size_t i = 0; i < n; ++i) {
        if (!lines_eq(&a->items[a0 + i], &b->items[b0 + i])) return 0;
    }
    return 1;
}

static int append_slice(StrBuf *sb, const LineList *ll, size_t start, size_t end) {
    for (size_t i = start; i < end; ++i) {
        if (sb_append(sb, ll->items[i].ptr, ll->items[i].len) != 0) return -1;
    }
    return 0;
}

/* Compute LCS mapping: out_match[i] = matched index in side, or -1 */
static int compute_lcs_match(const LineList *base, const LineList *side, int32_t **out_match) {
    size_t n = base->count;
    size_t m = side->count;
    int32_t *match = (int32_t *)malloc((n > 0 ? n : 1) * sizeof(int32_t));
    if (!match) return -1;
    for (size_t i = 0; i < n; ++i) match[i] = -1;

    /* Strip common prefix */
    size_t prefix = 0;
    while (prefix < n && prefix < m && lines_eq(&base->items[prefix], &side->items[prefix])) {
        match[prefix] = (int32_t)prefix;
        prefix++;
    }

    /* Strip common suffix */
    size_t suffix = 0;
    while (suffix < (n - prefix) && suffix < (m - prefix) &&
           lines_eq(&base->items[n - 1 - suffix], &side->items[m - 1 - suffix])) {
        match[n - 1 - suffix] = (int32_t)(m - 1 - suffix);
        suffix++;
    }

    size_t na = n - prefix - suffix;
    size_t nb = m - prefix - suffix;
    if (na == 0 || nb == 0) {
        *out_match = match;
        return 0;
    }

    /* Memory guard: cap DP table at ~16M entries (32MB uint16_t) */
    if (na > 65000 || nb > 65000 || (uint64_t)na * (uint64_t)nb > 16000000ULL) {
        *out_match = match;
        return 0;
    }

    size_t stride = nb + 1;
    uint16_t *dp = (uint16_t *)calloc((na + 1) * stride, sizeof(uint16_t));
    if (!dp) {
        free(match);
        return -1;
    }

    for (size_t i = na; i > 0; --i) {
        size_t r = i - 1;
        const Line *la = &base->items[prefix + r];
        uint16_t *row = dp + r * stride;
        const uint16_t *next_row = dp + (r + 1) * stride;
        for (size_t j = nb; j > 0; --j) {
            size_t c = j - 1;
            if (lines_eq(la, &side->items[prefix + c])) {
                row[c] = (uint16_t)(next_row[c + 1] + 1);
            } else {
                uint16_t down = next_row[c];
                uint16_t right = row[c + 1];
                row[c] = down >= right ? down : right;
            }
        }
    }

    /* Backtrack to extract matched indices */
    size_t i = 0, j = 0;
    while (i < na && j < nb) {
        if (lines_eq(&base->items[prefix + i], &side->items[prefix + j])) {
            match[prefix + i] = (int32_t)(prefix + j);
            i++;
            j++;
        } else if (dp[(i + 1) * stride + j] >= dp[i * stride + (j + 1)]) {
            i++;
        } else {
            j++;
        }
    }

    free(dp);
    *out_match = match;
    return 0;
}

int synk_diff3_merge(const char *base, size_t base_len,
                     const char *local, size_t local_len,
                     const char *remote, size_t remote_len,
                     const char *local_label, const char *remote_label,
                     SynkMergeResult *out) {
    if (!out) return -1;
    memset(out, 0, sizeof(*out));

    if (!local_label || !local_label[0]) local_label = "LOCAL";
    if (!remote_label || !remote_label[0]) remote_label = "REMOTE";

    if (looks_binary(base, base_len) || looks_binary(local, local_len) || looks_binary(remote, remote_len)) {
        out->is_binary = 1;
        return 0;
    }

    /* Fast paths */
    if (local_len == remote_len && (local_len == 0 || memcmp(local, remote, local_len) == 0)) {
        out->merged = (char *)malloc(local_len + 1);
        if (!out->merged) return -1;
        if (local_len > 0) memcpy(out->merged, local, local_len);
        out->merged[local_len] = '\0';
        out->merged_len = local_len;
        out->conflict_count = 0;
        return 0;
    }
    if (local_len == base_len && (local_len == 0 || memcmp(local, base, local_len) == 0)) {
        out->merged = (char *)malloc(remote_len + 1);
        if (!out->merged) return -1;
        if (remote_len > 0) memcpy(out->merged, remote, remote_len);
        out->merged[remote_len] = '\0';
        out->merged_len = remote_len;
        out->conflict_count = 0;
        return 0;
    }
    if (remote_len == base_len && (remote_len == 0 || memcmp(remote, base, remote_len) == 0)) {
        out->merged = (char *)malloc(local_len + 1);
        if (!out->merged) return -1;
        if (local_len > 0) memcpy(out->merged, local, local_len);
        out->merged[local_len] = '\0';
        out->merged_len = local_len;
        out->conflict_count = 0;
        return 0;
    }

    LineList bl = {0}, ll = {0}, rl = {0};
    int32_t *ml = NULL;
    int32_t *mr = NULL;
    StrBuf sb = {0};
    int rc = -1;

    if (split_lines(base, base_len, &bl) != 0) goto cleanup;
    if (split_lines(local, local_len, &ll) != 0) goto cleanup;
    if (split_lines(remote, remote_len, &rl) != 0) goto cleanup;

    if (compute_lcs_match(&bl, &ll, &ml) != 0) goto cleanup;
    if (compute_lcs_match(&bl, &rl, &mr) != 0) goto cleanup;

    size_t b = 0, l_pos = 0, r_pos = 0;
    int conflicts = 0;

    while (b <= bl.count) {
        size_t b_sync = b;
        size_t l_end = ll.count;
        size_t r_end = rl.count;

        while (b_sync < bl.count) {
            if (ml[b_sync] >= (int32_t)l_pos && mr[b_sync] >= (int32_t)r_pos) {
                l_end = (size_t)ml[b_sync];
                r_end = (size_t)mr[b_sync];
                break;
            }
            b_sync++;
        }

        if (b < b_sync || l_pos < l_end || r_pos < r_end) {
            int l_same_base = slices_eq(&ll, l_pos, l_end, &bl, b, b_sync);
            int r_same_base = slices_eq(&rl, r_pos, r_end, &bl, b, b_sync);
            int l_same_r    = slices_eq(&ll, l_pos, l_end, &rl, r_pos, r_end);

            if (l_same_base && r_same_base) {
                if (append_slice(&sb, &bl, b, b_sync) != 0) goto cleanup;
            } else if (l_same_base) {
                if (append_slice(&sb, &rl, r_pos, r_end) != 0) goto cleanup;
            } else if (r_same_base || l_same_r) {
                if (append_slice(&sb, &ll, l_pos, l_end) != 0) goto cleanup;
            } else {
                conflicts++;
                if (sb_append_cstr(&sb, "<<<<<<< ") != 0 ||
                    sb_append_cstr(&sb, local_label) != 0 ||
                    sb_append_cstr(&sb, "\n") != 0 ||
                    append_slice(&sb, &ll, l_pos, l_end) != 0) {
                    goto cleanup;
                }
                if (sb.len > 0 && sb.data[sb.len - 1] != '\n') {
                    if (sb_append_cstr(&sb, "\n") != 0) goto cleanup;
                }
                if (sb_append_cstr(&sb, "=======\n") != 0 ||
                    append_slice(&sb, &rl, r_pos, r_end) != 0) {
                    goto cleanup;
                }
                if (sb.len > 0 && sb.data[sb.len - 1] != '\n') {
                    if (sb_append_cstr(&sb, "\n") != 0) goto cleanup;
                }
                if (sb_append_cstr(&sb, ">>>>>>> ") != 0 ||
                    sb_append_cstr(&sb, remote_label) != 0 ||
                    sb_append_cstr(&sb, "\n") != 0) {
                    goto cleanup;
                }
            }
        }

        if (b_sync == bl.count) break;

        /* Emit synchronized unchanged base line */
        if (append_slice(&sb, &bl, b_sync, b_sync + 1) != 0) goto cleanup;
        b = b_sync + 1;
        l_pos = l_end + 1;
        r_pos = r_end + 1;
    }

    if (!sb.data) {
        sb.data = (char *)calloc(1, 1);
        if (!sb.data) goto cleanup;
        sb.len = 0;
    }
    out->merged = sb.data;
    out->merged_len = sb.len;
    out->conflict_count = conflicts;
    out->is_binary = 0;
    sb.data = NULL;
    rc = 0;

cleanup:
    free(sb.data);
    free(ml);
    free(mr);
    free(bl.items);
    free(ll.items);
    free(rl.items);
    return rc;
}

void synk_merge_result_free(SynkMergeResult *res) {
    if (!res) return;
    free(res->merged);
    res->merged = NULL;
    res->merged_len = 0;
    res->conflict_count = 0;
    res->is_binary = 0;
}
