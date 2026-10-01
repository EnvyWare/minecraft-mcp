package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.neoforge.mixin.AbstractSelectionListAccessor;
import io.izzel.minecraftmcp.screen.WidgetPath;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Describes a screen's widget tree for mc.screen.state and resolves widget ids for mc.screen.widget.click.
 * Top-level children keep their historical ids ("widget-N"); children of containers and list rows get
 * dotted ids ("widget-N.M").
 */
final class NeoForgeScreenTree {
    private static final int MAX_DEPTH = 8;

    private NeoForgeScreenTree() {}

    /** A widget with its id path and, when known, its screen bounds. */
    record Node(List<Integer> path, GuiEventListener listener, Integer x, Integer y, Integer width, Integer height, Boolean inView, String message) {
        boolean hasBounds() {
            return x != null && width != null && width > 0 && height != null && height > 0;
        }
        double centerX() { return x + width / 2.0; }
        double centerY() { return y + height / 2.0; }
        String id() { return WidgetPath.id(path); }
    }

    static List<Map<String, Object>> describe(Screen screen) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<? extends GuiEventListener> children = screen.children();
        for (int i = 0; i < children.size(); i++) result.add(describe(node(List.of(i), children.get(i), null), 1));
        return result;
    }

    /** All nodes, depth first, in the same order as {@link #describe(Screen)}. */
    static List<Node> flatten(Screen screen) {
        List<Node> nodes = new ArrayList<>();
        List<? extends GuiEventListener> children = screen.children();
        for (int i = 0; i < children.size(); i++) collect(node(List.of(i), children.get(i), null), 1, nodes);
        return nodes;
    }

    static Optional<Node> find(Screen screen, List<Integer> path) {
        if (path.isEmpty()) return Optional.empty();
        return flatten(screen).stream().filter(node -> node.path().equals(path)).findFirst();
    }

    private static void collect(Node node, int depth, List<Node> out) {
        out.add(node);
        if (depth >= MAX_DEPTH || !(node.listener() instanceof ContainerEventHandler container)) return;
        List<? extends GuiEventListener> children = container.children();
        for (int i = 0; i < children.size(); i++) collect(node(append(node.path(), i), children.get(i), container), depth + 1, out);
    }

    private static Map<String, Object> describe(Node node, int depth) {
        Map<String, Object> entry = new LinkedHashMap<>();
        GuiEventListener listener = node.listener();
        entry.put("index", node.path().get(node.path().size() - 1));
        entry.put("id", node.id());
        entry.put("class", listener.getClass().getName());
        if (node.hasBounds()) {
            entry.put("x", node.x());
            entry.put("y", node.y());
            entry.put("width", node.width());
            entry.put("height", node.height());
        }
        if (node.message() != null) entry.put("message", node.message());
        if (listener instanceof AbstractWidget widget) {
            entry.put("active", widget.active);
            entry.put("visible", widget.visible);
        }
        if (node.inView() != null) entry.put("inView", node.inView());
        if (depth < MAX_DEPTH && listener instanceof ContainerEventHandler container && !container.children().isEmpty()) {
            List<Map<String, Object>> nested = new ArrayList<>();
            List<? extends GuiEventListener> children = container.children();
            for (int i = 0; i < children.size(); i++) nested.add(describe(node(append(node.path(), i), children.get(i), container), depth + 1));
            entry.put("children", nested);
        }
        return entry;
    }

    private static Node node(List<Integer> path, GuiEventListener listener, ContainerEventHandler parent) {
        String message = listener instanceof AbstractWidget widget ? widget.getMessage().getString()
                : listener instanceof ObjectSelectionList.Entry<?> entry ? entry.getNarration().getString() : null;
        if (listener instanceof AbstractWidget widget) {
            return new Node(path, listener, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), null, message);
        }
        if (parent instanceof AbstractSelectionList<?> list) {
            int row = path.get(path.size() - 1);
            AbstractSelectionListAccessor accessor = (AbstractSelectionListAccessor) list;
            int top = accessor.minecraftMcp$getRowTop(row);
            int bottom = accessor.minecraftMcp$getRowBottom(row);
            boolean inView = top >= list.getY() && bottom <= list.getBottom();
            return new Node(path, listener, list.getRowLeft(), top, list.getRowWidth(), bottom - top, inView, message);
        }
        ScreenRectangle rectangle = listener.getRectangle();
        if (rectangle.width() > 0 && rectangle.height() > 0) {
            return new Node(path, listener, rectangle.left(), rectangle.top(), rectangle.width(), rectangle.height(), null, message);
        }
        return new Node(path, listener, null, null, null, null, null, message);
    }

    private static List<Integer> append(List<Integer> path, int index) {
        List<Integer> result = new ArrayList<>(path);
        result.add(index);
        return List.copyOf(result);
    }
}
