package io.izzel.minecraftmcp.stdio;

import io.izzel.minecraftmcp.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP JSON-RPC client for the in-game endpoint, found through a fixed port/token or {@code mcp/server.json}. */
public final class ClientEndpoint {
    public record Target(String host, int port, String path, String token, String source) {
        URI uri() { return URI.create("http://" + host + ":" + port + path); }
        Map<String, Object> toMap() { Map<String, Object> m = new LinkedHashMap<>(); m.put("host", host); m.put("port", port); m.put("path", path); m.put("source", source); return m; }
    }

    /** The client is not running, not reachable, or answered with something other than JSON-RPC. */
    public static final class UnavailableException extends IOException {
        UnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    private final Path serverJson; private final Integer fixedPort; private final String fixedToken;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicLong ids = new AtomicLong();

    public ClientEndpoint(Path serverJson, Integer fixedPort, String fixedToken) { this.serverJson = serverJson; this.fixedPort = fixedPort; this.fixedToken = fixedToken; }

    /** Where the client should be listening, or empty if that is not known yet (no server.json and no fixed endpoint). */
    @SuppressWarnings("unchecked")
    public Optional<Target> target() {
        Map<String, Object> json = null;
        try {
            if (Files.isRegularFile(serverJson) && Json.parse(Files.readString(serverJson, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) json = (Map<String, Object>) m;
        } catch (Exception ignored) {
            // half-written or deleted while reading; treat as missing
        }
        String token = fixedToken != null ? fixedToken : json == null ? null : (String) json.get("authToken");
        if (fixedPort != null) return token == null ? Optional.empty() : Optional.of(new Target("127.0.0.1", fixedPort, "/mcp", token, "fixed"));
        if (json == null || !(json.get("port") instanceof Number port) || token == null) return Optional.empty();
        String host = String.valueOf(json.getOrDefault("host", "127.0.0.1"));
        if (host.equals("0.0.0.0") || host.isBlank()) host = "127.0.0.1";
        return Optional.of(new Target(host, port.intValue(), String.valueOf(json.getOrDefault("path", "/mcp")), token, "server.json"));
    }

    /** Sends a request and returns the whole JSON-RPC response object. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> request(String method, Map<String, Object> params, Duration timeout) throws IOException, InterruptedException {
        Target target = target().orElseThrow(() -> new UnavailableException("No client endpoint: " + serverJson + " does not exist" + (fixedPort != null ? " and no --token was given" : ""), null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0"); body.put("id", "bridge-" + ids.incrementAndGet()); body.put("method", method); body.put("params", params);
        HttpRequest request = HttpRequest.newBuilder(target.uri()).timeout(timeout).header("Authorization", "Bearer " + target.token())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UnavailableException("Client endpoint " + target.uri() + " is not reachable: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        if (response.statusCode() != 200) throw new UnavailableException("Client endpoint " + target.uri() + " answered HTTP " + response.statusCode() + ": " + response.body(), null);
        try {
            return (Map<String, Object>) Json.parse(response.body());
        } catch (RuntimeException e) {
            throw new UnavailableException("Client endpoint " + target.uri() + " answered invalid JSON: " + e.getMessage(), e);
        }
    }

    /** The client's tools, or an exception if it is not reachable. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools(Duration timeout) throws IOException, InterruptedException {
        Map<String, Object> response = request("tools/list", Map.of(), timeout);
        if (response.get("result") instanceof Map<?, ?> result && result.get("tools") instanceof List<?> tools) return (List<Map<String, Object>>) tools;
        throw new UnavailableException("Client tools/list failed: " + Json.stringify(response.get("error")), null);
    }
}
