#include "synk.h"

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static char **copy_string_array(JNIEnv *env, jobjectArray arr, size_t *out_count) {
    *out_count = 0;
    if (!arr) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    if (n <= 0) return NULL;
    char **out = (char **)calloc((size_t)n, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; ++i) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        if (js) {
            const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
            if (utf) {
                size_t len = strlen(utf);
                out[i] = (char *)malloc(len + 1);
                if (out[i]) memcpy(out[i], utf, len + 1);
                (*env)->ReleaseStringUTFChars(env, js, utf);
            }
            (*env)->DeleteLocalRef(env, js);
        }
    }
    *out_count = (size_t)n;
    return out;
}

static void free_string_array(char **arr, size_t count) {
    if (!arr) return;
    for (size_t i = 0; i < count; ++i) free(arr[i]);
    free(arr);
}

/* Parse "isDir\tsize\tmtimeMs\thash\tetag\trelPath" */
static void parse_entries_from_java(JNIEnv *env, jobjectArray arr, SynkEntryList *out) {
    synk_entry_list_init(out);
    if (!arr) return;
    jsize n = (*env)->GetArrayLength(env, arr);
    for (jsize i = 0; i < n; ++i) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        if (!js) continue;
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        if (utf) {
            const char *t1 = strchr(utf, '\t');
            const char *t2 = t1 ? strchr(t1 + 1, '\t') : NULL;
            const char *t3 = t2 ? strchr(t2 + 1, '\t') : NULL;
            const char *t4 = t3 ? strchr(t3 + 1, '\t') : NULL;
            const char *t5 = t4 ? strchr(t4 + 1, '\t') : NULL;
            if (t1 && t2 && t3 && t4 && t5) {
                int is_dir = atoi(utf);
                int64_t size = strtoll(t1 + 1, NULL, 10);
                int64_t mtime_ms = strtoll(t2 + 1, NULL, 10);

                char hash[SYNK_SHA256_HEX_LEN];
                size_t hlen = (size_t)(t4 - (t3 + 1));
                if (hlen >= sizeof(hash)) hlen = sizeof(hash) - 1;
                memcpy(hash, t3 + 1, hlen);
                hash[hlen] = '\0';

                char etag[SYNK_ETAG_MAX_LEN];
                size_t elen = (size_t)(t5 - (t4 + 1));
                if (elen >= sizeof(etag)) elen = sizeof(etag) - 1;
                memcpy(etag, t4 + 1, elen);
                etag[elen] = '\0';

                const char *rel_path = t5 + 1;
                synk_entry_list_add(out, rel_path, is_dir, size, mtime_ms, hash, etag);
            }
            (*env)->ReleaseStringUTFChars(env, js, utf);
        }
        (*env)->DeleteLocalRef(env, js);
    }
}

JNIEXPORT jstring JNICALL
Java_org_synk_core_NativeBridge_nativeSha256File(JNIEnv *env, jclass cls, jstring jpath) {
    (void)cls;
    if (!jpath) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return NULL;
    char hex[SYNK_SHA256_HEX_LEN];
    int rc = synk_sha256_file(path, hex);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rc != 0) return NULL;
    return (*env)->NewStringUTF(env, hex);
}

JNIEXPORT jstring JNICALL
Java_org_synk_core_NativeBridge_nativeSha256Bytes(JNIEnv *env, jclass cls, jbyteArray jdata) {
    (void)cls;
    char hex[SYNK_SHA256_HEX_LEN];
    if (!jdata) {
        synk_sha256_bytes(NULL, 0, hex);
        return (*env)->NewStringUTF(env, hex);
    }
    jsize len = (*env)->GetArrayLength(env, jdata);
    jbyte *ptr = (*env)->GetByteArrayElements(env, jdata, NULL);
    synk_sha256_bytes((const uint8_t *)ptr, (size_t)len, hex);
    if (ptr) (*env)->ReleaseByteArrayElements(env, jdata, ptr, JNI_ABORT);
    return (*env)->NewStringUTF(env, hex);
}

JNIEXPORT jobjectArray JNICALL
Java_org_synk_core_NativeBridge_nativeScanDir(JNIEnv *env, jclass cls,
                                              jstring jroot,
                                              jobjectArray jignores,
                                              jboolean computeHashes) {
    (void)cls;
    if (!jroot) return NULL;
    const char *root = (*env)->GetStringUTFChars(env, jroot, NULL);
    if (!root) return NULL;

    size_t ig_count = 0;
    char **ignores = copy_string_array(env, jignores, &ig_count);

    SynkEntryList list;
    int rc = synk_scan_dir(root, (const char **)ignores, ig_count, computeHashes ? 1 : 0, &list);

    free_string_array(ignores, ig_count);
    (*env)->ReleaseStringUTFChars(env, jroot, root);

    if (rc != 0) return NULL;

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)list.count, strCls, NULL);
    if (result) {
        for (size_t i = 0; i < list.count; ++i) {
            const SynkFileEntry *e = &list.items[i];
            size_t need = strlen(e->rel_path) + 160;
            char *line = (char *)malloc(need);
            if (line) {
                snprintf(line, need, "%d\t%lld\t%lld\t%s\t%s",
                         e->is_dir,
                         (long long)e->size,
                         (long long)e->mtime_ms,
                         e->hash,
                         e->rel_path);
                jstring js = (*env)->NewStringUTF(env, line);
                (*env)->SetObjectArrayElement(env, result, (jsize)i, js);
                (*env)->DeleteLocalRef(env, js);
                free(line);
            }
        }
    }
    synk_entry_list_free(&list);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_org_synk_core_NativeBridge_nativeDiff3Merge(JNIEnv *env, jclass cls,
                                                 jbyteArray jbase,
                                                 jbyteArray jlocal,
                                                 jbyteArray jremote,
                                                 jstring jlocalLabel,
                                                 jstring jremoteLabel) {
    (void)cls;
    jsize blen = jbase ? (*env)->GetArrayLength(env, jbase) : 0;
    jsize llen = jlocal ? (*env)->GetArrayLength(env, jlocal) : 0;
    jsize rlen = jremote ? (*env)->GetArrayLength(env, jremote) : 0;

    jbyte *bptr = jbase ? (*env)->GetByteArrayElements(env, jbase, NULL) : NULL;
    jbyte *lptr = jlocal ? (*env)->GetByteArrayElements(env, jlocal, NULL) : NULL;
    jbyte *rptr = jremote ? (*env)->GetByteArrayElements(env, jremote, NULL) : NULL;

    const char *llab = jlocalLabel ? (*env)->GetStringUTFChars(env, jlocalLabel, NULL) : "LOCAL";
    const char *rlab = jremoteLabel ? (*env)->GetStringUTFChars(env, jremoteLabel, NULL) : "REMOTE";

    SynkMergeResult res;
    int rc = synk_diff3_merge((const char *)bptr, (size_t)blen,
                              (const char *)lptr, (size_t)llen,
                              (const char *)rptr, (size_t)rlen,
                              llab, rlab, &res);

    if (jlocalLabel && llab) (*env)->ReleaseStringUTFChars(env, jlocalLabel, llab);
    if (jremoteLabel && rlab) (*env)->ReleaseStringUTFChars(env, jremoteLabel, rlab);
    if (bptr) (*env)->ReleaseByteArrayElements(env, jbase, bptr, JNI_ABORT);
    if (lptr) (*env)->ReleaseByteArrayElements(env, jlocal, lptr, JNI_ABORT);
    if (rptr) (*env)->ReleaseByteArrayElements(env, jremote, rptr, JNI_ABORT);

    if (rc != 0) return NULL;

    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    jclass intCls = (*env)->FindClass(env, "java/lang/Integer");
    jmethodID intValueOf = (*env)->GetStaticMethodID(env, intCls, "valueOf", "(I)Ljava/lang/Integer;");

    jobjectArray out = (*env)->NewObjectArray(env, 3, objCls, NULL);
    if (out) {
        jobject jBin = (*env)->CallStaticObjectMethod(env, intCls, intValueOf, (jint)res.is_binary);
        jobject jCnt = (*env)->CallStaticObjectMethod(env, intCls, intValueOf, (jint)res.conflict_count);
        (*env)->SetObjectArrayElement(env, out, 0, jBin);
        (*env)->SetObjectArrayElement(env, out, 1, jCnt);
        if (res.merged) {
            jbyteArray jMerged = (*env)->NewByteArray(env, (jsize)res.merged_len);
            if (jMerged && res.merged_len > 0) {
                (*env)->SetByteArrayRegion(env, jMerged, 0, (jsize)res.merged_len, (const jbyte *)res.merged);
            }
            (*env)->SetObjectArrayElement(env, out, 2, jMerged);
            (*env)->DeleteLocalRef(env, jMerged);
        }
        (*env)->DeleteLocalRef(env, jBin);
        (*env)->DeleteLocalRef(env, jCnt);
    }

    synk_merge_result_free(&res);
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_org_synk_core_NativeBridge_nativePlanSync(JNIEnv *env, jclass cls,
                                               jobjectArray jbase,
                                               jobjectArray jlocal,
                                               jobjectArray jremote,
                                               jint mode,
                                               jint policy) {
    (void)cls;
    SynkEntryList bl, ll, rl;
    parse_entries_from_java(env, jbase, &bl);
    parse_entries_from_java(env, jlocal, &ll);
    parse_entries_from_java(env, jremote, &rl);

    SynkPlan plan;
    int rc = synk_plan_sync(&bl, &ll, &rl, (SynkMode)mode, (SynkConflictPolicy)policy, &plan);

    synk_entry_list_free(&bl);
    synk_entry_list_free(&ll);
    synk_entry_list_free(&rl);

    if (rc != 0) return NULL;

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)plan.count, strCls, NULL);
    if (result) {
        for (size_t i = 0; i < plan.count; ++i) {
            const SynkPlanAction *a = &plan.items[i];
            size_t need = strlen(a->rel_path) + 320;
            char *line = (char *)malloc(need);
            if (line) {
                snprintf(line, need, "%d\t%d\t%d\t%lld\t%lld\t%lld\t%lld\t%s\t%s\t%s",
                         (int)a->action,
                         (int)a->conflict_kind,
                         a->is_dir,
                         (long long)a->local_size,
                         (long long)a->local_mtime_ms,
                         (long long)a->remote_size,
                         (long long)a->remote_mtime_ms,
                         a->local_hash,
                         a->remote_etag,
                         a->rel_path);
                jstring js = (*env)->NewStringUTF(env, line);
                (*env)->SetObjectArrayElement(env, result, (jsize)i, js);
                (*env)->DeleteLocalRef(env, js);
                free(line);
            }
        }
    }
    synk_plan_free(&plan);
    return result;
}

typedef struct {
    SynkWatcher *watcher;
    jobject      cb_global;
    jmethodID    cb_method;
} JniWatcherCtx;

static void jni_watch_trampoline(void *userdata, const char *last_rel_path, int event_count) {
    JniWatcherCtx *ctx = (JniWatcherCtx *)userdata;
    if (!ctx || !g_jvm || !ctx->cb_global) return;

    JNIEnv *env = NULL;
    int attached = 0;
    jint st = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
#ifdef __ANDROID__
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) return;
#else
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL) != 0) return;
#endif
        attached = 1;
    } else if (st != JNI_OK || !env) {
        return;
    }

    jstring jpath = (*env)->NewStringUTF(env, last_rel_path ? last_rel_path : "");
    (*env)->CallVoidMethod(env, ctx->cb_global, ctx->cb_method, jpath, (jint)event_count);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (jpath) (*env)->DeleteLocalRef(env, jpath);

    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

JNIEXPORT jlong JNICALL
Java_org_synk_core_NativeBridge_nativeStartWatcher(JNIEnv *env, jclass cls,
                                                   jstring jroot,
                                                   jobjectArray jignores,
                                                   jint debounceMs,
                                                   jobject callback) {
    (void)cls;
    if (!jroot || !callback) return 0;

    jclass cbCls = (*env)->GetObjectClass(env, callback);
    jmethodID mid = (*env)->GetMethodID(env, cbCls, "onDirectoryChanged", "(Ljava/lang/String;I)V");
    (*env)->DeleteLocalRef(env, cbCls);
    if (!mid) return 0;

    JniWatcherCtx *ctx = (JniWatcherCtx *)calloc(1, sizeof(JniWatcherCtx));
    if (!ctx) return 0;
    ctx->cb_global = (*env)->NewGlobalRef(env, callback);
    ctx->cb_method = mid;

    const char *root = (*env)->GetStringUTFChars(env, jroot, NULL);
    size_t ig_count = 0;
    char **ignores = copy_string_array(env, jignores, &ig_count);

    ctx->watcher = synk_watcher_start(root, (const char **)ignores, ig_count,
                                      (int)debounceMs, jni_watch_trampoline, ctx);

    free_string_array(ignores, ig_count);
    (*env)->ReleaseStringUTFChars(env, jroot, root);

    if (!ctx->watcher) {
        (*env)->DeleteGlobalRef(env, ctx->cb_global);
        free(ctx);
        return 0;
    }
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_org_synk_core_NativeBridge_nativeStopWatcher(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    if (handle == 0) return;
    JniWatcherCtx *ctx = (JniWatcherCtx *)(intptr_t)handle;
    if (ctx->watcher) {
        synk_watcher_stop(ctx->watcher);
        ctx->watcher = NULL;
    }
    if (ctx->cb_global) {
        (*env)->DeleteGlobalRef(env, ctx->cb_global);
        ctx->cb_global = NULL;
    }
    free(ctx);
}
