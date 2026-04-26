package com.example.multitimer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistenzschicht fuer Timerdaten auf Basis von SharedPreferences.
 */
final class TimerPersistence {
    private static final String TAG = "TimerPersistence";
    private static final String PREFS_NAME = "multitimer_prefs";
    private static final String KEY_TIMERS = "timers";

    private TimerPersistence() {
    }

    /**
     * Laedt alle gespeicherten Timer.
     *
     * @param context App-Kontext
     * @return Liste rekonstruierter Timerobjekte
     */
    static List<ManagedTimer> load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY_TIMERS, "[]");
        List<ManagedTimer> timers = new ArrayList<>();

        try {
            JSONArray jsonArray = new JSONArray(raw);
            for (int index = 0; index < jsonArray.length(); index++) {
                JSONObject item = jsonArray.getJSONObject(index);
                timers.add(new ManagedTimer(
                        item.getLong("id"),
                        item.getString("name"),
                        item.getLong("durationMillis"),
                        item.getLong("endTimeMillis"),
                        item.optBoolean("started", true),
                        item.optLong("announcementIntervalMillis", ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS),
                        item.optInt("alarmVolume", ManagedTimer.DEFAULT_ALARM_VOLUME),
                        item.optBoolean("completed", false),
                        item.optBoolean("cancelled", false),
                        item.optBoolean("notificationDismissed", false),
                        item.optBoolean("completionAnnounced", false)
                ));
            }
        } catch (JSONException parseError) {
            // Keep the raw value so user data is not destroyed by one bad parse.
            Log.e(TAG, "Failed to parse persisted timers", parseError);
        }

        return timers;
    }

    /**
     * Speichert den kompletten Timerzustand als JSON-Array.
     *
     * @param context App-Kontext
     * @param timers aktuelle Timerliste
     */
    static void save(Context context, List<ManagedTimer> timers) {
        JSONArray jsonArray = new JSONArray();
        for (ManagedTimer timer : timers) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", timer.getId());
                item.put("name", timer.getName());
                item.put("durationMillis", timer.getDurationMillis());
                item.put("endTimeMillis", timer.getEndTimeMillis());
                item.put("started", timer.isStarted());
                item.put("announcementIntervalMillis", timer.getAnnouncementIntervalMillis());
                item.put("alarmVolume", timer.getAlarmVolume());
                item.put("completed", timer.isCompleted());
                item.put("cancelled", timer.isCancelled());
                item.put("notificationDismissed", timer.isNotificationDismissed());
                item.put("completionAnnounced", timer.isCompletionAnnounced());
                jsonArray.put(item);
            } catch (JSONException ignored) {
            }
        }

        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean ok = preferences.edit().putString(KEY_TIMERS, jsonArray.toString()).commit();
        if (!ok) {
            Log.e(TAG, "Failed to commit timers to SharedPreferences");
        }
    }
}