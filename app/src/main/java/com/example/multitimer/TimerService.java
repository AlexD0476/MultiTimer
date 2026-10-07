package com.example.multitimer;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hintergrundservice fuer Verwaltung und Ausfuehrung mehrerer Timer.
 *
 * <p>Der Service ist die zentrale Quelle fuer Timerzustand, Notification-Updates,
 * Persistenz und TTS-Ansagen bei Abschluss.</p>
 */
public final class TimerService extends Service implements TextToSpeech.OnInitListener {
    private static final String TAG = "TimerService";
    static final String ACTION_ADD_TIMER = "com.example.multitimer.ADD_TIMER";
    static final String ACTION_START_SAVED_TIMER = "com.example.multitimer.START_SAVED_TIMER";
    static final String ACTION_DELETE_SAVED_TIMER = "com.example.multitimer.DELETE_SAVED_TIMER";
    static final String ACTION_CANCEL_TIMER = "com.example.multitimer.CANCEL_TIMER";
    static final String ACTION_CLEAR_COMPLETED = "com.example.multitimer.CLEAR_COMPLETED";
    static final String ACTION_DISMISS_NOTIFICATION = "com.example.multitimer.DISMISS_NOTIFICATION";
    static final String ACTION_ACKNOWLEDGE_GROUP_STEP = "com.example.multitimer.ACKNOWLEDGE_GROUP_STEP";
    static final String ACTION_MUTE_PHASE_ANNOUNCEMENT = "com.example.multitimer.MUTE_PHASE_ANNOUNCEMENT";
    static final String ACTION_REQUEST_CANCEL_CONFIRMATION = "com.example.multitimer.REQUEST_CANCEL_CONFIRMATION";
    static final String ACTION_CONFIRM_CANCEL_TIMER = "com.example.multitimer.CONFIRM_CANCEL_TIMER";
    static final String ACTION_KEEP_TIMER_RUNNING = "com.example.multitimer.KEEP_TIMER_RUNNING";
    static final String ACTION_DISMISS_TIMER = "com.example.multitimer.DISMISS_TIMER";
    static final String ACTION_REPLACE_TIMER = "com.example.multitimer.REPLACE_TIMER";
    static final String ACTION_RESTART_TIMER = "com.example.multitimer.RESTART_TIMER";
    static final String ACTION_RESYNC = "com.example.multitimer.RESYNC";
    static final String EXTRA_NAME = "extra_name";
    static final String EXTRA_DURATION_MILLIS = "extra_duration_millis";
    static final String EXTRA_TIMER_ID = "extra_timer_id";
    static final String EXTRA_START_IMMEDIATELY = "extra_start_immediately";
    static final String EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS = "extra_announcement_interval_millis";
    static final String EXTRA_ALARM_VOLUME = "extra_alarm_volume";
    static final String EXTRA_COMPLETION_TEXT = "extra_completion_text";
    static final String EXTRA_TIMER_TYPE = "extra_timer_type";
    static final String EXTRA_REPEAT_COUNT = "extra_repeat_count";
    static final String EXTRA_WAIT_FOR_INTERVAL_CONFIRMATION = "extra_wait_for_interval_confirmation";
    static final String EXTRA_STEPS = "extra_steps";

    private static final String CHANNEL_RUNNING = "multitimer_running";
    private static final String CHANNEL_FINISHED = "multitimer_finished_silent";
    private static final String SWIPE_CONFIRMATION_TAG_PREFIX = "swipe_cancel_";
    private static final int FOREGROUND_NOTIFICATION_ID = Integer.MAX_VALUE;
    private static final long STOP_DELAY_MILLIS = 4000L;
    private static final long[] COMPLETION_VIBRATION_PATTERN = new long[]{0L, 180L, 120L, 220L};
    private static final Map<Long, ManagedTimer> TIMERS = new LinkedHashMap<>();
    private static final Map<Long, SavedTimer> SAVED_TIMERS = new LinkedHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);
    private static boolean timersLoaded;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;
    private final Runnable delayedStopRunnable = this::stopIfIdle;
    private final Map<Long, Long> nextAnnouncementAt = new LinkedHashMap<>();
    private final ArrayDeque<Long> pendingAnnouncementQueue = new ArrayDeque<>();
    private final Set<Long> queuedAnnouncementIds = new HashSet<>();
    private final Set<Long> fullScreenConfirmationPosted = new HashSet<>();
    private final Set<Long> pendingSwipeCancellationConfirmations = new HashSet<>();
    private long activeAnnouncementTimerId = -1L;
    private String activeCompletionUtteranceId;
    private NotificationManager notificationManager;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock partialWakeLock;

    /**
     * Legt einen Timer an und startet ihn sofort.
        *
        * @param context App-Kontext
        * @param name Anzeigename des Timers
        * @param durationMillis Dauer des Timers in Millisekunden
     */
    public static void enqueueStartTimer(Context context, String name, long durationMillis) {
        enqueueStartTimer(context, name, durationMillis, ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS);
    }

    public static void enqueueStartTimer(Context context, String name, long durationMillis, long announcementIntervalMillis) {
        enqueueStartTimer(context, name, durationMillis, announcementIntervalMillis, ManagedTimer.DEFAULT_ALARM_VOLUME);
    }

    public static void enqueueStartTimer(Context context, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_ADD_TIMER);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        intent.putExtra(EXTRA_START_IMMEDIATELY, true);
        intent.putExtra(EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS, Math.max(0L, announcementIntervalMillis));
        intent.putExtra(EXTRA_ALARM_VOLUME, Math.max(0, Math.min(100, alarmVolume)));
        startServiceBestEffort(context, intent, true);
    }

    /**
     * Legt einen Timer im Status "bereit" an (nicht sofort gestartet).
        *
        * @param context App-Kontext
        * @param name Anzeigename des Timers
        * @param durationMillis Dauer des Timers in Millisekunden
     */
    public static void enqueueCreateTimer(Context context, String name, long durationMillis) {
        enqueueCreateTimer(context, name, durationMillis, ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS);
    }

    public static void enqueueCreateTimer(Context context, String name, long durationMillis, long announcementIntervalMillis) {
        enqueueCreateTimer(context, name, durationMillis, announcementIntervalMillis, ManagedTimer.DEFAULT_ALARM_VOLUME);
    }

    public static void enqueueCreateTimer(Context context, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume) {
        enqueueCreateTimer(context, name, durationMillis, announcementIntervalMillis, alarmVolume, "");
    }

    public static void enqueueCreateTimer(Context context, String name, long durationMillis, long announcementIntervalMillis,
                                          int alarmVolume, String completionText) {
        enqueueCreateTimer(context, name, durationMillis, announcementIntervalMillis, alarmVolume, completionText,
            TimerType.STANDARD, 0, new ArrayList<>());
        }

        public static void enqueueCreateTimer(Context context, String name, long durationMillis, long announcementIntervalMillis,
                          int alarmVolume, String completionText, TimerType timerType,
                          int repeatCount, List<TimerStep> steps) {
            enqueueCreateTimer(context, name, durationMillis, announcementIntervalMillis, alarmVolume,
                completionText, timerType, repeatCount, false, steps);
            }

            public static void enqueueCreateTimer(Context context, String name, long durationMillis, long announcementIntervalMillis,
                              int alarmVolume, String completionText, TimerType timerType,
                              int repeatCount, boolean waitForIntervalConfirmation, List<TimerStep> steps) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_ADD_TIMER);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        intent.putExtra(EXTRA_START_IMMEDIATELY, false);
        intent.putExtra(EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS, Math.max(0L, announcementIntervalMillis));
        intent.putExtra(EXTRA_ALARM_VOLUME, Math.max(0, Math.min(100, alarmVolume)));
        intent.putExtra(EXTRA_COMPLETION_TEXT, completionText == null ? "" : completionText.trim());
        intent.putExtra(EXTRA_TIMER_TYPE, timerType.name());
        intent.putExtra(EXTRA_REPEAT_COUNT, Math.max(0, repeatCount));
        intent.putExtra(EXTRA_WAIT_FOR_INTERVAL_CONFIRMATION, waitForIntervalConfirmation);
        intent.putExtra(EXTRA_STEPS, serializeSteps(steps));
        startServiceBestEffort(context, intent, true);
    }

    /**
     * Entfernt alle terminalen Timer aus dem Speicher.
     *
     * @param context App-Kontext
     */
    public static void enqueueClearCompleted(Context context) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_CLEAR_COMPLETED);
        context.startService(intent);
    }

    /**
     * Bricht einen laufenden Timer ab.
        *
        * @param context App-Kontext
        * @param timerId eindeutige Timer-ID
     */
    public static void enqueueCancelTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_CANCEL_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    /**
     * Entfernt einen terminalen Timer aus der Liste.
        *
        * @param context App-Kontext
        * @param timerId eindeutige Timer-ID
     */
    public static void enqueueDismissTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_DISMISS_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueDeleteSavedTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_DELETE_SAVED_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueStartSavedTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_START_SAVED_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        startServiceBestEffort(context, intent, true);
    }

    /**
     * Markiert die Abschluss-Notification als bestaetigt und stoppt weitere Ansagen.
        *
        * @param context App-Kontext
        * @param timerId eindeutige Timer-ID
     */
    public static void enqueueDismissNotification(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_DISMISS_NOTIFICATION);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueAcknowledgeGroupStep(Context context, long timerId) {
        enqueueAcknowledgeTimerPhase(context, timerId);
    }

    public static void enqueueAcknowledgeTimerPhase(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_ACKNOWLEDGE_GROUP_STEP);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueMutePhaseAnnouncement(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_MUTE_PHASE_ANNOUNCEMENT);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueRefreshTimerNotification(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_RESYNC);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        startServiceBestEffort(context, intent, true);
    }

    /**
     * Ersetzt einen bestehenden Timer durch einen neuen Eintrag mit aktualisierten Werten.
        *
        * @param context App-Kontext
        * @param timerId bestehende Timer-ID
        * @param name neuer Anzeigename
        * @param durationMillis neue Dauer in Millisekunden
     */
    public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis) {
        enqueueReplaceTimer(context, timerId, name, durationMillis, ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS);
    }

    public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis, long announcementIntervalMillis) {
        enqueueReplaceTimer(context, timerId, name, durationMillis, announcementIntervalMillis, ManagedTimer.DEFAULT_ALARM_VOLUME);
    }

    public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume) {
        enqueueReplaceTimer(context, timerId, name, durationMillis, announcementIntervalMillis, alarmVolume, "");
    }

    public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis,
                                           long announcementIntervalMillis, int alarmVolume, String completionText) {
        enqueueReplaceTimer(context, timerId, name, durationMillis, announcementIntervalMillis, alarmVolume,
            completionText, TimerType.STANDARD, 0, new ArrayList<>());
        }

        public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis,
                           long announcementIntervalMillis, int alarmVolume, String completionText,
                           TimerType timerType, int repeatCount, List<TimerStep> steps) {
            enqueueReplaceTimer(context, timerId, name, durationMillis, announcementIntervalMillis, alarmVolume,
                completionText, timerType, repeatCount, false, steps);
            }

            public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis,
                               long announcementIntervalMillis, int alarmVolume, String completionText,
                               TimerType timerType, int repeatCount, boolean waitForIntervalConfirmation,
                               List<TimerStep> steps) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_REPLACE_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        intent.putExtra(EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS, Math.max(0L, announcementIntervalMillis));
        intent.putExtra(EXTRA_ALARM_VOLUME, Math.max(0, Math.min(100, alarmVolume)));
        intent.putExtra(EXTRA_COMPLETION_TEXT, completionText == null ? "" : completionText.trim());
        intent.putExtra(EXTRA_TIMER_TYPE, timerType.name());
        intent.putExtra(EXTRA_REPEAT_COUNT, Math.max(0, repeatCount));
        intent.putExtra(EXTRA_WAIT_FOR_INTERVAL_CONFIRMATION, waitForIntervalConfirmation);
        intent.putExtra(EXTRA_STEPS, serializeSteps(steps));
        startServiceBestEffort(context, intent, true);
    }

    private static String serializeSteps(List<TimerStep> steps) {
        JSONArray array = new JSONArray();
        if (steps != null) {
            for (TimerStep step : steps) {
                JSONObject item = new JSONObject();
                try {
                    item.put("name", step.getName());
                    item.put("durationMillis", step.getDurationMillis());
                    item.put("completionText", step.getCompletionText());
                    array.put(item);
                } catch (JSONException ignored) {
                }
            }
        }
        return array.toString();
    }

    private static List<TimerStep> parseSteps(String raw) {
        List<TimerStep> steps = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                steps.add(new TimerStep(item.optString("name", ""), item.optLong("durationMillis", 0L),
                        item.optString("completionText", "")));
            }
        } catch (JSONException parseError) {
            Log.e(TAG, "Failed to parse timer steps", parseError);
        }
        return steps;
    }

    /**
     * Startet einen vorhandenen Timer erneut mit seiner konfigurierten Dauer.
        *
        * @param context App-Kontext
        * @param timerId eindeutige Timer-ID
     */
    public static void enqueueRestartTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_RESTART_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        startServiceBestEffort(context, intent, true);
    }

    /**
     * Laedt persistierte Timer in den In-Memory-Cache, falls dieser noch leer ist.
        *
        * @param context App-Kontext
     */
    public static void ensureTimersLoaded(Context context) {
        synchronized (SAVED_TIMERS) {
            if (timersLoaded) {
                return;
            }
            TimerPersistence.LoadedData data = TimerPersistence.load(context.getApplicationContext());
            for (SavedTimer timer : data.savedTimers) {
                SAVED_TIMERS.put(timer.getId(), timer);
            }
            for (ManagedTimer timer : data.activeTimers) {
                TIMERS.put(timer.getId(), timer);
            }
            NEXT_ID.set(data.nextId);
            timersLoaded = true;
        }
    }

    /**
     * Startet den Service, wenn mindestens ein Timer aktiv laeuft.
     *
     * <p>Wird z. B. nach App-Start oder Reboot verwendet, um den Tick-Loop zu reaktivieren.</p>
        *
        * @param context App-Kontext
     */
    public static void ensureServiceRunningForActiveTimers(Context context) {
        ensureTimersLoaded(context);
        long now = System.currentTimeMillis();
        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.isRunning(now) || timer.isPhaseAwaitingAnnouncement()) {
                    Intent intent = new Intent(context, TimerService.class);
                    intent.setAction(ACTION_RESYNC);
                    startServiceBestEffort(context, intent, true);
                    return;
                }
            }
        }
    }

    private static void startServiceBestEffort(Context context, Intent intent, boolean preferForeground) {
        String action = intent == null ? "null" : intent.getAction();
        try {
            // Prefer a regular service start while app is in foreground; on some OEM builds
            // this avoids flaky foreground start restrictions for user-triggered actions.
            context.startService(intent);
        } catch (RuntimeException firstError) {
            Log.w(TAG, "Primary startService failed, trying fallback", firstError);
            try {
                if (preferForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent);
                } else {
                    context.startService(intent);
                }
            } catch (RuntimeException fallbackError) {
                Log.e(TAG, "Fallback service start failed", fallbackError);
            }
        }
    }

    /**
     * Liefert eine sortierte, defensive Kopie aller Timer fuer die UI.
     *
     * <p>Sortierung: fertige (nicht stummgeschaltete) Timer zuerst, dann laufende,
     * danach sonstige terminale Timer.</p>
        *
        * @return sortierter Snapshot aller Timer
     */
    public static List<ManagedTimer> getTimersSnapshot() {
        List<ManagedTimer> snapshot = new ArrayList<>();
        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (!timer.isTerminal() || (timer.isCompleted() && !timer.isNotificationDismissed())) {
                    snapshot.add(new ManagedTimer(timer));
                }
            }
        }
        return snapshot;
    }

    public static List<SavedTimer> getSavedTimersSnapshot() {
        List<SavedTimer> snapshot = new ArrayList<>();
        synchronized (SAVED_TIMERS) {
            snapshot.addAll(SAVED_TIMERS.values());
        }
        return snapshot;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                createNotificationChannels();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup notification manager", e);
        }

        try {
            alarmManager = getSystemService(AlarmManager.class);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup alarm manager", e);
        }

        try {
            PowerManager powerManager = getSystemService(PowerManager.class);
            if (powerManager != null) {
                partialWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiTimer:TimerWakeLock");
                partialWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup wake lock", e);
        }

        try {
            ensureTimersLoaded(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load timers", e);
        }

        try {
            textToSpeech = new TextToSpeech(getApplicationContext(), this);
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    handler.post(() -> finishActiveAnnouncement(utteranceId));
                }

                @Override
                public void onError(String utteranceId) {
                    handler.post(() -> failActiveAnnouncement(utteranceId));
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    handler.post(() -> finishActiveAnnouncement(utteranceId));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e);
            ttsReady = false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            handler.removeCallbacks(delayedStopRunnable);
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove delayed stop runnable", e);
        }

        if (intent != null) {
            try {
                String intentAction = intent.getAction();
                if (ACTION_RESYNC.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        fullScreenConfirmationPosted.remove(timerId);
                    }
                } else if (ACTION_ADD_TIMER.equals(intentAction)) {
                    String name = intent.getStringExtra(EXTRA_NAME);
                    long durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L);
                    long announcementIntervalMillis = intent.getLongExtra(
                            EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS,
                            ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS
                    );
                    int alarmVolume = intent.getIntExtra(EXTRA_ALARM_VOLUME, ManagedTimer.DEFAULT_ALARM_VOLUME);
                    String completionText = intent.getStringExtra(EXTRA_COMPLETION_TEXT);
                    TimerType timerType = TimerType.fromName(intent.getStringExtra(EXTRA_TIMER_TYPE));
                    int repeatCount = intent.getIntExtra(EXTRA_REPEAT_COUNT, 0);
                    boolean waitForIntervalConfirmation = intent.getBooleanExtra(EXTRA_WAIT_FOR_INTERVAL_CONFIRMATION, false);
                    List<TimerStep> steps = parseSteps(intent.getStringExtra(EXTRA_STEPS));
                    boolean startImmediately = intent.getBooleanExtra(EXTRA_START_IMMEDIATELY, true);
                    if (name != null && !name.trim().isEmpty() && durationMillis > 0L) {
                        long savedTimerId = addSavedTimer(name.trim(), durationMillis, announcementIntervalMillis,
                            alarmVolume, completionText, timerType, repeatCount,
                            waitForIntervalConfirmation, steps);
                        if (startImmediately) {
                            startSavedTimer(savedTimerId);
                        }
                    }
                } else if (ACTION_START_SAVED_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        startSavedTimer(timerId);
                    }
                } else if (ACTION_DELETE_SAVED_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        deleteSavedTimer(timerId);
                    }
                } else if (ACTION_CANCEL_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        cancelTimer(timerId);
                    }
                } else if (ACTION_CLEAR_COMPLETED.equals(intentAction)) {
                    clearCompletedTimers();
                } else if (ACTION_DISMISS_NOTIFICATION.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        dismissTimerNotification(timerId);
                    }
                } else if (ACTION_ACKNOWLEDGE_GROUP_STEP.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        dismissTimerNotification(timerId);
                    }
                } else if (ACTION_MUTE_PHASE_ANNOUNCEMENT.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        mutePhaseAnnouncement(timerId);
                    }
                } else if (ACTION_REQUEST_CANCEL_CONFIRMATION.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        requestSwipeCancellationConfirmation(timerId);
                    }
                } else if (ACTION_CONFIRM_CANCEL_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        pendingSwipeCancellationConfirmations.remove(timerId);
                        cancelTimer(timerId);
                    }
                } else if (ACTION_KEEP_TIMER_RUNNING.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        pendingSwipeCancellationConfirmations.remove(timerId);
                        cancelSwipeConfirmationNotification(timerId);
                        tick();
                    }
                } else if (ACTION_DISMISS_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        dismissCompletedTimer(timerId);
                    }
                } else if (ACTION_REPLACE_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    String name = intent.getStringExtra(EXTRA_NAME);
                    long durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L);
                    long announcementIntervalMillis = intent.getLongExtra(
                            EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS,
                            ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS
                    );
                    int alarmVolume = intent.getIntExtra(EXTRA_ALARM_VOLUME, ManagedTimer.DEFAULT_ALARM_VOLUME);
                    String completionText = intent.getStringExtra(EXTRA_COMPLETION_TEXT);
                    TimerType timerType = TimerType.fromName(intent.getStringExtra(EXTRA_TIMER_TYPE));
                    int repeatCount = intent.getIntExtra(EXTRA_REPEAT_COUNT, 0);
                    boolean waitForIntervalConfirmation = intent.getBooleanExtra(EXTRA_WAIT_FOR_INTERVAL_CONFIRMATION, false);
                    List<TimerStep> steps = parseSteps(intent.getStringExtra(EXTRA_STEPS));
                    if (timerId > 0L && name != null && !name.trim().isEmpty() && durationMillis > 0L) {
                        replaceTimer(timerId, name.trim(), durationMillis, announcementIntervalMillis,
                            alarmVolume, completionText, timerType, repeatCount,
                            waitForIntervalConfirmation, steps);
                    }
                } else if (ACTION_RESTART_TIMER.equals(intentAction)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        restartTimer(timerId);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing intent action", e);
            }
        }

        try {
            tick();
        } catch (Exception e) {
            Log.e(TAG, "Error in tick during onStartCommand", e);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            handler.removeCallbacksAndMessages(null);
        } catch (Exception e) {
            Log.w(TAG, "Error removing handler callbacks", e);
        }

        try {
            cancelAllCompletionAlarms();
        } catch (Exception e) {
            Log.w(TAG, "Error cancelling completion alarms", e);
        }

        try {
            if (partialWakeLock != null && partialWakeLock.isHeld()) {
                partialWakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing wake lock", e);
        }

        try {
            if (textToSpeech != null) {
                textToSpeech.stop();
                textToSpeech.shutdown();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error shutting down TextToSpeech", e);
        }

        pendingAnnouncementQueue.clear();
        queuedAnnouncementIds.clear();
        activeAnnouncementTimerId = -1L;
        activeCompletionUtteranceId = null;

        try {
            super.onDestroy();
        } catch (Exception e) {
            Log.e(TAG, "Error in super.onDestroy()", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Initialisiert TTS mit der aktuell ausgewaehlten App-Sprache. */
    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS && textToSpeech != null;
        if (!ttsReady) {
            return;
        }

        Locale appLocale = getResources().getConfiguration().getLocales().get(0);
        int localeResult = textToSpeech.setLanguage(appLocale);
        if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            textToSpeech.setLanguage(Locale.getDefault());
        }
        maybeStartNextAnnouncement();
    }

    /**
     * Legt einen Timer an und persisted den Zustand.
     *
     * @param name Anzeigename des Timers
     * @param durationMillis Dauer des Timers in Millisekunden
     * @param startImmediately {@code true} startet sofort, sonst Status "bereit"
     */
    private long addSavedTimer(String name, long durationMillis, long announcementIntervalMillis,
                               int alarmVolume, String completionText, TimerType timerType,
                               int repeatCount, boolean waitForIntervalConfirmation, List<TimerStep> steps) {
        try {
            long id = NEXT_ID.getAndIncrement();
            long now = System.currentTimeMillis();
            SavedTimer timer = new SavedTimer(
                    id,
                    name,
                    durationMillis,
                    Math.max(0L, announcementIntervalMillis),
                    Math.max(0, Math.min(100, alarmVolume)),
                    completionText,
                    now,
                    0L,
                    0,
                    timerType,
                    repeatCount,
                    waitForIntervalConfirmation,
                    steps
            );
            synchronized (SAVED_TIMERS) {
                SAVED_TIMERS.put(id, timer);
            }
            persistTimers();
            return id;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save timer", e);
            return -1L;
        }
    }

    private void startSavedTimer(long savedTimerId) {
        SavedTimer savedTimer;
        ManagedTimer timer;
        long now = System.currentTimeMillis();
        synchronized (SAVED_TIMERS) {
            savedTimer = SAVED_TIMERS.get(savedTimerId);
            if (savedTimer == null) {
                return;
            }
            savedTimer = savedTimer.recordStarted(now);
            SAVED_TIMERS.put(savedTimerId, savedTimer);
            long timerId = NEXT_ID.getAndIncrement();
                long firstDuration = savedTimer.getTimerType() == TimerType.GROUP && !savedTimer.getSteps().isEmpty()
                    ? savedTimer.getSteps().get(0).getDurationMillis()
                    : savedTimer.getDurationMillis();
            timer = new ManagedTimer(
                    timerId,
                    savedTimerId,
                    savedTimer.getName(),
                    firstDuration,
                    now,
                    now + firstDuration,
                    savedTimer.getAnnouncementIntervalMillis(),
                    savedTimer.getAlarmVolume(),
                    savedTimer.getCompletionText(),
                    savedTimer.getTimerType(),
                    savedTimer.getRepeatCount(),
                    savedTimer.waitsForIntervalConfirmation(),
                    1,
                    0,
                    savedTimer.getSteps(),
                    false
            );
            synchronized (TIMERS) {
                TIMERS.put(timerId, timer);
            }
        }
        persistTimers();
        showTimerNotification(timer, false, timer.getDurationMillis(), false);
        tick();
    }

    private void deleteSavedTimer(long timerId) {
        synchronized (SAVED_TIMERS) {
            SAVED_TIMERS.remove(timerId);
        }
        persistTimers();
    }

    /**
     * Entfernt alle terminalen Timer (fertig oder abgebrochen) aus Speicher und Notifications.
     */
    private void clearCompletedTimers() {
        boolean stopActiveAnnouncement = false;
        List<Long> completedIds = new ArrayList<>();
        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.isTerminal()) {
                    completedIds.add(timer.getId());
                }
            }
            for (Long id : completedIds) {
                TIMERS.remove(id);
            }
        }
        for (Long id : completedIds) {
            notificationManager.cancel(id.intValue());
            cancelSwipeConfirmationNotification(id);
            pendingSwipeCancellationConfirmations.remove(id);
            nextAnnouncementAt.remove(id);
            fullScreenConfirmationPosted.remove(id);
            if (removeQueuedAnnouncement(id)) {
                stopActiveAnnouncement = true;
            }
        }

        if (stopActiveAnnouncement && textToSpeech != null) {
            try {
                textToSpeech.stop();
            } catch (Exception stopError) {
                Log.w(TAG, "Failed to stop TTS while clearing timers", stopError);
            }
        }
        persistTimers();
        maybeStartNextAnnouncement();
    }

    /**
     * Loescht einen terminalen Timer explizit aus der Liste.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void dismissCompletedTimer(long timerId) {
        boolean removed = false;
        boolean stopActiveAnnouncement;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && timer.isTerminal()) {
                TIMERS.remove(timerId);
                removed = true;
            }
        }

        notificationManager.cancel((int) timerId);
        cancelSwipeConfirmationNotification(timerId);
        pendingSwipeCancellationConfirmations.remove(timerId);
        nextAnnouncementAt.remove(timerId);
        fullScreenConfirmationPosted.remove(timerId);
        stopActiveAnnouncement = removeQueuedAnnouncement(timerId);

        if (removed) {
            persistTimers();
        }

        if (stopActiveAnnouncement && textToSpeech != null) {
            try {
                textToSpeech.stop();
            } catch (Exception stopError) {
                Log.w(TAG, "Failed to stop TTS after removing completed timer", stopError);
            }
        }

        tick();
    }

    /**
     * Ersetzt einen bestehenden Timer durch eine neue Konfiguration.
     *
     * @param timerId bestehende Timer-ID
     * @param name neuer Name
     * @param durationMillis neue Dauer in Millisekunden
     */
    private void replaceTimer(long timerId, String name, long durationMillis, long announcementIntervalMillis,
                              int alarmVolume, String completionText, TimerType timerType,
                              int repeatCount, boolean waitForIntervalConfirmation, List<TimerStep> steps) {
        boolean replaced = false;
        synchronized (SAVED_TIMERS) {
            SavedTimer existing = SAVED_TIMERS.get(timerId);
            if (existing != null) {
                SAVED_TIMERS.put(timerId, existing.withConfiguration(name, durationMillis,
                    announcementIntervalMillis, alarmVolume, completionText, timerType, repeatCount,
                    waitForIntervalConfirmation, steps));
                replaced = true;
            }
        }
        if (replaced) {
            persistTimers();
        }
    }

    /**
     * Markiert die Abschlussmeldung als bestaetigt und stoppt Wiederholungsansagen.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void dismissTimerNotification(long timerId) {
        pendingSwipeCancellationConfirmations.remove(timerId);
        cancelSwipeConfirmationNotification(timerId);
        boolean removed = false;
        boolean advanced = false;
        boolean stopActiveAnnouncement;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && timer.isCompleted()) {
                TIMERS.remove(timerId);
                removed = true;
            } else if (timer != null && timer.isPhaseAwaitingAnnouncement()) {
                if (timer.getTimerType() == TimerType.GROUP && !timer.hasNextGroupStep()) {
                    timer.markCompleted();
                    advanced = true;
                } else {
                    advanced = timer.advanceAfterAnnouncement(System.currentTimeMillis());
                }
            }
        }

        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);
        fullScreenConfirmationPosted.remove(timerId);
        stopActiveAnnouncement = removeQueuedAnnouncement(timerId);

        if (removed || advanced) {
            persistTimers();
            if (stopActiveAnnouncement && textToSpeech != null) {
                try {
                    textToSpeech.stop();
                } catch (Exception stopError) {
                    Log.w(TAG, "Failed to stop TTS after dismiss", stopError);
                }
            }
        }

        if (!stopActiveAnnouncement) {
            maybeStartNextAnnouncement();
        }
        if (advanced) {
            tick();
        }
    }

    private void mutePhaseAnnouncement(long timerId) {
        boolean muted = false;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && timer.requiresPhaseConfirmation() && !timer.isPhaseAnnouncementMuted()) {
                timer.mutePhaseAnnouncement();
                muted = true;
            }
        }
        if (!muted) {
            return;
        }

        nextAnnouncementAt.remove(timerId);
        boolean stopActiveAnnouncement = removeQueuedAnnouncement(timerId);
        if (stopActiveAnnouncement && textToSpeech != null) {
            textToSpeech.stop();
        }
        persistTimers();
        tick();
        maybeStartNextAnnouncement();
    }

    /**
     * Bricht einen laufenden Timer ab und entfernt seine aktive Notification.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void cancelTimer(long timerId) {
        pendingSwipeCancellationConfirmations.remove(timerId);
        cancelSwipeConfirmationNotification(timerId);
        boolean removed;
        synchronized (TIMERS) {
            removed = TIMERS.remove(timerId) != null;
        }

        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);
        fullScreenConfirmationPosted.remove(timerId);
        boolean stopActiveAnnouncement = removeQueuedAnnouncement(timerId);
        if (removed) {
            persistTimers();
        }
        if (stopActiveAnnouncement && textToSpeech != null) {
            textToSpeech.stop();
        }
        tick();
    }

    private void requestSwipeCancellationConfirmation(long timerId) {
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer == null || timer.isCancelled()) {
                return;
            }
        }
        pendingSwipeCancellationConfirmations.add(timerId);
    }

    private void cancelSwipeConfirmationNotification(long timerId) {
        if (notificationManager != null) {
            notificationManager.cancel(SWIPE_CONFIRMATION_TAG_PREFIX + timerId, 0);
        }
    }

    /**
     * Startet einen vorhandenen Timer neu mit seiner hinterlegten Dauer.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void restartTimer(long timerId) {
        ManagedTimer previousRun;
        synchronized (TIMERS) {
            previousRun = TIMERS.get(timerId);
        }
        if (previousRun == null) {
            startSavedTimer(timerId);
            return;
        }

        boolean hasSavedTimer;
        synchronized (SAVED_TIMERS) {
            hasSavedTimer = SAVED_TIMERS.containsKey(previousRun.getSourceSavedTimerId());
        }
        if (hasSavedTimer) {
            startSavedTimer(previousRun.getSourceSavedTimerId());
            return;
        }

        long now = System.currentTimeMillis();
        long newId = NEXT_ID.getAndIncrement();
        long initialDuration = previousRun.getTimerType() == TimerType.GROUP && !previousRun.getSteps().isEmpty()
            ? previousRun.getSteps().get(0).getDurationMillis()
            : previousRun.getDurationMillis();
        ManagedTimer restarted = new ManagedTimer(
                newId,
                previousRun.getSourceSavedTimerId(),
                previousRun.getName(),
                initialDuration,
                now,
                now + initialDuration,
                previousRun.getAnnouncementIntervalMillis(),
                previousRun.getAlarmVolume(),
                previousRun.getCompletionText(),
                previousRun.getTimerType(),
                previousRun.getRepeatCount(),
                previousRun.waitsForIntervalConfirmation(),
                1,
                0,
                previousRun.getSteps(),
                false
        );
        synchronized (TIMERS) {
            TIMERS.put(newId, restarted);
        }
        persistTimers();
        showTimerNotification(restarted, false, restarted.getDurationMillis(), false);
        tick();
    }

    /**
     * Zentrale Tick-Schleife fuer Zustandsuebergaenge, Notifications und TTS.
     *
     * <p>Die Methode wird sekundenweise erneut eingeplant, solange laufende Timer
     * oder aktive Abschlussansagen vorhanden sind.</p>
     */
    private void tick() {
        handler.removeCallbacks(tickRunnable);
        long now = System.currentTimeMillis();
        List<ManagedTimer> currentTimers = new ArrayList<>();
        List<ManagedTimer> runningTimers = new ArrayList<>();
        List<ManagedTimer> completedWithActiveAnnouncement = new ArrayList<>();
        boolean changed = false;

        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.shouldComplete(now)) {
                    if (timer.hasNextPhaseAfterCompletion()) {
                        timer.markPhaseAwaitingAnnouncement();
                    } else {
                        timer.markCompleted();
                    }
                    changed = true;
                    acquireWakeLockBriefly(); // Wakelock beim Timer-Ende
                }
                currentTimers.add(new ManagedTimer(timer));
                if (timer.isRunning(now)) {
                    runningTimers.add(new ManagedTimer(timer));
                }
                if ((timer.isCompleted() || timer.isPhaseAwaitingAnnouncement())
                        && !timer.isNotificationDismissed()) {
                    completedWithActiveAnnouncement.add(new ManagedTimer(timer));
                }
            }
        }

        updateForegroundNotification(runningTimers, completedWithActiveAnnouncement, now);

        for (ManagedTimer timer : currentTimers) {
            boolean completed = timer.isCompleted() || timer.isPhaseAwaitingAnnouncement();
            long remainingMillis = completed ? 0L : timer.getRemainingMillis(now);
            if (timer.isStarted() && !timer.isCancelled() && !(timer.isTerminal() && timer.isNotificationDismissed())) {
                showTimerNotification(timer, completed, remainingMillis, false);
            }
        }

        boolean announcementStateChanged = handleCompletionAnnouncements(now, completedWithActiveAnnouncement);

        if (changed || announcementStateChanged) {
            persistTimers();
        }

        // Schedule AlarmManager fuer Doze-Mode Zuverlassigkeit
        scheduleCompletionAlarm();

        if (!runningTimers.isEmpty() || !completedWithActiveAnnouncement.isEmpty()) {
            handler.postDelayed(tickRunnable, 1000L);
        } else {
            handler.postDelayed(delayedStopRunnable, STOP_DELAY_MILLIS);
        }
    }

    /**
     * Stoppt den Service, wenn weder laufende Timer noch offene Abschlussansagen existieren.
     */
    private void stopIfIdle() {
        boolean hasRunningTimers = false;
        boolean hasPendingAnnouncements = false;
        synchronized (TIMERS) {
            long now = System.currentTimeMillis();
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.isRunning(now) || timer.isPhaseAwaitingAnnouncement()) {
                    hasRunningTimers = true;
                    break;
                }
                if ((timer.isCompleted() || timer.isPhaseAwaitingAnnouncement()) && !timer.isNotificationDismissed()) {
                    hasPendingAnnouncements = true;
                }
            }
        }

        if (hasRunningTimers || hasPendingAnnouncements) {
            tick();
            return;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Plant einen AlarmManager-Alarm fuer die naechste Timer-Completion.
     * Dies stellt sicher, dass Timer auch bei Doze-Mode gemeldet werden.
     */
    private void scheduleCompletionAlarm() {
        if (alarmManager == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long nextCompletionTime = Long.MAX_VALUE;
        long nextTimerId = -1L;

        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.isRunning(now) && timer.getEndTimeMillis() < nextCompletionTime) {
                    nextCompletionTime = timer.getEndTimeMillis();
                    nextTimerId = timer.getId();
                }
            }
        }

        // Cancel alte Alarme
        cancelAllCompletionAlarms();

        if (nextTimerId < 0 || nextCompletionTime == Long.MAX_VALUE) {
            return;
        }

        try {
            Intent intent = new Intent(this, TimerService.class);
            intent.setAction(ACTION_RESYNC);
            PendingIntent pendingIntent = PendingIntent.getService(
                    this,
                    (int) nextTimerId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Trigger AlarmManager setAndAllowWhileIdle - waecht das Geraet auf, auch im Doze-Mode
            long triggerTime = nextCompletionTime + 500; // 500ms nach dem erwarteten Ende
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }

            Log.d(TAG, "Scheduled alarm for timer " + nextTimerId + " at " + triggerTime);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to schedule completion alarm - missing SCHEDULE_EXACT_ALARM permission", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule completion alarm", e);
        }
    }

    /**
     * Canceliert alle eingeplanten AlarmManager-Alarme.
     */
    private void cancelAllCompletionAlarms() {
        if (alarmManager == null) {
            return;
        }

        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                try {
                    Intent intent = new Intent(this, TimerService.class);
                    intent.setAction(ACTION_RESYNC);
                    PendingIntent pendingIntent = PendingIntent.getService(
                            this,
                            (int) timer.getId(),
                            intent,
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
                    );

                    if (pendingIntent != null) {
                        alarmManager.cancel(pendingIntent);
                        pendingIntent.cancel();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to cancel alarm for timer " + timer.getId(), e);
                }
            }
        }
    }

    /**
     * Aktiviert Partial WakeLock fuer kurze Zeit waehrend kritischer Operationen.
     */
    private void acquireWakeLockBriefly() {
        if (partialWakeLock != null) {
            try {
                if (!partialWakeLock.isHeld()) {
                    partialWakeLock.acquire(10000); // 10 Sekunden fuer Vibration + TTS
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to acquire wake lock", e);
            }
        }
    }

    /**
     * Setzt die Alarmlautstärke basierend auf dem alarmVolume eines Timers (0-100).
     * Die Lautstärke wird auf den STREAM_ALARM gesetzt, um sicherzustellen,
     * dass der Alarm auch bei Stummschaltung hörbar ist.
     *
     * @param alarmVolume Lautstärke 0-100
     */
    private void setAlarmVolume(int alarmVolume) {
        try {
            AudioManager audioManager = getSystemService(AudioManager.class);
            if (audioManager == null) {
                return;
            }

            // Get max volume for STREAM_ALARM
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            // Map 0-100 to 0-maxVolume
            int volumeLevel = (alarmVolume * maxVolume) / 100;

            // Set volume - FLAG_SHOW_UI displays the volume UI (optional)
            audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    volumeLevel,
                    AudioManager.FLAG_SHOW_UI
            );

            Log.d(TAG, "Set alarm volume to " + alarmVolume + "% (level " + volumeLevel + "/" + maxVolume + ")");
        } catch (Exception e) {
            Log.w(TAG, "Failed to set alarm volume", e);
        }
    }

    /**
     * Erstellt die Notification-Channels fuer laufende und abgeschlossene Timer.
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        try {
            NotificationChannel runningChannel = new NotificationChannel(
                    CHANNEL_RUNNING,
                    getString(R.string.channel_running_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            runningChannel.setDescription(getString(R.string.channel_running_description));

            NotificationChannel finishedChannel = new NotificationChannel(
                    CHANNEL_FINISHED,
                    getString(R.string.channel_finished_name),
                    NotificationManager.IMPORTANCE_HIGH // Increase importance for alarm
            );
            finishedChannel.setDescription(getString(R.string.channel_finished_description));
            finishedChannel.enableVibration(true); // Enable vibration for completion
            
            // Set system notification sound - bypasses mute settings
            android.net.Uri soundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
            android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            finishedChannel.setSound(soundUri, audioAttributes);

            if (notificationManager != null) {
                notificationManager.createNotificationChannels(List.of(runningChannel, finishedChannel));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification channels", e);
        }
    }

    /** Haelt den Service mit einer separaten Notification aktiv, damit Timer-Notifications wischbar bleiben. */
    private void updateForegroundNotification(
            List<ManagedTimer> runningTimers,
            List<ManagedTimer> completedWithActiveAnnouncement,
            long now
    ) {
        if (runningTimers.isEmpty() && completedWithActiveAnnouncement.isEmpty()) {
            return;
        }

        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_RUNNING)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.foreground_timer_service_active))
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setContentIntent(buildMainPendingIntent());
            
            try {
                ServiceCompat.startForeground(
                        this,
                        FOREGROUND_NOTIFICATION_ID,
                        builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } catch (Exception primaryError) {
                // On targetSdk 34+, type "none" is prohibited. Never fall back to untyped startForeground.
                Log.e(TAG, "Unable to start typed foreground service", primaryError);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in updateForegroundNotification", e);
        }
    }

    /**
     * Aktualisiert die individuelle Notification eines Timers.
     *
     * @param timer Zieltimer
     * @param completed {@code true}, wenn Timer fertig ist
     * @param remainingMillis Restzeit fuer Running-Status
     * @param skipNotify {@code true}, wenn kein notify() ausgefuehrt werden soll
     */
    private void showTimerNotification(ManagedTimer timer, boolean completed, long remainingMillis, boolean skipNotify) {
        try {
            boolean includeFullScreenIntent = timer.requiresPhaseConfirmation()
                    && !fullScreenConfirmationPosted.contains(timer.getId());
            NotificationCompat.Builder builder = buildTimerNotification(
                    timer, completed, remainingMillis, includeFullScreenIntent);
            boolean notificationPosted = false;

            if (!skipNotify) {
                try {
                    if (notificationManager != null) {
                        if (pendingSwipeCancellationConfirmations.contains(timer.getId())) {
                            notificationManager.cancel((int) timer.getId());
                            notificationManager.notify(
                                    SWIPE_CONFIRMATION_TAG_PREFIX + timer.getId(),
                                    0,
                                    builder.build()
                            );
                            return;
                        }
                        notificationManager.notify((int) timer.getId(), builder.build());
                        notificationPosted = true;
                    }
                } catch (Exception notifyError) {
                    Log.e(TAG, "Unable to post timer notification", notifyError);
                    // Try fallback: attempt to post anyway without security checks
                    try {
                        if (notificationManager != null) {
                            notificationManager.notify((int) timer.getId(), builder.build());
                            notificationPosted = true;
                        }
                    } catch (Exception fallbackNotify) {
                        Log.e(TAG, "Fallback notification post also failed", fallbackNotify);
                    }
                }
            }
            if (notificationPosted && includeFullScreenIntent) {
                fullScreenConfirmationPosted.add(timer.getId());
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in showTimerNotification", e);
        }
    }

    /**
     * Baut die Notification fuer einen konkreten Timerzustand.
        *
        * @param timer Zieltimer
        * @param completed {@code true}, wenn der Timer bereits abgeschlossen ist
        * @param remainingMillis Restzeit in Millisekunden (bei laufendem Timer)
        * @return konfigurierte Notification fuer den Timerzustand
     */
    private NotificationCompat.Builder buildTimerNotification(ManagedTimer timer, boolean completed, long remainingMillis,
                                                               boolean includeFullScreenIntent) {
        if (pendingSwipeCancellationConfirmations.contains(timer.getId())) {
            String confirmationText = getString(R.string.dialog_cancel_timer_message, timer.getName());
            return new NotificationCompat.Builder(this, CHANNEL_RUNNING)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.dialog_cancel_timer_title))
                .setContentText(confirmationText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(confirmationText))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(buildMainPendingIntent())
                .addAction(
                    R.drawable.ic_ui_cancel,
                    getString(R.string.action_cancel_timer),
                    buildSwipeCancellationActionPendingIntent(timer.getId(), ACTION_CONFIRM_CANCEL_TIMER,
                        600000 + (int) timer.getId())
                )
                .addAction(
                    R.drawable.ic_ui_play,
                    getString(R.string.action_keep_running),
                    buildSwipeCancellationActionPendingIntent(timer.getId(), ACTION_KEEP_TIMER_RUNNING,
                        700000 + (int) timer.getId())
                );
        }

        boolean awaitingConfirmation = timer.requiresPhaseConfirmation();
        String channelId = completed ? CHANNEL_FINISHED : CHANNEL_RUNNING;
        String contentText = completed
                ? getString(R.string.timer_notification_completed)
                : getString(R.string.timer_notification_remaining, TimerFormatter.formatDuration(remainingMillis));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(timer.getName())
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(awaitingConfirmation ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_LOW)
            .setCategory(awaitingConfirmation ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
                .setAutoCancel(completed && !awaitingConfirmation)
                .setOnlyAlertOnce(true)
            .setDeleteIntent(buildSwipeConfirmationPendingIntent(timer.getId()))
                .setContentIntent(buildMainPendingIntent());

        if (awaitingConfirmation) {
            String phaseActionLabel = timer.getTimerType() == TimerType.GROUP
                ? (timer.hasNextGroupStep()
                    ? getString(R.string.action_confirm_next_group_step)
                    : getString(R.string.action_finish_timer_group))
                : getString(R.string.action_confirm_next_interval);
                if (includeFullScreenIntent) {
                builder.setFullScreenIntent(
                    buildTimerAlertPendingIntent(timer.getId(), TimerAlertActivity.ACTION_CONFIRM_PHASE,
                        100000 + (int) timer.getId()),
                    true
                );
                }
            builder.addAction(
                R.drawable.ic_ui_play,
                phaseActionLabel,
                buildAcknowledgeTimerPhasePendingIntent(timer.getId(), 200000 + (int) timer.getId())
            );
            builder.addAction(
                R.drawable.ic_ui_cancel,
                getString(R.string.action_cancel_timer),
                buildTimerAlertPendingIntent(timer.getId(), TimerAlertActivity.ACTION_CONFIRM_SWIPE, 300000 + (int) timer.getId())
            );
            if (!timer.isPhaseAnnouncementMuted()) {
                builder.addAction(
                    android.R.drawable.ic_lock_silent_mode,
                    getString(R.string.action_mute_phase_announcement),
                    buildMutePhaseAnnouncementPendingIntent(timer.getId(), 500000 + (int) timer.getId())
                );
            }
        }

        if (completed) {
            // DO NOT set silent - we want the alarm to be heard
            // .setSilent(true);  <- REMOVED to allow sound
            
            // Enable vibration and sound for completion
            builder.setVibrate(COMPLETION_VIBRATION_PATTERN);
            
            // Set sound via notification (bypasses mute for USAGE_ALARM)
            android.net.Uri soundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
            builder.setSound(soundUri, android.media.AudioManager.STREAM_ALARM);
            
                if (!awaitingConfirmation) {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.timer_notification_dismiss_action),
                    buildDismissNotificationPendingIntent(timer.getId(), 200000 + (int) timer.getId())
                );
                }
        }

        return builder;
    }

    private PendingIntent buildSwipeConfirmationPendingIntent(long timerId) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(ACTION_REQUEST_CANCEL_CONFIRMATION);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getService(
            this,
            400000 + (int) timerId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        }

        private PendingIntent buildSwipeCancellationActionPendingIntent(long timerId, String action, int requestCode) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent buildAcknowledgeTimerPhasePendingIntent(long timerId, int requestCode) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(ACTION_ACKNOWLEDGE_GROUP_STEP);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent buildMutePhaseAnnouncementPendingIntent(long timerId, int requestCode) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(ACTION_MUTE_PHASE_ANNOUNCEMENT);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent buildTimerAlertPendingIntent(long timerId, String action, int requestCode) {
        Intent intent = new Intent(this, TimerAlertActivity.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /**
     * PendingIntent zum Oeffnen der Hauptansicht aus einer Notification heraus.
        *
        * @return PendingIntent fuer MainActivity
     */
    private PendingIntent buildMainPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /**
     * PendingIntent zum Entfernen eines terminalen Timers.
        *
        * @param timerId eindeutige Timer-ID
        * @param requestCode Request-Code fuer das PendingIntent
        * @return PendingIntent fuer ACTION_DISMISS_TIMER
     */
    private PendingIntent buildDismissTimerPendingIntent(long timerId, int requestCode) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(ACTION_DISMISS_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /**
     * PendingIntent zum Dismiss der Abschluss-Notification.
        *
        * @param timerId eindeutige Timer-ID
        * @param requestCode Request-Code fuer das PendingIntent
        * @return PendingIntent fuer ACTION_DISMISS_NOTIFICATION
     */
    private PendingIntent buildDismissNotificationPendingIntent(long timerId, int requestCode) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(ACTION_DISMISS_NOTIFICATION);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

     /** Spricht ausschliesslich den pro Timer konfigurierten Abschlusstext aus. */
     private boolean announceCompletion(long timerId, String completionText) {
        if (!ttsReady || textToSpeech == null) {
            return false;
        }

        try {
            acquireWakeLockBriefly(); // Ensure device stays awake during announcement
            triggerCompletionVibration();

            long now = System.currentTimeMillis();
            String utteranceSuffix = timerId > 0L ? timerId + "-" + now : String.valueOf(now);
            String completionUtteranceId = "timer-done-" + utteranceSuffix;
            String spokenCompletionText = completionText == null || completionText.trim().isEmpty()
                    ? getString(R.string.tts_completed)
                    : completionText.trim();
            String[] words = spokenCompletionText.split("\\s+");
            if (words.length > 1) {
                StringBuilder precedingText = new StringBuilder();
                for (int index = 0; index < words.length - 1; index++) {
                    if (index > 0) {
                        precedingText.append(' ');
                    }
                    precedingText.append(words[index]);
                }

                int precedingResult = textToSpeech.speak(
                        precedingText.toString(), TextToSpeech.QUEUE_FLUSH, null,
                        "timer-text-prefix-" + utteranceSuffix);
                int pauseResult = textToSpeech.playSilentUtterance(
                        100L, TextToSpeech.QUEUE_ADD, "timer-text-pause-" + utteranceSuffix);
                int finalWordResult = textToSpeech.speak(
                        words[words.length - 1], TextToSpeech.QUEUE_ADD, null, completionUtteranceId);
                if (precedingResult == TextToSpeech.ERROR || pauseResult == TextToSpeech.ERROR
                        || finalWordResult == TextToSpeech.ERROR) {
                    return false;
                }
            } else {
                int speakResult = textToSpeech.speak(
                        spokenCompletionText, TextToSpeech.QUEUE_FLUSH, null, completionUtteranceId);
                if (speakResult == TextToSpeech.ERROR) {
                    return false;
                }
            }

            activeCompletionUtteranceId = completionUtteranceId;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to announce completion", e);
            ttsReady = false;
            return false;
        }
    }

    private void triggerCompletionVibration() {
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = getSystemService(VibratorManager.class);
                if (vibratorManager != null) {
                    vibrator = vibratorManager.getDefaultVibrator();
                }
            } else {
                vibrator = getSystemService(Vibrator.class);
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(COMPLETION_VIBRATION_PATTERN, -1));
            } else {
                vibrator.vibrate(COMPLETION_VIBRATION_PATTERN, -1);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to trigger completion vibration", e);
        }
    }

    /**
     * Steuert Erst- und Wiederholungsansagen fuer fertig gewordene Timer.
     *
     * @param now aktuelle Zeit in Millisekunden
     * @param announcementCandidates Timer mit aktiver Abschlussmeldung
     */
    private boolean handleCompletionAnnouncements(long now, List<ManagedTimer> announcementCandidates) {
        announcementCandidates.sort(new Comparator<ManagedTimer>() {
            @Override
            public int compare(ManagedTimer left, ManagedTimer right) {
                int byEndTime = Long.compare(left.getEndTimeMillis(), right.getEndTimeMillis());
                if (byEndTime != 0) {
                    return byEndTime;
                }
                return Long.compare(left.getId(), right.getId());
            }
        });

        Set<Long> candidateIds = new HashSet<>();
        for (ManagedTimer timer : announcementCandidates) {
            candidateIds.add(timer.getId());
        }

        Iterator<Map.Entry<Long, Long>> iterator = nextAnnouncementAt.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            if (!candidateIds.contains(entry.getKey())) {
                iterator.remove();
            }
        }

        pruneQueuedAnnouncements(candidateIds);

        for (ManagedTimer timer : announcementCandidates) {
            long timerId = timer.getId();
            ManagedTimer current;
            synchronized (TIMERS) {
                current = TIMERS.get(timerId);
            }

                if (current == null || (!current.isCompleted() && !current.isPhaseAwaitingAnnouncement())
                    || current.isNotificationDismissed() || current.isPhaseAnnouncementMuted()) {
                nextAnnouncementAt.remove(timerId);
                continue;
            }

                if (activeAnnouncementTimerId == timerId) {
                    continue;
                }

                long interval = current.repeatsCompletionAnnouncement()
                    ? current.getAnnouncementIntervalMillis()
                    : 0L;

            if (!current.isCompletionAnnounced()) {
                enqueueAnnouncement(timerId);
                continue;
            }

            if (interval <= 0L) {
                nextAnnouncementAt.remove(timerId);
                continue;
            }

            Long nextAt = nextAnnouncementAt.get(timerId);
            if (nextAt == null) {
                nextAnnouncementAt.put(timerId, now + interval);
                continue;
            }

            if (now >= nextAt) {
                enqueueAnnouncement(timerId);
            }
        }

        maybeStartNextAnnouncement();
        return false;
    }

    private void enqueueAnnouncement(long timerId) {
        if (timerId <= 0L) {
            return;
        }

        if (activeAnnouncementTimerId == timerId || queuedAnnouncementIds.contains(timerId)) {
            return;
        }

        pendingAnnouncementQueue.addLast(timerId);
        queuedAnnouncementIds.add(timerId);
    }

    private void maybeStartNextAnnouncement() {
        if (!ttsReady || textToSpeech == null || activeAnnouncementTimerId > 0L) {
            return;
        }

        while (!pendingAnnouncementQueue.isEmpty()) {
            long timerId = pendingAnnouncementQueue.removeFirst();
            queuedAnnouncementIds.remove(timerId);

            ManagedTimer timer;
            synchronized (TIMERS) {
                timer = TIMERS.get(timerId);
                if (timer == null || (!timer.isCompleted() && !timer.isPhaseAwaitingAnnouncement())
                    || timer.isNotificationDismissed() || timer.isPhaseAnnouncementMuted()) {
                    continue;
                }
            }

            activeAnnouncementTimerId = timerId;
            
            // Set the alarm volume for this timer before announcing
            if (AppSettings.forceAlarmSoundEnabled(this)) {
                setAlarmVolume(timer.getAlarmVolume());
            }
            
            if (announceCompletion(timerId, timer.getCompletionText())) {
                boolean persistRequired = false;

                synchronized (TIMERS) {
                    ManagedTimer live = TIMERS.get(timerId);
                        if (live != null && (live.isCompleted() || live.isPhaseAwaitingAnnouncement())
                            && !live.isNotificationDismissed()) {
                        if (!live.isCompletionAnnounced()) {
                            live.markCompletionAnnounced();
                            persistRequired = true;
                        }

                    }
                }

                if (persistRequired) {
                    persistTimers();
                }
                return;
            }

            activeAnnouncementTimerId = -1L;
            activeCompletionUtteranceId = null;
            nextAnnouncementAt.put(timerId, System.currentTimeMillis() + 1000L);
        }
    }

    private void finishActiveAnnouncement(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeCompletionUtteranceId)) {
            return;
        }

        long finishedTimerId = activeAnnouncementTimerId;
        activeAnnouncementTimerId = -1L;
        activeCompletionUtteranceId = null;
        ManagedTimer timer;
        boolean waitingForGroupAcknowledgement = false;
        boolean advanced = false;
        long finishedAt = System.currentTimeMillis();
        synchronized (TIMERS) {
            timer = TIMERS.get(finishedTimerId);
            if (timer != null && timer.isPhaseAwaitingAnnouncement()) {
                if (timer.isPhaseAnnouncementMuted()) {
                    nextAnnouncementAt.remove(finishedTimerId);
                } else if (timer.requiresPhaseConfirmation()) {
                    waitingForGroupAcknowledgement = true;
                } else {
                    advanced = timer.advanceAfterAnnouncement(finishedAt);
                }
            }
        }
        if (waitingForGroupAcknowledgement) {
            if (timer.repeatsCompletionAnnouncement() && timer.getAnnouncementIntervalMillis() > 0L) {
                nextAnnouncementAt.put(finishedTimerId, finishedAt + timer.getAnnouncementIntervalMillis());
            } else {
                nextAnnouncementAt.remove(finishedTimerId);
            }
            maybeStartNextAnnouncement();
            return;
        }
        if (advanced && timer != null && !timer.isTerminal()) {
            nextAnnouncementAt.remove(finishedTimerId);
            persistTimers();
            tick();
            return;
        }
        if (timer != null && timer.isCompleted() && timer.repeatsCompletionAnnouncement()
                && timer.getAnnouncementIntervalMillis() > 0L) {
            nextAnnouncementAt.put(finishedTimerId, finishedAt + timer.getAnnouncementIntervalMillis());
        } else {
            nextAnnouncementAt.remove(finishedTimerId);
        }
        maybeStartNextAnnouncement();
    }

    private void failActiveAnnouncement(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeCompletionUtteranceId)) {
            return;
        }

        long failedTimerId = activeAnnouncementTimerId;
        activeAnnouncementTimerId = -1L;
        activeCompletionUtteranceId = null;
        if (failedTimerId > 0L) {
            nextAnnouncementAt.put(failedTimerId, System.currentTimeMillis() + 1000L);
        }
        maybeStartNextAnnouncement();
    }

    private boolean removeQueuedAnnouncement(long timerId) {
        boolean removedQueued = queuedAnnouncementIds.remove(timerId);
        if (removedQueued) {
            pendingAnnouncementQueue.remove(timerId);
        }

        if (activeAnnouncementTimerId == timerId) {
            activeAnnouncementTimerId = -1L;
            activeCompletionUtteranceId = null;
            return true;
        }

        return false;
    }

    private void pruneQueuedAnnouncements(Set<Long> validTimerIds) {
        if (validTimerIds == null) {
            pendingAnnouncementQueue.clear();
            queuedAnnouncementIds.clear();
            return;
        }

        Iterator<Long> iterator = pendingAnnouncementQueue.iterator();
        while (iterator.hasNext()) {
            long timerId = iterator.next();
            if (!validTimerIds.contains(timerId)) {
                iterator.remove();
                queuedAnnouncementIds.remove(timerId);
            }
        }

        if (activeAnnouncementTimerId > 0L && !validTimerIds.contains(activeAnnouncementTimerId)) {
            activeAnnouncementTimerId = -1L;
            activeCompletionUtteranceId = null;
        }
    }

    /**
     * Persistiert den kompletten In-Memory-Zustand atomar in SharedPreferences.
     */
    private void persistTimers() {
        List<ManagedTimer> timersToPersist = new ArrayList<>();
        List<SavedTimer> savedTimersToPersist = new ArrayList<>();
        synchronized (SAVED_TIMERS) {
            savedTimersToPersist.addAll(SAVED_TIMERS.values());
        }
        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                timersToPersist.add(new ManagedTimer(timer));
            }
        }
        TimerPersistence.save(getApplicationContext(), savedTimersToPersist, timersToPersist);
    }
}