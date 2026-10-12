package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinToolsFilmTest {
    @Test
    void hudSetAcceptsHiddenOrVisibleAndReportsState() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ToolRegistry registry = registry(bridge);

        assertEquals(Map.of("hidden", true), registry.call("mc.hud.set", Map.of("hidden", true)));
        assertEquals(Map.of("hidden", false), registry.call("mc.hud.set", Map.of("visible", true)));
        assertEquals(Map.of("hidden", false), registry.call("mc.hud.state", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.hud.set", Map.of()));
    }

    @Test
    void cameraAndRecordToolsDelegateToBridge() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ToolRegistry registry = registry(bridge);

        assertEquals(Map.of("status", "ok"), registry.call("mc.camera.play", Map.of("draft", true)));
        registry.call("mc.camera.stop", Map.of());
        registry.call("mc.record.start", Map.of("fps", 30));
        registry.call("mc.record.state", Map.of());
        registry.call("mc.record.stop", Map.of());
        assertEquals(List.of("play {draft=true}", "stop", "record {fps=30}", "state", "record stop"), bridge.events);
    }

    @Test
    void loadersWithoutFilmSupportReportUnsupported() {
        ToolRegistry registry = registry(new BuiltinToolsWindowTest.BasicBridge());
        assertThrows(UnsupportedOperationException.class, () -> registry.call("mc.camera.play", Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> registry.call("mc.record.start", Map.of()));
        assertThrows(Exception.class, () -> registry.call("mc.hud.state", Map.of()));
    }

    private static ToolRegistry registry(MinecraftClientBridge bridge) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools.register(registry, bridge, new ScenarioEngine(registry));
        return registry;
    }

    static final class FakeBridge extends BuiltinToolsWindowTest.BasicBridge {
        final List<String> events = new ArrayList<>();
        boolean hidden;
        public Map<String, Object> hudState() { return Map.of("hidden", hidden); }
        public Map<String, Object> setHud(boolean hidden) { this.hidden = hidden; return hudState(); }
        public Map<String, Object> playCamera(Map<String, Object> args) { events.add("play " + args); return Map.of("status", "ok"); }
        public Map<String, Object> stopCamera() { events.add("stop"); return Map.of("status", "idle"); }
        public Map<String, Object> startRecording(Map<String, Object> args) { events.add("record " + args); return Map.of("status", "recording"); }
        public Map<String, Object> stopRecording() { events.add("record stop"); return Map.of("status", "stopped"); }
        public Map<String, Object> recordingState() { events.add("state"); return Map.of("recording", false); }
    }
}
