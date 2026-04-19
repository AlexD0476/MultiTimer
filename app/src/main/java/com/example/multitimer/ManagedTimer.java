package com.example.multitimer;

final class ManagedTimer {
    private final long id;
    private final String name;
    private final long durationMillis;
    private final long endTimeMillis;
    private boolean completed;
    private boolean cancelled;
    private boolean notificationDismissed;

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis) {
        this.id = id;
        this.name = name;
        this.durationMillis = durationMillis;
        this.endTimeMillis = endTimeMillis;
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean completed) {
        this(id, name, durationMillis, endTimeMillis);
        this.completed = completed;
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean completed, boolean cancelled) {
        this(id, name, durationMillis, endTimeMillis, completed);
        this.cancelled = cancelled;
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean completed, boolean cancelled, boolean notificationDismissed) {
        this(id, name, durationMillis, endTimeMillis, completed, cancelled);
        this.notificationDismissed = notificationDismissed;
    }

    ManagedTimer(ManagedTimer other) {
        this.id = other.id;
        this.name = other.name;
        this.durationMillis = other.durationMillis;
        this.endTimeMillis = other.endTimeMillis;
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
        return !isTerminal() && getRemainingMillis(now) > 0L;
    }

    long getRemainingMillis(long now) {
        if (isTerminal()) {
            return 0L;
        }
        return Math.max(0L, endTimeMillis - now);
    }

    boolean shouldComplete(long now) {
        return !isTerminal() && endTimeMillis <= now;
    }

    void markCompleted() {
        completed = true;
        cancelled = false;
        notificationDismissed = false;
    }

    void markCancelled() {
        cancelled = true;
        completed = false;
        notificationDismissed = false;
    }

    void markNotificationDismissed() {
        notificationDismissed = true;
    }
}