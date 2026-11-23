package org.synk.ui;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.synk.R;
import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.*;
import org.synk.core.SyncProgress;
import org.synk.service.SyncForegroundService;
import org.synk.service.SyncRunner;
import org.synk.service.SyncScheduler;
import org.synk.storage.SynkDbHelper;
import org.synk.storage.SynkSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal pitch-black AMOLED main interface for Synk.
 * Four tabs: Folders, Conflicts, Cal/Contacts (PIM), and Live Activity Log.
 */
public final class MainActivity extends Activity {

    private static final int TAB_FOLDERS   = 0;
    private static final int TAB_CONFLICTS = 1;
    private static final int TAB_PIM       = 2;
    private static final int TAB_LOGS      = 3;

    private static final int REQ_STORAGE_LEGACY = 1001;
    private static final int REQ_NOTIF          = 1002;
    private static final int REQ_CALENDAR       = 1003;
    private static final int REQ_CONTACTS       = 1004;

    private SynkDbHelper db;
    private int currentTab = TAB_FOLDERS;
    private long pendingPimSyncId = -1L;

    private TextView tvEngineStatus;
    private View panelLiveSync;
    private TextView tvLiveHeader, tvLiveFile, tvLiveStats, tvLiveCounters;
    private ProgressBar pbLiveProgress;
    private Button btnLiveCancel;
    private long liveCancelPairId = -1L;
    private View bannerStorage;
    private LinearLayout subActionBar;
    private ListView listContent;
    private TextView tvEmpty;
    private Button tabFolders, tabConflicts, tabPim, tabLogs;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && SyncRunner.ACTION_PROGRESS_CHANGED.equals(intent.getAction())) {
                updateLiveProgressPanel();
            } else {
                updateLiveProgressPanel();
                refreshUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = SynkDbHelper.getInstance(this);
        SynkSettings.of(this).applyEnginePreference();

        tvEngineStatus = findViewById(R.id.tv_engine_status);
        panelLiveSync = findViewById(R.id.panel_live_sync);
        tvLiveHeader = findViewById(R.id.tv_live_header);
        tvLiveFile = findViewById(R.id.tv_live_file);
        pbLiveProgress = findViewById(R.id.pb_live_progress);
        tvLiveStats = findViewById(R.id.tv_live_stats);
        tvLiveCounters = findViewById(R.id.tv_live_counters);
        btnLiveCancel = findViewById(R.id.btn_live_cancel);

        bannerStorage = findViewById(R.id.banner_storage);
        subActionBar = findViewById(R.id.sub_action_bar);
        listContent = findViewById(R.id.list_content);
        tvEmpty = findViewById(R.id.tv_empty);

        tabFolders = findViewById(R.id.tab_folders);
        tabConflicts = findViewById(R.id.tab_conflicts);
        tabPim = findViewById(R.id.tab_pim);
        tabLogs = findViewById(R.id.tab_logs);

        tvEngineStatus.setText("Engine: " + NativeBridge.engineName());

        btnLiveCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (liveCancelPairId > 0) {
                    SyncRunner.cancelPair(liveCancelPairId);
                } else {
                    SyncRunner.cancelAll();
                }
                Toast.makeText(MainActivity.this, "Cancelling sync...", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_servers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.showServersDialog(MainActivity.this, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });

        findViewById(R.id.btn_sync_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SyncForegroundService.triggerSyncAll(MainActivity.this);
                Toast.makeText(MainActivity.this, "Sync started", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentTab == TAB_PIM) {
                    Dialogs.showPimEditorDialog(MainActivity.this, null, new Runnable() {
                        @Override public void run() { refreshUi(); }
                    });
                } else {
                    Dialogs.showPairEditorDialog(MainActivity.this, null, new Runnable() {
                        @Override public void run() { refreshUi(); }
                    });
                }
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        findViewById(R.id.btn_grant_storage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStorageAccess();
            }
        });

        tabFolders.setOnClickListener(tabClick(TAB_FOLDERS));
        tabConflicts.setOnClickListener(tabClick(TAB_CONFLICTS));
        tabPim.setOnClickListener(tabClick(TAB_PIM));
        tabLogs.setOnClickListener(tabClick(TAB_LOGS));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
        }

        SyncScheduler.rescheduleAll(this);
        SyncForegroundService.refreshWatchers(this);
    }

    private View.OnClickListener tabClick(final int tab) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = tab;
                refreshUi();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        SynkSettings.of(this).applyEnginePreference();
        tvEngineStatus.setText("Engine: " + NativeBridge.engineName());
        IntentFilter filter = new IntentFilter();
        filter.addAction(SyncRunner.ACTION_STATE_CHANGED);
        filter.addAction(SyncRunner.ACTION_PROGRESS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        updateStorageBanner();
        updateLiveProgressPanel();
        refreshUi();
    }

    private void updateLiveProgressPanel() {
        List<SyncProgress.Snapshot> active = SyncRunner.getActiveSnapshots();
        if (active.isEmpty()) {
            panelLiveSync.setVisibility(View.GONE);
            liveCancelPairId = -1L;
            return;
        }

        SyncProgress.Snapshot s = active.get(0);
        liveCancelPairId = active.size() == 1 ? s.pairId : -1L;
        panelLiveSync.setVisibility(View.VISIBLE);

        String extraCount = active.size() > 1 ? (" (+" + (active.size() - 1) + " active)") : "";
        tvLiveHeader.setText(s.phase.label.toUpperCase(java.util.Locale.ROOT)
                + "  \u2022  " + s.pairName + extraCount);

        String fileLine = s.actionLabel;
        if (s.currentFile != null && !s.currentFile.isEmpty()) {
            fileLine += ": " + s.currentFile;
        }
        if (s.currentFileTotalBytes > 0) {
            fileLine += " (" + UiHelpers.formatBytes(s.currentFileBytes)
                    + " / " + UiHelpers.formatBytes(s.currentFileTotalBytes) + ")";
        }
        tvLiveFile.setText(fileLine);

        if (s.isIndeterminate()) {
            pbLiveProgress.setIndeterminate(true);
        } else {
            pbLiveProgress.setIndeterminate(false);
            pbLiveProgress.setProgress(s.overallPercent());
        }

        StringBuilder stats = new StringBuilder();
        stats.append(s.overallPercent()).append("%");
        if (s.filesTotal > 0) {
            stats.append("  \u2022  ").append(s.filesDone).append('/').append(s.filesTotal).append(" files");
        }
        stats.append("  \u2022  ").append(UiHelpers.formatSpeed(s.speedBytesPerSec));
        if (s.etaSeconds >= 0) {
            stats.append("  \u2022  ETA ").append(UiHelpers.formatEta(s.etaSeconds));
        }
        if (s.totalBytesPlanned > 0) {
            stats.append("  \u2022  ")
                 .append(UiHelpers.formatBytes(s.totalBytesDone))
                 .append(" / ")
                 .append(UiHelpers.formatBytes(s.totalBytesPlanned));
        }
        tvLiveStats.setText(stats.toString());

        tvLiveCounters.setText(
                "\u2191 " + s.uploaded
                + "   \u2193 " + s.downloaded
                + "   \u21C4 " + s.merged
                + "   \u2715 " + s.deleted
                + "   \u26A0 " + s.conflicts
                + (s.errors > 0 ? ("   ERR " + s.errors) : ""));
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
    }

    private void updateStorageBanner() {
        boolean granted;
        if (Build.VERSION.SDK_INT >= 30) {
            granted = Environment.isExternalStorageManager();
        } else {
            granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        bannerStorage.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE_LEGACY);
        }
    }

    private void triggerPimWithPermissionCheck(PimProfile profile) {
        if (profile.kind == PimKind.CALDAV) {
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                pendingPimSyncId = profile.id;
                requestPermissions(new String[]{
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                }, REQ_CALENDAR);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                pendingPimSyncId = profile.id;
                requestPermissions(new String[]{
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS
                }, REQ_CONTACTS);
                return;
            }
        }
        SyncForegroundService.triggerSyncPim(this, profile.id);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, perms, grantResults);
        updateStorageBanner();
        if ((requestCode == REQ_CALENDAR || requestCode == REQ_CONTACTS) && pendingPimSyncId > 0) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (ok) {
                SyncForegroundService.triggerSyncPim(this, pendingPimSyncId);
            } else {
                Toast.makeText(this, "Permission required for CalDAV/CardDAV sync", Toast.LENGTH_SHORT).show();
            }
            pendingPimSyncId = -1L;
        }
    }

    private void refreshUi() {
        List<ConflictRecord> conflicts = db.listConflicts();
        updateTabStyles(conflicts.size());

        subActionBar.removeAllViews();
        subActionBar.setVisibility(View.GONE);

        if (currentTab == TAB_FOLDERS) {
            bindFoldersTab();
        } else if (currentTab == TAB_CONFLICTS) {
            bindConflictsTab(conflicts);
        } else if (currentTab == TAB_PIM) {
            bindPimTab();
        } else {
            bindLogsTab();
        }
    }

    private void updateTabStyles(int conflictCount) {
        tabFolders.setTextColor(currentTab == TAB_FOLDERS ? UiHelpers.COLOR_GREEN : UiHelpers.COLOR_SECONDARY);
        tabConflicts.setText(conflictCount > 0 ? ("CONFLICTS (" + conflictCount + ")") : "CONFLICTS");
        tabConflicts.setTextColor(currentTab == TAB_CONFLICTS
                ? (conflictCount > 0 ? UiHelpers.COLOR_AMBER : UiHelpers.COLOR_GREEN)
                : (conflictCount > 0 ? UiHelpers.COLOR_AMBER : UiHelpers.COLOR_SECONDARY));
        tabPim.setTextColor(currentTab == TAB_PIM ? UiHelpers.COLOR_GREEN : UiHelpers.COLOR_SECONDARY);
        tabLogs.setTextColor(currentTab == TAB_LOGS ? UiHelpers.COLOR_GREEN : UiHelpers.COLOR_SECONDARY);
    }

    /* ---- Tab 0: Sync Folder Pairs ---- */

    private void bindFoldersTab() {
        final List<SyncPair> pairs = db.listPairs();
        final Map<Long, AccountConfig> accMap = new HashMap<>();
        for (AccountConfig a : db.listAccounts()) accMap.put(a.id, a);

        if (pairs.isEmpty()) {
            showEmpty("No folder sync pairs yet.\n\n1. Tap SERVERS to add WebDAV / Nextcloud / Seafile / FTP / S3 / Local.\n2. Tap + to choose a local folder and sync mode.");
            return;
        }
        showList();
        listContent.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return pairs.size(); }
            @Override public Object getItem(int i) { return pairs.get(i); }
            @Override public long getItemId(int i) { return pairs.get(i).id; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                final SyncPair pair = pairs.get(i);
                AccountConfig acc = accMap.get(pair.accountId);
                return buildPairCard(pair, acc);
            }
        });
    }

    private View buildPairCard(final SyncPair pair, AccountConfig acc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = UiHelpers.dp(this, 14);
        card.setPadding(pad, UiHelpers.dp(this, 12), pad, UiHelpers.dp(this, 12));

        /* Row 1: Title + SYNC button */
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        SyncProgress.Snapshot activeSnap = SyncRunner.getPairProgress(pair.id);
        int dotColor = (activeSnap != null && activeSnap.active)
                ? UiHelpers.COLOR_CYAN
                : statusColor(pair.lastStatus, pair.enabled);
        TextView dot = UiHelpers.label(this, "\u25CF ", 12, dotColor, true);
        TextView title = UiHelpers.label(this, pair.name, 15,
                pair.enabled ? UiHelpers.COLOR_TEXT : UiHelpers.COLOR_MUTED, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSync;
        if (activeSnap != null && activeSnap.active) {
            btnSync = UiHelpers.smallButton(this, "Stop", UiHelpers.COLOR_RED, false);
            btnSync.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SyncRunner.cancelPair(pair.id);
                }
            });
        } else {
            btnSync = UiHelpers.smallButton(this, "Sync", UiHelpers.COLOR_GREEN, true);
            btnSync.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SyncForegroundService.triggerSyncPair(MainActivity.this, pair.id);
                }
            });
        }

        row1.addView(dot);
        row1.addView(title);
        row1.addView(btnSync);
        card.addView(row1);

        /* Row 2: Badges (mode, schedule, watch, wifi) */
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiHelpers.dp(this, 6);
        chips.setLayoutParams(clp);

        chips.addView(UiHelpers.chip(this, pair.mode.name().replace('_', ' '), UiHelpers.COLOR_CYAN));
        chips.addView(UiHelpers.chip(this, UiHelpers.formatInterval(pair.intervalMinutes), UiHelpers.COLOR_SECONDARY));
        if (pair.watchEnabled) {
            chips.addView(UiHelpers.chip(this, "WATCH", UiHelpers.COLOR_GREEN));
        }
        if (pair.wifiOnly) {
            chips.addView(UiHelpers.chip(this, "WI-FI", UiHelpers.COLOR_SECONDARY));
        }
        card.addView(chips);

        /* Row 3: Paths */
        String remoteLabel = (acc != null ? acc.name + ":" : "?:") + (pair.remotePath == null ? "/" : pair.remotePath);
        TextView tvPaths = UiHelpers.label(this, pair.localPath + "\n\u21C4 " + remoteLabel,
                11, UiHelpers.COLOR_SECONDARY, false);
        tvPaths.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = UiHelpers.dp(this, 8);
        tvPaths.setLayoutParams(plp);
        card.addView(tvPaths);

        /* Row 4: Last sync status */
        String statusStr = (pair.lastStatus == null ? "Never synced" : pair.lastStatus)
                + (pair.lastSyncAt > 0 ? ("  \u2022  " + UiHelpers.formatTime(pair.lastSyncAt)) : "");
        TextView tvStatus = UiHelpers.label(this, statusStr, 11, dotColor, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiHelpers.dp(this, 6);
        tvStatus.setLayoutParams(slp);
        card.addView(tvStatus);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.showPairEditorDialog(MainActivity.this, pair, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });
        return card;
    }

    /* ---- Tab 1: Interactive Conflict Manager ---- */

    private void bindConflictsTab(final List<ConflictRecord> conflicts) {
        if (conflicts.isEmpty()) {
            showEmpty("No unresolved conflicts.\n\nWhen both client and server edit the same file while offline (and auto-merge cannot resolve overlapping lines), conflicts appear here.");
            return;
        }

        subActionBar.setVisibility(View.VISIBLE);
        Button bAllLocal = UiHelpers.smallButton(this, "All Local", UiHelpers.COLOR_GREEN, false);
        Button bAllRemote = UiHelpers.smallButton(this, "All Remote", UiHelpers.COLOR_CYAN, false);
        Button bAllBoth = UiHelpers.smallButton(this, "All Both", UiHelpers.COLOR_AMBER, false);
        bAllLocal.setOnClickListener(bulkResolveClick(conflicts, ConflictResolution.KEEP_LOCAL));
        bAllRemote.setOnClickListener(bulkResolveClick(conflicts, ConflictResolution.KEEP_REMOTE));
        bAllBoth.setOnClickListener(bulkResolveClick(conflicts, ConflictResolution.KEEP_BOTH));
        subActionBar.addView(bAllLocal);
        subActionBar.addView(bAllRemote);
        subActionBar.addView(bAllBoth);

        showList();
        listContent.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return conflicts.size(); }
            @Override public Object getItem(int i) { return conflicts.get(i); }
            @Override public long getItemId(int i) { return conflicts.get(i).id; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                return buildConflictCard(conflicts.get(i));
            }
        });
    }

    private View.OnClickListener bulkResolveClick(final List<ConflictRecord> list, final ConflictResolution choice) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<ConflictRecord> snap = new ArrayList<>(list);
                Toast.makeText(MainActivity.this, "Resolving " + snap.size() + " conflict(s)...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        for (ConflictRecord c : snap) {
                            try {
                                SyncRunner.resolveConflict(getApplicationContext(), c, choice, null);
                            } catch (Exception ignored) {}
                        }
                        runOnUiThread(new Runnable() {
                            @Override public void run() { refreshUi(); }
                        });
                    }
                }).start();
            }
        };
    }

    private View buildConflictCard(final ConflictRecord c) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = UiHelpers.dp(this, 14);
        card.setPadding(pad, UiHelpers.dp(this, 12), pad, UiHelpers.dp(this, 12));

        card.addView(UiHelpers.label(this, c.relPath, 14, UiHelpers.COLOR_TEXT, true));
        card.addView(UiHelpers.label(this, c.pairName + "  \u2022  " + c.kind.label, 12, UiHelpers.COLOR_AMBER, false));

        TextView meta = UiHelpers.label(this,
                "L: " + UiHelpers.formatBytes(c.localSize) + " (" + UiHelpers.formatTime(c.localMtimeMs) + ")   " +
                "R: " + UiHelpers.formatBytes(c.remoteSize) + " (" + UiHelpers.formatTime(c.remoteMtimeMs) + ")",
                11, UiHelpers.COLOR_SECONDARY, false);
        meta.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = UiHelpers.dp(this, 4);
        meta.setLayoutParams(mlp);
        card.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = UiHelpers.dp(this, 10);
        actions.setLayoutParams(alp);

        Button bInspect = UiHelpers.smallButton(this, c.mergedPreview != null ? "Diff / Merge" : "Inspect", UiHelpers.COLOR_TEXT, false);
        Button bLocal = UiHelpers.smallButton(this, "Local", UiHelpers.COLOR_GREEN, false);
        Button bRemote = UiHelpers.smallButton(this, "Remote", UiHelpers.COLOR_CYAN, false);
        Button bBoth = UiHelpers.smallButton(this, "Both", UiHelpers.COLOR_AMBER, false);

        bInspect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.showConflictInspectDialog(MainActivity.this, c, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });
        bLocal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.runResolveAsync(MainActivity.this, c, ConflictResolution.KEEP_LOCAL, null, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });
        bRemote.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.runResolveAsync(MainActivity.this, c, ConflictResolution.KEEP_REMOTE, null, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });
        bBoth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.runResolveAsync(MainActivity.this, c, ConflictResolution.KEEP_BOTH, null, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });

        actions.addView(bInspect);
        actions.addView(bLocal);
        actions.addView(bRemote);
        actions.addView(bBoth);
        card.addView(actions);
        return card;
    }

    /* ---- Tab 2: CalDAV & CardDAV Profiles ---- */

    private void bindPimTab() {
        final List<PimProfile> profiles = db.listPimProfiles();
        if (profiles.isEmpty()) {
            showEmpty("No CalDAV (Calendar) or CardDAV (Contacts) profiles.\n\nTap + to sync your Nextcloud, Radicale, or Baikal calendars and contacts directly with Android.");
            return;
        }
        showList();
        listContent.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return profiles.size(); }
            @Override public Object getItem(int i) { return profiles.get(i); }
            @Override public long getItemId(int i) { return profiles.get(i).id; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                return buildPimCard(profiles.get(i));
            }
        });
    }

    private View buildPimCard(final PimProfile p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = UiHelpers.dp(this, 14);
        card.setPadding(pad, UiHelpers.dp(this, 12), pad, UiHelpers.dp(this, 12));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiHelpers.label(this, p.name, 15, UiHelpers.COLOR_TEXT, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnSync = UiHelpers.smallButton(this, "Sync", UiHelpers.COLOR_GREEN, true);
        btnSync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerPimWithPermissionCheck(p);
            }
        });
        row1.addView(title);
        row1.addView(btnSync);
        card.addView(row1);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiHelpers.dp(this, 6);
        chips.setLayoutParams(clp);
        chips.addView(UiHelpers.chip(this, p.kind.name(),
                p.kind == PimKind.CALDAV ? UiHelpers.COLOR_CYAN : UiHelpers.COLOR_GREEN));
        chips.addView(UiHelpers.chip(this, UiHelpers.formatInterval(p.intervalMinutes), UiHelpers.COLOR_SECONDARY));
        card.addView(chips);

        TextView urlTv = UiHelpers.label(this, p.url, 11, UiHelpers.COLOR_SECONDARY, false);
        urlTv.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = UiHelpers.dp(this, 6);
        urlTv.setLayoutParams(ulp);
        card.addView(urlTv);

        int sc = statusColor(p.lastStatus, p.enabled);
        String st = (p.lastStatus == null ? "Never synced" : p.lastStatus)
                + (p.lastSyncAt > 0 ? ("  \u2022  " + UiHelpers.formatTime(p.lastSyncAt)) : "");
        TextView stTv = UiHelpers.label(this, st, 11, sc, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiHelpers.dp(this, 6);
        stTv.setLayoutParams(slp);
        card.addView(stTv);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.showPimEditorDialog(MainActivity.this, p, new Runnable() {
                    @Override public void run() { refreshUi(); }
                });
            }
        });
        return card;
    }

    /* ---- Tab 3: Activity Logs ---- */

    private void bindLogsTab() {
        final List<SyncLogEntry> logs = db.listLogs(200);
        subActionBar.setVisibility(View.VISIBLE);
        TextView info = UiHelpers.label(this, "Sync Log (" + logs.size() + ")", 12, UiHelpers.COLOR_SECONDARY, false);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button bClear = UiHelpers.smallButton(this, "Clear", UiHelpers.COLOR_RED, false);
        bClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                db.clearLogs();
                refreshUi();
            }
        });
        subActionBar.addView(info);
        subActionBar.addView(bClear);

        if (logs.isEmpty()) {
            showEmpty("No sync activity recorded yet.");
            return;
        }
        showList();
        listContent.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return logs.size(); }
            @Override public Object getItem(int i) { return logs.get(i); }
            @Override public long getItemId(int i) { return logs.get(i).id; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                SyncLogEntry e = logs.get(i);
                int color = UiHelpers.COLOR_SECONDARY;
                if ("ERROR".equals(e.level)) color = UiHelpers.COLOR_RED;
                else if ("WARN".equals(e.level)) color = UiHelpers.COLOR_AMBER;
                else if ("INFO".equals(e.level)) color = UiHelpers.COLOR_TEXT;

                TextView tv = UiHelpers.label(MainActivity.this,
                        UiHelpers.formatClock(e.timestamp) + " [" + e.level + "] " + e.source + ": " + e.message,
                        11, color, false);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setBackgroundResource(R.drawable.bg_card);
                int p = UiHelpers.dp(MainActivity.this, 8);
                tv.setPadding(p, dp6(), p, dp6());
                return tv;
            }
        });
    }

    private int dp6() {
        return UiHelpers.dp(this, 6);
    }

    private void showEmpty(String msg) {
        listContent.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(msg);
    }

    private void showList() {
        tvEmpty.setVisibility(View.GONE);
        listContent.setVisibility(View.VISIBLE);
    }

    private static int statusColor(String status, boolean enabled) {
        if (!enabled) return UiHelpers.COLOR_MUTED;
        if (status == null) return UiHelpers.COLOR_SECONDARY;
        String lower = status.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("error") || lower.contains("failed")) return UiHelpers.COLOR_RED;
        if (lower.contains("conflict")) return UiHelpers.COLOR_AMBER;
        if (lower.contains("syncing")) return UiHelpers.COLOR_CYAN;
        if (lower.startsWith("up ")) return UiHelpers.COLOR_GREEN;
        return UiHelpers.COLOR_SECONDARY;
    }
}
