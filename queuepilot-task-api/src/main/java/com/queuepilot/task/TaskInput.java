package com.queuepilot.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of a job's JSON input with typed accessors.
 * Values arrive as the JSON parser produced them (Integer, Long, Double, String, Boolean, List).
 */
public final class TaskInput {

    private final Map<String, Object> values;

    public TaskInput(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public boolean has(String name) {
        return values.get(name) != null;
    }

    public String getString(String name, String defaultValue) {
        Object value = values.get(name);
        return value == null ? defaultValue : value.toString();
    }

    public long getLong(String name, long defaultValue) {
        Object value = values.get(name);
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    public int getInt(String name, int defaultValue) {
        return (int) getLong(name, defaultValue);
    }

    public double getDouble(String name, double defaultValue) {
        Object value = values.get(name);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        Object value = values.get(name);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    public List<Long> getLongList(String name) {
        List<Long> result = new ArrayList<>();
        if (values.get(name) instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) {
                    result.add(number.longValue());
                }
            }
        }
        return result;
    }

    public List<String> getStringList(String name) {
        List<String> result = new ArrayList<>();
        if (values.get(name) instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
