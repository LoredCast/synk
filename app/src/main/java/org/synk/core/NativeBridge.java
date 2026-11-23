package org.synk.core;

import org.synk.core.SyncModels.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridge to the C11 native engine (libsynk_native.so) with transparent pure-Java fallback.
 */
public final class NativeBridge {

    public interface WatchCallback {
        void onDirectoryChanged(String lastRelPath, int eventCount);
    }

    private static volatile boolean nativeLoaded = false;
    private static volatile boolean forceJavaFallback = false;
    private static final AtomicLong FALLBACK_WATCH_IDS = new AtomicLong(-1000L);
    private static final Map<Long, Thread> FALLBACK_WATCHERS = new ConcurrentHashMap<>();

    static {
        try {
            System.loadLibrary("synk_native");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            nativeLoaded = false;
        }
    }

    private NativeBridge() {}

    public static boolean isNativeBinaryAvailable() {
        return nativeLoaded;
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded && !forceJavaFallback;
    }

    public static void setPreferNative(boolean preferNative) {
        forceJavaFallback = !preferNative;
    }

    public static void setForceJavaFallbackForTest(boolean force) {
        forceJavaFallback = force;
    }

    public static String engineName() {
        return isNativeLoaded() ? "Native C11 (JNI)" : "Pure Java Fallback";
    }

    /* ---- Native JNI declarations (implemented in native/jni/synk_jni.c) ---- */
    private static native String nativeSha256File(String absPath);
    private static native String nativeSha256Bytes(byte[] data);
    private static native String[] nativeScanDir(String rootDir, String[] ignorePatterns, boolean computeHashes);
    private static native Object[] nativeDiff3Merge(byte[] base, byte[] local, byte[] remote,
                                                    String localLabel, String remoteLabel);
    private static native String[] nativePlanSync(String[] baseEntries, String[] localEntries,
                                                  String[] remoteEntries, int mode, int policy);
    private static native long nativeStartWatcher(String rootDir, String[] ignorePatterns,
                                                  int debounceMs, WatchCallback callback);
    private static native void nativeStopWatcher(long handle);

    /* ---- Public unified API ---- */

    public static String sha256Bytes(byte[] data) {
        if (isNativeLoaded()) {
            String hex = nativeSha256Bytes(data);
            if (hex != null) return hex;
        }
        return PureJavaFallback.sha256Bytes(data);
    }

    public static String sha256File(String absPath) throws IOException {
        if (isNativeLoaded()) {
            String hex = nativeSha256File(absPath);
            if (hex != null) return hex;
        }
        return PureJavaFallback.sha256File(absPath);
    }

    public static List<FileEntry> scanDir(String rootDir, String[] ignores, boolean computeHashes) {
        if (isNativeLoaded()) {
            String[] lines = nativeScanDir(rootDir, ignores, computeHashes);
            if (lines != null) {
                List<FileEntry> out = new ArrayList<>(lines.length);
                for (String line : lines) {
                    if (line == null) continue;
                    String[] p = line.split("\t", 5);
                    if (p.length == 5) {
                        boolean isDir = "1".equals(p[0]);
                        long size = parseLong(p[1]);
                        long mtime = parseLong(p[2]);
                        out.add(new FileEntry(p[4], isDir, size, mtime, p[3], ""));
                    }
                }
                return out;
            }
        }
        return PureJavaFallback.scanDir(rootDir, ignores, computeHashes);
    }

    public static MergeResult diff3Merge(byte[] base, byte[] local, byte[] remote,
                                         String localLabel, String remoteLabel) {
        if (isNativeLoaded()) {
            Object[] res = nativeDiff3Merge(base, local, remote, localLabel, remoteLabel);
            if (res != null && res.length == 3) {
                boolean isBin = ((Integer) res[0]) != 0;
                int conflicts = (Integer) res[1];
                byte[] merged = (byte[]) res[2];
                return new MergeResult(isBin, conflicts, merged);
            }
        }
        return PureJavaFallback.diff3Merge(base, local, remote, localLabel, remoteLabel);
    }

    public static List<PlanAction> planSync(List<FileEntry> base, List<FileEntry> local,
                                            List<FileEntry> remote, SyncMode mode, ConflictPolicy policy) {
        if (isNativeLoaded()) {
            String[] sb = encodeEntries(base);
            String[] sl = encodeEntries(local);
            String[] sr = encodeEntries(remote);
            String[] raw = nativePlanSync(sb, sl, sr, mode.code, policy.code);
            if (raw != null) {
                List<PlanAction> plan = new ArrayList<>(raw.length);
                for (String line : raw) {
                    if (line == null) continue;
                    String[] p = line.split("\t", 10);
                    if (p.length == 10) {
                        plan.add(new PlanAction(
                                p[9],
                                "1".equals(p[2]),
                                ActionType.fromCode(parseInt(p[0])),
                                ConflictKind.fromCode(parseInt(p[1])),
                                parseLong(p[3]),
                                parseLong(p[4]),
                                parseLong(p[5]),
                                parseLong(p[6]),
                                p[7],
                                p[8]
                        ));
                    }
                }
                return plan;
            }
        }
        return PureJavaFallback.planSync(base, local, remote, mode, policy);
    }

    public static long startWatcher(final String rootDir, final String[] ignores,
                                    final int debounceMs, final WatchCallback callback) {
        if (callback == null || rootDir == null) return 0L;
        if (isNativeLoaded()) {
            long h = nativeStartWatcher(rootDir, ignores, Math.max(50, debounceMs), callback);
            if (h != 0L) return h;
        }
        /* Fallback lightweight directory tree snapshot watcher when native inotify isn't loaded */
        final long id = FALLBACK_WATCH_IDS.decrementAndGet();
        final int pollInterval = Math.max(1500, debounceMs);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String prevSig = snapshotSignature(rootDir, ignores);
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(pollInterval);
                    } catch (InterruptedException e) {
                        break;
                    }
                    String curSig = snapshotSignature(rootDir, ignores);
                    if (!curSig.equals(prevSig)) {
                        prevSig = curSig;
                        callback.onDirectoryChanged("*", 1);
                    }
                }
            }
        }, "synk-watch-fallback");
        t.setDaemon(true);
        FALLBACK_WATCHERS.put(id, t);
        t.start();
        return id;
    }

    public static void stopWatcher(long handle) {
        if (handle == 0L) return;
        if (handle < 0L) {
            Thread t = FALLBACK_WATCHERS.remove(handle);
            if (t != null) t.interrupt();
            return;
        }
        if (nativeLoaded) {
            nativeStopWatcher(handle);
        }
    }

    private static String snapshotSignature(String rootDir, String[] ignores) {
        List<FileEntry> list = PureJavaFallback.scanDir(rootDir, ignores, false);
        StringBuilder sb = new StringBuilder(list.size() * 32);
        for (FileEntry e : list) {
            sb.append(e.relPath).append('|').append(e.isDir ? 'D' : 'F')
              .append('|').append(e.size).append('|').append(e.mtimeMs).append('\n');
        }
        return sb.toString();
    }

    private static String[] encodeEntries(List<FileEntry> list) {
        if (list == null || list.isEmpty()) return new String[0];
        String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            FileEntry e = list.get(i);
            out[i] = (e.isDir ? "1" : "0") + "\t" + e.size + "\t" + e.mtimeMs + "\t"
                    + sanitizeTab(e.hash) + "\t" + sanitizeTab(e.etag) + "\t" + e.relPath;
        }
        return out;
    }

    private static String sanitizeTab(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }
}
