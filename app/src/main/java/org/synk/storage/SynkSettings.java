package org.synk.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.ConflictPolicy;
import org.synk.core.SyncModels.SyncMode;
import org.synk.core.SyncOptions;

/**
 * Fine-grained application settings persisted in SharedPreferences and applied directly
 * to SyncEngine, HttpUtil, NativeBridge, SyncForegroundService, and BootReceiver.
 */
public final class SynkSettings {

    private static final String PREFS_NAME = "synk_settings";

    public static final String KEY_PREFER_NATIVE       = "prefer_native";
    public static final String KEY_MAX_CONCURRENT      = "max_concurrent";
    public static final String KEY_BUFFER_KB           = "buffer_kb";
    public static final String KEY_BW_LIMIT_KBPS       = "bw_limit_kbps";
    public static final String KEY_MAX_FILE_MB         = "max_file_mb";
    public static final String KEY_PRESERVE_MTIME      = "preserve_mtime";
    public static final String KEY_PARANOID_REHASH     = "paranoid_rehash";

    public static final String KEY_WATCH_DEBOUNCE_MS   = "watch_debounce_ms";
    public static final String KEY_SELF_COOLDOWN_MS    = "self_cooldown_ms";
    public static final String KEY_AUTO_BOOT           = "auto_boot";
    public static final String KEY_MIN_BATTERY_PCT     = "min_battery_pct";
    public static final String KEY_PAUSE_POWER_SAVER   = "pause_power_saver";

    public static final String KEY_CONNECT_TIMEOUT_S   = "connect_timeout_s";
    public static final String KEY_READ_TIMEOUT_S      = "read_timeout_s";
    public static final String KEY_RETRY_COUNT         = "retry_count";

    public static final String KEY_DEFAULT_MODE        = "default_mode";
    public static final String KEY_DEFAULT_CONFLICT    = "default_conflict";
    public static final String KEY_MAX_MERGE_KB        = "max_merge_kb";
    public static final String KEY_DEVICE_TAG          = "device_tag";
    public static final String KEY_GLOBAL_IGNORES      = "global_ignores";

    public static final String KEY_LIVE_NOTIF          = "live_notif";
    public static final String KEY_NOTIFY_CONFLICTS    = "notify_conflicts";
    public static final String KEY_NOTIFY_ERRORS       = "notify_errors";
    public static final String KEY_LOG_RETENTION       = "log_retention";

    private static final String DEFAULT_GLOBAL_IGNORES = ".DS_Store\nThumbs.db\n*.part\n*.crdownload";

    private final SharedPreferences sp;

    public static SynkSettings of(Context ctx) {
        return new SynkSettings(ctx.getApplicationContext());
    }

    private SynkSettings(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences prefs() {
        return sp;
    }

    public boolean isPreferNative() { return sp.getBoolean(KEY_PREFER_NATIVE, true); }
    public int getMaxConcurrentJobs() { return sp.getInt(KEY_MAX_CONCURRENT, 2); }
    public int getBufferKB() { return sp.getInt(KEY_BUFFER_KB, 32); }
    public int getBandwidthLimitKBps() { return sp.getInt(KEY_BW_LIMIT_KBPS, 0); }
    public int getMaxFileSizeMB() { return sp.getInt(KEY_MAX_FILE_MB, 0); }
    public boolean isPreserveTimestamps() { return sp.getBoolean(KEY_PRESERVE_MTIME, true); }
    public boolean isParanoidRehash() { return sp.getBoolean(KEY_PARANOID_REHASH, false); }

    public int getWatchDebounceMs() { return sp.getInt(KEY_WATCH_DEBOUNCE_MS, 3000); }
    public int getSelfSyncCooldownMs() { return sp.getInt(KEY_SELF_COOLDOWN_MS, 2500); }
    public boolean isAutoStartOnBoot() { return sp.getBoolean(KEY_AUTO_BOOT, true); }
    public int getMinBatteryPercent() { return sp.getInt(KEY_MIN_BATTERY_PCT, 15); }
    public boolean isPauseOnPowerSaver() { return sp.getBoolean(KEY_PAUSE_POWER_SAVER, true); }

    public int getConnectTimeoutSec() { return sp.getInt(KEY_CONNECT_TIMEOUT_S, 15); }
    public int getReadTimeoutSec() { return sp.getInt(KEY_READ_TIMEOUT_S, 60); }
    public int getRetryCount() { return sp.getInt(KEY_RETRY_COUNT, 2); }

    public SyncMode getDefaultSyncMode() {
        return SyncMode.fromCode(sp.getInt(KEY_DEFAULT_MODE, SyncMode.TWO_WAY.code));
    }
    public ConflictPolicy getDefaultConflictPolicy() {
        return ConflictPolicy.fromCode(sp.getInt(KEY_DEFAULT_CONFLICT, ConflictPolicy.AUTO_MERGE.code));
    }
    public int getMaxAutoMergeKB() { return sp.getInt(KEY_MAX_MERGE_KB, 512); }

    public String getDeviceTag() {
        String def = defaultDeviceTag();
        String v = sp.getString(KEY_DEVICE_TAG, def);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    public String getGlobalIgnorePatterns() {
        return sp.getString(KEY_GLOBAL_IGNORES, DEFAULT_GLOBAL_IGNORES);
    }

    public boolean isShowLiveNotifProgress() { return sp.getBoolean(KEY_LIVE_NOTIF, true); }
    public boolean isNotifyConflicts() { return sp.getBoolean(KEY_NOTIFY_CONFLICTS, true); }
    public boolean isNotifyErrors() { return sp.getBoolean(KEY_NOTIFY_ERRORS, true); }
    public int getLogRetentionLimit() { return sp.getInt(KEY_LOG_RETENTION, 400); }

    public void applyEnginePreference() {
        NativeBridge.setPreferNative(isPreferNative());
    }

    public SyncOptions toSyncOptions() {
        applyEnginePreference();
        SyncOptions o = new SyncOptions();
        o.connectTimeoutMs = getConnectTimeoutSec() * 1000;
        o.readTimeoutMs = getReadTimeoutSec() * 1000;
        o.bufferSizeBytes = Math.max(8, getBufferKB()) * 1024;
        o.bandwidthLimitKBps = Math.max(0, getBandwidthLimitKBps());
        o.maxFileSizeMB = Math.max(0, getMaxFileSizeMB());
        o.maxAutoMergeKB = Math.max(16, getMaxAutoMergeKB());
        o.retryCount = Math.max(0, getRetryCount());
        o.preserveTimestamps = isPreserveTimestamps();
        o.paranoidRehash = isParanoidRehash();
        o.deviceTag = getDeviceTag();
        o.globalIgnorePatterns = getGlobalIgnorePatterns();
        return o;
    }

    public void resetDefaults() {
        sp.edit().clear().apply();
        applyEnginePreference();
    }

    private static String defaultDeviceTag() {
        String model = Build.MODEL == null ? "android" : Build.MODEL.toLowerCase(java.util.Locale.ROOT);
        String clean = model.replaceAll("[^a-z0-9_-]", "");
        if (clean.length() > 14) clean = clean.substring(0, 14);
        return clean.isEmpty() ? "android" : clean;
    }
}
