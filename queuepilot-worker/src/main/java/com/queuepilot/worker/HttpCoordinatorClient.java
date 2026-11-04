package com.queuepilot.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.CompletionReport;
import com.queuepilot.core.protocol.WorkerProtocol.FailureReport;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatRequest;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatResponse;
import com.queuepilot.core.protocol.WorkerProtocol.ProgressReport;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterRequest;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link CoordinatorApi} over HTTP/JSON using the JDK's built-in HttpClient. The worker identifies itself with the X-Worker-Id header. */
public class HttpCoordinatorClient implements CoordinatorApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private volatile String workerId;

    public HttpCoordinatorClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public RegisterResponse register(RegisterRequest request) {
        RegisterResponse response = read(send(jsonPost("/api/worker/register", request)), RegisterResponse.class);
        workerId = response.workerId();
        return response;
    }

    @Override
    public Optional<Assignment> claim() {
        HttpResponse<byte[]> response = send(jsonPost("/api/worker/claim", Map.of()));
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        return Optional.of(read(response, Assignment.class));
    }

    @Override
    public HeartbeatResponse heartbeat(List<String> activeAttemptIds) {
        return read(send(jsonPost("/api/worker/heartbeat", new HeartbeatRequest(activeAttemptIds))), HeartbeatResponse.class);
    }

    @Override
    public boolean progress(String attemptId, ProgressReport report) {
        return accepted(send(jsonPost("/api/worker/attempts/" + attemptId + "/progress", report)));
    }

    @Override
    public boolean complete(String attemptId, CompletionReport report) {
        return accepted(send(jsonPost("/api/worker/attempts/" + attemptId + "/complete", report)));
    }

    @Override
    public boolean fail(String attemptId, FailureReport report) {
        return accepted(send(jsonPost("/api/worker/attempts/" + attemptId + "/fail", report)));
    }

    @Override
    public void downloadInput(String attemptId, String artifactId, Path target) throws IOException {
        HttpRequest request = identified(HttpRequest.newBuilder(uri("/api/worker/attempts/" + attemptId + "/artifacts/" + artifactId)))
                .GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Download of " + artifactId + " failed with HTTP " + response.statusCode());
                }
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading", e);
        }
    }

    @Override
    public String uploadOutput(String attemptId, Path file, String contentType) throws IOException {
        String boundary = "qp-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + file.getFileName().toString().replace("\"", "") + "\"\r\nContent-Type: " + contentType + "\r\n\r\n";
        body.write(header.getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(file));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = identified(HttpRequest.newBuilder(uri("/api/worker/attempts/" + attemptId + "/artifacts")))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() != 201) {
            throw new IOException("Upload failed with HTTP " + response.statusCode());
        }
        return String.valueOf(read(response, Map.class).get("id"));
    }

    @Override
    public void deregister() {
        send(jsonPost("/api/worker/deregister", Map.of()));
    }

    private HttpRequest jsonPost(String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            return identified(builder).build();
        } catch (IOException e) {
            throw new CoordinatorException("Cannot serialise request", e);
        }
    }

    private HttpRequest.Builder identified(HttpRequest.Builder builder) {
        builder.timeout(TIMEOUT);
        return workerId == null ? builder : builder.header("X-Worker-Id", workerId);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new CoordinatorException("Coordinator unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoordinatorException("Interrupted", e);
        }
    }

    private boolean accepted(HttpResponse<byte[]> response) {
        if (response.statusCode() == 409) {
            return false;
        }
        check(response);
        return true;
    }

    private <T> T read(HttpResponse<byte[]> response, Class<T> type) {
        check(response);
        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new CoordinatorException("Unreadable response: " + e.getMessage(), e);
        }
    }

    private static void check(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 300) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            throw new CoordinatorException(response.statusCode(), "HTTP " + response.statusCode() + ": "
                    + (body.length() > 300 ? body.substring(0, 300) : body));
        }
    }
}
