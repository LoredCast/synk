package org.synk.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.synk.storage.SynkSettings;

/**
 * Restores periodic sync schedules and live folder watchers after device boot.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            SyncScheduler.rescheduleAll(context);
            if (SynkSettings.of(context).isAutoStartOnBoot()) {
                SyncForegroundService.refreshWatchers(context);
            }
        }
    }
}
