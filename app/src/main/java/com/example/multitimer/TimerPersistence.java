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
    static LoadedData load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY_TIMERS, "[]");
        LoadedData data = new LoadedData();

        try {
            if (raw.trim().startsWith("[")) {
                loadLegacyTimers(new JSONArray(raw), data);
            } else {
                JSONObject root = new JSONObject(raw);
                JSONArray savedTimers = root.optJSONArray("savedTimers");
                JSONArray activeTimers = root.optJSONArray("activeTimers");
                long maxId = 0L;
                if (savedTimers != null) {
                    for (int index = 0; index < savedTimers.length(); index++) {
                        SavedTimer timer = readSavedTimer(savedTimers.getJSONObject(index));
                        data.savedTimers.add(timer);
                        maxId = Math.max(maxId, timer.getId());
                    }
                }
                if (activeTimers != null) {
                    for (int index = 0; index < activeTimers.length(); index++) {
                        ManagedTimer timer = readActiveTimer(activeTimers.getJSONObject(index));
                        data.activeTimers.add(timer);
                        maxId = Math.max(maxId, timer.getId());
                    }
                }
                data.nextId = maxId + 1L;
            }
        } catch (JSONException parseError) {
            // Keep the raw value so user data is not destroyed by one bad parse.
            Log.e(TAG, "Failed to parse persisted timers", parseError);
        }

        return data;
    }

    private static void loadLegacyTimers(JSONArray oldTimers, LoadedData data) throws JSONException {
        long now = System.currentTimeMillis();
        long nextId = 1L;
        for (int index = 0; index < oldTimers.length(); index++) {
            JSONObject item = oldTimers.getJSONObject(index);
            nextId = Math.max(nextId, item.getLong("id") + 1L);
        }

        for (int index = 0; index < oldTimers.length(); index++) {
            JSONObject item = oldTimers.getJSONObject(index);
            long id = item.getLong("id");
            String name = item.getString("name");
            long duration = item.getLong("durationMillis");
            long endTime = item.getLong("endTimeMillis");
            boolean started = item.optBoolean("started", true);
            boolean completed = item.optBoolean("completed", false);
            boolean cancelled = item.optBoolean("cancelled", false);
            boolean dismissed = item.optBoolean("notificationDismissed", false);
            long startedAt = started ? Math.max(0L, endTime - duration) : 0L;
            data.savedTimers.add(new SavedTimer(
                    id,
                    name,
                    duration,
                    item.optLong("announcementIntervalMillis", ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS),
                    item.optInt("alarmVolume", ManagedTimer.DEFAULT_ALARM_VOLUME),
                    item.optString("completionText", ""),
                    now,
                    startedAt,
                    started ? 1 : 0
            ));

            if (started && !cancelled && (!completed || !dismissed)) {
                data.activeTimers.add(new ManagedTimer(
                        nextId++,
                        id,
                        name,
                        duration,
                        startedAt,
                        endTime,
                        item.optLong("announcementIntervalMillis", ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS),
                        item.optInt("alarmVolume", ManagedTimer.DEFAULT_ALARM_VOLUME),
                        item.optString("completionText", ""),
                        completed,
                        false,
                        dismissed,
                        item.optBoolean("completionAnnounced", false)
                ));
            }
        }
        data.nextId = nextId;
    }

    private static SavedTimer readSavedTimer(JSONObject item) throws JSONException {
        return new SavedTimer(
                item.getLong("id"),
                item.getString("name"),
                item.getLong("durationMillis"),
                item.optLong("announcementIntervalMillis", ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS),
                item.optInt("alarmVolume", ManagedTimer.DEFAULT_ALARM_VOLUME),
                item.optString("completionText", ""),
                item.optLong("createdAtMillis", System.currentTimeMillis()),
                item.optLong("lastStartedAtMillis", 0L),
                item.optInt("usageCount", 0),
                TimerType.fromName(item.optString("timerType", TimerType.STANDARD.name())),
                item.optInt("repeatCount", 0),
                item.optBoolean("waitForIntervalConfirmation", false),
                readSteps(item.optJSONArray("steps"))
        );
    }

    private static ManagedTimer readActiveTimer(JSONObject item) throws JSONException {
        return new ManagedTimer(
                item.getLong("id"),
                item.optLong("sourceSavedTimerId", item.getLong("id")),
                item.getString("name"),
                item.getLong("durationMillis"),
                item.optLong("startedAtMillis", item.getLong("endTimeMillis") - item.getLong("durationMillis")),
                item.getLong("endTimeMillis"),
                item.optLong("announcementIntervalMillis", ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS),
                item.optInt("alarmVolume", ManagedTimer.DEFAULT_ALARM_VOLUME),
                item.optString("completionText", ""),
                item.optBoolean("completed", false),
                false,
                item.optBoolean("notificationDismissed", false),
                item.optBoolean("completionAnnounced", false),
                TimerType.fromName(item.optString("timerType", TimerType.STANDARD.name())),
                item.optInt("repeatCount", 0),
                item.optBoolean("waitForIntervalConfirmation", false),
                item.optInt("completedCycles", 1),
                item.optInt("currentStepIndex", 0),
                readSteps(item.optJSONArray("steps")),
                item.optBoolean("phaseAwaitingAnnouncement", false),
                item.optBoolean("phaseAnnouncementMuted", false)
        );
    }

    private static List<TimerStep> readSteps(JSONArray stepsArray) throws JSONException {
        List<TimerStep> steps = new ArrayList<>();
        if (stepsArray == null) {
            return steps;
        }
        for (int index = 0; index < stepsArray.length(); index++) {
            JSONObject item = stepsArray.getJSONObject(index);
            steps.add(new TimerStep(
                    item.optString("name", ""),
                    item.optLong("durationMillis", 0L),
                    item.optString("completionText", "")
            ));
        }
        return steps;
    }

    private static JSONArray writeSteps(List<TimerStep> steps) throws JSONException {
        JSONArray array = new JSONArray();
        for (TimerStep step : steps) {
            JSONObject item = new JSONObject();
            item.put("name", step.getName());
            item.put("durationMillis", step.getDurationMillis());
            item.put("completionText", step.getCompletionText());
            array.put(item);
        }
        return array;
    }

    static void save(Context context, List<SavedTimer> savedTimers, List<ManagedTimer> activeTimers) {
        JSONObject root = new JSONObject();
        JSONArray savedArray = new JSONArray();
        for (SavedTimer timer : savedTimers) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", timer.getId());
                item.put("name", timer.getName());
                item.put("durationMillis", timer.getDurationMillis());
                item.put("announcementIntervalMillis", timer.getAnnouncementIntervalMillis());
                item.put("alarmVolume", timer.getAlarmVolume());
                item.put("completionText", timer.getCompletionText());
                item.put("createdAtMillis", timer.getCreatedAtMillis());
                item.put("lastStartedAtMillis", timer.getLastStartedAtMillis());
                item.put("usageCount", timer.getUsageCount());
                item.put("timerType", timer.getTimerType().name());
                item.put("repeatCount", timer.getRepeatCount());
                item.put("waitForIntervalConfirmation", timer.waitsForIntervalConfirmation());
                item.put("steps", writeSteps(timer.getSteps()));
                savedArray.put(item);
            } catch (JSONException ignored) {
            }
        }

        JSONArray activeArray = new JSONArray();
        for (ManagedTimer timer : activeTimers) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", timer.getId());
                item.put("sourceSavedTimerId", timer.getSourceSavedTimerId());
                item.put("name", timer.getName());
                item.put("durationMillis", timer.getDurationMillis());
                item.put("startedAtMillis", timer.getStartedAtMillis());
                item.put("endTimeMillis", timer.getEndTimeMillis());
                item.put("announcementIntervalMillis", timer.getAnnouncementIntervalMillis());
                item.put("alarmVolume", timer.getAlarmVolume());
                item.put("completionText", timer.getFinalCompletionText());
                item.put("completed", timer.isCompleted());
                item.put("notificationDismissed", timer.isNotificationDismissed());
                item.put("completionAnnounced", timer.isCompletionAnnounced());
                item.put("timerType", timer.getTimerType().name());
                item.put("repeatCount", timer.getRepeatCount());
                item.put("waitForIntervalConfirmation", timer.waitsForIntervalConfirmation());
                item.put("completedCycles", timer.getCompletedCycles());
                item.put("currentStepIndex", timer.getCurrentStepIndex());
                item.put("steps", writeSteps(timer.getSteps()));
                item.put("phaseAwaitingAnnouncement", timer.isPhaseAwaitingAnnouncement());
                item.put("phaseAnnouncementMuted", timer.isPhaseAnnouncementMuted());
                activeArray.put(item);
            } catch (JSONException ignored) {
            }
        }

        try {
            root.put("savedTimers", savedArray);
            root.put("activeTimers", activeArray);
        } catch (JSONException ignored) {
        }

        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean ok = preferences.edit().putString(KEY_TIMERS, root.toString()).commit();
        if (!ok) {
            Log.e(TAG, "Failed to commit timers to SharedPreferences");
        }
    }

    static final class LoadedData {
        final List<SavedTimer> savedTimers = new ArrayList<>();
        final List<ManagedTimer> activeTimers = new ArrayList<>();
        long nextId = 1L;
    }
}