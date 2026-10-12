package io.izzel.minecraftmcp.film;

import io.izzel.minecraftmcp.json.Json;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code mc.film.run}: sets up the world for a {@link FilmScene} through the normal tools, runs its shots with
 * {@code mc.camera.play}, and returns a compact report. Everything goes through the tool registry, so a scene does
 * exactly what an agent calling the same tools would.
 */
public final class FilmRunner {
    private final ToolRegistry registry;
    private final ScenarioEngine scenarios;
    private final Path gameDirectory;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public FilmRunner(ToolRegistry registry, ScenarioEngine scenarios, Path gameDirectory) {
        this(registry, scenarios, gameDirectory, Thread::sleep);
    }

    FilmRunner(ToolRegistry registry, ScenarioEngine scenarios, Path gameDirectory, Sleeper sleeper) {
        this.registry = registry;
        this.scenarios = scenarios;
        this.gameDirectory = gameDirectory;
        this.sleeper = sleeper;
    }

    public Map<String, Object> run(Map<String, Object> args) throws Exception {
        long started = System.nanoTime();
        FilmScene scene = FilmScene.parse(load(args.get("scene")));
        boolean draft = Boolean.parseBoolean(String.valueOf(args.getOrDefault("draft", false)));
        List<String> only = names(args.get("shots"));
        Map<String, Object> playArgs = scene.playArgs(draft, args.get("output") == null ? null : String.valueOf(args.get("output")), only);
        List<String> warnings = new ArrayList<>();
        Map<String, Object> setupReport = new LinkedHashMap<>();
        if (!Boolean.parseBoolean(String.valueOf(args.getOrDefault("skipSetup", false)))) setup(scene, setupReport, warnings);
        double setupSeconds = seconds(System.nanoTime() - started);
        double[] origin = origin(scene);
        if (origin != null) playArgs.put("shots", FilmScene.offsetShots(shots(playArgs), origin));
        Map<String, Object> report = asMap(registry.call("mc.camera.play", playArgs));
        Map<String, Object> result = compact(scene, report, draft, setupSeconds, seconds(System.nanoTime() - started), setupReport, warnings);
        if (origin != null) result.put("origin", List.of(origin[0], origin[1], origin[2]));
        if (Boolean.parseBoolean(String.valueOf(args.getOrDefault("verbose", false)))) result.put("cameraReport", report);
        return result;
    }

    /** A scene object, or the path of a JSON scene file (relative paths are under the game directory). */
    @SuppressWarnings("unchecked")
    Map<String, Object> load(Object scene) throws Exception {
        if (scene instanceof Map<?, ?> map) return Shot.stringKeys(map);
        if (scene == null || String.valueOf(scene).isBlank()) throw new IllegalArgumentException("scene is required: a scene object or the path of a scene JSON file");
        Path path = Path.of(String.valueOf(scene));
        if (!path.isAbsolute()) path = gameDirectory.resolve(path);
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) throw new IllegalArgumentException("YAML scenes are not supported; write the scene as JSON");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("scene file not found: " + path);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("a scene file must hold a JSON object");
        return Shot.stringKeys(map);
    }

    private void setup(FilmScene scene, Map<String, Object> report, List<String> warnings) throws Exception {
        if (scene.world() != null) {
            report.put("world", ensureWorld(scene));
        } else if (!Boolean.TRUE.equals(clientState().get("rawInWorld"))) {
            throw new IllegalStateException("the client is not in a world and the scene has no setup.world");
        }
        for (String command : scene.commands()) registry.call("mc.command.run", Map.of("command", command));
        if (!scene.commands().isEmpty()) {
            try {
                // a command suggestion round trip: the server has handled every command sent before it
                registry.call("mc.server.sync", Map.of("timeoutMs", 30000));
            } catch (Exception e) {
                warnings.add("could not confirm the setup commands ran: " + e.getMessage());
            }
        }
        report.put("commands", scene.commands().size());
        int steps = 0;
        for (Map<String, Object> step : scene.steps()) {
            String id = String.valueOf(step.getOrDefault("id", step.get("tool")));
            try {
                scenarios.runStep(step);
                steps++;
            } catch (Exception | AssertionError e) {
                String message = "setup step " + id + " failed: " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage());
                if (!Boolean.TRUE.equals(step.get("optional"))) throw new IllegalStateException(message, e);
                warnings.add(message);
            }
        }
        report.put("steps", steps);
    }

    /** Joins the scene's world unless the client is already in it; returns what it did. */
    private String ensureWorld(FilmScene scene) throws Exception {
        String name = String.valueOf(scene.world().get("name"));
        long deadline = System.currentTimeMillis() + (long) (scene.worldTimeoutSeconds() * 1000);
        if (Boolean.TRUE.equals(clientState().get("rawInWorld"))) {
            Object current = asMap(registry.call("mc.world.snapshot", Map.of())).get("levelName");
            if (name.equals(current)) return "already in " + name;
            registry.call("mc.world.leave", Map.of());
            while (Boolean.TRUE.equals(clientState().get("rawInWorld"))) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timed out leaving world " + current);
                sleeper.sleep(500);
            }
        }
        Map<String, Object> joined = asMap(registry.call("mc.world.join", scene.world()));
        while (true) {
            Map<String, Object> state = clientState();
            if (Boolean.TRUE.equals(state.get("rawInWorld")) && !Boolean.TRUE.equals(state.get("loading"))) break;
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timed out after " + scene.worldTimeoutSeconds() + " s waiting to be in world " + name);
            sleeper.sleep(1000);
        }
        return (Boolean.TRUE.equals(joined.get("created")) ? "created " : "joined ") + name;
    }

    /** The point shot coordinates are relative to: the world spawn (block centre) for {@code "spawn"}. */
    private double[] origin(FilmScene scene) throws Exception {
        Object origin = scene.origin();
        if (origin instanceof double[] point) return point;
        if (!"spawn".equals(origin)) return null;
        Object spawn = asMap(registry.call("mc.world.snapshot", Map.of())).get("spawn");
        if (!(spawn instanceof Map<?, ?> s)) throw new IllegalStateException("origin spawn: the world spawn is not known (not in a world?)");
        return new double[] {Shot.number(s.get("x"), "spawn.x") + 0.5, Shot.number(s.get("y"), "spawn.y"), Shot.number(s.get("z"), "spawn.z") + 0.5};
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shots(Map<String, Object> playArgs) {
        return (List<Map<String, Object>>) playArgs.get("shots");
    }

    private Map<String, Object> clientState() throws Exception {
        return asMap(registry.call("mc.client.state", Map.of()));
    }

    /** The parts of the camera report an agent needs to judge a take, without per-frame detail. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> compact(FilmScene scene, Map<String, Object> report, boolean draft, double setupSeconds, double wallSeconds, Map<String, Object> setup, List<String> setupWarnings) {
        Map<String, Object> output = report.get("output") instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of();
        Map<String, Object> timing = report.get("timing") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", report.getOrDefault("status", "ok"));
        result.put("scene", scene.name());
        result.put("output", output.get("path"));
        result.put("format", output.get("format"));
        result.put("width", output.get("width"));
        result.put("height", output.get("height"));
        result.put("fps", report.get("fps"));
        result.put("frames", report.get("frames"));
        result.put("seconds", report.get("seconds"));
        result.put("draft", draft);
        Map<String, Object> times = new LinkedHashMap<>();
        times.put("wallSeconds", round(wallSeconds));
        times.put("setupSeconds", round(setupSeconds));
        times.put("preloadSeconds", timing.get("preloadSeconds"));
        times.put("captureSeconds", timing.get("captureSeconds"));
        times.put("captureFps", timing.get("captureFps"));
        times.put("encodeTailSeconds", timing.get("encodeTailSeconds"));
        times.put("forcedFrames", timing.get("forcedFrames"));
        result.put("timing", times);
        result.put("quality", report.get("quality"));
        result.put("contactSheet", report.get("contactSheet"));
        List<Map<String, Object>> shots = new ArrayList<>();
        if (report.get("shots") instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> shot = (Map<String, Object>) item;
                Map<String, Object> s = new LinkedHashMap<>();
                for (String key : List.of("name", "frames", "lift", "captureFps", "forcedFrames", "maxSpeed", "maxTurnRate")) {
                    if (shot.containsKey(key)) s.put(key, shot.get(key));
                }
                shots.add(s);
            }
        }
        result.put("shots", shots);
        if (!setup.isEmpty()) result.put("setup", setup);
        List<String> warnings = new ArrayList<>(setupWarnings);
        if (report.get("warnings") instanceof List<?> list) list.forEach(w -> warnings.add(String.valueOf(w)));
        result.put("warnings", warnings);
        return result;
    }

    private static List<String> names(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
