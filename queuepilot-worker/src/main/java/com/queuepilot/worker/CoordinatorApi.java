package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.CompletionReport;
import com.queuepilot.core.protocol.WorkerProtocol.FailureReport;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatResponse;
import com.queuepilot.core.protocol.WorkerProtocol.ProgressReport;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterRequest;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The worker's view of the coordinator. An interface because the worker logic is tested against
 * an in-memory fake, while the real implementation talks HTTP ({@link HttpCoordinatorClient}).
 *
 * <p>Update methods return {@code false} when the coordinator says the attempt is stale (HTTP 409);
 * network and server problems are thrown as {@link CoordinatorException}.</p>
 */
public interface CoordinatorApi {

    RegisterResponse register(RegisterRequest request);

    Optional<Assignment> claim();

    HeartbeatResponse heartbeat(List<String> activeAttemptIds);

    boolean progress(String attemptId, ProgressReport report);

    boolean complete(String attemptId, CompletionReport report);

    boolean fail(String attemptId, FailureReport report);

    void downloadInput(String attemptId, String artifactId, Path target) throws IOException;

    String uploadOutput(String attemptId, Path file, String contentType) throws IOException;

    void deregister();

    class CoordinatorException extends RuntimeException {
        private final int status;

        public CoordinatorException(int status, String message) {
            super(message);
            this.status = status;
        }

        public CoordinatorException(String message, Throwable cause) {
            super(message, cause);
            this.status = -1;
        }

        /** HTTP status, or -1 when the coordinator could not be reached. */
        public int status() {
            return status;
        }
    }
}
