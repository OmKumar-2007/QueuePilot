package com.queuepilot.core.schema;

/** Value types supported in task input/output schemas (ADR-007). */
public enum FieldType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    /** A string restricted to {@link FieldSpec#values()}. */
    ENUM,
    STRING_LIST,
    INTEGER_LIST,
    NUMBER_LIST,
    /** The id of an uploaded artifact. */
    FILE
}
