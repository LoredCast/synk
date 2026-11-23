package org.synk.service;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PersistableBundle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JobScheduler background worker for scheduled file & CalDAV/CardDAV syncs.
 */
public final class SyncJobService extends JobService {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    @Override
    public boolean onStartJob(final JobParameters params) {
        PersistableBundle extras = params.getExtras();
        final String kind = extras.getString("kind", "pair");
        final long targetId = extras.getLong("target_id", -1L);
        if (targetId <= 0) return false;

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if ("pim".equals(kind)) {
                        SyncRunner.runPim(getApplicationContext(), targetId);
                    } else {
                        SyncRunner.runPair(getApplicationContext(), targetId, true);
                    }
                } finally {
                    jobFinished(params, false);
                }
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if interrupted by OS
    }
}
