package io.izzel.minecraftmcp;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.config.MinecraftMcpConfig;
import io.izzel.minecraftmcp.json.Json;
import io.izzel.minecraftmcp.mcp.*;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import io.izzel.minecraftmcp.scenario.ScenarioReport;
import io.izzel.minecraftmcp.scenario.ScenarioRunOptions;
import io.izzel.minecraftmcp.tools.BuiltinTools;
import io.izzel.minecraftmcp.tools.BuiltinServerTools;
import io.izzel.minecraftmcp.tools.ToolProviders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MinecraftMcpBootstrap {
    private MinecraftMcpBootstrap() {}
    public static LocalHttpMcpServer start(MinecraftClientBridge bridge) throws Exception {
        MinecraftMcpConfig config = MinecraftMcpConfig.load();
        ToolRegistry registry = new ToolRegistry();
        ScenarioEngine scenarios = new ScenarioEngine(registry);
        BuiltinTools.register(registry, bridge, scenarios);
        ToolProviders.registerClient(registry, bridge);
        LocalHttpMcpServer server = new LocalHttpMcpServer(config, new JsonRpcHandler(registry));
        server.start(bridge.gameDirectory(), bridge.loader(), bridge.minecraftVersion());
        if (!config.scenarioDir().isBlank()) {
            new Thread(() -> {
                try {
                    ScenarioReport report = scenarios.runBatch(config.scenarioDir(), ScenarioRunOptions.builder().loader(bridge.loader()).background(bridge.background()).build());
                    report.summaryLines().forEach(line -> System.out.println("[Minecraft MCP] scenario " + line));
                    Path reportFile = bridge.gameDirectory().resolve("mcp/scenario-report.json");
                    Files.createDirectories(reportFile.getParent());
                    Files.writeString(reportFile, Json.stringify(report.toMap()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (config.batchExit()) {
                        bridge.execute(bridge::shutdownClient);
                    }
                }
            }, "minecraft-mcp-scenario-batch").start();
        }
        return server;
    }

    public static LocalHttpMcpServer start(MinecraftServerBridge bridge) throws Exception {
        MinecraftMcpConfig config = MinecraftMcpConfig.load();
        ToolRegistry registry = new ToolRegistry();
        BuiltinServerTools.register(registry, bridge);
        ToolProviders.registerServer(registry, bridge);
        LocalHttpMcpServer server = new LocalHttpMcpServer(config, new JsonRpcHandler(registry));
        server.start(bridge.gameDirectory(), bridge.loader(), bridge.minecraftVersion());
        return server;
    }
}
