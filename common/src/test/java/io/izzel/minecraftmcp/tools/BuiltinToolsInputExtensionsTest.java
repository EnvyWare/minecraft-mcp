package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.ClientSnapshot;
import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.entity.EntitySelector;
import io.izzel.minecraftmcp.input.KeyChord;
import io.izzel.minecraftmcp.mcp.FutureResult;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinToolsInputExtensionsTest {
    @Test
    void plainKeyPressKeepsHistoricalResultAndBridgeCall() throws Exception {
        FakeBridge bridge = new FakeBridge();

        Object result = registry(bridge).call("mc.keyboard.press", Map.of("key", "E"));

        assertEquals(Map.of("status", "pressed", "key", "E"), result);
        assertEquals(List.of("press:E"), bridge.events);
    }

    @Test
    void keyPressWithModifiersAndRoutePassesChord() throws Exception {
        FakeBridge bridge = new FakeBridge();

        Map<?, ?> result = (Map<?, ?>) registry(bridge).call("mc.keyboard.press", Map.of("key", "Q", "modifiers", List.of("F3"), "route", "keyboard_handler"));

        assertEquals("pressed", result.get("status"));
        assertEquals(List.of("F3"), result.get("modifiers"));
        assertEquals("keyboard_handler", result.get("route"));
        assertEquals(true, result.get("handled"));
        assertEquals(List.of("chord:Q:[F3]:0:true"), bridge.events);
    }

    @Test
    void unknownRouteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> registry(new FakeBridge()).call("mc.keyboard.press", Map.of("key", "E", "route", "os")));
    }

    @Test
    void bridgesWithoutChordSupportRejectModifiers() {
        MinecraftClientBridge basic = new BasicBridge();
        assertThrows(Exception.class, () -> registry(basic).call("mc.keyboard.press", Map.of("key", "E", "modifiers", "shift")));
    }

    @Test
    void dragAndScrollDelegateWithDefaults() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ToolRegistry registry = registry(bridge);

        registry.call("mc.screen.mouse.drag", Map.of("fromX", 1, "fromY", 2, "toX", 30, "toY", 40));
        registry.call("mc.screen.scroll", Map.of("x", 5, "y", 6));
        registry.call("mc.screen.scroll", Map.of("x", 5, "y", 6, "amount", -3, "horizontal", 1));

        assertEquals(List.of("drag:1.0,2.0->30.0,40.0:0:5", "scroll:5.0,6.0:0.0,1.0", "scroll:5.0,6.0:1.0,-3.0"), bridge.events);
    }

    @Test
    void dragRequiresCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> registry(new FakeBridge()).call("mc.screen.mouse.drag", Map.of("fromX", 1)));
    }

    @Test
    void entityToolsParseSelectorsAndActions() throws Exception {
        FakeBridge bridge = new FakeBridge();
        ToolRegistry registry = registry(bridge);

        registry.call("mc.entity.list", Map.of("type", "pig", "radius", 8));
        registry.call("mc.player.look_at_entity", Map.of("id", 7));
        registry.call("mc.entity.interact", Map.of("type", "cow", "action", "attack"));
        registry.call("mc.entity.interact", Map.of("uuid", "abc", "hand", "off", "look", false));

        assertEquals(List.of(
                "list:pig:8.0:100:false",
                "look:7:null:null",
                "interact:null:null:cow:attack:main:true",
                "interact:null:abc:null:use:offhand:false"
        ), bridge.events);
    }

    @Test
    void entityToolsValidateArguments() {
        ToolRegistry registry = registry(new FakeBridge());
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.player.look_at_entity", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.entity.interact", Map.of("id", 1, "action", "kiss")));
    }

    private static ToolRegistry registry(MinecraftClientBridge bridge) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools.register(registry, bridge, new ScenarioEngine(registry));
        return registry;
    }

    static class BasicBridge implements MinecraftClientBridge {
        final List<String> events = new ArrayList<>();
        public String loader() { return "test"; }
        public String minecraftVersion() { return "1.21.1"; }
        public Path gameDirectory() { return Path.of("."); }
        public boolean isOnClientThread() { return true; }
        public void execute(Runnable runnable) { runnable.run(); }
        public ClientSnapshot snapshot() { return new ClientSnapshot(true, false, null, null, 0, 0, 0, 0, 0); }
        public void pressKey(String key) { events.add("press:" + key); }
    }

    static final class FakeBridge extends BasicBridge {
        public Map<String, Object> pressKey(String key, KeyChord chord, boolean viaKeyboardHandler) {
            events.add("chord:" + key + ":" + chord.heldKeys() + ":" + chord.modifiers() + ":" + viaKeyboardHandler);
            return Map.of("handled", true);
        }
        public Map<String, Object> dragScreen(double fromX, double fromY, double toX, double toY, int button, int steps) {
            events.add("drag:" + fromX + "," + fromY + "->" + toX + "," + toY + ":" + button + ":" + steps);
            return Map.of("status", "dragged");
        }
        public Map<String, Object> scrollScreen(double x, double y, double horizontal, double vertical) {
            events.add("scroll:" + x + "," + y + ":" + horizontal + "," + vertical);
            return Map.of("status", "scrolled");
        }
        public Map<String, Object> listEntities(String type, double radius, int limit, boolean includeSelf) {
            events.add("list:" + type + ":" + radius + ":" + limit + ":" + includeSelf);
            return Map.of("status", "ok");
        }
        public FutureResult<Map<String, Object>> lookAtEntity(EntitySelector.Query query) {
            events.add("look:" + query.id() + ":" + query.uuid() + ":" + query.type());
            return action(() -> Map.of("status", "looked_at_entity"));
        }
        public FutureResult<Map<String, Object>> interactEntity(EntitySelector.Query query, String action, String hand, boolean look) {
            events.add("interact:" + query.id() + ":" + query.uuid() + ":" + query.type() + ":" + action + ":" + hand + ":" + look);
            return action(() -> Map.of("status", "interacted"));
        }
    }
}
