package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.ClientSnapshot;
import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProvidersTest {
    @Test
    void discoversProvidersFromServiceFiles() {
        assertTrue(ToolProviders.providerNames().contains(TestToolProvider.class.getName()));
    }

    @Test
    void clientProvidersAddToolsButCannotReplaceBuiltins() throws Exception {
        ClientBridge bridge = new ClientBridge();
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools.register(registry, bridge, new ScenarioEngine(registry));

        ToolProviders.registerClient(registry, bridge);

        assertEquals(Map.of("loader", "test", "args", Map.of("x", 1)), registry.call("test.provider.echo", Map.of("x", 1)));
        Map<?, ?> state = (Map<?, ?>) registry.call("mc.client.state", Map.of());
        assertEquals(true, state.get("running"));
        assertFalse(state.containsKey("hijacked"));
    }

    @Test
    void serverProvidersRegisterServerTools() throws Exception {
        ToolRegistry registry = new ToolRegistry();

        ToolProviders.registerServer(registry, new BuiltinServerToolsTest.RecordingServerBridge());

        assertEquals(Map.of("loader", "test-server"), registry.call("test.provider.server", Map.of()));
        assertTrue(registry.find("test.provider.echo").isEmpty());
    }

    @Test
    void failingProviderDoesNotStopOthers() throws Exception {
        ToolProvider failing = new ToolProvider() {
            @Override
            public void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {
                registry.register(McpTool.of("partial.tool", "registered before failing", args -> Map.of()));
                throw new IllegalStateException("boom");
            }
        };
        ToolProvider working = new ToolProvider() {
            @Override
            public void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {
                registry.register(McpTool.of("working.tool", "ok", args -> Map.of("ok", true)));
            }
        };
        ToolRegistry registry = new ToolRegistry();
        ClientBridge bridge = new ClientBridge();

        ToolProviders.register(registry, List.of(failing, working), provider -> target -> provider.registerClient(target, bridge));

        assertTrue(registry.find("partial.tool").isEmpty());
        assertEquals(Map.of("ok", true), registry.call("working.tool", Map.of()));
    }

    @Test
    void capabilitiesListProviders() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools.register(registry, new ClientBridge(), new ScenarioEngine(registry));

        Map<?, ?> capabilities = (Map<?, ?>) registry.call("mc.debug.capabilities", Map.of());

        assertTrue(((List<?>) capabilities.get("toolProviders")).contains(TestToolProvider.class.getName()));
    }

    static final class ClientBridge implements MinecraftClientBridge {
        public String loader() { return "test"; }
        public String minecraftVersion() { return "1.21.1"; }
        public Path gameDirectory() { return Path.of("."); }
        public boolean isOnClientThread() { return true; }
        public void execute(Runnable runnable) { runnable.run(); }
        public ClientSnapshot snapshot() { return new ClientSnapshot(true, false, null, null, 0, 0, 0, 0, 0); }
    }
}
