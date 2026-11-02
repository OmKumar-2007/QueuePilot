package com.queuepilot.core.schema;

import java.util.List;

/** An ordered list of fields; the JSON form is {@code {"fields": [ ... ]}}. */
public record InputSchema(List<FieldSpec> fields) {

    public InputSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static InputSchema empty() {
        return new InputSchema(List.of());
    }
}
