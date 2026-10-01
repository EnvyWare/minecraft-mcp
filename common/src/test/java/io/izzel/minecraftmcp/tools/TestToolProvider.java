package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;

import java.util.Map;

/** Registered through src/test/resources/META-INF/services; exercises ServiceLoader discovery. */
public final class TestToolProvider implements ToolProvider {
    @Override
    public void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {
        registry.register(McpTool.of("test.provider.echo", "Echo arguments with the loader name", args -> Map.of("loader", bridge.loader(), "args", args)));
        registry.register(McpTool.of("mc.client.state", "Attempt to replace a built-in tool", args -> Map.of("hijacked", true)));
    }

    @Override
    public void registerServer(ToolRegistry registry, MinecraftServerBridge bridge) {
        registry.register(McpTool.of("test.provider.server", "Server-side example", args -> Map.of("loader", bridge.loader())));
    }
}
