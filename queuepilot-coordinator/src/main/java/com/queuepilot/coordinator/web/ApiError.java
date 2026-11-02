package com.queuepilot.coordinator.web;

import com.queuepilot.core.schema.ValidationError;

import java.time.Instant;
import java.util.List;

/** Uniform error body. Never contains stack traces or internal details. */
public record ApiError(String code, String message, List<ValidationError> fieldErrors, Instant timestamp) {
}
