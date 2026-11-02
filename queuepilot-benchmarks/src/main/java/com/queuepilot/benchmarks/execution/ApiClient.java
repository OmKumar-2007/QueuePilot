package com.queuepilot.benchmarks.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Minimal JSON client for the coordinator's public and admin API. */
public class ApiClient {

    private final String baseUrl;
    private final String basicAuth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiClient(String baseUrl, String adminUser, String adminPassword) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.basicAuth = adminPassword == null ? null : "Basic " + Base64.getEncoder()
                .encodeToString((adminUser + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), false);
    }

    public JsonNode post(String path, Object body, String clientId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Client-Id", clientId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        return send(builder, false);
    }

    public JsonNode adminPost(String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        return send(builder, true);
    }

    private JsonNode send(HttpRequest.Builder builder, boolean admin) throws IOException, InterruptedException {
        if (admin) {
            if (basicAuth == null) {
                throw new IOException("Admin password required (--admin-password or QP_ADMIN_PASSWORD)");
            }
            builder.header("Authorization", basicAuth);
        }
        HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from coordinator: " + response.body());
        }
        return response.body().isEmpty() ? null : mapper.readTree(response.body());
    }
}
