package io.izzel.minecraftmcp.stdio;

import io.izzel.minecraftmcp.json.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** MCP server side of the stdio transport: one JSON-RPC message per line in, one per line out. */
public final class StdioMcpServer {
    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2024-11-05", "2025-03-26", LATEST_PROTOCOL_VERSION);
    static final String INSTRUCTIONS = "Bridge to a Minecraft client running the minecraft-mcp mod. The client.* tools belong to the bridge and are always available; "
            + "the mc.* tools are forwarded to the game and are listed only while it runs. Call client.launch to start the game (a dev build takes a few minutes), "
            + "client.status to check it, client.logs to read its log and client.stop to stop it. After a code change: client.stop, then client.launch.";

    private final BridgeTools tools; private final Consumer<String> out; private final String version;
    private volatile boolean initialized;

    public StdioMcpServer(BridgeTools tools, Consumer<String> out, String version) {
        this.tools = tools; this.out = out; this.version = version;
        tools.onListChanged(this::sendToolsListChanged);
    }

    /** Reads messages until stdin closes, handling each on {@code executor} so a long tool call does not block pings or other calls. */
    public void serve(BufferedReader in, ExecutorService executor) throws IOException {
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) continue;
            String message = line;
            executor.execute(() -> {
                String response = handle(message);
                if (response != null) out.accept(response);
            });
        }
    }

    /** The response line for one incoming message, or {@code null} for notifications and responses. */
    @SuppressWarnings("unchecked")
    public String handle(String line) {
        Map<String, Object> msg;
        try {
            if (!(Json.parse(line) instanceof Map<?, ?> m)) return error(null, -32600, "Invalid Request: expected a JSON object");
            msg = (Map<String, Object>) m;
        } catch (RuntimeException e) {
            return error(null, -32700, "Parse error: " + e.getMessage());
        }
        Object id = msg.get("id");
        if (!(msg.get("method") instanceof String method)) return msg.containsKey("id") && (msg.containsKey("result") || msg.containsKey("error")) ? null : error(id, -32600, "Invalid Request: missing method");
        Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        if (!msg.containsKey("id")) {
            if (method.equals("notifications/initialized")) initialized = true;
            return null;
        }
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools.list());
                case "tools/call" -> callTool(params);
                default -> null;
            };
            return result == null ? error(id, -32601, "Method not found: " + method) : response(id, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(id, -32603, "Interrupted");
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            return error(id, -32603, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    public static String negotiateProtocolVersion(Object requested) {
        return requested instanceof String v && SUPPORTED_PROTOCOL_VERSIONS.contains(v) ? v : LATEST_PROTOCOL_VERSION;
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiateProtocolVersion(params.get("protocolVersion")));
        result.put("capabilities", Map.of("tools", Map.of("listChanged", true)));
        result.put("serverInfo", Map.of("name", "minecraft-mcp-bridge", "version", version));
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) throws InterruptedException {
        if (!(params.get("name") instanceof String name)) throw new IllegalArgumentException("tools/call needs a tool name");
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        return tools.call(name, args);
    }

    private void sendToolsListChanged() {
        if (initialized) out.accept("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
    }

    private static String response(Object id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>(); r.put("jsonrpc", "2.0"); r.put("id", id); r.put("result", result);
        return Json.stringify(r);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> e = new LinkedHashMap<>(); e.put("code", code); e.put("message", message);
        Map<String, Object> r = new LinkedHashMap<>(); r.put("jsonrpc", "2.0"); r.put("id", id); r.put("error", e);
        return Json.stringify(r);
    }
}
