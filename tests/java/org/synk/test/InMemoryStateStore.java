package org.synk.test;

import org.synk.core.SyncModels.*;
import org.synk.core.SyncStateStore;
import org.synk.pim.PimSyncEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryStateStore implements SyncStateStore, PimSyncEngine.PimBaselineStore {

    private final Map<Long, Map<String, FileEntry>> baselines = new HashMap<>();
    private final Map<Long, Map<String, byte[]>> snapshots = new HashMap<>();
    private final Map<String, ConflictRecord> conflicts = new HashMap<>();
    private final Map<Long, Map<String, PimSyncEngine.PimBaseline>> pimBaselines = new HashMap<>();
    public final List<String> logs = new ArrayList<>();

    @Override
    public synchronized List<FileEntry> loadBaseline(long pairId) {
        Map<String, FileEntry> m = baselines.get(pairId);
        return m == null ? new ArrayList<FileEntry>() : new ArrayList<>(m.values());
    }

    @Override
    public synchronized void upsertBaselineEntry(long pairId, FileEntry entry, byte[] baseTextSnapshot) {
        Map<String, FileEntry> m = baselines.computeIfAbsent(pairId, k -> new HashMap<String, FileEntry>());
        m.put(entry.relPath, entry);
        Map<String, byte[]> s = snapshots.computeIfAbsent(pairId, k -> new HashMap<String, byte[]>());
        if (baseTextSnapshot != null) s.put(entry.relPath, baseTextSnapshot.clone());
        else s.remove(entry.relPath);
    }

    @Override
    public synchronized void removeBaselineEntry(long pairId, String relPath) {
        Map<String, FileEntry> m = baselines.get(pairId);
        if (m != null) m.remove(relPath);
        Map<String, byte[]> s = snapshots.get(pairId);
        if (s != null) s.remove(relPath);
    }

    @Override
    public synchronized byte[] loadBaseTextSnapshot(long pairId, String relPath) {
        Map<String, byte[]> s = snapshots.get(pairId);
        return s == null ? null : s.get(relPath);
    }

    @Override
    public synchronized void upsertConflict(ConflictRecord conflict) {
        conflicts.put(conflict.pairId + ":" + conflict.relPath, conflict);
    }

    @Override
    public synchronized void removeConflict(long pairId, String relPath) {
        conflicts.remove(pairId + ":" + relPath);
    }

    @Override
    public synchronized List<ConflictRecord> listConflicts() {
        return new ArrayList<>(conflicts.values());
    }

    @Override
    public void updatePairStatus(long pairId, long syncedAt, String status) {}

    @Override
    public synchronized void appendLog(String source, String level, String message) {
        logs.add("[" + level + "] " + source + ": " + message);
    }

    @Override
    public synchronized List<PimSyncEngine.PimBaseline> loadPimBaseline(long profileId) {
        Map<String, PimSyncEngine.PimBaseline> m = pimBaselines.get(profileId);
        return m == null ? new ArrayList<PimSyncEngine.PimBaseline>() : new ArrayList<>(m.values());
    }

    @Override
    public synchronized void upsertPimBaseline(long profileId, PimSyncEngine.PimBaseline item) {
        pimBaselines.computeIfAbsent(profileId, k -> new HashMap<String, PimSyncEngine.PimBaseline>())
                .put(item.uid, item);
    }

    @Override
    public synchronized void removePimBaseline(long profileId, String uid) {
        Map<String, PimSyncEngine.PimBaseline> m = pimBaselines.get(profileId);
        if (m != null) m.remove(uid);
    }

    @Override
    public void updatePimStatus(long profileId, long syncedAt, String status) {}
}
