package coordinator;

import core.Jobs.IllegalStateTransitionException;
import core.Validation.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/** Uniform error body. Never contains stack traces or internal details. */
record ApiError(String code, String message, List<ValidationError> fieldErrors, Instant timestamp) {
}

/**
 * Base of all expected, client-facing errors. Each subclass decides its HTTP status, so the
 * exception handler can treat them polymorphically instead of with a chain of instanceof checks.
 */
abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }

    public abstract HttpStatus status();

    public abstract String code();
}

class NotFoundException extends ApiException {
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

class ConflictException extends ApiException {
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
class StaleAttemptException extends ConflictException {
    public StaleAttemptException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "STALE_ATTEMPT";
    }
}

class ValidationException extends ApiException {
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

/** Maps exceptions to {@link ApiError} responses (NFR5: no stack traces to clients). */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        List<ValidationError> fieldErrors =
                e instanceof ValidationException validation ? validation.errors() : List.of();
        return respond(e.status(), e.code(), e.getMessage(), fieldErrors);
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleTransition(IllegalStateTransitionException e) {
        return respond(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage(), List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body or parameters are malformed", List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException e) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Uploaded file is too large", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage(), List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint", List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDatabase(DataAccessException e) {
        log.error("Database error", e);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "The database is unavailable; nothing was changed", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", List.of());
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                                    List<ValidationError> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fieldErrors, Instant.now()));
    }
}
