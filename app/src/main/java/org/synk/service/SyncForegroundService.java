package org.synk.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.synk.R;
import org.synk.core.IgnoreMatcher;
import org.synk.core.NativeBridge;
import org.synk.core.SyncModels.PimProfile;
import org.synk.core.SyncModels.SyncPair;
import org.synk.core.SyncProgress;
import org.synk.storage.SynkDbHelper;
import org.synk.storage.SynkSettings;
import org.synk.ui.MainActivity;
import org.synk.ui.UiHelpers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service handling on-demand syncs with live progress notifications
 * and native C11 inotify recursive folder watchers.
 */
public final class SyncForegroundService extends Service {

    public static final String ACTION_SYNC_PAIR        = "org.synk.SYNC_PAIR";
    public static final String ACTION_SYNC_ALL         = "org.synk.SYNC_ALL";
    public static final String ACTION_SYNC_PIM         = "org.synk.SYNC_PIM";
    public static final String ACTION_REFRESH_WATCHERS = "org.synk.REFRESH_WATCHERS";
    public static final String EXTRA_ID = "id";

    private static final String CHANNEL_ID = "synk_status";
    private static final int NOTIF_ID = 101;

    private ExecutorService executor;
    private int currentPoolSize = 0;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final Map<Long, Long> watcherHandles = new HashMap<>();
    private final Map<Long, String> watchedSignatures = new HashMap<>();
    private final ConcurrentHashMap<Long, Long> lastSyncFinishMs = new ConcurrentHashMap<>();
    private volatile long lastNotifUpdateMs = 0L;

    public static void triggerSyncPair(Context ctx, long pairId) {
        Intent i = new Intent(ctx, SyncForegroundService.class);
        i.setAction(ACTION_SYNC_PAIR);
        i.putExtra(EXTRA_ID, pairId);
        startCompat(ctx, i);
    }

    public static void triggerSyncAll(Context ctx) {
        Intent i = new Intent(ctx, SyncForegroundService.class);
        i.setAction(ACTION_SYNC_ALL);
        startCompat(ctx, i);
    }

    public static void triggerSyncPim(Context ctx, long profileId) {
        Intent i = new Intent(ctx, SyncForegroundService.class);
        i.setAction(ACTION_SYNC_PIM);
        i.putExtra(EXTRA_ID, profileId);
        startCompat(ctx, i);
    }

    public static void refreshWatchers(Context ctx) {
        SynkDbHelper db = SynkDbHelper.getInstance(ctx);
        boolean anyWatched = false;
        for (SyncPair p : db.listPairs()) {
            if (p.enabled && p.watchEnabled) {
                anyWatched = true;
                break;
            }
        }
        Intent i = new Intent(ctx, SyncForegroundService.class);
        i.setAction(ACTION_REFRESH_WATCHERS);
        if (anyWatched) {
            startCompat(ctx, i);
        } else {
            ctx.stopService(i);
        }
    }

    private static void startCompat(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    private synchronized ExecutorService getExecutor() {
        int want = Math.max(1, Math.min(4, SynkSettings.of(this).getMaxConcurrentJobs()));
        if (executor == null || currentPoolSize != want) {
            if (executor != null) executor.shutdown();
            executor = Executors.newFixedThreadPool(want);
            currentPoolSize = want;
        }
        return executor;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        SynkSettings.of(this).applyEnginePreference();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteForeground("Synk active", 0, false);
        String action = intent != null ? intent.getAction() : ACTION_REFRESH_WATCHERS;
        if (ACTION_REFRESH_WATCHERS.equals(action)) {
            reconcileWatchers();
            maybeStopSelf();
            return hasWatchers() ? START_STICKY : START_NOT_STICKY;
        }

        final SyncProgress.Listener notifProgress = new SyncProgress.Listener() {
            @Override
            public void onProgress(SyncProgress.Snapshot snap) {
                if (!SynkSettings.of(getApplicationContext()).isShowLiveNotifProgress()) return;
                long now = System.currentTimeMillis();
                if (snap.active && now - lastNotifUpdateMs < 400L) return;
                lastNotifUpdateMs = now;
                if (!snap.active) return;

                String text = snap.pairName + ": " + snap.actionLabel;
                if (snap.currentFile != null && !snap.currentFile.isEmpty()) {
                    text += " " + snap.currentFile;
                }
                if (snap.speedBytesPerSec > 0) {
                    text += " (" + UiHelpers.formatBytes(snap.speedBytesPerSec) + "/s)";
                }
                updateNotification(text, snap.overallPercent(), !snap.isIndeterminate());
            }
        };

        if (ACTION_SYNC_PAIR.equals(action)) {
            final long pairId = intent.getLongExtra(EXTRA_ID, -1L);
            if (pairId > 0) {
                enqueueTask("Syncing folder...", new Runnable() {
                    @Override
                    public void run() {
                        SyncRunner.runPair(getApplicationContext(), pairId, false, notifProgress);
                        lastSyncFinishMs.put(pairId, System.currentTimeMillis());
                    }
                });
            }
        } else if (ACTION_SYNC_PIM.equals(action)) {
            final long pimId = intent.getLongExtra(EXTRA_ID, -1L);
            if (pimId > 0) {
                enqueueTask("Syncing CalDAV/CardDAV...", new Runnable() {
                    @Override
                    public void run() {
                        SyncRunner.runPim(getApplicationContext(), pimId);
                    }
                });
            }
        } else if (ACTION_SYNC_ALL.equals(action)) {
            enqueueTask("Syncing all folders & PIM...", new Runnable() {
                @Override
                public void run() {
                    SynkDbHelper db = SynkDbHelper.getInstance(getApplicationContext());
                    for (SyncPair p : db.listPairs()) {
                        if (p.enabled) {
                            SyncRunner.runPair(getApplicationContext(), p.id, false, notifProgress);
                            lastSyncFinishMs.put(p.id, System.currentTimeMillis());
                        }
                    }
                    for (PimProfile pim : db.listPimProfiles()) {
                        if (pim.enabled) {
                            SyncRunner.runPim(getApplicationContext(), pim.id);
                        }
                    }
                }
            });
        }

        reconcileWatchers();
        return hasWatchers() ? START_STICKY : START_NOT_STICKY;
    }

    private void enqueueTask(final String notifText, final Runnable work) {
        activeTasks.incrementAndGet();
        updateNotification(notifText, 0, false);
        getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    work.run();
                } finally {
                    int rem = activeTasks.decrementAndGet();
                    if (rem <= 0) {
                        if (hasWatchers()) {
                            updateNotification("Watching " + watcherCount() + " folder(s) ["
                                    + NativeBridge.engineName() + "]", 0, false);
                        } else {
                            maybeStopSelf();
                        }
                    }
                }
            }
        });
    }

    private synchronized void reconcileWatchers() {
        SynkDbHelper db = SynkDbHelper.getInstance(this);
        SynkSettings settings = SynkSettings.of(this);
        settings.applyEnginePreference();

        final int debounceMs = settings.getWatchDebounceMs();
        final long cooldownMs = settings.getSelfSyncCooldownMs();
        String globalIgnores = settings.getGlobalIgnorePatterns();

        List<SyncPair> pairs = db.listPairs();
        Set<Long> desired = new HashSet<>();

        for (final SyncPair pair : pairs) {
            if (!pair.enabled || !pair.watchEnabled) continue;
            desired.add(pair.id);
            String combinedIgnore = globalIgnores + "\n" + (pair.ignorePatterns == null ? "" : pair.ignorePatterns);
            String sig = pair.localPath + "|" + debounceMs + "|" + NativeBridge.isNativeLoaded() + "|" + combinedIgnore;
            if (sig.equals(watchedSignatures.get(pair.id)) && watcherHandles.containsKey(pair.id)) {
                continue;
            }
            stopWatcherFor(pair.id);
            String[] ignores = IgnoreMatcher.parsePatterns(combinedIgnore);
            long handle = NativeBridge.startWatcher(pair.localPath, ignores, debounceMs,
                    new NativeBridge.WatchCallback() {
                        @Override
                        public void onDirectoryChanged(String lastRelPath, int eventCount) {
                            Long lastFin = lastSyncFinishMs.get(pair.id);
                            if (lastFin != null && (System.currentTimeMillis() - lastFin) < cooldownMs) {
                                return;
                            }
                            SynkDbHelper.getInstance(getApplicationContext()).appendLog(
                                    pair.name, "INFO", "Change detected (" + lastRelPath + ", " + eventCount + " ev)");
                            enqueueTask("Auto-syncing " + pair.name + "...", new Runnable() {
                                @Override
                                public void run() {
                                    SyncRunner.runPair(getApplicationContext(), pair.id, true, null);
                                    lastSyncFinishMs.put(pair.id, System.currentTimeMillis());
                                }
                            });
                        }
                    });
            if (handle != 0L) {
                watcherHandles.put(pair.id, handle);
                watchedSignatures.put(pair.id, sig);
            }
        }

        Set<Long> existingIds = new HashSet<>(watcherHandles.keySet());
        for (Long id : existingIds) {
            if (!desired.contains(id)) stopWatcherFor(id);
        }

        if (activeTasks.get() <= 0 && !watcherHandles.isEmpty()) {
            updateNotification("Watching " + watcherHandles.size() + " folder(s) ["
                    + NativeBridge.engineName() + "]", 0, false);
        }
    }

    private synchronized void stopWatcherFor(long pairId) {
        Long h = watcherHandles.remove(pairId);
        watchedSignatures.remove(pairId);
        if (h != null && h != 0L) {
            NativeBridge.stopWatcher(h);
        }
    }

    private synchronized boolean hasWatchers() {
        return !watcherHandles.isEmpty();
    }

    private synchronized int watcherCount() {
        return watcherHandles.size();
    }

    private void maybeStopSelf() {
        if (activeTasks.get() <= 0 && !hasWatchers()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Synk Background Sync", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Active folder sync progress and change watcher");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text, int progressPct, boolean determinate) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("Synk")
         .setContentText(text)
         .setSmallIcon(R.drawable.ic_launcher)
         .setContentIntent(pi)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        if (determinate) {
            b.setProgress(100, Math.max(0, Math.min(100, progressPct)), false);
        }
        return b.build();
    }

    private void promoteForeground(String text, int pct, boolean determinate) {
        Notification n = buildNotification(text, pct, determinate);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification(String text, int pct, boolean determinate) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text, pct, determinate));
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            for (Long h : watcherHandles.values()) {
                if (h != null && h != 0L) NativeBridge.stopWatcher(h);
            }
            watcherHandles.clear();
            watchedSignatures.clear();
            if (executor != null) executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
