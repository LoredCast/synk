package org.synk.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time sync telemetry (phase, active file, file/byte progress, EWMA speed, ETA, cancellation).
 */
public final class SyncProgress {

    public enum Phase {
        IDLE("Idle"),
        SCANNING_LOCAL("Scanning local"),
        LISTING_REMOTE("Listing remote"),
        PLANNING("Planning 3-way diff"),
        SYNCING("Transferring"),
        MERGING("Auto-merging"),
        FINALIZING("Finalizing");

        public final String label;
        Phase(String label) { this.label = label; }
    }

    public interface Listener {
        void onProgress(Snapshot snapshot);
    }

    /** Low-level transfer hook passed to HTTP/FTP/S3/Local backends for byte counts, throttling & cancel. */
    public interface TransferObserver {
        void onBytes(long deltaBytes);
        boolean isCancelled();
        int bufferSize();
        long maxBytesPerSec();
    }

    public static final TransferObserver NOOP_OBSERVER = new TransferObserver() {
        @Override public void onBytes(long deltaBytes) {}
        @Override public boolean isCancelled() { return false; }
        @Override public int bufferSize() { return 32768; }
        @Override public long maxBytesPerSec() { return 0L; }
    };

    public static final class Snapshot {
        public final long pairId;
        public final String pairName;
        public final boolean active;
        public final boolean cancelled;
        public final Phase phase;
        public final String actionLabel;
        public final String currentFile;
        public final int filesDone;
        public final int filesTotal;
        public final long currentFileBytes;
        public final long currentFileTotalBytes;
        public final long totalBytesDone;
        public final long totalBytesPlanned;
        public final long speedBytesPerSec;
        public final long etaSeconds;
        public final long elapsedMs;
        public final int uploaded;
        public final int downloaded;
        public final int merged;
        public final int deleted;
        public final int conflicts;
        public final int errors;

        public Snapshot(long pairId, String pairName, boolean active, boolean cancelled, Phase phase,
                        String actionLabel, String currentFile, int filesDone, int filesTotal,
                        long currentFileBytes, long currentFileTotalBytes,
                        long totalBytesDone, long totalBytesPlanned,
                        long speedBytesPerSec, long etaSeconds, long elapsedMs,
                        int uploaded, int downloaded, int merged, int deleted, int conflicts, int errors) {
            this.pairId = pairId;
            this.pairName = pairName;
            this.active = active;
            this.cancelled = cancelled;
            this.phase = phase;
            this.actionLabel = actionLabel;
            this.currentFile = currentFile;
            this.filesDone = filesDone;
            this.filesTotal = filesTotal;
            this.currentFileBytes = currentFileBytes;
            this.currentFileTotalBytes = currentFileTotalBytes;
            this.totalBytesDone = totalBytesDone;
            this.totalBytesPlanned = totalBytesPlanned;
            this.speedBytesPerSec = speedBytesPerSec;
            this.etaSeconds = etaSeconds;
            this.elapsedMs = elapsedMs;
            this.uploaded = uploaded;
            this.downloaded = downloaded;
            this.merged = merged;
            this.deleted = deleted;
            this.conflicts = conflicts;
            this.errors = errors;
        }

        public int overallPercent() {
            if (totalBytesPlanned > 0) {
                return (int) Math.min(100L, (totalBytesDone * 100L) / totalBytesPlanned);
            }
            if (filesTotal > 0) {
                return Math.min(100, (filesDone * 100) / filesTotal);
            }
            return active ? 0 : 100;
        }

        public int currentFilePercent() {
            if (currentFileTotalBytes <= 0) return 0;
            return (int) Math.min(100L, (currentFileBytes * 100L) / currentFileTotalBytes);
        }

        public boolean isIndeterminate() {
            return active && (phase == Phase.SCANNING_LOCAL || phase == Phase.LISTING_REMOTE
                    || phase == Phase.PLANNING || (filesTotal == 0 && totalBytesPlanned == 0));
        }
    }

    /** Reusable bandwidth throttle helper called inside streaming read/write loops. */
    public static void throttleIfNeeded(TransferObserver obs, long bytesInChunk, long[] windowState) {
        if (obs == null) return;
        long limit = obs.maxBytesPerSec();
        if (limit <= 0L || bytesInChunk <= 0L) return;
        long now = System.nanoTime();
        if (windowState[0] == 0L) {
            windowState[0] = now;
            windowState[1] = 0L;
        }
        windowState[1] += bytesInChunk;
        long expectedNs = (windowState[1] * 1_000_000_000L) / limit;
        long actualNs = now - windowState[0];
        if (expectedNs > actualNs) {
            long sleepMs = (expectedNs - actualNs) / 1_000_000L;
            if (sleepMs > 0) {
                try { Thread.sleep(Math.min(sleepMs, 500L)); } catch (InterruptedException ignored) {}
            }
        }
        if (actualNs > 1_000_000_000L) {
            windowState[0] = System.nanoTime();
            windowState[1] = 0L;
        }
    }

    public static final class Tracker implements TransferObserver {
        private final long pairId;
        private final String pairName;
        private final Listener listener;
        private final AtomicBoolean cancelFlag;
        private final int bufferBytes;
        private final long maxBytesPerSec;
        private final long startTimeMs;

        private Phase phase = Phase.SCANNING_LOCAL;
        private String actionLabel = "";
        private String currentFile = "";
        private int filesDone;
        private int filesTotal;
        private long currentFileBytes;
        private long currentFileTotalBytes;
        private long totalBytesDone;
        private long totalBytesPlanned;
        private int uploaded, downloaded, merged, deleted, conflicts, errors;

        /* Rolling EWMA transfer speed calculation */
        private long lastSpeedTickMs;
        private long bytesSinceSpeedTick;
        private double ewmaSpeedBps;
        private long lastEmitMs;

        public Tracker(long pairId, String pairName, SyncOptions opts,
                       AtomicBoolean cancelFlag, Listener listener) {
            this.pairId = pairId;
            this.pairName = pairName == null ? "" : pairName;
            this.listener = listener;
            this.cancelFlag = cancelFlag != null ? cancelFlag : new AtomicBoolean(false);
            this.bufferBytes = opts != null ? opts.bufferSizeBytes : 32768;
            this.maxBytesPerSec = opts != null && opts.bandwidthLimitKBps > 0
                    ? opts.bandwidthLimitKBps * 1024L : 0L;
            this.startTimeMs = System.currentTimeMillis();
            this.lastSpeedTickMs = this.startTimeMs;
        }

        public synchronized void setPhase(Phase phase, String detail) {
            this.phase = phase;
            this.actionLabel = phase.label;
            this.currentFile = detail == null ? "" : detail;
            emit(true);
        }

        public synchronized void setPlanTotals(int filesTotal, long totalBytesPlanned) {
            this.filesTotal = filesTotal;
            this.totalBytesPlanned = totalBytesPlanned;
            this.phase = Phase.SYNCING;
            emit(true);
        }

        public synchronized void beginFile(String actionLabel, String relPath, long fileTotalBytes) {
            this.phase = "Merging".equals(actionLabel) ? Phase.MERGING : Phase.SYNCING;
            this.actionLabel = actionLabel;
            this.currentFile = relPath;
            this.currentFileBytes = 0L;
            this.currentFileTotalBytes = Math.max(0L, fileTotalBytes);
            emit(true);
        }

        public synchronized void finishFile(int upDelta, int downDelta, int mergeDelta,
                                            int delDelta, int conflictDelta, int errDelta) {
            this.filesDone++;
            this.uploaded += upDelta;
            this.downloaded += downDelta;
            this.merged += mergeDelta;
            this.deleted += delDelta;
            this.conflicts += conflictDelta;
            this.errors += errDelta;
            if (currentFileTotalBytes > currentFileBytes) {
                long rem = currentFileTotalBytes - currentFileBytes;
                currentFileBytes = currentFileTotalBytes;
                totalBytesDone = Math.min(Math.max(totalBytesPlanned, totalBytesDone + rem), totalBytesDone + rem);
            }
            emit(true);
        }

        public synchronized void finishAll() {
            this.phase = Phase.IDLE;
            emit(true);
        }

        @Override
        public synchronized void onBytes(long deltaBytes) {
            if (deltaBytes <= 0L) return;
            currentFileBytes += deltaBytes;
            totalBytesDone += deltaBytes;
            bytesSinceSpeedTick += deltaBytes;

            long now = System.currentTimeMillis();
            long dt = now - lastSpeedTickMs;
            if (dt >= 200L) {
                double instBps = (bytesSinceSpeedTick * 1000.0) / dt;
                ewmaSpeedBps = (ewmaSpeedBps <= 0.1) ? instBps : (0.35 * instBps + 0.65 * ewmaSpeedBps);
                lastSpeedTickMs = now;
                bytesSinceSpeedTick = 0L;
            }
            if (now - lastEmitMs >= 120L) {
                emit(false);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelFlag.get();
        }

        @Override
        public int bufferSize() {
            return bufferBytes;
        }

        @Override
        public long maxBytesPerSec() {
            return maxBytesPerSec;
        }

        public synchronized Snapshot snapshot(boolean active) {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(1L, now - startTimeMs);
            long speed = (long) ewmaSpeedBps;
            if (speed <= 0L && totalBytesDone > 0L && elapsed >= 50L) {
                speed = (totalBytesDone * 1000L) / elapsed;
            }
            long eta = -1L;
            if (speed > 0L && totalBytesPlanned > totalBytesDone) {
                eta = (totalBytesPlanned - totalBytesDone) / speed;
            }
            return new Snapshot(
                    pairId, pairName, active, cancelFlag.get(), phase, actionLabel, currentFile,
                    filesDone, filesTotal, currentFileBytes, currentFileTotalBytes,
                    totalBytesDone, totalBytesPlanned, speed, eta, elapsed,
                    uploaded, downloaded, merged, deleted, conflicts, errors
            );
        }

        private void emit(boolean force) {
            if (listener == null) return;
            long now = System.currentTimeMillis();
            if (!force && (now - lastEmitMs) < 120L) return;
            lastEmitMs = now;
            listener.onProgress(snapshot(phase != Phase.IDLE));
        }
    }
}
