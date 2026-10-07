package com.example.multitimer;

/** Ein zeitlich begrenzter Schritt innerhalb einer Timergruppe. */
final class TimerStep {
    private final String name;
    private final long durationMillis;
    private final String completionText;

    TimerStep(String name, long durationMillis, String completionText) {
        this.name = name == null ? "" : name.trim();
        this.durationMillis = Math.max(0L, durationMillis);
        this.completionText = completionText == null ? "" : completionText.trim();
    }

    String getName() {
        return name;
    }

    long getDurationMillis() {
        return durationMillis;
    }

    String getCompletionText() {
        return completionText;
    }
}