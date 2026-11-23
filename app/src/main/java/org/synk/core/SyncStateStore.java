package org.synk.core;

import org.synk.core.SyncModels.*;

import java.util.List;

/**
 * Storage abstraction for baseline sync snapshots, conflicts, and logs.
 * Implemented by Android SQLite (SynkDbHelper) and by an in-memory store for JVM tests.
 */
public interface SyncStateStore {

    List<FileEntry> loadBaseline(long pairId);

    void upsertBaselineEntry(long pairId, FileEntry entry, byte[] baseTextSnapshot);

    void removeBaselineEntry(long pairId, String relPath);

    byte[] loadBaseTextSnapshot(long pairId, String relPath);

    void upsertConflict(ConflictRecord conflict);

    void removeConflict(long pairId, String relPath);

    List<ConflictRecord> listConflicts();

    void updatePairStatus(long pairId, long syncedAt, String status);

    void appendLog(String source, String level, String message);
}
