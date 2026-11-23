package org.synk.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.synk.R;
import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.ConflictPolicy;
import org.synk.core.SyncModels.SyncMode;
import org.synk.service.SyncForegroundService;
import org.synk.storage.SynkDbHelper;
import org.synk.storage.SynkSettings;

/**
 * Full-screen pitch-black AMOLED fine-grained Settings page.
 */
public final class SettingsActivity extends Activity {

    private SynkSettings settings;
    private SynkDbHelper db;
    private EditText etDeviceTag;
    private EditText etGlobalIgnores;
    private TextView tvEngineInfo;
    private TextView tvMaintStats;

    private static final int[] CONCURRENT_VALS   = { 1, 2, 3, 4 };
    private static final String[] CONCURRENT_LBL = { "1 job (lowest battery)", "2 jobs (balanced)", "3 jobs", "4 jobs (max throughput)" };

    private static final int[] BUFFER_VALS       = { 16, 32, 64, 128, 256 };
    private static final String[] BUFFER_LBL     = { "16 KB", "32 KB (default)", "64 KB", "128 KB", "256 KB" };

    private static final int[] BW_VALS           = { 0, 256, 512, 1024, 2048, 5120, 10240 };
    private static final String[] BW_LBL         = { "Unlimited", "256 KB/s", "512 KB/s", "1 MB/s", "2 MB/s", "5 MB/s", "10 MB/s" };

    private static final int[] MAX_FILE_VALS     = { 0, 25, 100, 250, 500, 1024 };
    private static final String[] MAX_FILE_LBL   = { "Unlimited", "25 MB", "100 MB", "250 MB", "500 MB", "1 GB" };

    private static final int[] DEBOUNCE_VALS     = { 500, 1500, 3000, 5000, 10000 };
    private static final String[] DEBOUNCE_LBL   = { "500 ms (instant)", "1.5 sec", "3 sec (default)", "5 sec", "10 sec (battery friendly)" };

    private static final int[] COOLDOWN_VALS     = { 1000, 2500, 5000, 10000 };
    private static final String[] COOLDOWN_LBL   = { "1 sec", "2.5 sec (default)", "5 sec", "10 sec" };

    private static final int[] BATTERY_VALS      = { 0, 15, 20, 30 };
    private static final String[] BATTERY_LBL    = { "Off (sync at any level)", "Below 15%", "Below 20%", "Below 30%" };

    private static final int[] CONN_TO_VALS      = { 5, 10, 15, 30, 60 };
    private static final String[] CONN_TO_LBL    = { "5 sec", "10 sec", "15 sec (default)", "30 sec", "60 sec" };

    private static final int[] READ_TO_VALS      = { 15, 30, 60, 120, 300 };
    private static final String[] READ_TO_LBL    = { "15 sec", "30 sec", "60 sec (default)", "2 min", "5 min" };

    private static final int[] RETRY_VALS        = { 0, 1, 2, 3, 5 };
    private static final String[] RETRY_LBL      = { "No retries", "1 retry", "2 retries (default)", "3 retries", "5 retries" };

    private static final int[] MERGE_KB_VALS     = { 64, 256, 512, 1024, 2048 };
    private static final String[] MERGE_KB_LBL   = { "64 KB", "256 KB", "512 KB (default)", "1 MB", "2 MB" };

    private static final int[] LOG_RET_VALS      = { 100, 400, 1000 };
    private static final String[] LOG_RET_LBL    = { "100 entries", "400 entries (default)", "1000 entries" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = SynkSettings.of(this);
        db = SynkDbHelper.getInstance(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiHelpers.COLOR_BLACK);
        root.setFitsSystemWindows(true);

        /* Top AMOLED bar */
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(UiHelpers.dp(this, 12), UiHelpers.dp(this, 12),
                UiHelpers.dp(this, 12), UiHelpers.dp(this, 10));

        Button btnBack = UiHelpers.smallButton(this, "\u2190 Back", UiHelpers.COLOR_TEXT, false);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTextFieldsAndApply();
                finish();
            }
        });

        TextView title = UiHelpers.label(this, "SETTINGS", 16, UiHelpers.COLOR_TEXT, true);
        title.setLetterSpacing(0.12f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title.setPadding(UiHelpers.dp(this, 6), 0, 0, 0);

        Button btnReset = UiHelpers.smallButton(this, "Reset", UiHelpers.COLOR_RED, false);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Reset Settings")
                        .setMessage("Restore all fine-grained settings to defaults?")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                settings.resetDefaults();
                                buildUi();
                                Toast.makeText(SettingsActivity.this, "Defaults restored", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        header.addView(btnBack);
        header.addView(title);
        header.addView(btnReset);
        root.addView(header);

        View div = new View(this);
        div.setBackgroundColor(0xFF161616);
        div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelpers.dp(this, 1)));
        root.addView(div);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = UiHelpers.dp(this, 12);
        content.setPadding(pad, pad, pad, UiHelpers.dp(this, 24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* ---- Section 1: Engine & Transfer Performance ---- */
        LinearLayout sEngine = sectionCard(content, "ENGINE & TRANSFER PERFORMANCE");
        tvEngineInfo = UiHelpers.label(this, engineSummary(), 11, UiHelpers.COLOR_GREEN, false);
        tvEngineInfo.setTypeface(Typeface.MONOSPACE);
        sEngine.addView(tvEngineInfo);

        CheckBox cbNative = bindBool(sEngine, "Prefer Native C11 Engine (JNI)",
                SynkSettings.KEY_PREFER_NATIVE, settings.isPreferNative(), new Runnable() {
                    @Override
                    public void run() {
                        settings.applyEnginePreference();
                        tvEngineInfo.setText(engineSummary());
                    }
                });
        if (!NativeBridge.isNativeBinaryAvailable()) {
            cbNative.setEnabled(false);
            cbNative.setText("Prefer Native C11 Engine (bundle .so with NDK)");
            cbNative.setTextColor(UiHelpers.COLOR_MUTED);
        }

        bindIntSpinner(sEngine, "Max Concurrent Sync Jobs", SynkSettings.KEY_MAX_CONCURRENT,
                CONCURRENT_VALS, CONCURRENT_LBL, settings.getMaxConcurrentJobs());
        bindIntSpinner(sEngine, "Streaming I/O Buffer Size", SynkSettings.KEY_BUFFER_KB,
                BUFFER_VALS, BUFFER_LBL, settings.getBufferKB());
        bindIntSpinner(sEngine, "Bandwidth Throttle Limit", SynkSettings.KEY_BW_LIMIT_KBPS,
                BW_VALS, BW_LBL, settings.getBandwidthLimitKBps());
        bindIntSpinner(sEngine, "Max Single File Size", SynkSettings.KEY_MAX_FILE_MB,
                MAX_FILE_VALS, MAX_FILE_LBL, settings.getMaxFileSizeMB());
        bindBool(sEngine, "Preserve remote file timestamps on download",
                SynkSettings.KEY_PRESERVE_MTIME, settings.isPreserveTimestamps(), null);
        bindBool(sEngine, "Paranoid mode (re-hash unchanged files every sync)",
                SynkSettings.KEY_PARANOID_REHASH, settings.isParanoidRehash(), null);

        /* ---- Section 2: Watcher & Power Policy ---- */
        LinearLayout sWatch = sectionCard(content, "CHANGE WATCHER & POWER");
        bindIntSpinner(sWatch, "Inotify Debounce Delay", SynkSettings.KEY_WATCH_DEBOUNCE_MS,
                DEBOUNCE_VALS, DEBOUNCE_LBL, settings.getWatchDebounceMs());
        bindIntSpinner(sWatch, "Self-Sync Cooldown (avoid echo loops)", SynkSettings.KEY_SELF_COOLDOWN_MS,
                COOLDOWN_VALS, COOLDOWN_LBL, settings.getSelfSyncCooldownMs());
        bindBool(sWatch, "Auto-start live watchers on device boot",
                SynkSettings.KEY_AUTO_BOOT, settings.isAutoStartOnBoot(), null);
        bindIntSpinner(sWatch, "Pause background sync on low battery", SynkSettings.KEY_MIN_BATTERY_PCT,
                BATTERY_VALS, BATTERY_LBL, settings.getMinBatteryPercent());
        bindBool(sWatch, "Pause background sync when Battery Saver is on",
                SynkSettings.KEY_PAUSE_POWER_SAVER, settings.isPauseOnPowerSaver(), null);

        /* ---- Section 3: Network, Timeouts & Retries ---- */
        LinearLayout sNet = sectionCard(content, "NETWORK, TIMEOUTS & RETRIES");
        bindIntSpinner(sNet, "Connection Timeout", SynkSettings.KEY_CONNECT_TIMEOUT_S,
                CONN_TO_VALS, CONN_TO_LBL, settings.getConnectTimeoutSec());
        bindIntSpinner(sNet, "Read / Transfer Timeout", SynkSettings.KEY_READ_TIMEOUT_S,
                READ_TO_VALS, READ_TO_LBL, settings.getReadTimeoutSec());
        bindIntSpinner(sNet, "Auto-Retry Failed Transfers (Backoff)", SynkSettings.KEY_RETRY_COUNT,
                RETRY_VALS, RETRY_LBL, settings.getRetryCount());

        /* ---- Section 4: Sync & Conflict Defaults ---- */
        LinearLayout sDef = sectionCard(content, "SYNC, MERGE & IGNORE DEFAULTS");
        final SyncMode[] modes = SyncMode.values();
        int[] modeVals = new int[modes.length];
        String[] modeLbls = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeVals[i] = modes[i].code;
            modeLbls[i] = modes[i].label;
        }
        bindIntSpinner(sDef, "Default Sync Mode (New Folder Pairs)", SynkSettings.KEY_DEFAULT_MODE,
                modeVals, modeLbls, settings.getDefaultSyncMode().code);

        final ConflictPolicy[] pols = ConflictPolicy.values();
        int[] polVals = new int[pols.length];
        String[] polLbls = new String[pols.length];
        for (int i = 0; i < pols.length; i++) {
            polVals[i] = pols[i].code;
            polLbls[i] = pols[i].label;
        }
        bindIntSpinner(sDef, "Default Conflict Policy (New Folder Pairs)", SynkSettings.KEY_DEFAULT_CONFLICT,
                polVals, polLbls, settings.getDefaultConflictPolicy().code);

        bindIntSpinner(sDef, "Max Text File Size for 3-Way Auto-Merge", SynkSettings.KEY_MAX_MERGE_KB,
                MERGE_KB_VALS, MERGE_KB_LBL, settings.getMaxAutoMergeKB());

        sDef.addView(UiHelpers.fieldLabel(this, "Device Tag (conflict copies & diff3 marker)"));
        etDeviceTag = UiHelpers.textInput(this, "e.g. pixel9", settings.getDeviceTag());
        sDef.addView(etDeviceTag);

        sDef.addView(UiHelpers.fieldLabel(this, "Global Ignore Patterns (applied to all folders)"));
        etGlobalIgnores = UiHelpers.input(this, "*.tmp\n.DS_Store", settings.getGlobalIgnorePatterns(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etGlobalIgnores.setMinLines(3);
        etGlobalIgnores.setTypeface(Typeface.MONOSPACE);
        sDef.addView(etGlobalIgnores);

        /* ---- Section 5: Notifications & Maintenance ---- */
        LinearLayout sNotif = sectionCard(content, "NOTIFICATIONS & MAINTENANCE");
        bindBool(sNotif, "Show live file/speed progress in notification",
                SynkSettings.KEY_LIVE_NOTIF, settings.isShowLiveNotifProgress(), null);
        bindBool(sNotif, "Notify when file conflicts need resolution",
                SynkSettings.KEY_NOTIFY_CONFLICTS, settings.isNotifyConflicts(), null);
        bindBool(sNotif, "Notify when sync transfers fail",
                SynkSettings.KEY_NOTIFY_ERRORS, settings.isNotifyErrors(), null);
        bindIntSpinner(sNotif, "Activity Log Retention", SynkSettings.KEY_LOG_RETENTION,
                LOG_RET_VALS, LOG_RET_LBL, settings.getLogRetentionLimit());

        tvMaintStats = UiHelpers.label(this, maintSummary(), 11, UiHelpers.COLOR_SECONDARY, false);
        tvMaintStats.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = UiHelpers.dp(this, 10);
        tvMaintStats.setLayoutParams(mlp);
        sNotif.addView(tvMaintStats);

        LinearLayout maintRow = new LinearLayout(this);
        maintRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiHelpers.dp(this, 8);
        maintRow.setLayoutParams(rlp);

        Button btnClearLogs = UiHelpers.smallButton(this, "Clear Logs", UiHelpers.COLOR_AMBER, false);
        btnClearLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                db.clearLogs();
                tvMaintStats.setText(maintSummary());
                Toast.makeText(SettingsActivity.this, "Logs cleared", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnResetBase = UiHelpers.smallButton(this, "Reset All Baselines", UiHelpers.COLOR_RED, false);
        btnResetBase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Reset All Baselines")
                        .setMessage("Clears stored file & PIM sync snapshots. Your files are untouched; the next sync will re-index everything.")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                db.resetAllBaselines();
                                tvMaintStats.setText(maintSummary());
                                Toast.makeText(SettingsActivity.this, "All baselines cleared", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        maintRow.addView(btnClearLogs);
        maintRow.addView(btnResetBase);
        sNotif.addView(maintRow);

        setContentView(root);
    }

    private LinearLayout sectionCard(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = UiHelpers.dp(this, 14);
        card.setPadding(p, UiHelpers.dp(this, 12), p, UiHelpers.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiHelpers.dp(this, 10);
        card.setLayoutParams(lp);

        TextView hdr = UiHelpers.label(this, title, 11, UiHelpers.COLOR_CYAN, true);
        hdr.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = UiHelpers.dp(this, 6);
        hdr.setLayoutParams(hlp);
        card.addView(hdr);

        parent.addView(card);
        return card;
    }

    private CheckBox bindBool(LinearLayout parent, String label, final String key,
                              boolean initial, final Runnable afterChange) {
        CheckBox cb = UiHelpers.checkBox(this, label, initial);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.prefs().edit().putBoolean(key, isChecked).apply();
                if (afterChange != null) afterChange.run();
            }
        });
        parent.addView(cb);
        return cb;
    }

    private void bindIntSpinner(LinearLayout parent, String label, final String key,
                                final int[] values, String[] labels, int currentVal) {
        parent.addView(UiHelpers.fieldLabel(this, label));
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == currentVal) { idx = i; break; }
        }
        Spinner sp = UiHelpers.spinner(this, labels, idx);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                settings.prefs().edit().putInt(key, values[pos]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(sp);
    }

    private String engineSummary() {
        return "Active: " + NativeBridge.engineName()
                + (NativeBridge.isNativeBinaryAvailable() ? " (.so loaded)" : " (Java mirror)");
    }

    private String maintSummary() {
        return "Indexed baseline entries: " + db.countBaselineEntries()
                + "  |  Log entries: " + db.listLogs(1000).size();
    }

    private void saveTextFieldsAndApply() {
        SharedPreferences.Editor ed = settings.prefs().edit();
        if (etDeviceTag != null) ed.putString(SynkSettings.KEY_DEVICE_TAG, etDeviceTag.getText().toString().trim());
        if (etGlobalIgnores != null) ed.putString(SynkSettings.KEY_GLOBAL_IGNORES, etGlobalIgnores.getText().toString());
        ed.apply();
        settings.toSyncOptions();
        SyncForegroundService.refreshWatchers(this);
    }

    @Override
    protected void onPause() {
        saveTextFieldsAndApply();
        super.onPause();
    }
}
