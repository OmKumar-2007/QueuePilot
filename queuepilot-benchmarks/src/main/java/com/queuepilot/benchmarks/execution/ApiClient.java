package com.queuepilot.benchmarks.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal JSON client for the coordinator's API. */
public class ApiClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public JsonNode get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public JsonNode post(String path, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))));
    }

    private JsonNode send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from coordinator: " + response.body());
        }
        return response.body().isEmpty() ? null : mapper.readTree(response.body());
    }
}
