package com.example.multitimer;

final class ManagedTimer {
    private final long id;
    private final String name;
    private final long durationMillis;
    private long endTimeMillis;
    private boolean started;
    private boolean completed;
    private boolean cancelled;
    private boolean notificationDismissed;

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis) {
        this.id = id;
        this.name = name;
        this.durationMillis = durationMillis;
        this.endTimeMillis = endTimeMillis;
        this.started = true;
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean started) {
        this.id = id;
        this.name = name;
        this.durationMillis = durationMillis;
        this.endTimeMillis = endTimeMillis;
        this.started = started;
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean started, boolean completed, boolean cancelled, boolean notificationDismissed) {
        this(id, name, durationMillis, endTimeMillis, started);
        this.completed = completed;
        this.cancelled = cancelled;
        this.notificationDismissed = notificationDismissed;
    }

    ManagedTimer(ManagedTimer other) {
        this.id = other.id;
        this.name = other.name;
        this.durationMillis = other.durationMillis;
        this.endTimeMillis = other.endTimeMillis;
        this.started = other.started;
        this.completed = other.completed;
        this.cancelled = other.cancelled;
        this.notificationDismissed = other.notificationDismissed;
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

    long getEndTimeMillis() {
        return endTimeMillis;
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

    boolean isRunning(long now) {
        return started && !isTerminal() && getRemainingMillis(now) > 0L;
    }

    long getRemainingMillis(long now) {
        if (isTerminal()) {
            return 0L;
        }
        if (!started) {
            return durationMillis;
        }
        return Math.max(0L, endTimeMillis - now);
    }

    boolean shouldComplete(long now) {
        return started && !isTerminal() && endTimeMillis <= now;
    }

    void markCompleted() {
        completed = true;
        cancelled = false;
        started = true;
        notificationDismissed = false;
    }

    void markCancelled() {
        cancelled = true;
        completed = false;
        started = true;
        notificationDismissed = false;
    }

    void markStarted(long now) {
        started = true;
        completed = false;
        cancelled = false;
        notificationDismissed = false;
        endTimeMillis = now + durationMillis;
    }

    void markNotificationDismissed() {
        notificationDismissed = true;
    }
}