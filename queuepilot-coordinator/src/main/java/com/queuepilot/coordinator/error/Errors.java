package com.queuepilot.coordinator.error;

import com.queuepilot.core.schema.ValidationError;
import org.springframework.http.HttpStatus;

import java.util.List;

/** The concrete API errors. Kept together because each is only a status code and an error code. */
public final class Errors {

    private Errors() {
    }

    public static class NotFoundException extends ApiException {
        public NotFoundException(String message) {
            super(message);
        }

        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        public String code() {
            return "NOT_FOUND";
        }
    }

    public static class ConflictException extends ApiException {
        public ConflictException(String message) {
            super(message);
        }

        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }

        public String code() {
            return "CONFLICT";
        }
    }

    /** A worker used an attempt id/token that is no longer current. */
    public static class StaleAttemptException extends ConflictException {
        public StaleAttemptException(String message) {
            super(message);
        }

        @Override
        public String code() {
            return "STALE_ATTEMPT";
        }
    }

    public static class ValidationException extends ApiException {
        private final List<ValidationError> errors;

        public ValidationException(List<ValidationError> errors) {
            super("Request is invalid");
            this.errors = List.copyOf(errors);
        }

        public ValidationException(String field, String message) {
            this(List.of(new ValidationError(field, message)));
        }

        public List<ValidationError> errors() {
            return errors;
        }

        public HttpStatus status() {
            return HttpStatus.BAD_REQUEST;
        }

        public String code() {
            return "VALIDATION_FAILED";
        }
    }
}
