package com.example.multitimer;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFERENCES_NAME = "multitimer_settings";
    private static final String KEY_FORCE_ALARM_SOUND = "force_alarm_sound";

    private AppSettings() {
    }

    static boolean forceAlarmSoundEnabled(Context context) {
        return preferences(context).getBoolean(KEY_FORCE_ALARM_SOUND, true);
    }

    static void setForceAlarmSoundEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_FORCE_ALARM_SOUND, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}