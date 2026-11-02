package com.queuepilot.core.schema;

import java.util.List;

/**
 * One field of a task input or output schema. Constraint fields are optional (null = unchecked).
 *
 * @param min       minimum numeric value (INTEGER, NUMBER and list elements)
 * @param max       maximum numeric value
 * @param maxLength maximum string length (STRING)
 * @param maxItems  maximum list size (*_LIST)
 * @param values    allowed values (ENUM)
 */
public record FieldSpec(String name,
                        FieldType type,
                        boolean required,
                        Double min,
                        Double max,
                        Integer maxLength,
                        Integer maxItems,
                        List<String> values,
                        String description) {

    public static FieldSpec of(String name, FieldType type, boolean required) {
        return new FieldSpec(name, type, required, null, null, null, null, null, null);
    }
}
