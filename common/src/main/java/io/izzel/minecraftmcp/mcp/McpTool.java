package io.izzel.minecraftmcp.mcp;

import java.util.Map;

public interface McpTool {
    String name();
    String description();
    Map<String, Object> inputSchema();
    Object call(Map<String, Object> arguments) throws Exception;

    /** A tool with a free-form object input schema. Results are serialized to JSON; return maps, lists and primitives. */
    static McpTool of(String name, String description, Handler handler) {
        return new McpTool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public Object call(Map<String, Object> arguments) throws Exception { return handler.call(arguments); }
        };
    }

    @FunctionalInterface
    interface Handler {
        Object call(Map<String, Object> arguments) throws Exception;
    }
}
