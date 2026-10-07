package com.example.multitimer;

enum TimerType {
    STANDARD,
    INTERVAL,
    GROUP;

    static TimerType fromName(String name) {
        if (name == null) {
            return STANDARD;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException exception) {
            return STANDARD;
        }
    }
}