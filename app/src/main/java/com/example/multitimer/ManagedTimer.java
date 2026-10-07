package com.example.multitimer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Laufzeitdaten eines einzelnen Timer-Starts.
 *
 * <p>Das Objekt beschreibt Konfiguration (Name, Dauer) und Laufzeitstatus
 * (gestartet, fertig, abgebrochen, Notification bestaetigt).</p>
 */
final class ManagedTimer {
    static final long DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS = 5000L;
    static final int DEFAULT_ALARM_VOLUME = 100; // 0-100 scale

    private final long id;
    private final long sourceSavedTimerId;
    private final String name;
    private long durationMillis;
    private final long announcementIntervalMillis;
    private final int alarmVolume; // 0-100 scale
    private String completionText;
    private final String finalCompletionText;
    private final TimerType timerType;
    private final int repeatCount;
    private final boolean waitForIntervalConfirmation;
    private int completedCycles;
    private int currentStepIndex;
    private final List<TimerStep> steps;
    private boolean phaseAwaitingAnnouncement;
    private boolean phaseAnnouncementMuted;
    private long startedAtMillis;
    private long endTimeMillis;
    private boolean started;
    private boolean completed;
    private boolean cancelled;
    private boolean notificationDismissed;
    private boolean completionAnnounced;

    ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                 long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText) {
        this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
            announcementIntervalMillis, alarmVolume, completionText, TimerType.STANDARD, 0, 1, 0,
            Collections.emptyList(), false);
        }

        ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
             long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
             TimerType timerType, int repeatCount, int completedCycles, int currentStepIndex,
             List<TimerStep> steps, boolean phaseAwaitingAnnouncement) {
            this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
                announcementIntervalMillis, alarmVolume, completionText, timerType, repeatCount,
                false, completedCycles, currentStepIndex, steps, phaseAwaitingAnnouncement);
            }

            ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                 long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
                 TimerType timerType, int repeatCount, boolean waitForIntervalConfirmation,
                 int completedCycles, int currentStepIndex, List<TimerStep> steps,
                 boolean phaseAwaitingAnnouncement) {
                this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
                    announcementIntervalMillis, alarmVolume, completionText, timerType, repeatCount,
                    waitForIntervalConfirmation, completedCycles, currentStepIndex, steps,
                    phaseAwaitingAnnouncement, false);
                }

                ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                     long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
                     TimerType timerType, int repeatCount, boolean waitForIntervalConfirmation,
                     int completedCycles, int currentStepIndex, List<TimerStep> steps,
                     boolean phaseAwaitingAnnouncement, boolean phaseAnnouncementMuted) {
        this.id = id;
        this.sourceSavedTimerId = sourceSavedTimerId;
        this.name = name;
        this.durationMillis = durationMillis;
        this.endTimeMillis = endTimeMillis;
        this.startedAtMillis = startedAtMillis;
        this.started = true;
        this.announcementIntervalMillis = Math.max(0L, announcementIntervalMillis);
        this.alarmVolume = Math.max(0, Math.min(100, alarmVolume));
        this.completionText = completionText == null ? "" : completionText.trim();
        this.finalCompletionText = this.completionText;
        this.timerType = timerType == null ? TimerType.STANDARD : timerType;
        this.repeatCount = Math.max(0, repeatCount);
        this.waitForIntervalConfirmation = waitForIntervalConfirmation;
        this.completedCycles = Math.max(1, completedCycles);
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps == null ? Collections.emptyList() : steps));
        this.currentStepIndex = this.steps.isEmpty() ? 0 : Math.max(0, Math.min(currentStepIndex, this.steps.size() - 1));
        this.phaseAwaitingAnnouncement = phaseAwaitingAnnouncement;
        this.phaseAnnouncementMuted = phaseAnnouncementMuted;
        applyCurrentStep();
    }

    ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                 long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
                 boolean completed, boolean cancelled, boolean notificationDismissed, boolean completionAnnounced,
                 TimerType timerType, int repeatCount, int completedCycles, int currentStepIndex,
                 List<TimerStep> steps, boolean phaseAwaitingAnnouncement) {
            this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
                announcementIntervalMillis, alarmVolume, completionText, completed, cancelled,
                notificationDismissed, completionAnnounced, timerType, repeatCount, false,
                completedCycles, currentStepIndex, steps, phaseAwaitingAnnouncement);
            }

            ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                 long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
                 boolean completed, boolean cancelled, boolean notificationDismissed, boolean completionAnnounced,
                 TimerType timerType, int repeatCount, boolean waitForIntervalConfirmation,
                 int completedCycles, int currentStepIndex, List<TimerStep> steps,
                 boolean phaseAwaitingAnnouncement) {
                this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
                    announcementIntervalMillis, alarmVolume, completionText, completed, cancelled,
                    notificationDismissed, completionAnnounced, timerType, repeatCount,
                    waitForIntervalConfirmation, completedCycles, currentStepIndex, steps,
                    phaseAwaitingAnnouncement, false);
                }

                ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                     long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
                     boolean completed, boolean cancelled, boolean notificationDismissed, boolean completionAnnounced,
                     TimerType timerType, int repeatCount, boolean waitForIntervalConfirmation,
                     int completedCycles, int currentStepIndex, List<TimerStep> steps,
                     boolean phaseAwaitingAnnouncement, boolean phaseAnnouncementMuted) {
        this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
                announcementIntervalMillis, alarmVolume, completionText, timerType, repeatCount,
                waitForIntervalConfirmation,
                    completedCycles, currentStepIndex, steps, phaseAwaitingAnnouncement, phaseAnnouncementMuted);
        this.completed = completed;
        this.cancelled = cancelled;
        this.notificationDismissed = notificationDismissed;
        this.completionAnnounced = completionAnnounced;
    }

    ManagedTimer(long id, long sourceSavedTimerId, String name, long durationMillis, long startedAtMillis,
                 long endTimeMillis, long announcementIntervalMillis, int alarmVolume, String completionText,
                 boolean completed, boolean cancelled, boolean notificationDismissed, boolean completionAnnounced) {
        this(id, sourceSavedTimerId, name, durationMillis, startedAtMillis, endTimeMillis,
                announcementIntervalMillis, alarmVolume, completionText);
        this.completed = completed;
        this.cancelled = cancelled;
        this.notificationDismissed = notificationDismissed;
        this.completionAnnounced = completionAnnounced;
    }

    ManagedTimer(ManagedTimer other) {
        this.id = other.id;
        this.sourceSavedTimerId = other.sourceSavedTimerId;
        this.name = other.name;
        this.durationMillis = other.durationMillis;
        this.announcementIntervalMillis = other.announcementIntervalMillis;
        this.alarmVolume = other.alarmVolume;
        this.completionText = other.completionText;
        this.finalCompletionText = other.finalCompletionText;
        this.timerType = other.timerType;
        this.repeatCount = other.repeatCount;
        this.waitForIntervalConfirmation = other.waitForIntervalConfirmation;
        this.completedCycles = other.completedCycles;
        this.currentStepIndex = other.currentStepIndex;
        this.steps = other.steps;
        this.phaseAwaitingAnnouncement = other.phaseAwaitingAnnouncement;
        this.phaseAnnouncementMuted = other.phaseAnnouncementMuted;
        this.startedAtMillis = other.startedAtMillis;
        this.endTimeMillis = other.endTimeMillis;
        this.started = other.started;
        this.completed = other.completed;
        this.cancelled = other.cancelled;
        this.notificationDismissed = other.notificationDismissed;
        this.completionAnnounced = other.completionAnnounced;
    }

    long getId() {
        return id;
    }

    long getSourceSavedTimerId() {
        return sourceSavedTimerId;
    }

    String getName() {
        return name;
    }

    long getDurationMillis() {
        return durationMillis;
    }

    long getAnnouncementIntervalMillis() {
        return announcementIntervalMillis;
    }

    int getAlarmVolume() {
        return alarmVolume;
    }

    String getCompletionText() {
        if (timerType == TimerType.GROUP && completed) {
            return finalCompletionText;
        }
        if (timerType == TimerType.GROUP && !steps.isEmpty()) {
            return steps.get(currentStepIndex).getCompletionText();
        }
        return completionText;
    }

    String getFinalCompletionText() {
        return finalCompletionText;
    }

    TimerType getTimerType() {
        return timerType;
    }

    int getRepeatCount() {
        return repeatCount;
    }

    boolean waitsForIntervalConfirmation() {
        return waitForIntervalConfirmation;
    }

    boolean requiresPhaseConfirmation() {
        return phaseAwaitingAnnouncement && (timerType == TimerType.GROUP
                || (timerType == TimerType.INTERVAL && waitForIntervalConfirmation));
    }

    boolean repeatsCompletionAnnouncement() {
        return timerType != TimerType.INTERVAL || waitForIntervalConfirmation;
    }

    int getCompletedCycles() {
        return completedCycles;
    }

    int getCurrentStepIndex() {
        return currentStepIndex;
    }

    List<TimerStep> getSteps() {
        return steps;
    }

    boolean isPhaseAwaitingAnnouncement() {
        return phaseAwaitingAnnouncement;
    }

    boolean isPhaseAnnouncementMuted() {
        return phaseAnnouncementMuted;
    }

    void mutePhaseAnnouncement() {
        if (requiresPhaseConfirmation()) {
            phaseAnnouncementMuted = true;
        }
    }

    String getAnnouncementName() {
        if (timerType == TimerType.GROUP && !steps.isEmpty()) {
            return steps.get(currentStepIndex).getName();
        }
        return name;
    }

    String getProgressLabel() {
        if (timerType == TimerType.GROUP && !steps.isEmpty()) {
            return (currentStepIndex + 1) + "/" + steps.size();
        }
        return "";
    }

    long getEndTimeMillis() {
        return endTimeMillis;
    }

    long getStartedAtMillis() {
        return startedAtMillis;
    }

    boolean isStarted() {
        return started;
    }

    boolean isCompleted() {
        return completed;
    }

    boolean isCancelled() {
        return cancelled;
    }

    boolean isTerminal() {
        return completed || cancelled;
    }

    boolean isNotificationDismissed() {
        return notificationDismissed;
    }

    boolean isCompletionAnnounced() {
        return completionAnnounced;
    }

    /**
     * @param now aktuelle Zeit in Millisekunden
     * @return {@code true}, wenn der Timer aktiv herunterzaehlt
     */
    boolean isRunning(long now) {
        return started && !isTerminal() && getRemainingMillis(now) > 0L;
    }

    /**
     * Berechnet die verbleibende Laufzeit.
     *
     * @param now aktuelle Zeit in Millisekunden
     * @return verbleibende Zeit in Millisekunden, niemals negativ
     */
    long getRemainingMillis(long now) {
        if (isTerminal()) {
            return 0L;
        }
        if (!started) {
            return durationMillis;
        }
        return Math.max(0L, endTimeMillis - now);
    }

    /**
     * @param now aktuelle Zeit in Millisekunden
     * @return {@code true}, wenn der Timer auf "fertig" wechseln soll
     */
    boolean shouldComplete(long now) {
        return started && !isTerminal() && !phaseAwaitingAnnouncement && endTimeMillis <= now;
    }

    boolean hasNextPhaseAfterCompletion() {
        if (timerType == TimerType.INTERVAL) {
            return repeatCount == 0 || completedCycles < repeatCount;
        }
        return timerType == TimerType.GROUP;
    }

    boolean hasNextGroupStep() {
        return timerType == TimerType.GROUP && currentStepIndex + 1 < steps.size();
    }

    void markPhaseAwaitingAnnouncement() {
        phaseAwaitingAnnouncement = true;
        phaseAnnouncementMuted = false;
        completionAnnounced = false;
    }

    boolean advanceAfterAnnouncement(long now) {
        if (!phaseAwaitingAnnouncement) {
            return false;
        }
        if (timerType == TimerType.GROUP && !hasNextGroupStep()) {
            markCompleted();
            return false;
        }
        phaseAwaitingAnnouncement = false;
        phaseAnnouncementMuted = false;
        completionAnnounced = false;
        startedAtMillis = now;
        if (timerType == TimerType.INTERVAL) {
            completedCycles++;
        } else if (timerType == TimerType.GROUP) {
            currentStepIndex++;
            applyCurrentStep();
        }
        endTimeMillis = now + durationMillis;
        return true;
    }

    private void applyCurrentStep() {
        if (timerType == TimerType.GROUP && !steps.isEmpty()) {
            TimerStep step = steps.get(currentStepIndex);
            durationMillis = step.getDurationMillis();
            completionText = step.getCompletionText();
        }
    }

    void markCompleted() {
        completed = true;
        cancelled = false;
        started = true;
        phaseAwaitingAnnouncement = false;
        phaseAnnouncementMuted = false;
        notificationDismissed = false;
        completionAnnounced = false;
    }

    void markCancelled() {
        cancelled = true;
        completed = false;
        started = true;
        phaseAwaitingAnnouncement = false;
        phaseAnnouncementMuted = false;
        notificationDismissed = false;
        completionAnnounced = false;
    }

    void markStarted(long now) {
        started = true;
        startedAtMillis = now;
        completed = false;
        cancelled = false;
        phaseAwaitingAnnouncement = false;
        phaseAnnouncementMuted = false;
        notificationDismissed = false;
        completionAnnounced = false;
        endTimeMillis = now + durationMillis;
    }

    void markNotificationDismissed() {
        notificationDismissed = true;
    }

    void markCompletionAnnounced() {
        completionAnnounced = true;
    }
}