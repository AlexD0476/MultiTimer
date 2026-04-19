package com.example.multitimer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

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

    private static final String CHANNEL_RUNNING = "multitimer_running";
    private static final String CHANNEL_FINISHED = "multitimer_finished_silent";
    private static final long STOP_DELAY_MILLIS = 4000L;
    private static final long ANNOUNCEMENT_REPEAT_MILLIS = 5000L;
    private static final Map<Long, ManagedTimer> TIMERS = new LinkedHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;
    private final Runnable delayedStopRunnable = this::stopIfIdle;
    private final Map<Long, Long> nextAnnouncementAt = new LinkedHashMap<>();
    private NotificationManager notificationManager;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;

    public static void enqueueStartTimer(Context context, String name, long durationMillis) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_ADD_TIMER);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void enqueueClearCompleted(Context context) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_CLEAR_COMPLETED);
        context.startService(intent);
    }

    public static void enqueueCancelTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_CANCEL_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueDismissTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_DISMISS_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueDismissNotification(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_DISMISS_NOTIFICATION);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        context.startService(intent);
    }

    public static void enqueueReplaceTimer(Context context, long timerId, String name, long durationMillis) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_REPLACE_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_DURATION_MILLIS, durationMillis);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void enqueueRestartTimer(Context context, long timerId) {
        ensureTimersLoaded(context);
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(ACTION_RESTART_TIMER);
        intent.putExtra(EXTRA_TIMER_ID, timerId);
        ContextCompat.startForegroundService(context, intent);
    }

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

    public static void ensureServiceRunningForActiveTimers(Context context) {
        ensureTimersLoaded(context);
        long now = System.currentTimeMillis();
        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.isRunning(now)) {
                    Intent intent = new Intent(context, TimerService.class);
                    intent.setAction(ACTION_RESYNC);
                    ContextCompat.startForegroundService(context, intent);
                    return;
                }
            }
        }
    }

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
            ensureTimersLoaded(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load timers", e);
        }

        try {
            textToSpeech = new TextToSpeech(getApplicationContext(), this);
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
                String action = intent.getAction();
                if (ACTION_ADD_TIMER.equals(action)) {
                    String name = intent.getStringExtra(EXTRA_NAME);
                    long durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L);
                    if (name != null && !name.trim().isEmpty() && durationMillis > 0L) {
                        addTimer(name.trim(), durationMillis);
                    }
                } else if (ACTION_CANCEL_TIMER.equals(action)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        cancelTimer(timerId);
                    }
                } else if (ACTION_CLEAR_COMPLETED.equals(action)) {
                    clearCompletedTimers();
                } else if (ACTION_DISMISS_NOTIFICATION.equals(action)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        dismissTimerNotification(timerId);
                    }
                } else if (ACTION_DISMISS_TIMER.equals(action)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    if (timerId > 0L) {
                        dismissCompletedTimer(timerId);
                    }
                } else if (ACTION_REPLACE_TIMER.equals(action)) {
                    long timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L);
                    String name = intent.getStringExtra(EXTRA_NAME);
                    long durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, 0L);
                    if (timerId > 0L && name != null && !name.trim().isEmpty() && durationMillis > 0L) {
                        replaceTimer(timerId, name.trim(), durationMillis);
                    }
                } else if (ACTION_RESTART_TIMER.equals(action)) {
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
            if (textToSpeech != null) {
                textToSpeech.stop();
                textToSpeech.shutdown();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error shutting down TextToSpeech", e);
        }

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
    }

    private void addTimer(String name, long durationMillis) {
        try {
            long id = NEXT_ID.getAndIncrement();
            long endsAt = System.currentTimeMillis() + durationMillis;
            ManagedTimer timer = new ManagedTimer(id, name, durationMillis, endsAt);
            synchronized (TIMERS) {
                TIMERS.put(id, timer);
            }
            try {
                persistTimers();
            } catch (Exception persistError) {
                Log.w(TAG, "Failed to persist timers after add", persistError);
            }
            try {
                showTimerNotification(timer, false, durationMillis, false);
            } catch (Exception notifyError) {
                Log.w(TAG, "Failed to show notification after add", notifyError);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to add timer", e);
        }
    }

    private void clearCompletedTimers() {
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
        }
        persistTimers();
    }

    private void dismissCompletedTimer(long timerId) {
        boolean removed = false;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && timer.isTerminal()) {
                TIMERS.remove(timerId);
                removed = true;
            }
        }

        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);

        if (removed) {
            persistTimers();
        }

        tick();
    }

    private void replaceTimer(long timerId, String name, long durationMillis) {
        synchronized (TIMERS) {
            TIMERS.remove(timerId);
        }
        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);
        persistTimers();
        addTimer(name, durationMillis);
    }

    private void dismissTimerNotification(long timerId) {
        boolean updated = false;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null && timer.isTerminal() && !timer.isNotificationDismissed()) {
                timer.markNotificationDismissed();
                updated = true;
            }
        }

        notificationManager.cancel((int) timerId);
        nextAnnouncementAt.remove(timerId);

        if (updated) {
            persistTimers();
        }
    }

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

    private void restartTimer(long timerId) {
        boolean updated = false;
        synchronized (TIMERS) {
            ManagedTimer timer = TIMERS.get(timerId);
            if (timer != null) {
                long endsAt = System.currentTimeMillis() + timer.getDurationMillis();
                TIMERS.put(timerId, new ManagedTimer(timerId, timer.getName(), timer.getDurationMillis(), endsAt));
                updated = true;
            }
        }

        if (updated) {
            persistTimers();
        }

        nextAnnouncementAt.remove(timerId);

        tick();
    }

    private void tick() {
        handler.removeCallbacks(tickRunnable);
        long now = System.currentTimeMillis();
        List<ManagedTimer> justFinished = new ArrayList<>();
        List<ManagedTimer> currentTimers = new ArrayList<>();
        List<ManagedTimer> runningTimers = new ArrayList<>();
        List<ManagedTimer> completedWithActiveAnnouncement = new ArrayList<>();
        boolean changed = false;

        synchronized (TIMERS) {
            for (ManagedTimer timer : TIMERS.values()) {
                if (timer.shouldComplete(now)) {
                    timer.markCompleted();
                    justFinished.add(new ManagedTimer(timer));
                    changed = true;
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

        if (changed) {
            persistTimers();
        }

        updateForegroundNotification(runningTimers, now);

        for (ManagedTimer timer : currentTimers) {
            boolean completed = timer.isCompleted();
            long remainingMillis = completed ? 0L : timer.getRemainingMillis(now);
            if (!timer.isCancelled() && !(timer.isTerminal() && timer.isNotificationDismissed())) {
                showTimerNotification(timer, completed, remainingMillis, false);
            }
        }

        handleCompletionAnnouncements(now, justFinished, completedWithActiveAnnouncement);

        if (!runningTimers.isEmpty() || !completedWithActiveAnnouncement.isEmpty()) {
            handler.postDelayed(tickRunnable, 1000L);
        } else {
            handler.postDelayed(delayedStopRunnable, STOP_DELAY_MILLIS);
        }
    }

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
                NotificationManager.IMPORTANCE_LOW
            );
            finishedChannel.setDescription(getString(R.string.channel_finished_description));
            finishedChannel.enableVibration(false);
            finishedChannel.setSound(null, null);

            if (notificationManager != null) {
                notificationManager.createNotificationChannels(List.of(runningChannel, finishedChannel));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification channels", e);
        }
    }

    private void updateForegroundNotification(List<ManagedTimer> runningTimers, long now) {
        if (runningTimers.isEmpty()) {
            return;
        }

        try {
            ManagedTimer foregroundTimer = runningTimers.get(0);
            long remainingMillis = foregroundTimer.getRemainingMillis(now);
            NotificationCompat.Builder builder = buildTimerNotification(foregroundTimer, false, remainingMillis);
            
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
            builder.setSilent(true);
            builder.setDeleteIntent(buildDismissNotificationPendingIntent(timer.getId(), (int) timer.getId()));
            builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.timer_notification_dismiss_action),
                    buildDismissNotificationPendingIntent(timer.getId(), 200000 + (int) timer.getId())
            );
        }

        return builder;
    }

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

    private void announceCompletion(String timerName) {
        if (!ttsReady || textToSpeech == null) {
            return;
        }

        try {
            String normalizedName = timerName == null ? "" : timerName.trim();
            if (normalizedName.isEmpty()) {
                normalizedName = getString(R.string.app_name);
            }

            String utterance = normalizedName + " " + getString(R.string.tts_completed);
            String utteranceId = "timer-" + System.currentTimeMillis();
            textToSpeech.speak(utterance, TextToSpeech.QUEUE_ADD, null, utteranceId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to announce completion", e);
            ttsReady = false;
        }
    }

    private void handleCompletionAnnouncements(long now, List<ManagedTimer> justFinished, List<ManagedTimer> announcementCandidates) {
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

        Set<Long> justFinishedIds = new HashSet<>();
        for (ManagedTimer timer : justFinished) {
            justFinishedIds.add(timer.getId());
        }

        for (ManagedTimer timer : announcementCandidates) {
            long timerId = timer.getId();
            if (justFinishedIds.contains(timerId)) {
                announceCompletion(timer.getName());
                nextAnnouncementAt.put(timerId, now + ANNOUNCEMENT_REPEAT_MILLIS);
                continue;
            }

            Long nextAt = nextAnnouncementAt.get(timerId);
            if (nextAt == null) {
                nextAnnouncementAt.put(timerId, now + ANNOUNCEMENT_REPEAT_MILLIS);
                continue;
            }

            if (now >= nextAt) {
                announceCompletion(timer.getName());
                nextAnnouncementAt.put(timerId, now + ANNOUNCEMENT_REPEAT_MILLIS);
            }
        }
    }

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