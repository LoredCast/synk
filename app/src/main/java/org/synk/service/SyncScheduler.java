package org.synk.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import org.synk.core.SyncModels.PimProfile;
import org.synk.core.SyncModels.SyncPair;
import org.synk.storage.SynkDbHelper;

/**
 * Configures periodic background sync schedules via Android's built-in JobScheduler.
 */
public final class SyncScheduler {
    private static final int PAIR_JOB_BASE = 10_000;
    private static final int PIM_JOB_BASE = 20_000;

    private SyncScheduler() {}

    public static void rescheduleAll(Context ctx) {
        SynkDbHelper db = SynkDbHelper.getInstance(ctx);
        for (SyncPair p : db.listPairs()) {
            schedulePair(ctx, p);
        }
        for (PimProfile pim : db.listPimProfiles()) {
            schedulePim(ctx, pim);
        }
    }

    public static void schedulePair(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        int jobId = PAIR_JOB_BASE + (int) (pair.id % 9000);
        js.cancel(jobId);

        if (!pair.enabled || pair.intervalMinutes <= 0) return;

        long periodMs = Math.max(15L, pair.intervalMinutes) * 60_000L;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("kind", "pair");
        extras.putLong("target_id", pair.id);

        JobInfo.Builder b = new JobInfo.Builder(jobId, new ComponentName(ctx, SyncJobService.class))
                .setExtras(extras)
                .setPeriodic(periodMs)
                .setPersisted(true)
                .setRequiredNetworkType(pair.wifiOnly
                        ? JobInfo.NETWORK_TYPE_UNMETERED
                        : JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(pair.chargingOnly);
        js.schedule(b.build());
    }

    public static void cancelPair(Context ctx, long pairId) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(PAIR_JOB_BASE + (int) (pairId % 9000));
    }

    public static void schedulePim(Context ctx, PimProfile profile) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        int jobId = PIM_JOB_BASE + (int) (profile.id % 9000);
        js.cancel(jobId);

        if (!profile.enabled || profile.intervalMinutes <= 0) return;

        long periodMs = Math.max(15L, profile.intervalMinutes) * 60_000L;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("kind", "pim");
        extras.putLong("target_id", profile.id);

        JobInfo.Builder b = new JobInfo.Builder(jobId, new ComponentName(ctx, SyncJobService.class))
                .setExtras(extras)
                .setPeriodic(periodMs)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        js.schedule(b.build());
    }

    public static void cancelPim(Context ctx, long profileId) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(PIM_JOB_BASE + (int) (profileId % 9000));
    }
}
