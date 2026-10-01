package io.izzel.minecraftmcp.entity;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Loader-independent entity filtering and selection for the mc.entity.* and mc.player.look_at_entity tools. */
public final class EntitySelector {
    public static final double DEFAULT_RADIUS = 32.0D;

    private EntitySelector() {}

    public record Candidate(int id, String uuid, String type, double distance, boolean self) {}

    /** Selects by id, then uuid, then the nearest entity of a type within the radius. */
    public record Query(Integer id, String uuid, String type, double radius) {
        public static Query from(Map<String, Object> args) {
            Integer id = args.get("id") instanceof Number number ? number.intValue() : null;
            String uuid = blankToNull(args.get("uuid"));
            String type = blankToNull(args.get("type"));
            double radius = args.get("radius") instanceof Number number ? number.doubleValue() : DEFAULT_RADIUS;
            if (id == null && uuid == null && type == null) throw new IllegalArgumentException("one of id, uuid or type is required");
            return new Query(id, uuid, type, radius);
        }

        public Map<String, Object> describe() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (id != null) map.put("id", id);
            if (uuid != null) map.put("uuid", uuid);
            if (type != null) map.put("type", normalizeType(type));
            map.put("radius", radius);
            return map;
        }
    }

    public static Optional<Candidate> select(Query query, List<Candidate> candidates) {
        if (query.id() != null) return candidates.stream().filter(c -> c.id() == query.id()).findFirst();
        if (query.uuid() != null) return candidates.stream().filter(c -> c.uuid().equalsIgnoreCase(query.uuid())).findFirst();
        return filter(candidates, query.type(), query.radius(), 1, false).stream().findFirst();
    }

    /** Entities matching the optional type within the radius ({@code <= 0} for any distance), nearest first. */
    public static List<Candidate> filter(List<Candidate> candidates, String type, double radius, int limit, boolean includeSelf) {
        String wanted = type == null || type.isBlank() ? null : normalizeType(type);
        return candidates.stream()
                .filter(c -> includeSelf || !c.self())
                .filter(c -> radius <= 0 || c.distance() <= radius)
                .filter(c -> wanted == null || wanted.equals(normalizeType(c.type())))
                .sorted(Comparator.comparingDouble(Candidate::distance).thenComparingInt(Candidate::id))
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
    }

    public static String normalizeType(String type) {
        String trimmed = type.trim().toLowerCase(Locale.ROOT);
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static String blankToNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
