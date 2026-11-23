package org.synk.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import org.synk.R;
import org.synk.core.SyncEngine;
import org.synk.core.SyncModels.*;
import org.synk.core.SyncOptions;
import org.synk.core.SyncProgress;
import org.synk.pim.PimSyncEngine;
import org.synk.pim.android.AndroidCalendarAdapter;
import org.synk.pim.android.AndroidContactsAdapter;
import org.synk.protocol.BackendFactory;
import org.synk.protocol.RemoteBackend;
import org.synk.storage.SynkDbHelper;
import org.synk.storage.SynkSettings;
import org.synk.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared execution coordinator with live progress telemetry, cooperative cancellation,
 * battery/power-saver/network guards, and conflict/error alerts.
 */
public final class SyncRunner {
    public static final String ACTION_STATE_CHANGED    = "org.synk.ACTION_STATE_CHANGED";
    public static final String ACTION_PROGRESS_CHANGED = "org.synk.ACTION_PROGRESS_CHANGED";

    private static final String ALERT_CHANNEL_ID = "synk_alerts";
    private static final int CONFLICT_NOTIF_ID   = 201;
    private static final int ERROR_NOTIF_ID      = 202;

    private static final ConcurrentHashMap<Long, SyncProgress.Snapshot> ACTIVE_PROGRESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, AtomicBoolean> CANCEL_FLAGS = new ConcurrentHashMap<>();

    private SyncRunner() {}

    public static List<SyncProgress.Snapshot> getActiveSnapshots() {
        return new ArrayList<>(ACTIVE_PROGRESS.values());
    }

    public static SyncProgress.Snapshot getPairProgress(long pairId) {
        return ACTIVE_PROGRESS.get(pairId);
    }

    public static void cancelPair(long pairId) {
        AtomicBoolean f = CANCEL_FLAGS.get(pairId);
        if (f != null) f.set(true);
    }

    public static void cancelAll() {
        for (AtomicBoolean f : CANCEL_FLAGS.values()) {
            f.set(true);
        }
    }

    public static void notifyUi(Context ctx) {
        Intent i = new Intent(ACTION_STATE_CHANGED);
        i.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(i);
    }

    public static void notifyProgress(Context ctx) {
        Intent i = new Intent(ACTION_PROGRESS_CHANGED);
        i.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(i);
    }

    public static boolean isUnmeteredNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static String checkPowerConstraints(Context ctx, SynkSettings settings) {
        if (settings.isPauseOnPowerSaver()) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                return "Paused (Battery Saver active)";
            }
        }
        int minBat = settings.getMinBatteryPercent();
        if (minBat > 0) {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null && !bm.isCharging()) {
                int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (pct > 0 && pct < minBat) {
                    return "Paused (battery " + pct + "% < " + minBat + "%)";
                }
            }
        }
        return null;
    }

    public static SyncResult runPair(Context ctx, long pairId, boolean enforceConstraints) {
        return runPair(ctx, pairId, enforceConstraints, null);
    }

    public static SyncResult runPair(final Context ctx, final long pairId, boolean enforceConstraints,
                                     final SyncProgress.Listener externalListener) {
        SynkDbHelper db = SynkDbHelper.getInstance(ctx);
        SynkSettings settings = SynkSettings.of(ctx);
        SyncPair pair = db.getPair(pairId);
        if (pair == null || !pair.enabled) return new SyncResult();

        AccountConfig acc = db.getAccount(pair.accountId);
        if (acc == null) {
            db.updatePairStatus(pair.id, System.currentTimeMillis(), "Error: Account missing");
            notifyUi(ctx);
            return new SyncResult();
        }

        if (enforceConstraints) {
            if (acc.protocol != Protocol.LOCAL && pair.wifiOnly && !isUnmeteredNetwork(ctx)) {
                db.appendLog(pair.name, "INFO", "Skipped auto-sync (waiting for Wi-Fi)");
                return new SyncResult();
            }
            String powerBlock = checkPowerConstraints(ctx, settings);
            if (powerBlock != null) {
                db.appendLog(pair.name, "INFO", "Skipped auto-sync: " + powerBlock);
                return new SyncResult();
            }
        }

        SyncOptions opts = settings.toSyncOptions();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        CANCEL_FLAGS.put(pair.id, cancelFlag);

        db.updatePairStatus(pair.id, pair.lastSyncAt, "Syncing...");
        notifyUi(ctx);

        SyncProgress.Listener combinedListener = new SyncProgress.Listener() {
            @Override
            public void onProgress(SyncProgress.Snapshot snap) {
                if (snap.active) {
                    ACTIVE_PROGRESS.put(pairId, snap);
                } else {
                    ACTIVE_PROGRESS.remove(pairId);
                }
                if (externalListener != null) externalListener.onProgress(snap);
                notifyProgress(ctx);
            }
        };

        SyncEngine engine = new SyncEngine(db);
        try (RemoteBackend backend = BackendFactory.create(acc)) {
            SyncResult res = engine.syncPair(pair, backend, opts, cancelFlag, combinedListener);
            if (res.conflicts > 0 && settings.isNotifyConflicts()) {
                postAlert(ctx, CONFLICT_NOTIF_ID, "Synk Conflict Detected",
                        pair.name + ": " + res.conflicts + " file conflict(s) need attention.");
            }
            if (res.errors > 0 && settings.isNotifyErrors()) {
                postAlert(ctx, ERROR_NOTIF_ID, "Synk Transfer Error",
                        pair.name + ": " + res.errors + " error(s) occurred. Open Log for details.");
            }
            return res;
        } catch (Exception e) {
            db.appendLog(pair.name, "ERROR", "Backend error: " + e.getMessage());
            db.updatePairStatus(pair.id, System.currentTimeMillis(), "Error: " + e.getMessage());
            if (settings.isNotifyErrors()) {
                postAlert(ctx, ERROR_NOTIF_ID, "Synk Error (" + pair.name + ")", e.getMessage());
            }
            SyncResult r = new SyncResult();
            r.errors = 1;
            return r;
        } finally {
            ACTIVE_PROGRESS.remove(pairId);
            CANCEL_FLAGS.remove(pairId);
            notifyProgress(ctx);
            notifyUi(ctx);
        }
    }

    public static SyncResult runPim(Context ctx, long profileId) {
        SynkDbHelper db = SynkDbHelper.getInstance(ctx);
        SynkSettings settings = SynkSettings.of(ctx);
        settings.toSyncOptions(); // applies timeouts & engine preference

        PimProfile profile = db.getPimProfile(profileId);
        if (profile == null || !profile.enabled) return new SyncResult();

        db.updatePimStatus(profile.id, profile.lastSyncAt, "Syncing...");
        notifyUi(ctx);

        PimSyncEngine engine = new PimSyncEngine(db);
        PimSyncEngine.LocalPimAdapter adapter = (profile.kind == PimKind.CALDAV)
                ? new AndroidCalendarAdapter(ctx, profile)
                : new AndroidContactsAdapter(ctx, profile);

        SyncResult res = engine.sync(profile, adapter);
        if (res.errors > 0 && settings.isNotifyErrors()) {
            postAlert(ctx, ERROR_NOTIF_ID, "Synk PIM Error (" + profile.name + ")",
                    res.messages.isEmpty() ? (res.errors + " errors") : res.messages.get(0));
        }
        notifyUi(ctx);
        return res;
    }

    public static void resolveConflict(Context ctx, ConflictRecord conflict,
                                       ConflictResolution choice, byte[] mergedBytes) throws Exception {
        SynkDbHelper db = SynkDbHelper.getInstance(ctx);
        SyncOptions opts = SynkSettings.of(ctx).toSyncOptions();
        SyncPair pair = db.getPair(conflict.pairId);
        if (pair == null) {
            db.removeConflict(conflict.pairId, conflict.relPath);
            notifyUi(ctx);
            return;
        }
        AccountConfig acc = db.getAccount(pair.accountId);
        if (acc == null) throw new IllegalStateException("Account missing for pair " + pair.name);
        SyncEngine engine = new SyncEngine(db);
        try (RemoteBackend backend = BackendFactory.create(acc)) {
            engine.resolveConflict(pair, backend, conflict, choice, mergedBytes, opts);
        }
        notifyUi(ctx);
    }

    private static void postAlert(Context ctx, int id, String title, String message) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        ALERT_CHANNEL_ID, "Synk Alerts", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Sync conflict and error notifications");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, ALERT_CHANNEL_ID)
                    : new Notification.Builder(ctx);
            Notification n = b.setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
        } catch (Throwable ignored) {}
    }
}
