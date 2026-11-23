package org.synk.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.synk.core.SyncModels.*;
import org.synk.pim.DavPimClient;
import org.synk.protocol.BackendFactory;
import org.synk.protocol.RemoteBackend;
import org.synk.service.SyncForegroundService;
import org.synk.service.SyncRunner;
import org.synk.service.SyncScheduler;
import org.synk.storage.SynkDbHelper;
import org.synk.storage.SynkSettings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * All AMOLED configuration, folder-picker, server-test, PIM, and conflict-resolution dialogs.
 */
public final class Dialogs {

    private static final int[] INTERVAL_VALUES = { 0, 15, 30, 60, 180, 360, 720, 1440 };
    private static final String[] INTERVAL_LABELS = {
            "Manual only", "Every 15 min", "Every 30 min", "Every 1 hour",
            "Every 3 hours", "Every 6 hours", "Every 12 hours", "Every 24 hours"
    };

    private Dialogs() {}

    /* ---- 1. Remote Servers List & Editor ---- */

    public static void showServersDialog(final Activity act, final Runnable onChanged) {
        final SynkDbHelper db = SynkDbHelper.getInstance(act);
        final List<AccountConfig> accounts = db.listAccounts();

        String[] items = new String[accounts.size() + 1];
        for (int i = 0; i < accounts.size(); i++) {
            AccountConfig a = accounts.get(i);
            items[i] = a.name + "  [" + a.protocol.name() + "]\n" + a.url;
        }
        items[accounts.size()] = "+ Add New Server / Cloud Provider";

        new AlertDialog.Builder(act)
                .setTitle("Remote Servers")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == accounts.size()) {
                            showAccountEditorDialog(act, null, onChanged);
                        } else {
                            showAccountEditorDialog(act, accounts.get(which), onChanged);
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    public static void showAccountEditorDialog(final Activity act, final AccountConfig existing, final Runnable onSaved) {
        final SynkDbHelper db = SynkDbHelper.getInstance(act);
        final AccountConfig acc = existing != null ? existing : new AccountConfig();

        ScrollView scroll = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiHelpers.dp(act, 16);
        box.setPadding(pad, UiHelpers.dp(act, 8), pad, pad);
        scroll.addView(box);

        box.addView(UiHelpers.fieldLabel(act, "Server Name"));
        final EditText etName = UiHelpers.textInput(act, "e.g. Nextcloud Home", acc.name);
        box.addView(etName);

        box.addView(UiHelpers.fieldLabel(act, "Protocol"));
        final Protocol[] protos = Protocol.values();
        String[] protoLabels = new String[protos.length];
        int pIdx = 0;
        for (int i = 0; i < protos.length; i++) {
            protoLabels[i] = protos[i].label;
            if (protos[i] == acc.protocol) pIdx = i;
        }
        final Spinner spProto = UiHelpers.spinner(act, protoLabels, pIdx);
        box.addView(spProto);

        box.addView(UiHelpers.fieldLabel(act, "URL / Host / Local Path"));
        final EditText etUrl = UiHelpers.textInput(act,
                "https://cloud.example.com/remote.php/dav/files/user", acc.url);
        box.addView(etUrl);

        box.addView(UiHelpers.fieldLabel(act, "Username / S3 Access Key"));
        final EditText etUser = UiHelpers.textInput(act, "username (optional for Bearer/Local)", acc.username);
        box.addView(etUser);

        box.addView(UiHelpers.fieldLabel(act, "Password / App Token / S3 Secret"));
        final EditText etPass = UiHelpers.passwordInput(act, "password or 'Bearer <token>'", acc.password);
        box.addView(etPass);

        box.addView(UiHelpers.fieldLabel(act, "Extra (S3: bucket|region)"));
        final EditText etExtra = UiHelpers.textInput(act, "my-bucket|us-east-1 (S3 only)", acc.extra);
        box.addView(etExtra);

        final CheckBox cbTrust = UiHelpers.checkBox(act, "Trust self-signed TLS certificates", acc.trustAllCerts);
        box.addView(cbTrust);

        spProto.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                switch (protos[pos]) {
                    case WEBDAV:
                        etUrl.setHint("https://host/remote.php/dav/files/user or /seafdav");
                        break;
                    case FTP:
                        etUrl.setHint("ftp://192.168.1.10:21/backup");
                        break;
                    case FTPS:
                        etUrl.setHint("ftps://host:21/backup");
                        break;
                    case S3:
                        etUrl.setHint("https://s3.us-east-1.amazonaws.com");
                        break;
                    case LOCAL:
                        etUrl.setHint("/storage/emulated/0/SynkBackup");
                        break;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        /* Live connection tester */
        LinearLayout testRow = new LinearLayout(act);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = UiHelpers.dp(act, 12);
        testRow.setLayoutParams(tlp);

        Button btnTest = UiHelpers.smallButton(act, "Test Connection", UiHelpers.COLOR_CYAN, false);
        Button btnBrowseSrv = UiHelpers.smallButton(act, "Browse Files", UiHelpers.COLOR_GREEN, false);
        final TextView tvTestRes = UiHelpers.label(act, "", 12, UiHelpers.COLOR_SECONDARY, false);
        testRow.addView(btnTest);
        testRow.addView(btnBrowseSrv);
        box.addView(testRow);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = UiHelpers.dp(act, 6);
        tvTestRes.setLayoutParams(trlp);
        box.addView(tvTestRes);

        btnBrowseSrv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccountConfig tmp = new AccountConfig(0,
                        etName.getText().toString().trim().isEmpty() ? "Server" : etName.getText().toString().trim(),
                        protos[spProto.getSelectedItemPosition()],
                        etUrl.getText().toString().trim(),
                        etUser.getText().toString().trim(),
                        etPass.getText().toString(),
                        etExtra.getText().toString().trim(),
                        cbTrust.isChecked());
                showRemoteFolderPickerDialog(act, tmp, "/", null);
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final AccountConfig tmp = new AccountConfig(0,
                        etName.getText().toString().trim(),
                        protos[spProto.getSelectedItemPosition()],
                        etUrl.getText().toString().trim(),
                        etUser.getText().toString().trim(),
                        etPass.getText().toString(),
                        etExtra.getText().toString().trim(),
                        cbTrust.isChecked());
                tvTestRes.setTextColor(UiHelpers.COLOR_CYAN);
                tvTestRes.setText("Testing...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try (RemoteBackend b = BackendFactory.create(tmp)) {
                            final String ok = b.testConnection();
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvTestRes.setTextColor(UiHelpers.COLOR_GREEN);
                                    tvTestRes.setText(ok);
                                }
                            });
                        } catch (final Exception e) {
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvTestRes.setTextColor(UiHelpers.COLOR_RED);
                                    tvTestRes.setText("Failed: " + e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        AlertDialog.Builder dlg = new AlertDialog.Builder(act)
                .setTitle(existing != null ? "Edit Server" : "Add Server")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        String url = etUrl.getText().toString().trim();
                        if (name.isEmpty() || url.isEmpty()) {
                            Toast.makeText(act, "Name and URL are required", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        acc.name = name;
                        acc.protocol = protos[spProto.getSelectedItemPosition()];
                        acc.url = url;
                        acc.username = etUser.getText().toString().trim();
                        acc.password = etPass.getText().toString();
                        acc.extra = etExtra.getText().toString().trim();
                        acc.trustAllCerts = cbTrust.isChecked();
                        db.saveAccount(acc);
                        if (onSaved != null) onSaved.run();
                    }
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            dlg.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    db.deleteAccount(existing.id);
                    SyncScheduler.rescheduleAll(act);
                    SyncForegroundService.refreshWatchers(act);
                    if (onSaved != null) onSaved.run();
                }
            });
        }
        dlg.show();
    }

    /* ---- 2. Sync Folder Pair Editor ---- */

    public static void showPairEditorDialog(final Activity act, final SyncPair existing, final Runnable onSaved) {
        final SynkDbHelper db = SynkDbHelper.getInstance(act);
        final List<AccountConfig> accounts = db.listAccounts();
        if (accounts.isEmpty()) {
            Toast.makeText(act, "Add a remote server first", Toast.LENGTH_SHORT).show();
            showAccountEditorDialog(act, null, onSaved);
            return;
        }

        final SyncPair pair = existing != null ? existing : new SyncPair();
        if (existing == null) {
            SynkSettings settings = SynkSettings.of(act);
            pair.mode = settings.getDefaultSyncMode();
            pair.conflictPolicy = settings.getDefaultConflictPolicy();
        }
        if (pair.localPath == null || pair.localPath.isEmpty()) {
            pair.localPath = new File(Environment.getExternalStorageDirectory(), "Documents").getAbsolutePath();
        }
        if (pair.remotePath == null || pair.remotePath.isEmpty()) {
            pair.remotePath = "/Documents";
        }

        ScrollView scroll = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiHelpers.dp(act, 16);
        box.setPadding(pad, UiHelpers.dp(act, 8), pad, pad);
        scroll.addView(box);

        box.addView(UiHelpers.fieldLabel(act, "Pair Name"));
        final EditText etName = UiHelpers.textInput(act, "e.g. Obsidian Notes", pair.name);
        box.addView(etName);

        box.addView(UiHelpers.fieldLabel(act, "Remote Server"));
        String[] accLabels = new String[accounts.size()];
        int accIdx = 0;
        for (int i = 0; i < accounts.size(); i++) {
            accLabels[i] = accounts.get(i).name + " (" + accounts.get(i).protocol.name() + ")";
            if (accounts.get(i).id == pair.accountId) accIdx = i;
        }
        final Spinner spAcc = UiHelpers.spinner(act, accLabels, accIdx);
        box.addView(spAcc);

        box.addView(UiHelpers.fieldLabel(act, "Local Folder"));
        LinearLayout localRow = new LinearLayout(act);
        localRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etLocal = UiHelpers.textInput(act, "/storage/emulated/0/Notes", pair.localPath);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        elp.rightMargin = UiHelpers.dp(act, 6);
        etLocal.setLayoutParams(elp);
        Button btnBrowse = UiHelpers.smallButton(act, "Browse", UiHelpers.COLOR_CYAN, false);
        localRow.addView(etLocal);
        localRow.addView(btnBrowse);
        box.addView(localRow);

        btnBrowse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFolderPickerDialog(act, etLocal.getText().toString().trim(), new FolderSelectedCallback() {
                    @Override
                    public void onSelected(String path) {
                        etLocal.setText(path);
                        if (etName.getText().toString().trim().isEmpty()) {
                            etName.setText(new File(path).getName());
                        }
                    }
                });
            }
        });

        box.addView(UiHelpers.fieldLabel(act, "Remote Path"));
        LinearLayout remoteRow = new LinearLayout(act);
        remoteRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etRemote = UiHelpers.textInput(act, "/Notes", pair.remotePath);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.rightMargin = UiHelpers.dp(act, 6);
        etRemote.setLayoutParams(rlp);
        Button btnBrowseRemote = UiHelpers.smallButton(act, "Browse", UiHelpers.COLOR_CYAN, false);
        remoteRow.addView(etRemote);
        remoteRow.addView(btnBrowseRemote);
        box.addView(remoteRow);

        btnBrowseRemote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccountConfig chosen = accounts.get(spAcc.getSelectedItemPosition());
                showRemoteFolderPickerDialog(act, chosen, etRemote.getText().toString().trim(), new FolderSelectedCallback() {
                    @Override
                    public void onSelected(String path) {
                        etRemote.setText(path);
                    }
                });
            }
        });

        box.addView(UiHelpers.fieldLabel(act, "Sync Mode"));
        final SyncMode[] modes = SyncMode.values();
        String[] modeLabels = new String[modes.length];
        int modeIdx = 0;
        for (int i = 0; i < modes.length; i++) {
            modeLabels[i] = modes[i].label;
            if (modes[i] == pair.mode) modeIdx = i;
        }
        final Spinner spMode = UiHelpers.spinner(act, modeLabels, modeIdx);
        box.addView(spMode);

        box.addView(UiHelpers.fieldLabel(act, "Conflict Handling"));
        final ConflictPolicy[] policies = ConflictPolicy.values();
        String[] polLabels = new String[policies.length];
        int polIdx = 1;
        for (int i = 0; i < policies.length; i++) {
            polLabels[i] = policies[i].label;
            if (policies[i] == pair.conflictPolicy) polIdx = i;
        }
        final Spinner spPolicy = UiHelpers.spinner(act, polLabels, polIdx);
        box.addView(spPolicy);

        box.addView(UiHelpers.fieldLabel(act, "Scheduled Sync Interval"));
        int intIdx = 0;
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            if (INTERVAL_VALUES[i] == pair.intervalMinutes) intIdx = i;
        }
        final Spinner spInterval = UiHelpers.spinner(act, INTERVAL_LABELS, intIdx);
        box.addView(spInterval);

        final CheckBox cbWatch = UiHelpers.checkBox(act, "Watch local changes live (native inotify)", pair.watchEnabled);
        final CheckBox cbWifi = UiHelpers.checkBox(act, "Auto-sync on Wi-Fi / unmetered only", pair.wifiOnly);
        final CheckBox cbCharge = UiHelpers.checkBox(act, "Scheduled sync only while charging", pair.chargingOnly);
        final CheckBox cbEnabled = UiHelpers.checkBox(act, "Enabled", pair.enabled);
        box.addView(cbWatch);
        box.addView(cbWifi);
        box.addView(cbCharge);
        box.addView(cbEnabled);

        box.addView(UiHelpers.fieldLabel(act, "Ignore Patterns (one glob per line)"));
        final EditText etIgnore = UiHelpers.input(act, "*.tmp\n.git/**", pair.ignorePatterns,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etIgnore.setMinLines(3);
        etIgnore.setTypeface(Typeface.MONOSPACE);
        box.addView(etIgnore);

        if (existing != null) {
            Button btnReset = UiHelpers.smallButton(act, "Reset Sync Baseline", UiHelpers.COLOR_AMBER, false);
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            resetLp.topMargin = UiHelpers.dp(act, 10);
            btnReset.setLayoutParams(resetLp);
            btnReset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    db.resetBaseline(existing.id);
                    Toast.makeText(act, "Baseline cleared; next sync will re-index", Toast.LENGTH_SHORT).show();
                }
            });
            box.addView(btnReset);
        }

        AlertDialog.Builder dlg = new AlertDialog.Builder(act)
                .setTitle(existing != null ? "Edit Sync Folder" : "Add Sync Folder")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String local = etLocal.getText().toString().trim();
                        if (local.isEmpty()) {
                            Toast.makeText(act, "Local folder is required", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String name = etName.getText().toString().trim();
                        if (name.isEmpty()) name = new File(local).getName();

                        pair.name = name;
                        pair.accountId = accounts.get(spAcc.getSelectedItemPosition()).id;
                        pair.localPath = local;
                        pair.remotePath = etRemote.getText().toString().trim();
                        pair.mode = modes[spMode.getSelectedItemPosition()];
                        pair.conflictPolicy = policies[spPolicy.getSelectedItemPosition()];
                        pair.intervalMinutes = INTERVAL_VALUES[spInterval.getSelectedItemPosition()];
                        pair.watchEnabled = cbWatch.isChecked();
                        pair.wifiOnly = cbWifi.isChecked();
                        pair.chargingOnly = cbCharge.isChecked();
                        pair.enabled = cbEnabled.isChecked();
                        pair.ignorePatterns = etIgnore.getText().toString();

                        db.savePair(pair);
                        SyncScheduler.schedulePair(act, pair);
                        SyncForegroundService.refreshWatchers(act);
                        if (onSaved != null) onSaved.run();
                    }
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            dlg.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    SyncScheduler.cancelPair(act, existing.id);
                    db.deletePair(existing.id);
                    SyncForegroundService.refreshWatchers(act);
                    if (onSaved != null) onSaved.run();
                }
            });
        }
        dlg.show();
    }

    /* ---- 3. Built-in AMOLED Local Folder Picker ---- */

    public interface FolderSelectedCallback {
        void onSelected(String path);
    }

    public static void showFolderPickerDialog(final Activity act, String startPath, final FolderSelectedCallback cb) {
        File start = (startPath != null && !startPath.isEmpty())
                ? new File(startPath) : Environment.getExternalStorageDirectory();
        if (!start.exists() || !start.isDirectory()) {
            start = Environment.getExternalStorageDirectory();
        }
        final File[] currentDir = { start };

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiHelpers.COLOR_BLACK);
        int pad = UiHelpers.dp(act, 12);
        root.setPadding(pad, pad, pad, pad);

        final TextView tvCur = UiHelpers.label(act, currentDir[0].getAbsolutePath(), 12, UiHelpers.COLOR_GREEN, true);
        tvCur.setTypeface(Typeface.MONOSPACE);
        root.addView(tvCur);

        LinearLayout topBtns = new LinearLayout(act);
        topBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = UiHelpers.dp(act, 6);
        tlp.bottomMargin = UiHelpers.dp(act, 6);
        topBtns.setLayoutParams(tlp);

        Button btnUp = UiHelpers.smallButton(act, ".. Up", UiHelpers.COLOR_TEXT, false);
        Button btnNew = UiHelpers.smallButton(act, "+ New Folder", UiHelpers.COLOR_CYAN, false);
        topBtns.addView(btnUp);
        topBtns.addView(btnNew);
        root.addView(topBtns);

        final List<File> dirs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final ListView lv = new ListView(act);
        lv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelpers.dp(act, 300)));
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convertView, parent);
                tv.setTextColor(UiHelpers.COLOR_TEXT);
                tv.setTextSize(14);
                return tv;
            }
        };
        lv.setAdapter(adapter);
        root.addView(lv);

        final Runnable reload = new Runnable() {
            @Override
            public void run() {
                tvCur.setText(currentDir[0].getAbsolutePath());
                dirs.clear();
                names.clear();
                File[] kids = currentDir[0].listFiles();
                if (kids != null) {
                    List<File> sub = new ArrayList<>();
                    for (File f : kids) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) sub.add(f);
                    }
                    Collections.sort(sub, new Comparator<File>() {
                        @Override
                        public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                    for (File f : sub) {
                        dirs.add(f);
                        names.add("/" + f.getName());
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };
        reload.run();

        btnUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File parent = currentDir[0].getParentFile();
                if (parent != null && parent.canRead()) {
                    currentDir[0] = parent;
                    reload.run();
                }
            }
        });

        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText et = UiHelpers.textInput(act, "Folder name", "");
                new AlertDialog.Builder(act)
                        .setTitle("Create Folder")
                        .setView(et)
                        .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String n = et.getText().toString().trim();
                                if (!n.isEmpty()) {
                                    File nf = new File(currentDir[0], n);
                                    if (nf.mkdirs()) {
                                        currentDir[0] = nf;
                                        reload.run();
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < dirs.size()) {
                    currentDir[0] = dirs.get(pos);
                    reload.run();
                }
            }
        });

        new AlertDialog.Builder(act)
                .setTitle("Select Local Folder")
                .setView(root)
                .setPositiveButton("Select This Folder", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cb.onSelected(currentDir[0].getAbsolutePath());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ---- 3b. Built-in AMOLED Remote File Explorer (WebDAV / FTP / FTPS / S3 / Local) ---- */

    public static void showRemoteFolderPickerDialog(final Activity act,
                                                    final AccountConfig account,
                                                    String startPath,
                                                    final FolderSelectedCallback cb) {
        final String[] currentPath = { normalizeRemotePath(startPath) };
        final int[] loadGen = { 0 };

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiHelpers.COLOR_BLACK);
        int pad = UiHelpers.dp(act, 12);
        root.setPadding(pad, pad, pad, pad);

        TextView tvServer = UiHelpers.label(act,
                account.name + "  [" + account.protocol.name() + "]", 11, UiHelpers.COLOR_CYAN, true);
        root.addView(tvServer);

        final TextView tvCurPath = UiHelpers.label(act, currentPath[0], 13, UiHelpers.COLOR_GREEN, true);
        tvCurPath.setTypeface(Typeface.MONOSPACE);
        root.addView(tvCurPath);

        LinearLayout toolbar = new LinearLayout(act);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = UiHelpers.dp(act, 8);
        tlp.bottomMargin = UiHelpers.dp(act, 6);
        toolbar.setLayoutParams(tlp);

        Button btnUp = UiHelpers.smallButton(act, ".. Up", UiHelpers.COLOR_TEXT, false);
        Button btnRefresh = UiHelpers.smallButton(act, "Refresh", UiHelpers.COLOR_SECONDARY, false);
        Button btnNewDir = UiHelpers.smallButton(act, "+ Folder", UiHelpers.COLOR_CYAN, false);
        toolbar.addView(btnUp);
        toolbar.addView(btnRefresh);
        toolbar.addView(btnNewDir);
        root.addView(toolbar);

        final TextView tvStatus = UiHelpers.label(act, "Connecting...", 11, UiHelpers.COLOR_CYAN, false);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = UiHelpers.dp(act, 6);
        tvStatus.setLayoutParams(slp);
        root.addView(tvStatus);

        final List<FileEntry> entries = new ArrayList<>();
        final ListView lv = new ListView(act);
        lv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelpers.dp(act, 310)));
        lv.setBackgroundColor(UiHelpers.COLOR_BLACK);

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int i) { return entries.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                FileEntry e = entries.get(i);
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = UiHelpers.dp(act, 8);
                row.setPadding(p, UiHelpers.dp(act, 9), p, UiHelpers.dp(act, 9));

                TextView badge = UiHelpers.label(act, e.isDir ? "[DIR] " : "      ", 11,
                        e.isDir ? UiHelpers.COLOR_CYAN : UiHelpers.COLOR_MUTED, e.isDir);
                badge.setTypeface(Typeface.MONOSPACE);

                TextView name = UiHelpers.label(act, e.isDir ? (e.relPath + "/") : e.relPath, 13,
                        e.isDir ? UiHelpers.COLOR_TEXT : UiHelpers.COLOR_SECONDARY, e.isDir);
                name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.addView(badge);
                row.addView(name);

                if (!e.isDir) {
                    TextView sz = UiHelpers.label(act, UiHelpers.formatBytes(e.size), 11, UiHelpers.COLOR_MUTED, false);
                    sz.setTypeface(Typeface.MONOSPACE);
                    row.addView(sz);
                }
                return row;
            }
        };
        lv.setAdapter(adapter);
        root.addView(lv);

        final Runnable loadCurrent = new Runnable() {
            @Override
            public void run() {
                final String target = currentPath[0];
                final int myGen = ++loadGen[0];
                tvCurPath.setText(target);
                tvStatus.setTextColor(UiHelpers.COLOR_CYAN);
                tvStatus.setText("Loading " + target + " ...");
                entries.clear();
                adapter.notifyDataSetChanged();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        SynkSettings.of(act).toSyncOptions(); // apply timeouts
                        try (RemoteBackend backend = BackendFactory.create(account)) {
                            final List<FileEntry> list = backend.listChildren(target);
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (myGen != loadGen[0]) return;
                                    entries.clear();
                                    entries.addAll(list);
                                    adapter.notifyDataSetChanged();
                                    int dirs = 0, files = 0;
                                    for (FileEntry f : list) {
                                        if (f.isDir) dirs++; else files++;
                                    }
                                    tvStatus.setTextColor(UiHelpers.COLOR_SECONDARY);
                                    tvStatus.setText(list.isEmpty()
                                            ? "Empty folder (tap Select to use this path)"
                                            : (dirs + " folder(s), " + files + " file(s)"));
                                }
                            });
                        } catch (final Exception err) {
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (myGen != loadGen[0]) return;
                                    tvStatus.setTextColor(UiHelpers.COLOR_RED);
                                    tvStatus.setText("Error: " + err.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            }
        };
        loadCurrent.run();

        btnUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = currentPath[0];
                if ("/".equals(p)) return;
                int slash = p.lastIndexOf('/');
                currentPath[0] = (slash <= 0) ? "/" : p.substring(0, slash);
                loadCurrent.run();
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCurrent.run();
            }
        });

        btnNewDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText et = UiHelpers.textInput(act, "Remote folder name", "");
                new AlertDialog.Builder(act)
                        .setTitle("Create Remote Folder")
                        .setView(et)
                        .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                final String name = et.getText().toString().trim().replace("/", "");
                                if (name.isEmpty()) return;
                                final String base = currentPath[0];
                                tvStatus.setTextColor(UiHelpers.COLOR_CYAN);
                                tvStatus.setText("Creating /" + name + " ...");
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try (RemoteBackend backend = BackendFactory.create(account)) {
                                            backend.mkdir(base, name);
                                            act.runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    currentPath[0] = "/".equals(base) ? ("/" + name) : (base + "/" + name);
                                                    loadCurrent.run();
                                                }
                                            });
                                        } catch (final Exception e) {
                                            act.runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    tvStatus.setTextColor(UiHelpers.COLOR_RED);
                                                    tvStatus.setText("mkdir failed: " + e.getMessage());
                                                }
                                            });
                                        }
                                    }
                                }).start();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= entries.size()) return;
                FileEntry chosen = entries.get(pos);
                if (!chosen.isDir) {
                    Toast.makeText(act, chosen.relPath + " (" + UiHelpers.formatBytes(chosen.size) + ")",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String cur = currentPath[0];
                currentPath[0] = "/".equals(cur) ? ("/" + chosen.relPath) : (cur + "/" + chosen.relPath);
                loadCurrent.run();
            }
        });

        AlertDialog.Builder dlg = new AlertDialog.Builder(act)
                .setTitle(cb != null ? "Select Remote Folder" : "Remote File Explorer")
                .setView(root)
                .setNegativeButton("Close", null);

        if (cb != null) {
            dlg.setPositiveButton("Select This Folder", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    cb.onSelected(currentPath[0]);
                }
            });
        }
        dlg.show();
    }

    private static String normalizeRemotePath(String p) {
        if (p == null || p.trim().isEmpty()) return "/";
        String t = p.trim();
        while (t.endsWith("/") && t.length() > 1) t = t.substring(0, t.length() - 1);
        if (!t.startsWith("/")) t = "/" + t;
        return t;
    }

    /* ---- 4. Interactive Conflict Inspection & Diff3 Resolution Dialog ---- */

    public static void showConflictInspectDialog(final Activity act, final ConflictRecord c, final Runnable onDone) {
        ScrollView scroll = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiHelpers.dp(act, 14);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);

        box.addView(UiHelpers.label(act, c.relPath, 14, UiHelpers.COLOR_TEXT, true));
        box.addView(UiHelpers.label(act, "Folder: " + c.pairName + "  |  " + c.kind.label, 12, UiHelpers.COLOR_AMBER, false));
        box.addView(UiHelpers.label(act,
                "Local:  " + UiHelpers.formatBytes(c.localSize) + " (" + UiHelpers.formatTime(c.localMtimeMs) + ")\n" +
                "Remote: " + UiHelpers.formatBytes(c.remoteSize) + " (" + UiHelpers.formatTime(c.remoteMtimeMs) + ")",
                11, UiHelpers.COLOR_SECONDARY, false));

        final EditText etMerged;
        if (c.mergedPreview != null && !c.mergedPreview.isEmpty()) {
            box.addView(UiHelpers.fieldLabel(act, "3-Way Diff3 Merge Preview (editable)"));
            etMerged = UiHelpers.input(act, "", c.mergedPreview,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            etMerged.setTypeface(Typeface.MONOSPACE);
            etMerged.setTextSize(11);
            etMerged.setMinLines(8);
            box.addView(etMerged);
        } else {
            etMerged = null;
        }

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Resolve Conflict")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiHelpers.dp(act, 12);
        btnRow.setLayoutParams(blp);

        Button bLocal = UiHelpers.smallButton(act, "Keep Local", UiHelpers.COLOR_GREEN, false);
        Button bRemote = UiHelpers.smallButton(act, "Keep Remote", UiHelpers.COLOR_CYAN, false);
        Button bBoth = UiHelpers.smallButton(act, "Keep Both", UiHelpers.COLOR_AMBER, false);
        btnRow.addView(bLocal);
        btnRow.addView(bRemote);
        btnRow.addView(bBoth);
        box.addView(btnRow);

        if (etMerged != null) {
            Button bMerged = UiHelpers.smallButton(act, "Save Edited Merge", UiHelpers.COLOR_GREEN, true);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = UiHelpers.dp(act, 8);
            bMerged.setLayoutParams(mlp);
            bMerged.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    runResolveAsync(act, c, ConflictResolution.USE_MERGED,
                            etMerged.getText().toString().getBytes(StandardCharsets.UTF_8), onDone);
                }
            });
            box.addView(bMerged);
        }

        bLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                runResolveAsync(act, c, ConflictResolution.KEEP_LOCAL, null, onDone);
            }
        });
        bRemote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                runResolveAsync(act, c, ConflictResolution.KEEP_REMOTE, null, onDone);
            }
        });
        bBoth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                runResolveAsync(act, c, ConflictResolution.KEEP_BOTH, null, onDone);
            }
        });

        dlg.show();
    }

    public static void runResolveAsync(final Activity act, final ConflictRecord c,
                                       final ConflictResolution res, final byte[] mergedBytes,
                                       final Runnable onDone) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SyncRunner.resolveConflict(act.getApplicationContext(), c, res, mergedBytes);
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(act, "Resolved: " + c.relPath, Toast.LENGTH_SHORT).show();
                            if (onDone != null) onDone.run();
                        }
                    });
                } catch (final Exception e) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(act, "Resolve failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /* ---- 5. CalDAV / CardDAV Profile Editor ---- */

    public static void showPimEditorDialog(final Activity act, final PimProfile existing, final Runnable onSaved) {
        final SynkDbHelper db = SynkDbHelper.getInstance(act);
        final PimProfile p = existing != null ? existing : new PimProfile();

        ScrollView scroll = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiHelpers.dp(act, 16);
        box.setPadding(pad, UiHelpers.dp(act, 8), pad, pad);
        scroll.addView(box);

        box.addView(UiHelpers.fieldLabel(act, "Profile Name"));
        final EditText etName = UiHelpers.textInput(act, "e.g. Personal Calendar", p.name);
        box.addView(etName);

        box.addView(UiHelpers.fieldLabel(act, "PIM Protocol"));
        final PimKind[] kinds = PimKind.values();
        String[] kindLabels = { kinds[0].label, kinds[1].label };
        final Spinner spKind = UiHelpers.spinner(act, kindLabels, p.kind == PimKind.CARDDAV ? 1 : 0);
        box.addView(spKind);

        box.addView(UiHelpers.fieldLabel(act, "Collection URL"));
        final EditText etUrl = UiHelpers.textInput(act,
                "https://cloud.example.com/remote.php/dav/calendars/user/personal/", p.url);
        box.addView(etUrl);

        spKind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) {
                    etUrl.setHint("https://host/remote.php/dav/calendars/user/personal/");
                } else {
                    etUrl.setHint("https://host/remote.php/dav/addressbooks/users/user/contacts/");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        box.addView(UiHelpers.fieldLabel(act, "Username"));
        final EditText etUser = UiHelpers.textInput(act, "username", p.username);
        box.addView(etUser);

        box.addView(UiHelpers.fieldLabel(act, "Password / App Password"));
        final EditText etPass = UiHelpers.passwordInput(act, "password", p.password);
        box.addView(etPass);

        box.addView(UiHelpers.fieldLabel(act, "Conflict Handling"));
        final ConflictPolicy[] policies = ConflictPolicy.values();
        String[] polLabels = new String[policies.length];
        int polIdx = 5;
        for (int i = 0; i < policies.length; i++) {
            polLabels[i] = policies[i].label;
            if (policies[i] == p.conflictPolicy) polIdx = i;
        }
        final Spinner spPol = UiHelpers.spinner(act, polLabels, polIdx);
        box.addView(spPol);

        box.addView(UiHelpers.fieldLabel(act, "Scheduled Sync Interval"));
        int intIdx = 3;
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            if (INTERVAL_VALUES[i] == p.intervalMinutes) intIdx = i;
        }
        final Spinner spInt = UiHelpers.spinner(act, INTERVAL_LABELS, intIdx);
        box.addView(spInt);

        final CheckBox cbTrust = UiHelpers.checkBox(act, "Trust self-signed TLS certificates", p.trustAllCerts);
        final CheckBox cbEnabled = UiHelpers.checkBox(act, "Enabled", p.enabled);
        box.addView(cbTrust);
        box.addView(cbEnabled);

        /* Test button */
        LinearLayout testRow = new LinearLayout(act);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = UiHelpers.dp(act, 10);
        testRow.setLayoutParams(tlp);
        Button btnTest = UiHelpers.smallButton(act, "Test DAV", UiHelpers.COLOR_CYAN, false);
        final TextView tvRes = UiHelpers.label(act, "", 12, UiHelpers.COLOR_SECONDARY, false);
        testRow.addView(btnTest);
        testRow.addView(tvRes);
        box.addView(testRow);

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final PimProfile tmp = new PimProfile();
                tmp.kind = kinds[spKind.getSelectedItemPosition()];
                tmp.url = etUrl.getText().toString().trim();
                tmp.username = etUser.getText().toString().trim();
                tmp.password = etPass.getText().toString();
                tmp.trustAllCerts = cbTrust.isChecked();
                tvRes.setTextColor(UiHelpers.COLOR_CYAN);
                tvRes.setText("Testing...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String ok = new DavPimClient(tmp).testConnection();
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvRes.setTextColor(UiHelpers.COLOR_GREEN);
                                    tvRes.setText(ok);
                                }
                            });
                        } catch (final Exception e) {
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tvRes.setTextColor(UiHelpers.COLOR_RED);
                                    tvRes.setText("Failed: " + e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        AlertDialog.Builder dlg = new AlertDialog.Builder(act)
                .setTitle(existing != null ? "Edit CalDAV / CardDAV" : "Add CalDAV / CardDAV")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        String url = etUrl.getText().toString().trim();
                        if (name.isEmpty() || url.isEmpty()) {
                            Toast.makeText(act, "Name and URL are required", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        p.name = name;
                        p.kind = kinds[spKind.getSelectedItemPosition()];
                        p.url = url;
                        p.username = etUser.getText().toString().trim();
                        p.password = etPass.getText().toString();
                        p.conflictPolicy = policies[spPol.getSelectedItemPosition()];
                        p.intervalMinutes = INTERVAL_VALUES[spInt.getSelectedItemPosition()];
                        p.trustAllCerts = cbTrust.isChecked();
                        p.enabled = cbEnabled.isChecked();
                        db.savePimProfile(p);
                        SyncScheduler.schedulePim(act, p);
                        if (onSaved != null) onSaved.run();
                    }
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            dlg.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    SyncScheduler.cancelPim(act, existing.id);
                    db.deletePimProfile(existing.id);
                    if (onSaved != null) onSaved.run();
                }
            });
        }
        dlg.show();
    }
}
