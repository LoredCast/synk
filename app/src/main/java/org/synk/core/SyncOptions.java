package org.synk.core;

/**
 * Fine-grained runtime sync configuration populated from Android Settings (SynkSettings).
 */
public final class SyncOptions {
    public int connectTimeoutMs = 15_000;
    public int readTimeoutMs = 60_000;
    public int bufferSizeBytes = 32_768;
    public int bandwidthLimitKBps = 0;      // 0 = unlimited
    public int maxFileSizeMB = 0;           // 0 = unlimited
    public int maxAutoMergeKB = 512;        // max text file size eligible for 3-way diff3 merge
    public int retryCount = 2;              // automatic retries on transient transfer failures
    public boolean preserveTimestamps = true;
    public boolean paranoidRehash = false;  // re-hash local files even when size & mtime match baseline
    public String deviceTag = "phone";      // used in conflict copy names & diff3 conflict markers
    public String globalIgnorePatterns = "";

    public static SyncOptions defaults() {
        return new SyncOptions();
    }

    public long maxFileSizeBytes() {
        return maxFileSizeMB > 0 ? maxFileSizeMB * 1024L * 1024L : 0L;
    }

    public int maxAutoMergeBytes() {
        return Math.max(16, maxAutoMergeKB) * 1024;
    }
}
