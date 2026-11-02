package com.queuepilot.coordinator.error;

import org.springframework.http.HttpStatus;

/**
 * Base of all expected, client-facing errors. Each subclass decides its HTTP status, so the
 * exception handler can treat them polymorphically instead of with a chain of instanceof checks.
 */
public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }

    public abstract HttpStatus status();

    public abstract String code();
}
