package io.izzel.minecraftmcp.example;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.tools.ToolProvider;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Example provider living in a separate mod; mirrors the snippet in docs/tool-provider-spi.md. */
public final class ExampleToolProvider implements ToolProvider {
    @Override
    public void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {
        registry.register(McpTool.of("example.echo", "Echo the arguments back", args -> Map.of("echo", args)));
        registry.register(McpTool.of("example.player_health", "Current health of the client player", args ->
                bridge.submit(() -> {
                    var player = Minecraft.getInstance().player;
                    return player == null
                            ? Map.<String, Object>of("inWorld", false)
                            : Map.<String, Object>of("inWorld", true, "health", player.getHealth(), "maxHealth", player.getMaxHealth());
                }).get(10, TimeUnit.SECONDS)));
    }

    @Override
    public void registerServer(ToolRegistry registry, MinecraftServerBridge bridge) {
        registry.register(McpTool.of("example.server_state", "Server state seen by the example provider", args ->
                bridge.submit(bridge::serverState).get(10, TimeUnit.SECONDS)));
    }
}
