package com.queuepilot.core.schema;

/** A single, user-facing validation problem tied to a field name. */
public record ValidationError(String field, String message) {
}
