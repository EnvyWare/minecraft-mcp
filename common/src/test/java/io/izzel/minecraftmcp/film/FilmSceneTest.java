package io.izzel.minecraftmcp.film;

import io.izzel.minecraftmcp.json.Json;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilmSceneTest {
    @TempDir
    Path dir;

    private static final String SCENE = """
            {
              "name": "valley",
              "setup": {
                "world": {"name": "film_valley", "seed": 42},
                "time": 1000,
                "weather": "clear",
                "gamerules": {"randomTickSpeed": 0, "doDaylightCycle": true},
                "clearEntities": true,
                "commands": ["/effect give @s minecraft:night_vision infinite 0 true"],
                "steps": [
                  {"tool": "pixelmon.party.set", "args": {"pokemon": ["charizard lvl:50"]}, "optional": true},
                  {"tool": "test.echo", "args": {"value": 3}, "expect": {"result.value": 3}}
                ]
              },
              "camera": {"readyRadius": 4},
              "output": {"path": "films/valley.mp4", "width": 1280, "height": 720},
              "draft": {"width": 480, "height": 270, "fps": 12},
              "shots": [
                {"name": "a", "seconds": 2, "camera": [[0, 80, 0], [20, 80, 0]]},
                {"name": "b", "preset": "orbit", "center": [0, 64, 0], "seconds": 2}
              ]
            }
            """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        return (Map<String, Object>) Json.parse(text);
    }

    @Test
    void setupBecomesQuietOrderedCommands() {
        FilmScene scene = FilmScene.parse(json(SCENE));
        assertEquals("{name=film_valley, seed=42, structures=true}", scene.world().toString());
        List<String> commands = scene.commands();
        assertEquals("gamerule sendCommandFeedback false", commands.get(0));
        assertEquals("gamemode spectator", commands.get(1));
        assertTrue(commands.contains("gamerule doDaylightCycle true"), "explicit gamerules override the quiet ones");
        assertFalse(commands.contains("gamerule doDaylightCycle false"));
        assertTrue(commands.contains("gamerule doMobSpawning false"));
        assertTrue(commands.contains("gamerule randomTickSpeed 0"));
        assertTrue(commands.indexOf("time set 1000") < commands.indexOf("weather clear"));
        assertTrue(commands.contains("execute as @e[type=!minecraft:player] run kill @s"));
        assertEquals("effect give @s minecraft:night_vision infinite 0 true", commands.get(commands.size() - 1));
    }

    @Test
    void playArgsMergeOutputCameraDraftAndShotFilter() {
        FilmScene scene = FilmScene.parse(json(SCENE));
        Map<String, Object> full = scene.playArgs(false, null, null);
        assertEquals("films/valley.mp4", full.get("output"));
        assertEquals(1280L, full.get("width"));
        assertEquals(4L, full.get("readyRadius"));
        assertEquals(2, ((List<?>) full.get("shots")).size());

        Map<String, Object> draft = scene.playArgs(true, null, List.of("b"));
        assertEquals("films/valley-draft.mp4", draft.get("output"));
        assertEquals(480L, draft.get("width"));
        assertEquals(12L, draft.get("fps"));
        assertEquals("ultrafast", draft.get("preset"));
        assertEquals(1, ((List<?>) draft.get("shots")).size());
        assertNull(draft.get("draft"));

        Map<String, Object> noDraftBlock = FilmScene.parse(json("{\"shots\":[{\"camera\":[[0,0,0]]}]}")).playArgs(true, "films/x.mp4", null);
        assertEquals(true, noDraftBlock.get("draft"));
        assertEquals("films/x.mp4", noDraftBlock.get("output"));
        assertThrows(IllegalArgumentException.class, () -> scene.playArgs(false, null, List.of("missing")));
    }

    @Test
    void badScenesFailBeforeAnySetup() {
        assertThrows(IllegalArgumentException.class, () -> FilmScene.parse(json("{\"shots\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> FilmScene.parse(json("{\"shots\":[{\"preset\":\"orbit\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> FilmScene.parse(json("{\"setup\":{\"time\":\"1000; op me\"},\"shots\":[{\"camera\":[[0,0,0]]}]}")));
        assertThrows(IllegalArgumentException.class, () -> FilmScene.parse(json("{\"setup\":{\"world\":{\"seed\":1}},\"shots\":[{\"camera\":[[0,0,0]]}]}")));
    }

    /** A client that starts on the title screen and enters the world one state poll after mc.world.join. */
    static final class FakeClient {
        final List<String> calls = new ArrayList<>();
        boolean inWorld;
        boolean joining;
        String level = "";

        ToolRegistry registry() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(McpTool.of("mc.client.state", "", args -> {
                if (joining) { inWorld = true; joining = false; }
                return Map.of("rawInWorld", inWorld, "loading", false);
            }));
            registry.register(McpTool.of("mc.world.snapshot", "", args -> Map.of("inWorld", inWorld, "levelName", level, "spawn", Map.of("x", 100, "y", 64, "z", -20))));
            registry.register(McpTool.of("mc.world.leave", "", args -> { calls.add("leave"); inWorld = false; return Map.of(); }));
            registry.register(McpTool.of("mc.world.join", "", args -> { calls.add("join " + args); joining = true; level = String.valueOf(args.get("name")); return Map.of("status", "joining", "created", true); }));
            registry.register(McpTool.of("mc.command.run", "", args -> { calls.add("/" + args.get("command")); return Map.of("status", "sent"); }));
            registry.register(McpTool.of("mc.server.sync", "", args -> { calls.add("sync"); return Map.of("status", "synced"); }));
            registry.register(McpTool.of("test.echo", "", args -> { calls.add("echo"); return args; }));
            registry.register(McpTool.of("mc.camera.play", "", args -> {
                calls.add("play " + ((List<?>) args.get("shots")).size() + " " + args.get("output"));
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("status", "ok");
                report.put("output", Map.of("format", "mp4", "path", "C:/films/valley.mp4", "width", 1280, "height", 720));
                report.put("frames", 120);
                report.put("fps", 30);
                report.put("seconds", 4.0);
                report.put("quality", Map.of("verdict", "ok"));
                report.put("contactSheet", "C:/films/valley.contact.png");
                report.put("timing", Map.of("captureFps", 60.0, "preloadSeconds", 1.0, "captureSeconds", 2.0));
                report.put("shots", List.of(Map.of("name", "a", "frames", 60, "lift", 0, "preloadStops", 3), Map.of("name", "b", "frames", 60, "lift", 2)));
                report.put("warnings", List.of("camera warning"));
                return report;
            }));
            return registry;
        }
    }

    @Test
    void runJoinsSetsUpPlaysAndReportsCompactly() throws Exception {
        FakeClient client = new FakeClient();
        ToolRegistry registry = client.registry();
        Files.writeString(dir.resolve("valley.json"), "\uFEFF" + SCENE, StandardCharsets.UTF_8);
        FilmRunner runner = new FilmRunner(registry, new ScenarioEngine(registry), dir, millis -> {});

        Map<String, Object> result = runner.run(Map.of("scene", "valley.json"));

        assertEquals("join {name=film_valley, seed=42, structures=true}", client.calls.get(0));
        assertTrue(client.calls.contains("sync"));
        assertTrue(client.calls.contains("echo"));
        assertEquals("play 2 films/valley.mp4", client.calls.get(client.calls.size() - 1));
        assertEquals("ok", result.get("status"));
        assertEquals("C:/films/valley.mp4", result.get("output"));
        assertEquals(120, result.get("frames"));
        assertEquals("C:/films/valley.contact.png", result.get("contactSheet"));
        assertEquals(60.0, ((Map<?, ?>) result.get("timing")).get("captureFps"));
        assertEquals(Map.of("name", "a", "frames", 60, "lift", 0), ((List<?>) result.get("shots")).get(0));
        List<?> warnings = (List<?>) result.get("warnings");
        assertTrue(warnings.get(0).toString().startsWith("setup step pixelmon.party.set failed"), String.valueOf(warnings));
        assertEquals("camera warning", warnings.get(1));
        assertEquals("created film_valley", ((Map<?, ?>) result.get("setup")).get("world"));
    }

    @Test
    void runReusesTheCurrentWorldAndCanSkipSetup() throws Exception {
        FakeClient client = new FakeClient();
        client.inWorld = true;
        client.level = "film_valley";
        ToolRegistry registry = client.registry();
        FilmRunner runner = new FilmRunner(registry, new ScenarioEngine(registry), dir, millis -> {});

        Map<String, Object> result = runner.run(Map.of("scene", json(SCENE), "draft", true, "shots", "a"));
        assertFalse(client.calls.stream().anyMatch(c -> c.startsWith("join")));
        assertEquals("play 1 films/valley-draft.mp4", client.calls.get(client.calls.size() - 1));
        assertEquals("already in film_valley", ((Map<?, ?>) result.get("setup")).get("world"));

        client.calls.clear();
        runner.run(Map.of("scene", json(SCENE), "skipSetup", true));
        assertEquals(List.of("play 2 films/valley.mp4"), client.calls);
    }

    @Test
    void spawnOriginMovesEveryShotCoordinate() throws Exception {
        FakeClient client = new FakeClient();
        client.inWorld = true;
        client.level = "film_valley";
        ToolRegistry registry = client.registry();
        List<Object> played = new ArrayList<>();
        registry.register(McpTool.of("mc.camera.play", "", args -> { played.add(args.get("shots")); return Map.of("status", "ok"); }));
        FilmRunner runner = new FilmRunner(registry, new ScenarioEngine(registry), dir, millis -> {});
        String scene = "{\"origin\":\"spawn\",\"shots\":[{\"camera\":[[0,10,0],{\"x\":1,\"y\":2,\"z\":3}],\"lookAt\":[0,0,5]},{\"preset\":\"orbit\",\"center\":[0,0,0]}]}";

        Map<String, Object> result = runner.run(Map.of("scene", json(scene), "skipSetup", true));

        assertEquals(List.of(100.5, 64.0, -19.5), result.get("origin"));
        List<?> shots = (List<?>) played.get(0);
        Map<?, ?> first = (Map<?, ?>) shots.get(0);
        assertEquals(List.of(List.of(100.5, 74.0, -19.5), List.of(101.5, 66.0, -16.5)), first.get("camera"));
        assertEquals(List.of(100.5, 64.0, -14.5), first.get("lookAt"));
        assertEquals(List.of(100.5, 64.0, -19.5), ((Map<?, ?>) shots.get(1)).get("center"));
    }

    @Test
    void runFailsOnARequiredStepAndOnMissingOrYamlFiles() throws Exception {
        FakeClient client = new FakeClient();
        ToolRegistry registry = client.registry();
        FilmRunner runner = new FilmRunner(registry, new ScenarioEngine(registry), dir, millis -> {});
        String failing = SCENE.replace("\"expect\": {\"result.value\": 3}", "\"expect\": {\"result.value\": 4}");
        Exception e = assertThrows(IllegalStateException.class, () -> runner.run(Map.of("scene", json(failing))));
        assertTrue(e.getMessage().startsWith("setup step test.echo failed"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> runner.run(Map.of("scene", "missing.json")));
        assertThrows(IllegalArgumentException.class, () -> runner.run(Map.of("scene", "scene.yaml")));
    }
}
