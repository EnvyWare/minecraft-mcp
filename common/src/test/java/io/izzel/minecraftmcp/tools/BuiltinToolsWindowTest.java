package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.ClientSnapshot;
import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinToolsWindowTest {
    @Test
    void showAndHideDelegateToBridge() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ToolRegistry registry = registry(bridge);

        assertEquals(Map.of("status", "shown", "visible", true), registry.call("mc.window.show", Map.of()));
        assertEquals(Map.of("status", "hidden", "visible", false), registry.call("mc.window.hide", Map.of()));
        assertEquals(Map.of("visible", false), registry.call("mc.window.state", Map.of()));
        assertEquals(List.of("show", "hide"), bridge.events);
    }

    @Test
    void clientStateKeepsExistingFieldsAndAddsBackgroundAndWindow() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ToolRegistry registry = registry(bridge);

        Map<?, ?> state = (Map<?, ?>) registry.call("mc.client.state", Map.of());

        assertEquals(true, state.get("running"));
        assertEquals(false, state.get("inWorld"));
        assertTrue(state.containsKey("position"));
        assertEquals(true, state.get("background"));
        assertEquals(Map.of("visible", false), state.get("window"));
    }

    @Test
    void capabilitiesReportBackgroundAndWindow() throws Exception {
        ToolRegistry registry = registry(new FakeBridge());

        Map<?, ?> capabilities = (Map<?, ?>) registry.call("mc.debug.capabilities", Map.of());

        assertEquals("test", capabilities.get("loader"));
        assertEquals(true, capabilities.get("clientThreadScheduling"));
        assertEquals(true, capabilities.get("background"));
        assertEquals(Map.of("visible", false), capabilities.get("window"));
    }

    @Test
    void bridgesWithoutWindowControlReportUnsupported() throws Exception {
        MinecraftClientBridge bridge = new BasicBridge();
        ToolRegistry registry = registry(bridge);

        Map<?, ?> capabilities = (Map<?, ?>) registry.call("mc.debug.capabilities", Map.of());

        assertEquals(false, capabilities.get("background"));
        assertEquals(false, capabilities.get("windowControl"));
        assertEquals(Map.of("supported", false), capabilities.get("window"));
        assertThrows(Exception.class, () -> registry.call("mc.window.show", Map.of()));
    }

    private static ToolRegistry registry(MinecraftClientBridge bridge) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools.register(registry, bridge, new ScenarioEngine(registry));
        return registry;
    }

    static class BasicBridge implements MinecraftClientBridge {
        public String loader() { return "test"; }
        public String minecraftVersion() { return "1.21.1"; }
        public Path gameDirectory() { return Path.of("."); }
        public boolean isOnClientThread() { return true; }
        public void execute(Runnable runnable) { runnable.run(); }
        public ClientSnapshot snapshot() { return new ClientSnapshot(true, false, null, null, 0, 0, 0, 0, 0); }
    }

    static final class FakeBridge extends BasicBridge {
        final List<String> events = new ArrayList<>();
        public boolean background() { return true; }
        public Map<String, Object> windowState() { return Map.of("visible", false); }
        public Map<String, Object> showWindow() { events.add("show"); return Map.of("status", "shown", "visible", true); }
        public Map<String, Object> hideWindow() { events.add("hide"); return Map.of("status", "hidden", "visible", false); }
    }
}
