package io.izzel.minecraftmcp.film;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One camera move, parsed from a shot object. See docs/film-tools.md for the format.
 *
 * <p>Presets: {@code path} (the default) flies through {@code camera} keyframes; {@code orbit} circles a {@code center}
 * point or a {@code target} entity; {@code follow} flies above a {@code path} (a road, a river) looking ahead along it.
 */
public final class Shot {
    public enum Preset { PATH, ORBIT, FOLLOW }

    /** Where a {@code path} shot looks. */
    public enum Look { POINTS, POINT, TARGET, ANGLES, FORWARD }

    static final double DEFAULT_SMOOTHING = 0.17;

    final String name;
    final double seconds;
    final Easing easing;
    final boolean arcLength;
    final Preset preset;
    final List<double[]> camera;
    final Look look;
    final List<double[]> lookPoints;
    final double[] lookPoint;
    final List<double[]> angles;
    final Map<String, Object> target;
    final Double pitchMin;
    final Double pitchMax;
    final double smoothing;
    final List<double[]> fov;
    final List<double[]> roll;
    final int clearance;
    final boolean preload;
    final double[] center;
    final double radius;
    final double height;
    final double startAngle;
    final double degrees;
    final double lookHeight;
    final double back;
    final double lookAhead;

    private Shot(Map<String, Object> m, int index) {
        name = String.valueOf(m.getOrDefault("name", "shot" + (index + 1)));
        seconds = number(m.getOrDefault("seconds", m.getOrDefault("duration", 6)), "seconds");
        if (!(seconds > 0)) throw new IllegalArgumentException(name + ": seconds must be positive");
        easing = Boolean.TRUE.equals(m.get("linear")) ? Easing.LINEAR : Easing.parse(m.get("easing"));
        String spacing = String.valueOf(m.getOrDefault("spacing", "arc")).toLowerCase(Locale.ROOT);
        if (!spacing.equals("arc") && !spacing.equals("uniform")) throw new IllegalArgumentException(name + ": spacing must be arc or uniform");
        arcLength = spacing.equals("arc");
        String presetName = String.valueOf(m.getOrDefault("preset", "path")).toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        preset = switch (presetName) {
            case "path", "flythrough" -> Preset.PATH;
            case "orbit", "orbitentity" -> Preset.ORBIT;
            case "follow", "followpath" -> Preset.FOLLOW;
            default -> throw new IllegalArgumentException(name + ": unknown preset " + m.get("preset") + " (expected path, orbit or follow)");
        };
        target = m.get("target") instanceof Map<?, ?> t ? stringKeys(t) : null;
        Object cameraValue = m.containsKey("camera") ? m.get("camera") : m.containsKey("cam") ? m.get("cam") : m.get("path");
        camera = preset == Preset.ORBIT ? List.of() : points(cameraValue, name + ": " + (preset == Preset.FOLLOW ? "path" : "camera"));
        if (preset != Preset.ORBIT && camera.isEmpty()) throw new IllegalArgumentException(name + ": " + (preset == Preset.FOLLOW ? "path" : "camera") + " keyframes are required");
        Object lookValue = m.get("look");
        Object lookAtValue = m.get("lookAt");
        if (lookValue != null) {
            look = Look.POINTS;
            lookPoints = points(lookValue, name + ": look");
            lookPoint = null;
        } else if (lookAtValue instanceof List<?> list && !list.isEmpty() && (list.get(0) instanceof List<?> || list.get(0) instanceof Map<?, ?>)) {
            look = Look.POINTS;
            lookPoints = points(lookAtValue, name + ": lookAt");
            lookPoint = null;
        } else if (lookAtValue != null) {
            look = Look.POINT;
            lookPoints = List.of();
            lookPoint = point(lookAtValue, name + ": lookAt");
        } else if (target != null) {
            look = Look.TARGET;
            lookPoints = List.of();
            lookPoint = null;
        } else if (m.get("angles") != null || m.get("yaw") != null) {
            look = Look.ANGLES;
            lookPoints = List.of();
            lookPoint = null;
        } else {
            look = Look.FORWARD;
            lookPoints = List.of();
            lookPoint = null;
        }
        if (look == Look.POINTS && lookPoints.isEmpty()) throw new IllegalArgumentException(name + ": look keyframes are empty");
        if (look == Look.ANGLES) {
            List<double[]> parsed = m.get("angles") != null ? tuples(m.get("angles"), 2, name + ": angles") : List.of(new double[] {number(m.get("yaw"), "yaw"), number(m.getOrDefault("pitch", 0), "pitch")});
            List<double[]> copy = new ArrayList<>();
            for (double[] a : parsed) copy.add(a.clone());
            CameraMath.unwrapYaw(copy);
            angles = copy;
        } else {
            angles = List.of();
        }
        pitchMin = m.get("pitchMin") == null ? null : number(m.get("pitchMin"), "pitchMin");
        pitchMax = m.get("pitchMax") == null ? null : number(m.get("pitchMax"), "pitchMax");
        boolean exactLook = preset == Preset.ORBIT || (preset == Preset.PATH && (look == Look.POINT || look == Look.ANGLES));
        smoothing = Math.max(0, number(m.getOrDefault("smoothing", exactLook ? 0 : DEFAULT_SMOOTHING), "smoothing"));
        fov = m.get("fov") == null ? null : tuples(m.get("fov"), 1, name + ": fov");
        roll = m.get("roll") == null ? null : tuples(m.get("roll"), 1, name + ": roll");
        Object clearanceValue = m.get("clearance");
        clearance = clearanceValue instanceof Boolean b ? (b ? 3 : 0) : clearanceValue instanceof Number n ? Math.max(0, n.intValue()) : 0;
        preload = !Boolean.FALSE.equals(m.get("preload"));
        if (preset == Preset.ORBIT) {
            center = m.get("center") == null ? null : point(m.get("center"), name + ": center");
            if (center == null && target == null) throw new IllegalArgumentException(name + ": orbit needs center or target");
        } else {
            center = null;
        }
        radius = number(m.getOrDefault("radius", 8), "radius");
        height = number(m.getOrDefault("height", preset == Preset.ORBIT ? 3 : 2), "height");
        startAngle = number(m.getOrDefault("startAngle", 0), "startAngle");
        degrees = number(m.getOrDefault("degrees", 90), "degrees");
        lookHeight = number(m.getOrDefault("lookHeight", preset == Preset.FOLLOW ? 1 : 0), "lookHeight");
        back = number(m.getOrDefault("back", 0), "back");
        lookAhead = number(m.getOrDefault("lookAhead", 12), "lookAhead");
    }

    public static Shot parse(Map<String, Object> shot, int index) {
        return new Shot(shot, index);
    }

    /** Shots from {@code shots} (a list), {@code shot} (one object), or shot fields given at the top level. */
    public static List<Shot> parseAll(Map<String, Object> args) {
        List<Shot> shots = new ArrayList<>();
        if (args.get("shots") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("each shot must be an object");
                shots.add(parse(stringKeys(map), shots.size()));
            }
        } else if (args.get("shot") instanceof Map<?, ?> map) {
            shots.add(parse(stringKeys(map), 0));
        } else {
            shots.add(parse(args, 0));
        }
        if (shots.isEmpty()) throw new IllegalArgumentException("shots must not be empty");
        return shots;
    }

    public String name() { return name; }
    public double seconds() { return seconds; }
    public int clearance() { return clearance; }
    public boolean preload() { return preload; }
    public Preset preset() { return preset; }
    public Look look() { return look; }
    /** The entity query for {@code target}, or {@code null}. */
    public Map<String, Object> target() { return target; }
    /** Whether the camera itself moves with the target entity (an orbit around a target). */
    public boolean cameraFollowsTarget() { return preset == Preset.ORBIT && center == null; }

    public int frames(int fps) {
        return Math.max(1, (int) Math.round(seconds * fps));
    }

    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("preset", preset.name().toLowerCase(Locale.ROOT));
        map.put("seconds", seconds);
        map.put("easing", easing.id());
        return map;
    }

    static double number(Object value, String field) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    static double[] point(Object value, String field) {
        return tuple(value, 3, field);
    }

    static List<double[]> points(Object value, String field) {
        return tuples(value, 3, field);
    }

    /** A list of tuples; a bare tuple (or a bare number for one dimension) counts as a list of one. */
    static List<double[]> tuples(Object value, int dims, String field) {
        if (value == null) return List.of();
        if (value instanceof Number || value instanceof Map<?, ?>) return List.of(tuple(value, dims, field));
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(field + " must be a list");
        if (!list.isEmpty() && list.get(0) instanceof Number && dims > 1) return List.of(tuple(value, dims, field));
        List<double[]> out = new ArrayList<>();
        for (Object item : list) out.add(tuple(item, dims, field));
        return out;
    }

    static double[] tuple(Object value, int dims, String field) {
        if (dims == 1 && value instanceof Number n) return new double[] {n.doubleValue()};
        if (value instanceof Map<?, ?> map && dims == 3) {
            return new double[] {number(map.get("x"), field + ".x"), number(map.get("y"), field + ".y"), number(map.get("z"), field + ".z")};
        }
        if (value instanceof List<?> list && list.size() >= dims) {
            double[] out = new double[dims];
            for (int i = 0; i < dims; i++) out[i] = number(list.get(i), field + "[" + i + "]");
            return out;
        }
        throw new IllegalArgumentException(field + " must be " + (dims == 3 ? "[x,y,z] or {x,y,z}" : dims == 2 ? "[yaw,pitch]" : "a number"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
