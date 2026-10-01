package io.izzel.minecraftmcp.screen;

import java.util.ArrayList;
import java.util.List;

/** Widget ids from mc.screen.state: "widget-3" for a top-level child, "widget-3.0.2" for nested children. */
public final class WidgetPath {
    private static final String PREFIX = "widget-";

    private WidgetPath() {}

    public static String id(List<Integer> path) {
        StringBuilder builder = new StringBuilder(PREFIX);
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) builder.append('.');
            builder.append(path.get(i));
        }
        return builder.toString();
    }

    /** Returns the index path, or an empty list when the id is not a widget id. */
    public static List<Integer> parse(String id) {
        if (id == null || !id.startsWith(PREFIX) || id.length() == PREFIX.length()) return List.of();
        List<Integer> path = new ArrayList<>();
        for (String part : id.substring(PREFIX.length()).split("\\.", -1)) {
            try {
                int index = Integer.parseInt(part);
                if (index < 0) return List.of();
                path.add(index);
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return List.copyOf(path);
    }
}
