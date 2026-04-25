package com.example.multitimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reaktiviert den TimerService nach Boot oder App-Update, falls aktive Timer vorhanden sind.
 */
public final class BootReceiver extends BroadcastReceiver {
    /**
     * Reagiert auf System- und Paket-Events und startet den Service bei Bedarf.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            TimerService.ensureServiceRunningForActiveTimers(context.getApplicationContext());
        }
    }
}
