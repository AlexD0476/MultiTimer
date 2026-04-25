package com.example.multitimer;

/**
 * Interner Datencontainer fuer einen einzelnen Timer.
 *
 * <p>Das Objekt beschreibt Konfiguration (Name, Dauer) und Laufzeitstatus
 * (gestartet, fertig, abgebrochen, Notification bestaetigt).</p>
 */
final class ManagedTimer {
    static final long DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS = 5000L;

    private final long id;
    private final String name;
    private final long durationMillis;
    private final long announcementIntervalMillis;
    private long endTimeMillis;
    private boolean started;
    private boolean completed;
    private boolean cancelled;
    private boolean notificationDismissed;
    private boolean completionAnnounced;

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis) {
        this(id, name, durationMillis, endTimeMillis, true, DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS);
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean started) {
        this(id, name, durationMillis, endTimeMillis, started, DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS);
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean started, long announcementIntervalMillis) {
        this.id = id;
        this.name = name;
        this.durationMillis = durationMillis;
        this.endTimeMillis = endTimeMillis;
        this.started = started;
        this.announcementIntervalMillis = Math.max(0L, announcementIntervalMillis);
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean started, boolean completed, boolean cancelled, boolean notificationDismissed) {
        this(id, name, durationMillis, endTimeMillis, started, DEFAULT_ANNOUNCEMENT_INTERVAL_MILLIS, completed, cancelled, notificationDismissed, false);
    }

    ManagedTimer(long id, String name, long durationMillis, long endTimeMillis, boolean started,
                 long announcementIntervalMillis, boolean completed, boolean cancelled,
                 boolean notificationDismissed, boolean completionAnnounced) {
        this(id, name, durationMillis, endTimeMillis, started, announcementIntervalMillis);
        this.completed = completed;
        this.cancelled = cancelled;
        this.notificationDismissed = notificationDismissed;
        this.completionAnnounced = completionAnnounced;
    }

    ManagedTimer(ManagedTimer other) {
        this.id = other.id;
        this.name = other.name;
        this.durationMillis = other.durationMillis;
        this.announcementIntervalMillis = other.announcementIntervalMillis;
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

    String getName() {
        return name;
    }

    long getDurationMillis() {
        return durationMillis;
    }

    long getAnnouncementIntervalMillis() {
        return announcementIntervalMillis;
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
        return started && !isTerminal() && endTimeMillis <= now;
    }

    void markCompleted() {
        completed = true;
        cancelled = false;
        started = true;
        notificationDismissed = false;
        completionAnnounced = false;
    }

    void markCancelled() {
        cancelled = true;
        completed = false;
        started = true;
        notificationDismissed = false;
        completionAnnounced = false;
    }

    void markStarted(long now) {
        started = true;
        completed = false;
        cancelled = false;
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