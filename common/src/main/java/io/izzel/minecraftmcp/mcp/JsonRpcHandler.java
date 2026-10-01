package io.izzel.minecraftmcp.mcp;

import io.izzel.minecraftmcp.json.Json;
import java.util.*;

public final class JsonRpcHandler {
    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2024-11-05", "2025-03-26", LATEST_PROTOCOL_VERSION);
    private final ToolRegistry registry;
    public JsonRpcHandler(ToolRegistry registry) { this.registry = registry; }

    /** Returns the JSON-RPC response, or {@code null} for a notification, which gets no response. */
    @SuppressWarnings("unchecked")
    public String handle(String requestJson) {
        Object id = null;
        try {
            Map<String,Object> req = (Map<String,Object>) Json.parse(requestJson);
            id = req.get("id");
            String method = String.valueOf(req.get("method"));
            if (!req.containsKey("id") && method.startsWith("notifications/")) return null;
            Map<String,Object> params = req.get("params") instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
            Object result;
            switch (method) {
                case "initialize" -> result = Map.of("protocolVersion", negotiateProtocolVersion(params.get("protocolVersion")), "serverInfo", Map.of("name", "minecraft-mcp", "version", "0.1.0"), "capabilities", Map.of("tools", Map.of(), "resources", Map.of()));
                case "ping" -> result = Map.of();
                case "tools/list" -> result = Map.of("tools", registry.listTools());
                case "tools/call" -> result = callTool(params);
                default -> throw new McpException(-32601, "Method not found: " + method);
            }
            return response(id, result);
        } catch (McpException e) {
            return error(id, e.code, e.getMessage());
        } catch (Exception e) {
            return error(id, -32603, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    /** The client's version if supported, otherwise the latest one this server speaks. */
    public static String negotiateProtocolVersion(Object requested) {
        return requested instanceof String version && SUPPORTED_PROTOCOL_VERSIONS.contains(version) ? version : LATEST_PROTOCOL_VERSION;
    }

    @SuppressWarnings("unchecked")
    private Object callTool(Map<String,Object> params) throws Exception {
        String name = String.valueOf(params.get("name"));
        Map<String,Object> args = params.get("arguments") instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
        Object result = registry.call(name, args);
        return Map.of("content", List.of(Map.of("type", "text", "text", Json.stringify(result))), "isError", false);
    }

    private String response(Object id, Object result) { return Json.stringify(new LinkedHashMap<>(Map.of("jsonrpc", "2.0", "id", id == null ? 0 : id, "result", result))); }
    private String error(Object id, int code, String message) { Map<String,Object> e = new LinkedHashMap<>(); e.put("code", code); e.put("message", message); Map<String,Object> r = new LinkedHashMap<>(); r.put("jsonrpc","2.0"); r.put("id", id == null ? 0 : id); r.put("error", e); return Json.stringify(r); }
    static final class McpException extends RuntimeException { final int code; McpException(int code, String message){super(message);this.code=code;} }
}
