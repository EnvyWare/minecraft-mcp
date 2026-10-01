# Tool provider SPI

Other mods can add their own MCP tools without forking this mod. Implement `io.izzel.minecraftmcp.tools.ToolProvider`
and list the class in a service file, the same way as the [scenario condition property SPI](scenario-condition-property-spi.md).

Typical use: a dev-only runtime dependency on this mod, plus tools that expose your mod's state or actions to agents
(open your GUI, read your capability data, trigger your network packets).

## Minimal provider

```java
package com.example.mymod.mcp;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.tools.ToolProvider;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class MyModTools implements ToolProvider {
    @Override
    public void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {
        registry.register(McpTool.of("mymod.player_health", "Current health of the client player", args ->
                // game state must be read on the client thread
                bridge.submit(() -> {
                    var player = Minecraft.getInstance().player;
                    return player == null
                            ? Map.<String, Object>of("inWorld", false)
                            : Map.<String, Object>of("inWorld", true, "health", player.getHealth());
                }).get(10, TimeUnit.SECONDS)));
    }

    @Override
    public void registerServer(ToolRegistry registry, MinecraftServerBridge bridge) {
        registry.register(McpTool.of("mymod.server_state", "Server state", args ->
                bridge.submit(bridge::serverState).get(10, TimeUnit.SECONDS)));
    }
}
```

Service file `src/main/resources/META-INF/services/io.izzel.minecraftmcp.tools.ToolProvider`:

```text
com.example.mymod.mcp.MyModTools
```

Both methods have empty defaults, so implement only the side you need.

## When providers are called

- `registerClient`: once, when the client MCP endpoint starts (during this mod's construction), before it accepts
  requests. Tools are listed by `tools/list` and can be used in scenarios like built-in tools.
- `registerServer`: for every server-side tool registry. That covers the dedicated server MCP endpoint, the server
  plugin channel that answers `mc.server.call` from a connected client, and `mc.server.call` in singleplayer.

Do not touch game state while registering. Tool bodies run on an HTTP or scenario thread: use `bridge.submit(...)` (or
`bridge.execute`) to run on the client or server thread, as in the example.

## Rules

- Tool names must be unique. A provider cannot replace an existing tool (built-in or from another provider). The
  conflicting tool is skipped and a warning is logged. Use your own prefix, such as `mymod.`.
- If `registerClient` or `registerServer` throws, that provider's tools from that call are discarded and the error is
  logged; other providers are unaffected.
- `McpTool.of(name, description, handler)` creates a tool with a free-form object input schema. Implement `McpTool`
  yourself for a custom schema. Return maps, lists, strings, numbers and booleans; they are serialized to JSON.
- `mc.debug.capabilities` lists the loaded providers under `toolProviders`, and startup logs
  `[Minecraft MCP] Tool provider <class> (found via ...)`.

## Discovery and loaders

Providers are found with `java.util.ServiceLoader` through the thread context class loader, this mod's class loader,
and this mod's module layer. On NeoForge 21.1 each mod jar is a separate module in the game layer; a provider in
another mod jar is found by all three lookups, so no NeoForge event is needed. This was verified with the example below.
On Fabric everything shares one class loader, so the service file is enough. Fabric discovery is not covered by
an end-to-end test here.

## Example and test

`neoforge/src/exampleToolProvider` is a separate test-only mod (`minecraft_mcp_example_tool_provider`) that
contributes `example.echo`, `example.player_health` and the server tool `example.server_state`. The
`clientWithExampleToolProvider` run loads it as its own mod next to this one:

```bash
./gradlew :neoforge:runClientWithExampleToolProvider -DminecraftMcp.background=true \
  -DminecraftMcp.scenarioDir=$PWD/neoforge/src/exampleToolProvider/scenarios -DminecraftMcp.batchExit=true
```

The scenario checks that the provider is listed, calls the client tools before and after joining a world, calls the
server tool through `mc.server.call`, and confirms built-in tools were not replaced. Discovery, the no-override rule and
failure isolation are also unit tested in `ToolProvidersTest`.
