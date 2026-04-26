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
    static final String ACTION_CANCEL_TIMER = "com.example.multitimer.CANCEL_TIMER";
    static final String ACTION_CLEAR_COMPLETED = "com.example.multitimer.CLEAR_COMPLETED";
    static final String ACTION_DISMISS_NOTIFICATION = "com.example.multitimer.DISMISS_NOTIFICATION";
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

    private static final String CHANNEL_RUNNING = "multitimer_running";
    private static final String CHANNEL_FINISHED = "multitimer_finished_silent";
    private static final long STOP_DELAY_MILLIS = 4000L;
    private static final long[] COMPLETION_VIBRATION_PATTERN = new long[]{0L, 180L, 120L, 220L};
    private static final Map<Long, ManagedTimer> TIMERS = new LinkedHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;
    private final Runnable delayedStopRunnable = this::stopIfIdle;
    private final Map<Long, Long> nextAnnouncementAt = new LinkedHashMap<>();
    private final ArrayDeque<Long> pendingAnnouncementQueue = new ArrayDeque<>();
    private final Set<Long> queuedAnnouncementIds = new HashSet<>();
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
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_ADD_TIMER);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        intent.putExtra(EXTRA_START_IMMEDIATELY, false);
        intent.putExtra(EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS, Math.max(0L, announcementIntervalMillis));
        intent.putExtra(EXTRA_ALARM_VOLUME, Math.max(0, Math.min(100, alarmVolume)));
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
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_REPLACE_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        intent.putExtra(EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS, Math.max(0L, announcementIntervalMillis));
        intent.putExtra(EXTRA_ALARM_VOLUME, Math.max(0, Math.min(100, alarmVolume)));
        startServiceBestEffort(context, intent, true);
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
        synchronized (TIMERS) {
            if (!TIMERS.isEmpty()) {
                return;
            }
            long nextId = 1L;
            for (ManagedTimer timer : TimerPersistence.load(context.getApplicationContext())) {
                TIMERS.put(timer.getId(), timer);
                nextId = Math.max(nextId, timer.getId() + 1L);
            }
            NEXT_ID.set(nextId);
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
                if (timer.isRunning(now)) {
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
                snapshot.add(new ManagedTimer(timer));
            }
        }
        snapshot.sort(new Comparator<ManagedTimer>() {
            @Override
            public int compare(ManagedTimer left, ManagedTimer right) {
                int leftGroup = left.isCompleted() && !left.isNotificationDismissed() ? 0
                        : (left.isTerminal() ? 2 : 1);
                int rightGroup = right.isCompleted() && !right.isNotificationDismissed() ? 0
                        : (right.isTerminal() ? 2 : 1);

                if (leftGroup != rightGroup) {
                    return Integer.compare(leftGroup, rightGroup);
                }

                if (leftGroup == 1) {
                    return Long.compare(left.getEndTimeMillis(), right.getEndTimeMillis());
                }

                int byName = left.getName().compareToIgnoreCase(right.getName());
                if (byName != 0) {
                    return byName;
                }
                return Long.compare(left.getId(), right.getId());
            }
        });
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
                if (ACTION_ADD_TIMER.equals(intentAction)) {
                    String name = intent.getStringExtra(EXTRA_NAME);
                    long durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L);
                    long announcementIntervalMillis = intent.getLongExtra(
                            EXTRA_ANNOUNCEMENT_INTERVAL_MILLIS,
                            ManagedTimer.DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS
                    );
                    int alarmVolume = intent.getIntExtra(EXTRA_ALARM_VOLUME, ManagedTimer.DEFAULT_ALARM_VOLUME);
                    boolean startImmediately = intent.getBooleanExtra(EXTRA_START_IMMEDIATELY, true);
                    if (name != null && !name.trim().isEmpty() && durationMillis > 0L) {
                        addTimer(name.trim(), durationMillis, startImmediately, announcementIntervalMillis, alarmVolume);
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
                    if (timerId > 0L && name != null && !name.trim().isEmpty() && durationMillis > 0L) {
                        replaceTimer(timerId, name.trim(), durationMillis, announcementIntervalMillis, alarmVolume);
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

    /**
     * Initialisiert die TTS-Engine und waehlt bevorzugt Deutsch als Sprache.
        *
        * @param status Initialisierungsstatus der TTS-Engine
     */
    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS && textToSpeech != null;
        if (!ttsReady) {
            return;
        }

        int germanResult = textToSpeech.setLanguage(Locale.GERMAN);
        if (germanResult != TextToSpeech.LANG_MISSING_DATA && germanResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return;
        }

        int fallbackResult = textToSpeech.setLanguage(Locale.getDefault());
        if (fallbackResult != TextToSpeech.LANG_MISSING_DATA && fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return;
        }

        // Keep TTS enabled even if language selection failed; some engines still speak with their own default voice.
        ttsReady = true;
        maybeStartNextAnnouncement();
    }

    /**
     * Legt einen Timer an und persisted den Zustand.
     *
     * @param name Anzeigename des Timers
     * @param durationMillis Dauer des Timers in Millisekunden
     * @param startImmediately {@code true} startet sofort, sonst Status "bereit"
     */
    private void addTimer(String name, long durationMillis, boolean startImmediately, long announcementIntervalMillis) {
        addTimer(name, durationMillis, startImmediately, announcementIntervalMillis, ManagedTimer.DEFAULT_ALARM_VOLUME);
    }

    private void addTimer(String name, long durationMillis, boolean startImmediately, long announcementIntervalMillis, int alarmVolume) {
        try {
            long id = NEXT_ID.getAndIncrement();
            long now = System.currentTimeMillis();
            long endsAt = startImmediately ? now + durationMillis : now;
            ManagedTimer timer = new ManagedTimer(
                    id,
                    name,
                    durationMillis,
                    endsAt,
                    startImmediately,
                    Math.max(0L, announcementIntervalMillis),
                    Math.max(0, Math.min(100, alarmVolume))
            );
            synchronized (TIMERS) {
                TIMERS.put(id, timer);
            }
            try {
                persistTimers();
            } catch (Exception persistError) {
                Log.w(TAG, "Failed to persist timers after add", persistError);
            }
            if (startImmediately) {
                try {
                    showTimerNotification(timer, false, durationMillis, false);
                } catch (Exception notifyError) {
                    Log.w(TAG, "Failed to show notification after add", notifyError);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to add timer", e);
        }
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
            nextAnnouncementAt.remove(id);
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
        nextAnnouncementAt.remove(timerId);
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
    private void replaceTimer(long timerId, String name, long durationMillis, long announcementIntervalMillis) {
        replaceTimer(timerId, name, durationMillis, announcementIntervalMillis, ManagedTimer.DEFAULT_ALARM_VOLUME);
    }

    private void replaceTimer(long timerId, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume) {
        synchronized (TIMERS) {
            TIMERS.remove(timerId);
        }
        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);
        boolean stopActiveAnnouncement = removeQueuedAnnouncement(timerId);
        persistTimers();
        if (stopActiveAnnouncement && textToSpeech != null) {
            try {
                textToSpeech.stop();
            } catch (Exception stopError) {
                Log.w(TAG, "Failed to stop TTS while replacing timer", stopError);
            }
        }
        addTimer(name, durationMillis, false, announcementIntervalMillis, alarmVolume);
    }

    /**
     * Markiert die Abschlussmeldung als bestaetigt und stoppt Wiederholungsansagen.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void dismissTimerNotification(long timerId) {
        boolean updated = false;
        boolean stopActiveAnnouncement;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && timer.isTerminal() && !timer.isNotificationDismissed()) {
                timer.markNotificationDismissed();
                updated = true;
            }
        }

        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);
        stopActiveAnnouncement = removeQueuedAnnouncement(timerId);

        if (updated) {
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
    }

    /**
     * Bricht einen laufenden Timer ab und entfernt seine aktive Notification.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void cancelTimer(long timerId) {
        boolean updated = false;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && !timer.isTerminal()) {
                timer.markCancelled();
                updated = true;
            }
        }

        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);

        if (updated) {
            persistTimers();
        }

        tick();
    }

    /**
     * Startet einen vorhandenen Timer neu mit seiner hinterlegten Dauer.
     *
     * @param timerId eindeutige Timer-ID
     */
    private void restartTimer(long timerId) {
        boolean updated = false;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null) {
                timer.markStarted(System.currentTimeMillis());
                updated = true;
            }
        }

        if (updated) {
            persistTimers();
        }

        nextAnnouncementAt.remove(timerId);

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
                    timer.markCompleted();
                    changed = true;
                    acquireWakeLockBriefly(); // Wakelock beim Timer-Ende
                }
                currentTimers.add(new ManagedTimer(timer));
                if (timer.isRunning(now)) {
                    runningTimers.add(new ManagedTimer(timer));
                }
                if (timer.isCompleted() && !timer.isNotificationDismissed()) {
                    completedWithActiveAnnouncement.add(new ManagedTimer(timer));
                }
            }
        }

        updateForegroundNotification(runningTimers, completedWithActiveAnnouncement, now);

        for (ManagedTimer timer : currentTimers) {
            boolean completed = timer.isCompleted();
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
                if (timer.isRunning(now)) {
                    hasRunningTimers = true;
                    break;
                }
                if (timer.isCompleted() && !timer.isNotificationDismissed()) {
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

    /**
     * Setzt die Foreground-Notification auf Basis des naechsten laufenden Timers.
     *
     * @param runningTimers aktuell laufende Timer
     * @param now aktuelle Zeit in Millisekunden
     */
    private void updateForegroundNotification(
            List<ManagedTimer> runningTimers,
            List<ManagedTimer> completedWithActiveAnnouncement,
            long now
    ) {
        if (runningTimers.isEmpty() && completedWithActiveAnnouncement.isEmpty()) {
            return;
        }

        try {
            ManagedTimer foregroundTimer;
            boolean completedForForeground;
            long remainingMillis;

            if (!runningTimers.isEmpty()) {
                foregroundTimer = runningTimers.get(0);
                completedForForeground = false;
                remainingMillis = foregroundTimer.getRemainingMillis(now);
            } else {
                foregroundTimer = completedWithActiveAnnouncement.get(0);
                completedForForeground = true;
                remainingMillis = 0L;
            }

            NotificationCompat.Builder builder = buildTimerNotification(
                    foregroundTimer,
                    completedForForeground,
                    remainingMillis
            );
            
            try {
                ServiceCompat.startForeground(
                        this,
                        (int) foregroundTimer.getId(),
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
            NotificationCompat.Builder builder = buildTimerNotification(timer, completed, remainingMillis);

            if (!skipNotify) {
                try {
                    if (notificationManager != null) {
                        notificationManager.notify((int) timer.getId(), builder.build());
                    }
                } catch (Exception notifyError) {
                    Log.e(TAG, "Unable to post timer notification", notifyError);
                    // Try fallback: attempt to post anyway without security checks
                    try {
                        if (notificationManager != null) {
                            notificationManager.notify((int) timer.getId(), builder.build());
                        }
                    } catch (Exception fallbackNotify) {
                        Log.e(TAG, "Fallback notification post also failed", fallbackNotify);
                    }
                }
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
    private NotificationCompat.Builder buildTimerNotification(ManagedTimer timer, boolean completed, long remainingMillis) {
        String channelId = completed ? CHANNEL_FINISHED : CHANNEL_RUNNING;
        String contentText = completed
                ? getString(R.string.timer_notification_completed)
                : getString(R.string.timer_notification_remaining, TimerFormatter.formatDuration(remainingMillis));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(timer.getName())
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(!completed)
                .setAutoCancel(completed)
                .setOnlyAlertOnce(!completed)
                .setContentIntent(buildMainPendingIntent());

        if (completed) {
            // DO NOT set silent - we want the alarm to be heard
            // .setSilent(true);  <- REMOVED to allow sound
            
            // Enable vibration and sound for completion
            builder.setVibrate(COMPLETION_VIBRATION_PATTERN);
            
            // Set sound via notification (bypasses mute for USAGE_ALARM)
            android.net.Uri soundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
            builder.setSound(soundUri, android.media.AudioManager.STREAM_ALARM);
            
            builder.setDeleteIntent(buildDismissNotificationPendingIntent(timer.getId(), (int) timer.getId()));
            builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.timer_notification_dismiss_action),
                    buildDismissNotificationPendingIntent(timer.getId(), 200000 + (int) timer.getId())
            );
        }

        return builder;
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

    /**
     * Spricht den Abschluss eines Timers ueber TTS aus.
        *
        * @param timerName Name des abgeschlossenen Timers
     */
    private boolean announceCompletion(String timerName) {
        return announceCompletion(-1L, timerName);
    }

    private boolean announceCompletion(long timerId, String timerName) {
        if (!ttsReady || textToSpeech == null) {
            return false;
        }

        try {
            acquireWakeLockBriefly(); // Ensure device stays awake during announcement
            triggerCompletionVibration();

            String normalizedName = timerName == null ? "" : timerName.trim();
            if (normalizedName.isEmpty()) {
                normalizedName = getString(R.string.app_name);
            }

            long now = System.currentTimeMillis();
            String utteranceSuffix = timerId > 0L ? timerId + "-" + now : String.valueOf(now);
            String completionUtteranceId = "timer-done-" + utteranceSuffix;
            int speakNameResult = textToSpeech.speak(
                    normalizedName,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "timer-name-" + utteranceSuffix
            );
            int pauseResult = textToSpeech.playSilentUtterance(
                    15L,
                    TextToSpeech.QUEUE_ADD,
                    "timer-pause-" + utteranceSuffix
            );
            int speakDoneResult = textToSpeech.speak(
                    getString(R.string.tts_completed),
                    TextToSpeech.QUEUE_ADD,
                    null,
                    completionUtteranceId
            );

            if (speakNameResult == TextToSpeech.ERROR
                    || pauseResult == TextToSpeech.ERROR
                    || speakDoneResult == TextToSpeech.ERROR) {
                return false;
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

            if (current == null || !current.isCompleted() || current.isNotificationDismissed()) {
                nextAnnouncementAt.remove(timerId);
                continue;
            }

            long interval = current.getAnnouncementIntervalMillis();

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
                if (timer == null || !timer.isCompleted() || timer.isNotificationDismissed()) {
                    continue;
                }
            }

            activeAnnouncementTimerId = timerId;
            
            // Set the alarm volume for this timer before announcing
            setAlarmVolume(timer.getAlarmVolume());
            
            if (announceCompletion(timerId, timer.getName())) {
                long now = System.currentTimeMillis();
                boolean persistRequired = false;

                synchronized (TIMERS) {
                    ManagedTimer live = TIMERS.get(timerId);
                    if (live != null && live.isCompleted() && !live.isNotificationDismissed()) {
                        if (!live.isCompletionAnnounced()) {
                            live.markCompletionAnnounced();
                            persistRequired = true;
                        }

                        if (live.getAnnouncementIntervalMillis() > 0L) {
                            nextAnnouncementAt.put(timerId, now + live.getAnnouncementIntervalMillis());
                        } else {
                            nextAnnouncementAt.remove(timerId);
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

        activeAnnouncementTimerId = -1L;
        activeCompletionUtteranceId = null;
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
        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                timersToPersist.add(new ManagedTimer(timer));
            }
        }
        TimerPersistence.save(getApplicationContext(), timersToPersist);
    }
}