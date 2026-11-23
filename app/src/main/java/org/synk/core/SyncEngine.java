package org.synk.core;

import org.synk.core.SyncModels.*;
import org.synk.protocol.HttpUtil;
import org.synk.protocol.RemoteBackend;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateful 3-way file synchronization engine with diff3 auto-merge, atomic transfers,
 * live byte/speed/ETA progress telemetry, retry/backoff, and interactive conflict resolution.
 */
public final class SyncEngine {

    private static final int MAX_TEXT_BASE_SNAPSHOT_BYTES = 256 * 1024;
    private static final long MTIME_FAST_TOLERANCE_MS = 2000L;
    private static final ConcurrentHashMap<Long, Object> PAIR_LOCKS = new ConcurrentHashMap<>();

    private final SyncStateStore store;

    public SyncEngine(SyncStateStore store) {
        this.store = store;
    }

    private static Object lockForPair(long pairId) {
        Object created = new Object();
        Object existing = PAIR_LOCKS.putIfAbsent(pairId, created);
        return existing != null ? existing : created;
    }

    public SyncResult syncPair(SyncPair pair, RemoteBackend backend) {
        return syncPair(pair, backend, SyncOptions.defaults(), null, null);
    }

    public SyncResult syncPair(SyncPair pair, RemoteBackend backend, SyncOptions opts,
                               AtomicBoolean cancelFlag, SyncProgress.Listener progressListener) {
        synchronized (lockForPair(pair.id)) {
            return syncPairLocked(pair, backend, opts != null ? opts : SyncOptions.defaults(),
                    cancelFlag != null ? cancelFlag : new AtomicBoolean(false), progressListener);
        }
    }

    private SyncResult syncPairLocked(SyncPair pair, RemoteBackend backend, SyncOptions opts,
                                      AtomicBoolean cancelFlag, SyncProgress.Listener progressListener) {
        long start = System.currentTimeMillis();
        SyncResult res = new SyncResult();
        HttpUtil.setTimeouts(opts.connectTimeoutMs, opts.readTimeoutMs);

        SyncProgress.Tracker tracker = new SyncProgress.Tracker(
                pair.id, pair.name, opts, cancelFlag, progressListener);

        File localRoot = new File(pair.localPath);
        if (!localRoot.exists() && !localRoot.mkdirs()) {
            res.errors++;
            String msg = "Cannot create local folder: " + pair.localPath;
            res.messages.add(msg);
            store.appendLog(pair.name, "ERROR", msg);
            store.updatePairStatus(pair.id, System.currentTimeMillis(), "Error: " + msg);
            res.durationMs = System.currentTimeMillis() - start;
            tracker.finishAll();
            return res;
        }

        String combinedIgnore = (opts.globalIgnorePatterns == null ? "" : opts.globalIgnorePatterns)
                + "\n" + (pair.ignorePatterns == null ? "" : pair.ignorePatterns);
        String[] ignores = IgnoreMatcher.parsePatterns(combinedIgnore);
        long maxFileBytes = opts.maxFileSizeBytes();

        try {
            /* 1. Load baseline */
            tracker.setPhase(SyncProgress.Phase.SCANNING_LOCAL, pair.localPath);
            List<FileEntry> baseline = store.loadBaseline(pair.id);
            Map<String, FileEntry> baseMap = new HashMap<>(baseline.size() * 2);
            for (FileEntry e : baseline) baseMap.put(e.relPath, e);

            /* 2. Fast local scan (lstat first, hash only when changed or paranoidRehash enabled) */
            List<FileEntry> rawLocal = NativeBridge.scanDir(localRoot.getAbsolutePath(), ignores, false);
            List<FileEntry> localEntries = new ArrayList<>(rawLocal.size());
            for (FileEntry e : rawLocal) {
                if (cancelFlag.get()) throw new IOException("Sync cancelled");
                if (e.isDir) {
                    localEntries.add(e);
                    continue;
                }
                if (maxFileBytes > 0 && e.size > maxFileBytes) {
                    continue; // respect max single file size limit
                }
                FileEntry b = baseMap.get(e.relPath);
                String hash;
                if (!opts.paranoidRehash && b != null && !b.isDir && !b.hash.isEmpty()
                        && b.size == e.size
                        && Math.abs(b.mtimeMs - e.mtimeMs) <= MTIME_FAST_TOLERANCE_MS) {
                    hash = b.hash;
                } else {
                    hash = NativeBridge.sha256File(new File(localRoot, e.relPath).getAbsolutePath());
                }
                localEntries.add(new FileEntry(e.relPath, false, e.size, e.mtimeMs, hash, ""));
            }

            /* 3. List remote tree */
            if (cancelFlag.get()) throw new IOException("Sync cancelled");
            tracker.setPhase(SyncProgress.Phase.LISTING_REMOTE, pair.remotePath);
            List<FileEntry> rawRemote = backend.listRecursive(pair.remotePath, ignores);
            List<FileEntry> remoteEntries = new ArrayList<>(rawRemote.size());
            for (FileEntry e : rawRemote) {
                if (!e.isDir && maxFileBytes > 0 && e.size > maxFileBytes) continue;
                remoteEntries.add(e);
            }

            Map<String, FileEntry> remoteMap = new HashMap<>(remoteEntries.size() * 2);
            for (FileEntry e : remoteEntries) remoteMap.put(e.relPath, e);
            Map<String, FileEntry> localMap = new HashMap<>(localEntries.size() * 2);
            for (FileEntry e : localEntries) localMap.put(e.relPath, e);

            /* 4. Plan 3-way actions using C11 native planner (or Java mirror) */
            if (cancelFlag.get()) throw new IOException("Sync cancelled");
            tracker.setPhase(SyncProgress.Phase.PLANNING,
                    localEntries.size() + " local / " + remoteEntries.size() + " remote");
            List<PlanAction> plan = NativeBridge.planSync(
                    baseline, localEntries, remoteEntries, pair.mode, pair.conflictPolicy);

            /* Compute planned byte & file totals for accurate progress bar & ETA */
            int plannedFiles = 0;
            long plannedBytes = 0L;
            for (PlanAction act : plan) {
                if (act.action == ActionType.NONE) continue;
                plannedFiles++;
                if (act.action == ActionType.UPLOAD) plannedBytes += Math.max(0L, act.localSize);
                else if (act.action == ActionType.DOWNLOAD) plannedBytes += Math.max(0L, act.remoteSize);
                else if (act.action == ActionType.TRY_MERGE || act.action == ActionType.CONFLICT_KEEP_BOTH) {
                    plannedBytes += Math.max(0L, act.localSize) + Math.max(0L, act.remoteSize);
                }
            }
            tracker.setPlanTotals(plannedFiles, plannedBytes);

            /* 5. Execute planned actions atomically with retry support */
            for (PlanAction act : plan) {
                if (cancelFlag.get()) {
                    store.appendLog(pair.name, "WARN", "Sync cancelled by user");
                    break;
                }
                int upBefore = res.uploaded, downBefore = res.downloaded, mergeBefore = res.merged;
                int delBefore = res.deletedLocal + res.deletedRemote;
                int confBefore = res.conflicts, errBefore = res.errors;

                long actBytes = estimateBytes(act);
                tracker.beginFile(actionDisplayLabel(act.action), act.relPath, actBytes);

                try {
                    executeWithRetries(pair, localRoot, backend, act, localMap, remoteMap, opts, tracker, res);
                } catch (Exception itemErr) {
                    if (cancelFlag.get()) {
                        store.appendLog(pair.name, "WARN", "Sync cancelled during " + act.relPath);
                        break;
                    }
                    res.errors++;
                    String emsg = act.action + " failed for " + act.relPath + ": " + itemErr.getMessage();
                    res.messages.add(emsg);
                    store.appendLog(pair.name, "ERROR", emsg);
                }

                tracker.finishFile(
                        res.uploaded - upBefore,
                        res.downloaded - downBefore,
                        res.merged - mergeBefore,
                        (res.deletedLocal + res.deletedRemote) - delBefore,
                        res.conflicts - confBefore,
                        res.errors - errBefore);
            }

            tracker.setPhase(SyncProgress.Phase.FINALIZING, "");
            res.durationMs = System.currentTimeMillis() - start;
            String summary = formatCompletionSummary(res, cancelFlag.get());
            store.updatePairStatus(pair.id, System.currentTimeMillis(), summary);
            store.appendLog(pair.name, (res.errors > 0 || cancelFlag.get()) ? "WARN" : "INFO",
                    "Sync finished [" + pair.mode.label + "]: " + summary);
        } catch (Exception fatal) {
            res.durationMs = System.currentTimeMillis() - start;
            if (cancelFlag.get()) {
                String msg = "Cancelled (" + res.summary() + ")";
                store.appendLog(pair.name, "WARN", msg);
                store.updatePairStatus(pair.id, System.currentTimeMillis(), msg);
            } else {
                res.errors++;
                String msg = "Sync failed: " + fatal.getMessage();
                res.messages.add(msg);
                store.appendLog(pair.name, "ERROR", msg);
                store.updatePairStatus(pair.id, System.currentTimeMillis(), msg);
            }
        } finally {
            tracker.finishAll();
        }
        return res;
    }

    private void executeWithRetries(SyncPair pair, File localRoot, RemoteBackend backend, PlanAction act,
                                    Map<String, FileEntry> localMap, Map<String, FileEntry> remoteMap,
                                    SyncOptions opts, SyncProgress.Tracker tracker, SyncResult res) throws IOException {
        int maxAttempts = Math.max(1, 1 + opts.retryCount);
        IOException lastErr = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                executeAction(pair, localRoot, backend, act, localMap, remoteMap, opts, tracker, res);
                return;
            } catch (IOException e) {
                lastErr = e;
                if (tracker.isCancelled() || attempt == maxAttempts) throw e;
                long backoffMs = 400L * attempt * attempt;
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { throw e; }
            }
        }
        if (lastErr != null) throw lastErr;
    }

    private void executeAction(SyncPair pair, File localRoot, RemoteBackend backend, PlanAction act,
                               Map<String, FileEntry> localMap, Map<String, FileEntry> remoteMap,
                               SyncOptions opts, SyncProgress.Tracker tracker, SyncResult res) throws IOException {
        File localFile = new File(localRoot, act.relPath);
        switch (act.action) {
            case MKDIR_LOCAL: {
                if (!localFile.exists() && !localFile.mkdirs()) {
                    throw new IOException("mkdirs failed: " + localFile.getAbsolutePath());
                }
                FileEntry r = remoteMap.get(act.relPath);
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, true, 0L, localFile.lastModified(), "", r != null ? r.etag : ""),
                        null);
                break;
            }
            case MKDIR_REMOTE: {
                backend.mkdir(pair.remotePath, act.relPath);
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, true, 0L, localFile.lastModified(), "", ""),
                        null);
                break;
            }
            case UPLOAD: {
                if (!localFile.isFile()) break;
                FileEntry uploaded = backend.upload(pair.remotePath, act.relPath, localFile, tracker);
                String hash = !act.localHash.isEmpty() ? act.localHash
                        : NativeBridge.sha256File(localFile.getAbsolutePath());
                byte[] snap = readSmallTextSnapshot(localFile);
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, false, localFile.length(),
                                localFile.lastModified(), hash, uploaded.etag),
                        snap);
                store.removeConflict(pair.id, act.relPath);
                res.uploaded++;
                res.bytesTransferred += localFile.length();
                break;
            }
            case DOWNLOAD: {
                FileEntry dl = downloadAtomic(backend, pair.remotePath, act.relPath, localFile, opts, tracker);
                String hash = NativeBridge.sha256File(localFile.getAbsolutePath());
                byte[] snap = readSmallTextSnapshot(localFile);
                String etag = !dl.etag.isEmpty() ? dl.etag : act.remoteEtag;
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, false, localFile.length(),
                                localFile.lastModified(), hash, etag),
                        snap);
                store.removeConflict(pair.id, act.relPath);
                res.downloaded++;
                res.bytesTransferred += localFile.length();
                break;
            }
            case DELETE_LOCAL: {
                if (localFile.exists()) {
                    if (localFile.isDirectory()) {
                        File[] kids = localFile.listFiles();
                        if (kids != null && kids.length > 0) break;
                    }
                    if (!localFile.delete()) {
                        throw new IOException("Could not delete local path: " + act.relPath);
                    }
                    res.deletedLocal++;
                }
                store.removeBaselineEntry(pair.id, act.relPath);
                store.removeConflict(pair.id, act.relPath);
                break;
            }
            case DELETE_REMOTE: {
                backend.delete(pair.remotePath, act.relPath, act.isDir);
                store.removeBaselineEntry(pair.id, act.relPath);
                store.removeConflict(pair.id, act.relPath);
                res.deletedRemote++;
                break;
            }
            case UPDATE_BASE: {
                FileEntry l = localMap.get(act.relPath);
                FileEntry r = remoteMap.get(act.relPath);
                String hash = l != null ? l.hash : act.localHash;
                String etag = r != null ? r.etag : act.remoteEtag;
                long size = l != null ? l.size : (r != null ? r.size : 0L);
                long mtime = l != null ? l.mtimeMs : (r != null ? r.mtimeMs : 0L);
                byte[] snap = (!act.isDir && localFile.isFile()) ? readSmallTextSnapshot(localFile) : null;
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, act.isDir, size, mtime, hash, etag), snap);
                store.removeConflict(pair.id, act.relPath);
                break;
            }
            case REMOVE_BASE: {
                store.removeBaselineEntry(pair.id, act.relPath);
                store.removeConflict(pair.id, act.relPath);
                break;
            }
            case TRY_MERGE: {
                handleTryMerge(pair, localRoot, backend, act, opts, tracker, res);
                break;
            }
            case CONFLICT_KEEP_BOTH: {
                handleKeepBoth(pair, localRoot, backend, act, opts, tracker, res);
                break;
            }
            case CONFLICT_ASK: {
                recordConflict(pair, localFile, backend, act, null, opts);
                res.conflicts++;
                break;
            }
            case NONE:
            default:
                break;
        }
    }

    private void handleTryMerge(SyncPair pair, File localRoot, RemoteBackend backend,
                                PlanAction act, SyncOptions opts,
                                SyncProgress.Tracker tracker, SyncResult res) throws IOException {
        File localFile = new File(localRoot, act.relPath);
        File tmpRemote = new File(localRoot, ".synk.tmp.merge." + System.nanoTime());
        String tag = sanitizeTag(opts.deviceTag, "");
        String localLabel = (tag.isEmpty() || "LOCAL".equalsIgnoreCase(tag)) ? "LOCAL" : ("LOCAL (" + tag + ")");
        try {
            FileEntry remoteMeta = backend.download(pair.remotePath, act.relPath, tmpRemote, tracker);
            String remoteHash = NativeBridge.sha256File(tmpRemote.getAbsolutePath());
            String localHash = NativeBridge.sha256File(localFile.getAbsolutePath());

            if (remoteHash.equals(localHash)) {
                String etag = !remoteMeta.etag.isEmpty() ? remoteMeta.etag : act.remoteEtag;
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, false, localFile.length(),
                                localFile.lastModified(), localHash, etag),
                        readSmallTextSnapshot(localFile));
                store.removeConflict(pair.id, act.relPath);
                return;
            }

            int maxMerge = opts.maxAutoMergeBytes();
            if (localFile.length() <= maxMerge && tmpRemote.length() <= maxMerge) {
                byte[] baseBytes = store.loadBaseTextSnapshot(pair.id, act.relPath);
                if (baseBytes == null) baseBytes = new byte[0];
                byte[] localBytes = readAllBytes(localFile);
                byte[] remoteBytes = readAllBytes(tmpRemote);

                MergeResult mr = NativeBridge.diff3Merge(baseBytes, localBytes, remoteBytes, localLabel, "REMOTE");
                if (!mr.isBinary && mr.conflictCount == 0 && mr.mergedBytes != null) {
                    writeAtomic(localFile, mr.mergedBytes);
                    FileEntry uploaded = backend.upload(pair.remotePath, act.relPath, localFile, tracker);
                    String mergedHash = NativeBridge.sha256Bytes(mr.mergedBytes);
                    byte[] snap = mr.mergedBytes.length <= MAX_TEXT_BASE_SNAPSHOT_BYTES ? mr.mergedBytes : null;
                    store.upsertBaselineEntry(pair.id,
                            new FileEntry(act.relPath, false, localFile.length(),
                                    localFile.lastModified(), mergedHash, uploaded.etag),
                            snap);
                    store.removeConflict(pair.id, act.relPath);
                    res.merged++;
                    res.bytesTransferred += mr.mergedBytes.length;
                    store.appendLog(pair.name, "INFO", "Auto-merged " + act.relPath);
                    return;
                }

                String preview = null;
                if (!mr.isBinary && mr.mergedBytes != null && mr.mergedBytes.length <= 128 * 1024) {
                    preview = new String(mr.mergedBytes, StandardCharsets.UTF_8);
                }
                ConflictKind kind = !mr.isBinary ? ConflictKind.MERGE_OVERLAP : act.conflictKind;
                recordConflict(pair, localFile, backend, new PlanAction(
                        act.relPath, false, ActionType.CONFLICT_ASK, kind,
                        localFile.length(), localFile.lastModified(),
                        tmpRemote.length(), remoteMeta.mtimeMs, localHash,
                        !remoteMeta.etag.isEmpty() ? remoteMeta.etag : act.remoteEtag), preview, opts);
                res.conflicts++;
                return;
            }

            recordConflict(pair, localFile, backend, act, null, opts);
            res.conflicts++;
        } finally {
            tmpRemote.delete();
        }
    }

    private void handleKeepBoth(SyncPair pair, File localRoot, RemoteBackend backend,
                                PlanAction act, SyncOptions opts,
                                SyncProgress.Tracker tracker, SyncResult res) throws IOException {
        File localFile = new File(localRoot, act.relPath);
        File tmpRemote = new File(localRoot, ".synk.tmp.kb." + System.nanoTime());
        try {
            FileEntry remoteMeta = backend.download(pair.remotePath, act.relPath, tmpRemote, tracker);
            String remoteHash = NativeBridge.sha256File(tmpRemote.getAbsolutePath());
            String localHash = NativeBridge.sha256File(localFile.getAbsolutePath());
            if (remoteHash.equals(localHash)) {
                String etag = !remoteMeta.etag.isEmpty() ? remoteMeta.etag : act.remoteEtag;
                store.upsertBaselineEntry(pair.id,
                        new FileEntry(act.relPath, false, localFile.length(),
                                localFile.lastModified(), localHash, etag),
                        readSmallTextSnapshot(localFile));
                store.removeConflict(pair.id, act.relPath);
                return;
            }

            String conflictRel = makeConflictCopyName(act.relPath, opts.deviceTag);
            File conflictLocal = new File(localRoot, conflictRel);
            copyFile(localFile, conflictLocal);
            conflictLocal.setLastModified(localFile.lastModified());

            replaceFile(tmpRemote, localFile);
            if (opts.preserveTimestamps && remoteMeta.mtimeMs > 0) {
                localFile.setLastModified(remoteMeta.mtimeMs);
            }

            FileEntry conflictUp = backend.upload(pair.remotePath, conflictRel, conflictLocal, tracker);

            String etag = !remoteMeta.etag.isEmpty() ? remoteMeta.etag : act.remoteEtag;
            store.upsertBaselineEntry(pair.id,
                    new FileEntry(act.relPath, false, localFile.length(),
                            localFile.lastModified(), remoteHash, etag),
                    readSmallTextSnapshot(localFile));
            store.upsertBaselineEntry(pair.id,
                    new FileEntry(conflictRel, false, conflictLocal.length(),
                            conflictLocal.lastModified(), localHash, conflictUp.etag),
                    readSmallTextSnapshot(conflictLocal));
            store.removeConflict(pair.id, act.relPath);

            res.downloaded++;
            res.uploaded++;
            res.bytesTransferred += localFile.length() + conflictLocal.length();
            store.appendLog(pair.name, "WARN", "Kept both copies: " + act.relPath + " & " + conflictRel);
        } finally {
            tmpRemote.delete();
        }
    }

    private void recordConflict(SyncPair pair, File localFile, RemoteBackend backend,
                                PlanAction act, String precomputedPreview, SyncOptions opts) {
        ConflictRecord rec = new ConflictRecord();
        rec.pairId = pair.id;
        rec.pairName = pair.name;
        rec.relPath = act.relPath;
        rec.kind = act.conflictKind;
        rec.localSize = act.localSize;
        rec.localMtimeMs = act.localMtimeMs;
        rec.localHash = act.localHash;
        rec.remoteSize = act.remoteSize;
        rec.remoteMtimeMs = act.remoteMtimeMs;
        rec.remoteEtag = act.remoteEtag;
        rec.mergedPreview = precomputedPreview;
        rec.detectedAt = System.currentTimeMillis();

        if (rec.mergedPreview == null
                && (act.conflictKind == ConflictKind.BOTH_MODIFIED || act.conflictKind == ConflictKind.BOTH_CREATED)
                && localFile.isFile() && localFile.length() <= 128 * 1024 && act.remoteSize <= 128 * 1024) {
            File tmp = new File(localFile.getParentFile(), ".synk.tmp.prev." + System.nanoTime());
            try {
                backend.download(pair.remotePath, act.relPath, tmp);
                byte[] base = store.loadBaseTextSnapshot(pair.id, act.relPath);
                if (base == null) base = new byte[0];
                String tag = sanitizeTag(opts.deviceTag, "");
                String localLabel = (tag.isEmpty() || "LOCAL".equalsIgnoreCase(tag)) ? "LOCAL" : ("LOCAL (" + tag + ")");
                MergeResult mr = NativeBridge.diff3Merge(
                        base, readAllBytes(localFile), readAllBytes(tmp), localLabel, "REMOTE");
                if (!mr.isBinary && mr.mergedBytes != null) {
                    rec.mergedPreview = new String(mr.mergedBytes, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            } finally {
                tmp.delete();
            }
        }

        store.upsertConflict(rec);
        store.appendLog(pair.name, "WARN", "Conflict (" + rec.kind.label + "): " + rec.relPath);
    }

    public void resolveConflict(SyncPair pair, RemoteBackend backend,
                                ConflictRecord conflict, ConflictResolution choice,
                                byte[] customMergedBytes) throws IOException {
        resolveConflict(pair, backend, conflict, choice, customMergedBytes, SyncOptions.defaults());
    }

    public void resolveConflict(SyncPair pair, RemoteBackend backend,
                                ConflictRecord conflict, ConflictResolution choice,
                                byte[] customMergedBytes, SyncOptions opts) throws IOException {
        SyncOptions o = opts != null ? opts : SyncOptions.defaults();
        synchronized (lockForPair(pair.id)) {
            File localRoot = new File(pair.localPath);
            File localFile = new File(localRoot, conflict.relPath);

            switch (choice) {
                case KEEP_LOCAL: {
                    if (localFile.exists()) {
                        FileEntry up = backend.upload(pair.remotePath, conflict.relPath, localFile);
                        String hash = NativeBridge.sha256File(localFile.getAbsolutePath());
                        store.upsertBaselineEntry(pair.id,
                                new FileEntry(conflict.relPath, false, localFile.length(),
                                        localFile.lastModified(), hash, up.etag),
                                readSmallTextSnapshot(localFile));
                    } else {
                        backend.delete(pair.remotePath, conflict.relPath, false);
                        store.removeBaselineEntry(pair.id, conflict.relPath);
                    }
                    store.removeConflict(pair.id, conflict.relPath);
                    store.appendLog(pair.name, "INFO", "Resolved (Keep Local): " + conflict.relPath);
                    break;
                }
                case KEEP_REMOTE: {
                    if (conflict.kind == ConflictKind.LOCAL_MOD_REMOTE_DEL) {
                        if (localFile.exists()) localFile.delete();
                        store.removeBaselineEntry(pair.id, conflict.relPath);
                    } else {
                        FileEntry dl = downloadAtomic(backend, pair.remotePath, conflict.relPath,
                                localFile, o, SyncProgress.NOOP_OBSERVER);
                        String hash = NativeBridge.sha256File(localFile.getAbsolutePath());
                        store.upsertBaselineEntry(pair.id,
                                new FileEntry(conflict.relPath, false, localFile.length(),
                                        localFile.lastModified(), hash,
                                        !dl.etag.isEmpty() ? dl.etag : conflict.remoteEtag),
                                readSmallTextSnapshot(localFile));
                    }
                    store.removeConflict(pair.id, conflict.relPath);
                    store.appendLog(pair.name, "INFO", "Resolved (Keep Remote): " + conflict.relPath);
                    break;
                }
                case KEEP_BOTH: {
                    if (conflict.kind == ConflictKind.LOCAL_MOD_REMOTE_DEL) {
                        resolveConflict(pair, backend, conflict, ConflictResolution.KEEP_LOCAL, null, o);
                        return;
                    }
                    if (conflict.kind == ConflictKind.LOCAL_DEL_REMOTE_MOD) {
                        resolveConflict(pair, backend, conflict, ConflictResolution.KEEP_REMOTE, null, o);
                        return;
                    }
                    String conflictRel = makeConflictCopyName(conflict.relPath, o.deviceTag);
                    File conflictLocal = new File(localRoot, conflictRel);
                    copyFile(localFile, conflictLocal);
                    conflictLocal.setLastModified(localFile.lastModified());
                    String localHash = NativeBridge.sha256File(conflictLocal.getAbsolutePath());
                    FileEntry up = backend.upload(pair.remotePath, conflictRel, conflictLocal);
                    store.upsertBaselineEntry(pair.id,
                            new FileEntry(conflictRel, false, conflictLocal.length(),
                                    conflictLocal.lastModified(), localHash, up.etag),
                            readSmallTextSnapshot(conflictLocal));

                    FileEntry dl = downloadAtomic(backend, pair.remotePath, conflict.relPath,
                            localFile, o, SyncProgress.NOOP_OBSERVER);
                    String remoteHash = NativeBridge.sha256File(localFile.getAbsolutePath());
                    store.upsertBaselineEntry(pair.id,
                            new FileEntry(conflict.relPath, false, localFile.length(),
                                    localFile.lastModified(), remoteHash,
                                    !dl.etag.isEmpty() ? dl.etag : conflict.remoteEtag),
                            readSmallTextSnapshot(localFile));
                    store.removeConflict(pair.id, conflict.relPath);
                    store.appendLog(pair.name, "INFO", "Resolved (Keep Both): " + conflict.relPath + " -> " + conflictRel);
                    break;
                }
                case USE_MERGED: {
                    byte[] data = customMergedBytes != null ? customMergedBytes
                            : (conflict.mergedPreview != null
                                ? conflict.mergedPreview.getBytes(StandardCharsets.UTF_8) : null);
                    if (data == null) throw new IOException("No merged text available");
                    writeAtomic(localFile, data);
                    FileEntry up = backend.upload(pair.remotePath, conflict.relPath, localFile);
                    String hash = NativeBridge.sha256Bytes(data);
                    byte[] snap = data.length <= MAX_TEXT_BASE_SNAPSHOT_BYTES ? data : null;
                    store.upsertBaselineEntry(pair.id,
                            new FileEntry(conflict.relPath, false, localFile.length(),
                                    localFile.lastModified(), hash, up.etag),
                            snap);
                    store.removeConflict(pair.id, conflict.relPath);
                    store.appendLog(pair.name, "INFO", "Resolved (Merged): " + conflict.relPath);
                    break;
                }
            }
        }
    }

    /* ---- Formatting & atomic helpers ---- */

    private static long estimateBytes(PlanAction act) {
        switch (act.action) {
            case UPLOAD: return act.localSize;
            case DOWNLOAD: return act.remoteSize;
            case TRY_MERGE:
            case CONFLICT_KEEP_BOTH: return act.localSize + act.remoteSize;
            default: return 0L;
        }
    }

    private static String actionDisplayLabel(ActionType a) {
        switch (a) {
            case UPLOAD: return "Uploading";
            case DOWNLOAD: return "Downloading";
            case TRY_MERGE: return "Merging";
            case CONFLICT_KEEP_BOTH: return "Keeping Both";
            case MKDIR_LOCAL:
            case MKDIR_REMOTE: return "Creating folder";
            case DELETE_LOCAL:
            case DELETE_REMOTE: return "Deleting";
            case CONFLICT_ASK: return "Conflict";
            default: return "Indexing";
        }
    }

    private static String formatCompletionSummary(SyncResult r, boolean cancelled) {
        StringBuilder sb = new StringBuilder();
        if (cancelled) sb.append("Cancelled: ");
        sb.append(r.summary());
        if (r.bytesTransferred > 0) {
            long secMs = Math.max(1L, r.durationMs);
            long bps = (r.bytesTransferred * 1000L) / secMs;
            sb.append(" • ").append(humanBytes(r.bytesTransferred))
              .append(" @ ").append(humanBytes(bps)).append("/s");
        }
        sb.append(" (").append(r.durationMs < 1000 ? (r.durationMs + "ms")
                : String.format(Locale.US, "%.1fs", r.durationMs / 1000.0)).append(')');
        return sb.toString();
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private static FileEntry downloadAtomic(RemoteBackend backend, String remoteBasePath,
                                            String relPath, File targetFile, SyncOptions opts,
                                            SyncProgress.TransferObserver observer) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, ".synk.tmp." + System.nanoTime());
        try {
            FileEntry meta = backend.download(remoteBasePath, relPath, tmp, observer);
            replaceFile(tmp, targetFile);
            if (opts.preserveTimestamps && meta.mtimeMs > 0) {
                targetFile.setLastModified(meta.mtimeMs);
            }
            return meta;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, ".synk.tmp." + System.nanoTime());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
                fos.getFD().sync();
            }
            replaceFile(tmp, target);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void replaceFile(File tmp, File target) throws IOException {
        if (!tmp.renameTo(target)) {
            copyFile(tmp, target);
            tmp.delete();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static byte[] readAllBytes(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), 65536));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readSmallTextSnapshot(File f) {
        if (!f.isFile() || f.length() > MAX_TEXT_BASE_SNAPSHOT_BYTES) return null;
        try {
            byte[] data = readAllBytes(f);
            int check = Math.min(data.length, 4096);
            for (int i = 0; i < check; i++) {
                if (data[i] == 0) return null;
            }
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    private static String sanitizeTag(String tag, String fallback) {
        if (tag == null) return fallback;
        String clean = tag.trim().replaceAll("[^a-zA-Z0-9._-]", "");
        return clean.isEmpty() ? fallback : clean;
    }

    public static String makeConflictCopyName(String relPath) {
        return makeConflictCopyName(relPath, "");
    }

    public static String makeConflictCopyName(String relPath, String deviceTag) {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String tag = sanitizeTag(deviceTag, "");
        String suffix = tag.isEmpty() ? (".conflict-" + ts) : (".conflict-" + tag + "-" + ts);
        int slash = relPath.lastIndexOf('/');
        int dot = relPath.lastIndexOf('.');
        if (dot > slash + 1) {
            return relPath.substring(0, dot) + suffix + relPath.substring(dot);
        }
        return relPath + suffix;
    }
}
