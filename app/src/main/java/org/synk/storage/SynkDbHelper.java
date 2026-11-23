package org.synk.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.synk.core.SyncModels.*;
import org.synk.core.SyncStateStore;
import org.synk.pim.PimSyncEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency SQLite persistence (WAL mode) for accounts, sync pairs,
 * 3-way file baselines + base text snapshots, conflicts, PIM profiles/baselines, and logs.
 */
public final class SynkDbHelper extends SQLiteOpenHelper
        implements SyncStateStore, PimSyncEngine.PimBaselineStore {

    private static final String DB_NAME = "synk.db";
    private static final int DB_VERSION = 1;
    private static volatile SynkDbHelper instance;
    private final Context appContext;

    public static SynkDbHelper getInstance(Context ctx) {
        if (instance == null) {
            synchronized (SynkDbHelper.class) {
                if (instance == null) {
                    instance = new SynkDbHelper(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SynkDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        this.appContext = ctx;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "protocol INTEGER NOT NULL," +
                "url TEXT NOT NULL," +
                "username TEXT," +
                "password_enc TEXT," +
                "extra TEXT," +
                "trust_all INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE sync_pairs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE," +
                "local_path TEXT NOT NULL," +
                "remote_path TEXT NOT NULL," +
                "mode INTEGER NOT NULL," +
                "conflict_policy INTEGER NOT NULL," +
                "interval_min INTEGER DEFAULT 0," +
                "watch_enabled INTEGER DEFAULT 0," +
                "wifi_only INTEGER DEFAULT 1," +
                "charging_only INTEGER DEFAULT 0," +
                "ignore_patterns TEXT," +
                "enabled INTEGER DEFAULT 1," +
                "last_sync_at INTEGER DEFAULT 0," +
                "last_status TEXT)");

        db.execSQL("CREATE TABLE file_baseline (" +
                "pair_id INTEGER NOT NULL REFERENCES sync_pairs(id) ON DELETE CASCADE," +
                "rel_path TEXT NOT NULL," +
                "is_dir INTEGER NOT NULL," +
                "size INTEGER DEFAULT 0," +
                "mtime_ms INTEGER DEFAULT 0," +
                "hash TEXT," +
                "etag TEXT," +
                "base_snapshot BLOB," +
                "PRIMARY KEY (pair_id, rel_path))");

        db.execSQL("CREATE TABLE conflicts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "pair_id INTEGER NOT NULL REFERENCES sync_pairs(id) ON DELETE CASCADE," +
                "pair_name TEXT," +
                "rel_path TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "local_size INTEGER DEFAULT 0," +
                "local_mtime_ms INTEGER DEFAULT 0," +
                "local_hash TEXT," +
                "remote_size INTEGER DEFAULT 0," +
                "remote_mtime_ms INTEGER DEFAULT 0," +
                "remote_etag TEXT," +
                "merged_preview TEXT," +
                "detected_at INTEGER NOT NULL," +
                "UNIQUE (pair_id, rel_path))");

        db.execSQL("CREATE TABLE pim_profiles (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "url TEXT NOT NULL," +
                "username TEXT," +
                "password_enc TEXT," +
                "trust_all INTEGER DEFAULT 0," +
                "interval_min INTEGER DEFAULT 60," +
                "conflict_policy INTEGER DEFAULT 5," +
                "enabled INTEGER DEFAULT 1," +
                "last_sync_at INTEGER DEFAULT 0," +
                "last_status TEXT)");

        db.execSQL("CREATE TABLE pim_baseline (" +
                "profile_id INTEGER NOT NULL REFERENCES pim_profiles(id) ON DELETE CASCADE," +
                "uid TEXT NOT NULL," +
                "href TEXT," +
                "local_hash TEXT," +
                "remote_etag TEXT," +
                "PRIMARY KEY (profile_id, uid))");

        db.execSQL("CREATE TABLE sync_logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "source TEXT," +
                "level TEXT," +
                "message TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    /* ---- Accounts ---- */

    public long saveAccount(AccountConfig a) {
        ContentValues cv = new ContentValues();
        cv.put("name", a.name);
        cv.put("protocol", a.protocol.code);
        cv.put("url", a.url);
        cv.put("username", a.username);
        cv.put("password_enc", CredentialCipher.encrypt(a.password));
        cv.put("extra", a.extra);
        cv.put("trust_all", a.trustAllCerts ? 1 : 0);
        SQLiteDatabase db = getWritableDatabase();
        if (a.id > 0) {
            db.update("accounts", cv, "id=?", new String[]{ String.valueOf(a.id) });
            return a.id;
        }
        a.id = db.insert("accounts", null, cv);
        return a.id;
    }

    public void deleteAccount(long id) {
        getWritableDatabase().delete("accounts", "id=?", new String[]{ String.valueOf(id) });
    }

    public AccountConfig getAccount(long id) {
        try (Cursor c = getReadableDatabase().query("accounts", null, "id=?",
                new String[]{ String.valueOf(id) }, null, null, null)) {
            if (c.moveToFirst()) return readAccount(c);
        }
        return null;
    }

    public List<AccountConfig> listAccounts() {
        List<AccountConfig> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("accounts", null, null, null, null, null, "name ASC")) {
            while (c.moveToNext()) out.add(readAccount(c));
        }
        return out;
    }

    private static AccountConfig readAccount(Cursor c) {
        return new AccountConfig(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                Protocol.fromCode(c.getInt(c.getColumnIndexOrThrow("protocol"))),
                c.getString(c.getColumnIndexOrThrow("url")),
                c.getString(c.getColumnIndexOrThrow("username")),
                CredentialCipher.decrypt(c.getString(c.getColumnIndexOrThrow("password_enc"))),
                c.getString(c.getColumnIndexOrThrow("extra")),
                c.getInt(c.getColumnIndexOrThrow("trust_all")) != 0
        );
    }

    /* ---- Sync Pairs ---- */

    public long savePair(SyncPair p) {
        ContentValues cv = new ContentValues();
        cv.put("name", p.name);
        cv.put("account_id", p.accountId);
        cv.put("local_path", p.localPath);
        cv.put("remote_path", p.remotePath);
        cv.put("mode", p.mode.code);
        cv.put("conflict_policy", p.conflictPolicy.code);
        cv.put("interval_min", p.intervalMinutes);
        cv.put("watch_enabled", p.watchEnabled ? 1 : 0);
        cv.put("wifi_only", p.wifiOnly ? 1 : 0);
        cv.put("charging_only", p.chargingOnly ? 1 : 0);
        cv.put("ignore_patterns", p.ignorePatterns);
        cv.put("enabled", p.enabled ? 1 : 0);
        cv.put("last_sync_at", p.lastSyncAt);
        cv.put("last_status", p.lastStatus);
        SQLiteDatabase db = getWritableDatabase();
        if (p.id > 0) {
            db.update("sync_pairs", cv, "id=?", new String[]{ String.valueOf(p.id) });
            return p.id;
        }
        p.id = db.insert("sync_pairs", null, cv);
        return p.id;
    }

    public void deletePair(long id) {
        getWritableDatabase().delete("sync_pairs", "id=?", new String[]{ String.valueOf(id) });
    }

    public void resetBaseline(long pairId) {
        getWritableDatabase().delete("file_baseline", "pair_id=?", new String[]{ String.valueOf(pairId) });
    }

    public SyncPair getPair(long id) {
        try (Cursor c = getReadableDatabase().query("sync_pairs", null, "id=?",
                new String[]{ String.valueOf(id) }, null, null, null)) {
            if (c.moveToFirst()) return readPair(c);
        }
        return null;
    }

    public List<SyncPair> listPairs() {
        List<SyncPair> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("sync_pairs", null, null, null, null, null, "name ASC")) {
            while (c.moveToNext()) out.add(readPair(c));
        }
        return out;
    }

    private static SyncPair readPair(Cursor c) {
        SyncPair p = new SyncPair();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.name = c.getString(c.getColumnIndexOrThrow("name"));
        p.accountId = c.getLong(c.getColumnIndexOrThrow("account_id"));
        p.localPath = c.getString(c.getColumnIndexOrThrow("local_path"));
        p.remotePath = c.getString(c.getColumnIndexOrThrow("remote_path"));
        p.mode = SyncMode.fromCode(c.getInt(c.getColumnIndexOrThrow("mode")));
        p.conflictPolicy = ConflictPolicy.fromCode(c.getInt(c.getColumnIndexOrThrow("conflict_policy")));
        p.intervalMinutes = c.getInt(c.getColumnIndexOrThrow("interval_min"));
        p.watchEnabled = c.getInt(c.getColumnIndexOrThrow("watch_enabled")) != 0;
        p.wifiOnly = c.getInt(c.getColumnIndexOrThrow("wifi_only")) != 0;
        p.chargingOnly = c.getInt(c.getColumnIndexOrThrow("charging_only")) != 0;
        p.ignorePatterns = c.getString(c.getColumnIndexOrThrow("ignore_patterns"));
        p.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
        p.lastSyncAt = c.getLong(c.getColumnIndexOrThrow("last_sync_at"));
        p.lastStatus = c.getString(c.getColumnIndexOrThrow("last_status"));
        return p;
    }

    /* ---- SyncStateStore implementation ---- */

    @Override
    public List<FileEntry> loadBaseline(long pairId) {
        List<FileEntry> out = new ArrayList<>();
        String[] cols = { "rel_path", "is_dir", "size", "mtime_ms", "hash", "etag" };
        try (Cursor c = getReadableDatabase().query("file_baseline", cols, "pair_id=?",
                new String[]{ String.valueOf(pairId) }, null, null, "rel_path ASC")) {
            while (c.moveToNext()) {
                out.add(new FileEntry(
                        c.getString(0),
                        c.getInt(1) != 0,
                        c.getLong(2),
                        c.getLong(3),
                        c.getString(4),
                        c.getString(5)));
            }
        }
        return out;
    }

    @Override
    public void upsertBaselineEntry(long pairId, FileEntry entry, byte[] baseTextSnapshot) {
        ContentValues cv = new ContentValues();
        cv.put("pair_id", pairId);
        cv.put("rel_path", entry.relPath);
        cv.put("is_dir", entry.isDir ? 1 : 0);
        cv.put("size", entry.size);
        cv.put("mtime_ms", entry.mtimeMs);
        cv.put("hash", entry.hash);
        cv.put("etag", entry.etag);
        if (baseTextSnapshot != null) {
            cv.put("base_snapshot", baseTextSnapshot);
        } else {
            cv.putNull("base_snapshot");
        }
        getWritableDatabase().insertWithOnConflict("file_baseline", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void removeBaselineEntry(long pairId, String relPath) {
        getWritableDatabase().delete("file_baseline", "pair_id=? AND rel_path=?",
                new String[]{ String.valueOf(pairId), relPath });
    }

    @Override
    public byte[] loadBaseTextSnapshot(long pairId, String relPath) {
        try (Cursor c = getReadableDatabase().query("file_baseline", new String[]{ "base_snapshot" },
                "pair_id=? AND rel_path=?", new String[]{ String.valueOf(pairId), relPath },
                null, null, null)) {
            if (c.moveToFirst()) return c.getBlob(0);
        }
        return null;
    }

    @Override
    public void upsertConflict(ConflictRecord r) {
        ContentValues cv = new ContentValues();
        cv.put("pair_id", r.pairId);
        cv.put("pair_name", r.pairName);
        cv.put("rel_path", r.relPath);
        cv.put("kind", r.kind.code);
        cv.put("local_size", r.localSize);
        cv.put("local_mtime_ms", r.localMtimeMs);
        cv.put("local_hash", r.localHash);
        cv.put("remote_size", r.remoteSize);
        cv.put("remote_mtime_ms", r.remoteMtimeMs);
        cv.put("remote_etag", r.remoteEtag);
        cv.put("merged_preview", r.mergedPreview);
        cv.put("detected_at", r.detectedAt);
        getWritableDatabase().insertWithOnConflict("conflicts", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void removeConflict(long pairId, String relPath) {
        getWritableDatabase().delete("conflicts", "pair_id=? AND rel_path=?",
                new String[]{ String.valueOf(pairId), relPath });
    }

    @Override
    public List<ConflictRecord> listConflicts() {
        List<ConflictRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("conflicts", null, null, null, null, null, "detected_at DESC")) {
            while (c.moveToNext()) {
                ConflictRecord r = new ConflictRecord();
                r.id = c.getLong(c.getColumnIndexOrThrow("id"));
                r.pairId = c.getLong(c.getColumnIndexOrThrow("pair_id"));
                r.pairName = c.getString(c.getColumnIndexOrThrow("pair_name"));
                r.relPath = c.getString(c.getColumnIndexOrThrow("rel_path"));
                r.kind = ConflictKind.fromCode(c.getInt(c.getColumnIndexOrThrow("kind")));
                r.localSize = c.getLong(c.getColumnIndexOrThrow("local_size"));
                r.localMtimeMs = c.getLong(c.getColumnIndexOrThrow("local_mtime_ms"));
                r.localHash = c.getString(c.getColumnIndexOrThrow("local_hash"));
                r.remoteSize = c.getLong(c.getColumnIndexOrThrow("remote_size"));
                r.remoteMtimeMs = c.getLong(c.getColumnIndexOrThrow("remote_mtime_ms"));
                r.remoteEtag = c.getString(c.getColumnIndexOrThrow("remote_etag"));
                r.mergedPreview = c.getString(c.getColumnIndexOrThrow("merged_preview"));
                r.detectedAt = c.getLong(c.getColumnIndexOrThrow("detected_at"));
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public void updatePairStatus(long pairId, long syncedAt, String status) {
        ContentValues cv = new ContentValues();
        cv.put("last_sync_at", syncedAt);
        cv.put("last_status", status);
        getWritableDatabase().update("sync_pairs", cv, "id=?", new String[]{ String.valueOf(pairId) });
    }

    /* ---- PIM Profiles & Baselines ---- */

    public long savePimProfile(PimProfile p) {
        ContentValues cv = new ContentValues();
        cv.put("name", p.name);
        cv.put("kind", p.kind.code);
        cv.put("url", p.url);
        cv.put("username", p.username);
        cv.put("password_enc", CredentialCipher.encrypt(p.password));
        cv.put("trust_all", p.trustAllCerts ? 1 : 0);
        cv.put("interval_min", p.intervalMinutes);
        cv.put("conflict_policy", p.conflictPolicy.code);
        cv.put("enabled", p.enabled ? 1 : 0);
        cv.put("last_sync_at", p.lastSyncAt);
        cv.put("last_status", p.lastStatus);
        SQLiteDatabase db = getWritableDatabase();
        if (p.id > 0) {
            db.update("pim_profiles", cv, "id=?", new String[]{ String.valueOf(p.id) });
            return p.id;
        }
        p.id = db.insert("pim_profiles", null, cv);
        return p.id;
    }

    public void deletePimProfile(long id) {
        getWritableDatabase().delete("pim_profiles", "id=?", new String[]{ String.valueOf(id) });
    }

    public PimProfile getPimProfile(long id) {
        try (Cursor c = getReadableDatabase().query("pim_profiles", null, "id=?",
                new String[]{ String.valueOf(id) }, null, null, null)) {
            if (c.moveToFirst()) return readPimProfile(c);
        }
        return null;
    }

    public List<PimProfile> listPimProfiles() {
        List<PimProfile> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("pim_profiles", null, null, null, null, null, "name ASC")) {
            while (c.moveToNext()) out.add(readPimProfile(c));
        }
        return out;
    }

    private static PimProfile readPimProfile(Cursor c) {
        PimProfile p = new PimProfile();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.name = c.getString(c.getColumnIndexOrThrow("name"));
        p.kind = PimKind.fromCode(c.getInt(c.getColumnIndexOrThrow("kind")));
        p.url = c.getString(c.getColumnIndexOrThrow("url"));
        p.username = c.getString(c.getColumnIndexOrThrow("username"));
        p.password = CredentialCipher.decrypt(c.getString(c.getColumnIndexOrThrow("password_enc")));
        p.trustAllCerts = c.getInt(c.getColumnIndexOrThrow("trust_all")) != 0;
        p.intervalMinutes = c.getInt(c.getColumnIndexOrThrow("interval_min"));
        p.conflictPolicy = ConflictPolicy.fromCode(c.getInt(c.getColumnIndexOrThrow("conflict_policy")));
        p.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
        p.lastSyncAt = c.getLong(c.getColumnIndexOrThrow("last_sync_at"));
        p.lastStatus = c.getString(c.getColumnIndexOrThrow("last_status"));
        return p;
    }

    @Override
    public List<PimSyncEngine.PimBaseline> loadPimBaseline(long profileId) {
        List<PimSyncEngine.PimBaseline> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("pim_baseline",
                new String[]{ "uid", "href", "local_hash", "remote_etag" },
                "profile_id=?", new String[]{ String.valueOf(profileId) }, null, null, null)) {
            while (c.moveToNext()) {
                out.add(new PimSyncEngine.PimBaseline(
                        c.getString(0), c.getString(1), c.getString(2), c.getString(3)));
            }
        }
        return out;
    }

    @Override
    public void upsertPimBaseline(long profileId, PimSyncEngine.PimBaseline item) {
        ContentValues cv = new ContentValues();
        cv.put("profile_id", profileId);
        cv.put("uid", item.uid);
        cv.put("href", item.href);
        cv.put("local_hash", item.localHash);
        cv.put("remote_etag", item.remoteEtag);
        getWritableDatabase().insertWithOnConflict("pim_baseline", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void removePimBaseline(long profileId, String uid) {
        getWritableDatabase().delete("pim_baseline", "profile_id=? AND uid=?",
                new String[]{ String.valueOf(profileId), uid });
    }

    @Override
    public void updatePimStatus(long profileId, long syncedAt, String status) {
        ContentValues cv = new ContentValues();
        cv.put("last_sync_at", syncedAt);
        cv.put("last_status", status);
        getWritableDatabase().update("pim_profiles", cv, "id=?", new String[]{ String.valueOf(profileId) });
    }

    /* ---- Activity Logs ---- */

    @Override
    public void appendLog(String source, String level, String message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("ts", System.currentTimeMillis());
        cv.put("source", source == null ? "Synk" : source);
        cv.put("level", level == null ? "INFO" : level);
        cv.put("message", message == null ? "" : message);
        db.insert("sync_logs", null, cv);
        int limit = Math.max(50, SynkSettings.of(appContext).getLogRetentionLimit());
        db.execSQL("DELETE FROM sync_logs WHERE id NOT IN (SELECT id FROM sync_logs ORDER BY id DESC LIMIT " + limit + ")");
    }

    public List<SyncLogEntry> listLogs(int limit) {
        List<SyncLogEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("sync_logs", null, null, null, null, null,
                "id DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                SyncLogEntry e = new SyncLogEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("id"));
                e.timestamp = c.getLong(c.getColumnIndexOrThrow("ts"));
                e.source = c.getString(c.getColumnIndexOrThrow("source"));
                e.level = c.getString(c.getColumnIndexOrThrow("level"));
                e.message = c.getString(c.getColumnIndexOrThrow("message"));
                out.add(e);
            }
        }
        return out;
    }

    public void clearLogs() {
        getWritableDatabase().delete("sync_logs", null, null);
    }

    public void resetAllBaselines() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("file_baseline", null, null);
        db.delete("pim_baseline", null, null);
    }

    public int countBaselineEntries() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM file_baseline", null)) {
            if (c.moveToFirst()) return c.getInt(0);
        }
        return 0;
    }
}
