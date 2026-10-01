package io.izzel.minecraftmcp.stdio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.izzel.minecraftmcp.json.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** A fake in-game endpoint: lists one tool, echoes calls to it, and writes mcp/server.json like the mod does. */
final class StubClient implements AutoCloseable {
    final HttpServer server;
    final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());

    @SuppressWarnings("unchecked")
    StubClient(Path gameDir, String token) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            if (!("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) { send(ex, 401, "unauthorized"); return; }
            Map<String, Object> req = (Map<String, Object>) Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(req);
            Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0"); response.put("id", req.get("id"));
            switch (String.valueOf(req.get("method"))) {
                case "tools/list" -> response.put("result", Map.of("tools", List.of(Map.of("name", "mc.echo", "description", "Echo", "inputSchema", Map.of("type", "object")))));
                case "tools/call" -> {
                    if ("mc.echo".equals(params.get("name"))) response.put("result", Map.of("content", List.of(Map.of("type", "text", "text", Json.stringify(params.get("arguments")))), "isError", false));
                    else response.put("error", Map.of("code", -32603, "message", "Unknown tool: " + params.get("name")));
                }
                default -> response.put("error", Map.of("code", -32601, "message", "Method not found"));
            }
            send(ex, 200, Json.stringify(response));
        });
        server.start();
        Files.createDirectories(gameDir.resolve("mcp"));
        Files.writeString(gameDir.resolve("mcp/server.json"), Json.stringify(Map.of("host", "127.0.0.1", "port", port(), "path", "/mcp", "authToken", token)));
    }

    int port() { return server.getAddress().getPort(); }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    @Override
    public void close() { server.stop(0); }
}
