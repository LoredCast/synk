package org.synk.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Core immutable/mutable data models for Synk file & PIM synchronization.
 * Zero external dependencies.
 */
public final class SyncModels {
    private SyncModels() {}

    public enum Protocol {
        WEBDAV(0, "WebDAV / Nextcloud / Seafile"),
        FTP(1, "FTP"),
        FTPS(2, "FTPS (Explicit TLS)"),
        S3(3, "S3 Compatible (AWS/B2/R2/MinIO)"),
        LOCAL(4, "Local / Mounted Folder");

        public final int code;
        public final String label;

        Protocol(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public static Protocol fromCode(int code) {
            for (Protocol p : values()) {
                if (p.code == code) return p;
            }
            return WEBDAV;
        }
    }

    public enum SyncMode {
        TWO_WAY(0, "Two-Way Sync"),
        UPLOAD_ONLY(1, "Upload Only"),
        UPLOAD_MIRROR(2, "Upload Mirror"),
        DOWNLOAD_ONLY(3, "Download Only"),
        DOWNLOAD_MIRROR(4, "Download Mirror"),
        BACKUP_APPEND(5, "Backup (No Delete)");

        public final int code;
        public final String label;

        SyncMode(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public static SyncMode fromCode(int code) {
            for (SyncMode m : values()) {
                if (m.code == code) return m;
            }
            return TWO_WAY;
        }
    }

    public enum ConflictPolicy {
        ASK(0, "Ask (Conflict Manager)"),
        AUTO_MERGE(1, "Auto-Merge Text, Else Ask"),
        KEEP_BOTH(2, "Keep Both Copies"),
        LOCAL_WINS(3, "Local Always Wins"),
        REMOTE_WINS(4, "Remote Always Wins"),
        NEWEST_WINS(5, "Newest Timestamp Wins");

        public final int code;
        public final String label;

        ConflictPolicy(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public static ConflictPolicy fromCode(int code) {
            for (ConflictPolicy p : values()) {
                if (p.code == code) return p;
            }
            return AUTO_MERGE;
        }
    }

    public enum ActionType {
        NONE(0),
        MKDIR_LOCAL(1),
        MKDIR_REMOTE(2),
        UPLOAD(3),
        DOWNLOAD(4),
        DELETE_LOCAL(5),
        DELETE_REMOTE(6),
        UPDATE_BASE(7),
        REMOVE_BASE(8),
        TRY_MERGE(9),
        CONFLICT_ASK(10),
        CONFLICT_KEEP_BOTH(11);

        public final int code;

        ActionType(int code) {
            this.code = code;
        }

        public static ActionType fromCode(int code) {
            for (ActionType a : values()) {
                if (a.code == code) return a;
            }
            return NONE;
        }
    }

    public enum ConflictKind {
        NONE(0, "None"),
        BOTH_MODIFIED(1, "Both Modified"),
        BOTH_CREATED(2, "Both Created"),
        LOCAL_MOD_REMOTE_DEL(3, "Local Edited / Remote Deleted"),
        LOCAL_DEL_REMOTE_MOD(4, "Local Deleted / Remote Edited"),
        TYPE_MISMATCH(5, "File vs Folder Mismatch"),
        MERGE_OVERLAP(6, "Overlapping Text Edits");

        public final int code;
        public final String label;

        ConflictKind(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public static ConflictKind fromCode(int code) {
            for (ConflictKind k : values()) {
                if (k.code == code) return k;
            }
            return NONE;
        }
    }

    public enum ConflictResolution {
        KEEP_LOCAL,
        KEEP_REMOTE,
        KEEP_BOTH,
        USE_MERGED
    }

    public static final class AccountConfig {
        public long id;
        public String name;
        public Protocol protocol = Protocol.WEBDAV;
        public String url;          // WebDAV base URL, FTP host[:port], S3 endpoint, or local dir
        public String username;     // Username or S3 Access Key ID
        public String password;     // Password, Bearer token, or S3 Secret Access Key
        public String extra;        // S3 bucket/region ("bucket|region"), Bearer prefix flag, etc.
        public boolean trustAllCerts;

        public AccountConfig() {}

        public AccountConfig(long id, String name, Protocol protocol, String url,
                             String username, String password, String extra, boolean trustAllCerts) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.url = url;
            this.username = username;
            this.password = password;
            this.extra = extra;
            this.trustAllCerts = trustAllCerts;
        }
    }

    public static final class SyncPair {
        public long id;
        public String name;
        public long accountId;
        public String localPath;
        public String remotePath;
        public SyncMode mode = SyncMode.TWO_WAY;
        public ConflictPolicy conflictPolicy = ConflictPolicy.AUTO_MERGE;
        public int intervalMinutes;     // 0 = manual only
        public boolean watchEnabled;
        public boolean wifiOnly = true;
        public boolean chargingOnly;
        public String ignorePatterns = "*.tmp\n.DS_Store\nThumbs.db\n.git/**";
        public boolean enabled = true;
        public long lastSyncAt;
        public String lastStatus = "Never synced";
    }

    public static final class FileEntry {
        public final String relPath;
        public final boolean isDir;
        public final long size;
        public final long mtimeMs;
        public final String hash;
        public final String etag;

        public FileEntry(String relPath, boolean isDir, long size, long mtimeMs, String hash, String etag) {
            this.relPath = relPath;
            this.isDir = isDir;
            this.size = isDir ? 0L : size;
            this.mtimeMs = mtimeMs;
            this.hash = hash == null ? "" : hash;
            this.etag = etag == null ? "" : etag;
        }
    }

    public static final class PlanAction {
        public final String relPath;
        public final boolean isDir;
        public final ActionType action;
        public final ConflictKind conflictKind;
        public final long localSize;
        public final long localMtimeMs;
        public final long remoteSize;
        public final long remoteMtimeMs;
        public final String localHash;
        public final String remoteEtag;

        public PlanAction(String relPath, boolean isDir, ActionType action, ConflictKind conflictKind,
                          long localSize, long localMtimeMs, long remoteSize, long remoteMtimeMs,
                          String localHash, String remoteEtag) {
            this.relPath = relPath;
            this.isDir = isDir;
            this.action = action;
            this.conflictKind = conflictKind;
            this.localSize = localSize;
            this.localMtimeMs = localMtimeMs;
            this.remoteSize = remoteSize;
            this.remoteMtimeMs = remoteMtimeMs;
            this.localHash = localHash == null ? "" : localHash;
            this.remoteEtag = remoteEtag == null ? "" : remoteEtag;
        }
    }

    public static final class MergeResult {
        public final boolean isBinary;
        public final int conflictCount;
        public final byte[] mergedBytes;

        public MergeResult(boolean isBinary, int conflictCount, byte[] mergedBytes) {
            this.isBinary = isBinary;
            this.conflictCount = conflictCount;
            this.mergedBytes = mergedBytes;
        }
    }

    public static final class ConflictRecord {
        public long id;
        public long pairId;
        public String pairName;
        public String relPath;
        public ConflictKind kind;
        public long localSize;
        public long localMtimeMs;
        public String localHash;
        public long remoteSize;
        public long remoteMtimeMs;
        public String remoteEtag;
        public String mergedPreview; // Optional conflict-marked merge text preview
        public long detectedAt;
    }

    public static final class SyncResult {
        public int uploaded;
        public int downloaded;
        public int merged;
        public int deletedLocal;
        public int deletedRemote;
        public int conflicts;
        public int errors;
        public long bytesTransferred;
        public long durationMs;
        public final List<String> messages = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Up ").append(uploaded)
              .append(" / Down ").append(downloaded);
            if (merged > 0) sb.append(" / Merged ").append(merged);
            if (deletedLocal > 0 || deletedRemote > 0) {
                sb.append(" / Del ").append(deletedLocal + deletedRemote);
            }
            if (conflicts > 0) sb.append(" / Conflicts ").append(conflicts);
            if (errors > 0) sb.append(" / Errors ").append(errors);
            return sb.toString();
        }
    }

    public enum PimKind {
        CALDAV(0, "CalDAV (Calendar)"),
        CARDDAV(1, "CardDAV (Contacts)");

        public final int code;
        public final String label;

        PimKind(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public static PimKind fromCode(int code) {
            return code == 1 ? CARDDAV : CALDAV;
        }
    }

    public static final class PimProfile {
        public long id;
        public String name;
        public PimKind kind = PimKind.CALDAV;
        public String url;
        public String username;
        public String password;
        public boolean trustAllCerts;
        public int intervalMinutes = 60;
        public ConflictPolicy conflictPolicy = ConflictPolicy.NEWEST_WINS;
        public boolean enabled = true;
        public long lastSyncAt;
        public String lastStatus = "Never synced";
    }

    public static final class SyncLogEntry {
        public long id;
        public long timestamp;
        public String source;
        public String level;   // INFO, WARN, ERROR
        public String message;
    }
}
