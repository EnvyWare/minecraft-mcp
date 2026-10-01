package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.ToolRegistry;

/**
 * Lets other mods contribute MCP tools. Implementations are discovered with {@link java.util.ServiceLoader}: list the
 * class in {@code META-INF/services/io.izzel.minecraftmcp.tools.ToolProvider}. See docs/tool-provider-spi.md.
 *
 * <p>Tools may not replace existing tools; a name that is already registered is skipped with a warning.
 */
public interface ToolProvider {
    /** Called once when the client MCP server starts, before it accepts requests. */
    default void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {}

    /**
     * Called for every server-side tool registry: the dedicated server MCP endpoint, the server plugin channel
     * used by {@code mc.server.call}, and singleplayer {@code mc.server.call} requests.
     */
    default void registerServer(ToolRegistry registry, MinecraftServerBridge bridge) {}
}
