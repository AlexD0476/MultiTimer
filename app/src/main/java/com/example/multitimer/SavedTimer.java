package com.example.multitimer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistente Timer-Vorlage, aus der unabhaengige Lauf-Instanzen gestartet werden. */
final class SavedTimer {
    private final long id;
    private final String name;
    private final long durationMillis;
    private final long announcementIntervalMillis;
    private final int alarmVolume;
    private final String completionText;
    private final TimerType timerType;
    private final int repeatCount;
    private final boolean waitForIntervalConfirmation;
    private final List<TimerStep> steps;
    private final long createdAtMillis;
    private final long lastStartedAtMillis;
    private final int usageCount;

    SavedTimer(long id, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume,
               String completionText, long createdAtMillis, long lastStartedAtMillis, int usageCount) {
        this(id, name, durationMillis, announcementIntervalMillis, alarmVolume, completionText,
            createdAtMillis, lastStartedAtMillis, usageCount, TimerType.STANDARD, 0, false, Collections.emptyList());
        }

        SavedTimer(long id, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume,
               String completionText, long createdAtMillis, long lastStartedAtMillis, int usageCount,
               TimerType timerType, int repeatCount, List<TimerStep> steps) {
            this(id, name, durationMillis, announcementIntervalMillis, alarmVolume, completionText,
                createdAtMillis, lastStartedAtMillis, usageCount, timerType, repeatCount, false, steps);
            }

            SavedTimer(long id, String name, long durationMillis, long announcementIntervalMillis, int alarmVolume,
                   String completionText, long createdAtMillis, long lastStartedAtMillis, int usageCount,
                   TimerType timerType, int repeatCount, boolean waitForIntervalConfirmation, List<TimerStep> steps) {
        this.id = id;
        this.name = name;
        this.durationMillis = durationMillis;
        this.announcementIntervalMillis = Math.max(0L, announcementIntervalMillis);
        this.alarmVolume = Math.max(0, Math.min(100, alarmVolume));
        this.completionText = completionText == null ? "" : completionText.trim();
        this.timerType = timerType == null ? TimerType.STANDARD : timerType;
        this.repeatCount = Math.max(0, repeatCount);
        this.waitForIntervalConfirmation = waitForIntervalConfirmation;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps == null ? Collections.emptyList() : steps));
        this.createdAtMillis = createdAtMillis;
        this.lastStartedAtMillis = lastStartedAtMillis;
        this.usageCount = Math.max(0, usageCount);
    }

    SavedTimer recordStarted(long startedAtMillis) {
        return new SavedTimer(id, name, durationMillis, announcementIntervalMillis, alarmVolume, completionText,
                createdAtMillis, startedAtMillis, usageCount + 1, timerType, repeatCount,
                waitForIntervalConfirmation, steps);
    }

    SavedTimer withConfiguration(String name, long durationMillis, long announcementIntervalMillis,
                                 int alarmVolume, String completionText) {
        return new SavedTimer(id, name, durationMillis, announcementIntervalMillis, alarmVolume, completionText,
                createdAtMillis, lastStartedAtMillis, usageCount, timerType, repeatCount,
                waitForIntervalConfirmation, steps);
        }

        SavedTimer withConfiguration(String name, long durationMillis, long announcementIntervalMillis,
                     int alarmVolume, String completionText, TimerType timerType,
                     int repeatCount, List<TimerStep> steps) {
            return withConfiguration(name, durationMillis, announcementIntervalMillis, alarmVolume,
                completionText, timerType, repeatCount, waitForIntervalConfirmation, steps);
            }

            SavedTimer withConfiguration(String name, long durationMillis, long announcementIntervalMillis,
                         int alarmVolume, String completionText, TimerType timerType,
                         int repeatCount, boolean waitForIntervalConfirmation, List<TimerStep> steps) {
        return new SavedTimer(id, name, durationMillis, announcementIntervalMillis, alarmVolume, completionText,
                createdAtMillis, lastStartedAtMillis, usageCount, timerType, repeatCount,
                waitForIntervalConfirmation, steps);
    }

    long getId() {
        return id;
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
        return completionText;
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

    List<TimerStep> getSteps() {
        return steps;
    }

    long getCreatedAtMillis() {
        return createdAtMillis;
    }

    long getLastStartedAtMillis() {
        return lastStartedAtMillis;
    }

    int getUsageCount() {
        return usageCount;
    }
}