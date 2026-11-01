package com.queuepilot.model;

public enum JobPriority {
    LOW(1, "Low Priority"),
    NORMAL(5, "Normal Priority"),
    HIGH(10, "High Priority"),
    CRITICAL(20, "Critical Priority");

    private final int level;
    private final String label;

    JobPriority(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public int getLevel() {
        return level;
    }

    public String getLabel() {
        return label;
    }

    public static JobPriority fromLevel(int level) {
        if (level >= 20) return CRITICAL;
        if (level >= 10) return HIGH;
        if (level >= 5) return NORMAL;
        return LOW;
    }
}
