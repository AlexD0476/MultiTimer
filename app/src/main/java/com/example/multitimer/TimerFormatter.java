package com.example.multitimer;

import java.util.Locale;

final class TimerFormatter {
    private TimerFormatter() {
    }

    static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return String.format(Locale.GERMANY, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.GERMANY, "%02d:%02d", minutes, seconds);
    }
}