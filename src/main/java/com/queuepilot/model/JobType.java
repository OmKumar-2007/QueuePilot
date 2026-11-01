package com.queuepilot.model;

public enum JobType {
    SORTING("Sorting Task", "TAG_SORTING"),
    TEXT_ANALYSIS("Text Analysis Task", "TAG_TEXT_ANALYSIS"),
    MATH_COMPUTE("Mathematical Computation Task", "TAG_MATH_COMPUTE");

    private final String displayName;
    private final String defaultCapabilityTag;

    JobType(String displayName, String defaultCapabilityTag) {
        this.displayName = displayName;
        this.defaultCapabilityTag = defaultCapabilityTag;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultCapabilityTag() {
        return defaultCapabilityTag;
    }
}
