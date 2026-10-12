package io.izzel.minecraftmcp.film;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A declarative film scene: world and game setup, shots, and output, run by {@code mc.film.run}. Setup {@code steps}
 * use the scenario step format ({@code tool}, {@code args}, {@code expect}). See docs/film-tools.md.
 */
public final class FilmScene {
    /** Gamerules that keep a scene still and quiet while it is filmed; {@code setup.quiet: false} skips them. */
    static final Map<String, Object> QUIET_GAMERULES = quietGamerules();
    static final String CLEAR_ALL = "type=!minecraft:player";
    static final double DEFAULT_WORLD_TIMEOUT_SECONDS = 300;

    final String name;
    final Map<String, Object> world;
    final double worldTimeoutSeconds;
    final List<String> commands;
    final List<Map<String, Object>> steps;
    final List<Map<String, Object>> shots;
    final Map<String, Object> output;
    final Map<String, Object> camera;
    final Map<String, Object> draft;
    final Object origin;

    private FilmScene(String name, Map<String, Object> world, double worldTimeoutSeconds, List<String> commands, List<Map<String, Object>> steps,
                      List<Map<String, Object>> shots, Map<String, Object> output, Map<String, Object> camera, Map<String, Object> draft, Object origin) {
        this.name = name;
        this.world = world;
        this.worldTimeoutSeconds = worldTimeoutSeconds;
        this.commands = commands;
        this.steps = steps;
        this.shots = shots;
        this.output = output;
        this.camera = camera;
        this.draft = draft;
        this.origin = origin;
    }

    public String name() { return name; }
    /** {@code mc.world.join} arguments, or {@code null} to film in whatever world the client is in. */
    public Map<String, Object> world() { return world; }
    public double worldTimeoutSeconds() { return worldTimeoutSeconds; }
    /** Setup commands in the order they run. */
    public List<String> commands() { return commands; }
    public List<Map<String, Object>> steps() { return steps; }
    /** {@code "spawn"}, a fixed {@code double[3]}, or {@code null}: what shot coordinates are relative to. */
    public Object origin() { return origin; }

    public static FilmScene parse(Map<String, Object> scene) {
        String name = String.valueOf(scene.getOrDefault("name", "scene"));
        Map<String, Object> setup = map(scene.get("setup"), "setup");
        Map<String, Object> world = null;
        if (setup.get("world") instanceof String worldName) {
            world = new LinkedHashMap<>(Map.of("name", worldName));
        } else if (setup.get("world") != null) {
            world = new LinkedHashMap<>(map(setup.get("world"), "setup.world"));
            if (world.get("name") == null) throw new IllegalArgumentException("setup.world.name is required");
        }
        // filming wants villages and other structures, unlike mc.world.join's test-world default
        if (world != null) world.putIfAbsent("structures", true);
        double worldTimeout = setup.get("worldTimeoutSeconds") == null ? DEFAULT_WORLD_TIMEOUT_SECONDS : Shot.number(setup.get("worldTimeoutSeconds"), "setup.worldTimeoutSeconds");
        List<String> commands = new ArrayList<>();
        Map<String, Object> gamerules = new LinkedHashMap<>();
        if (!Boolean.FALSE.equals(setup.get("quiet"))) gamerules.putAll(QUIET_GAMERULES);
        gamerules.putAll(map(setup.get("gamerules"), "setup.gamerules"));
        // command feedback off first, so the commands after it do not print to chat
        Object feedback = gamerules.remove("sendCommandFeedback");
        if (feedback != null) commands.add("gamerule sendCommandFeedback " + feedback);
        if (setup.get("gamemode") != null) commands.add("gamemode " + word(setup.get("gamemode"), "setup.gamemode"));
        else commands.add("gamemode spectator");
        gamerules.forEach((rule, value) -> commands.add("gamerule " + word(rule, "gamerule") + " " + word(value, "gamerule " + rule)));
        if (setup.get("time") != null) commands.add("time set " + word(setup.get("time") instanceof Number n ? n.longValue() : setup.get("time"), "setup.time"));
        if (setup.get("weather") != null) commands.add("weather " + word(setup.get("weather"), "setup.weather"));
        Object clear = setup.get("clearEntities");
        if (clear != null && !Boolean.FALSE.equals(clear)) {
            String selector = Boolean.TRUE.equals(clear) ? CLEAR_ALL : String.valueOf(clear).trim();
            if (selector.startsWith("@e[") && selector.endsWith("]")) selector = selector.substring(3, selector.length() - 1);
            // "execute as ... run kill" is silent when nothing matches; then the drops they leave behind
            commands.add("execute as @e[" + selector + "] run kill @s");
            commands.add("execute as @e[type=minecraft:item] run kill @s");
            commands.add("execute as @e[type=minecraft:experience_orb] run kill @s");
        }
        for (Object command : list(setup.get("commands"), "setup.commands")) {
            String text = String.valueOf(command).trim();
            commands.add(text.startsWith("/") ? text.substring(1) : text);
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object step : list(setup.get("steps"), "setup.steps")) {
            Map<String, Object> s = map(step, "setup.steps[]");
            if (s.get("tool") == null) throw new IllegalArgumentException("each setup step needs a tool");
            steps.add(s);
        }
        List<Map<String, Object>> shots = new ArrayList<>();
        for (Object shot : list(scene.get("shots"), "shots")) shots.add(map(shot, "shots[]"));
        if (shots.isEmpty()) throw new IllegalArgumentException("a scene needs at least one shot");
        FilmScene parsed = new FilmScene(name, world, worldTimeout, List.copyOf(commands), List.copyOf(steps), List.copyOf(shots),
                map(scene.get("output"), "output"), map(scene.get("camera"), "camera"), scene.get("draft") == null ? null : map(scene.get("draft"), "draft"), origin(scene.get("origin")));
        // fail on a bad shot now, not after minutes of world setup
        Shot.parseAll(parsed.playArgs(false, null, null));
        return parsed;
    }

    /**
     * Arguments for {@code mc.camera.play}: the output and camera options, the shots (only those named in {@code only},
     * if given), and for a draft either the scene's {@code draft} overrides or the built-in draft settings.
     */
    public Map<String, Object> playArgs(boolean draftRun, String outputOverride, List<String> only) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.putAll(camera);
        args.putAll(output);
        if (output.containsKey("path") && !output.containsKey("output")) args.put("output", args.remove("path"));
        if (draftRun) {
            if (draft != null) {
                args.put("crf", 28);
                args.put("preset", "ultrafast");
                args.putAll(draft);
                args.put("output", draftPath(args.get("output")));
            } else {
                args.put("draft", true);
                args.put("output", draftPath(args.get("output")));
            }
        }
        if (outputOverride != null && !outputOverride.isBlank()) args.put("output", outputOverride);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            if (only == null || only.isEmpty() || only.contains(String.valueOf(shot.get("name")))) selected.add(shot);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("no shot is named " + only);
        args.put("shots", selected);
        return args;
    }

    /** {@code films/x.mp4} becomes {@code films/x-draft.mp4}, so a draft never overwrites the final take. */
    static Object draftPath(Object path) {
        if (path == null) return null;
        String text = String.valueOf(path);
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") ? text.substring(0, text.length() - 4) + "-draft.mp4" : text + "-draft";
    }

    private static Map<String, Object> quietGamerules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("sendCommandFeedback", false);
        rules.put("doDaylightCycle", false);
        rules.put("doWeatherCycle", false);
        rules.put("doMobSpawning", false);
        rules.put("doPatrolSpawning", false);
        rules.put("doTraderSpawning", false);
        rules.put("doInsomnia", false);
        rules.put("doFireTick", false);
        rules.put("announceAdvancements", false);
        return java.util.Collections.unmodifiableMap(rules);
    }

    private static Object origin(Object value) {
        if (value == null) return null;
        if ("spawn".equals(value)) return "spawn";
        return Shot.point(value, "origin");
    }

    /** Shots with every coordinate (camera, path, look, lookAt, center) moved by {@code offset}. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> offsetShots(List<Map<String, Object>> shots, double[] offset) {
        List<Map<String, Object>> moved = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            Map<String, Object> copy = new LinkedHashMap<>(shot);
            for (String key : List.of("camera", "cam", "path", "look", "lookAt", "center")) {
                Object value = copy.get(key);
                if (value instanceof List<?> list && !list.isEmpty() && !(list.get(0) instanceof Number)) {
                    List<Object> points = new ArrayList<>();
                    for (Object point : list) points.add(offsetPoint(point, offset));
                    copy.put(key, points);
                } else if (value != null) {
                    copy.put(key, offsetPoint(value, offset));
                }
            }
            moved.add(copy);
        }
        return moved;
    }

    private static List<Double> offsetPoint(Object point, double[] offset) {
        double[] p = Shot.point(point, "point");
        return List.of(p[0] + offset[0], p[1] + offset[1], p[2] + offset[2]);
    }

    /** One command word: no spaces or command separators, so setup values cannot smuggle extra commands. */
    private static String word(Object value, String field) {
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || !text.matches("[A-Za-z0-9_:.+-]+")) throw new IllegalArgumentException(field + " must be a single word: " + value);
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String field) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException(field + " must be an object");
        return Shot.stringKeys(m);
    }

    private static List<?> list(Object value, String field) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> l)) throw new IllegalArgumentException(field + " must be a list");
        return l;
    }
}
