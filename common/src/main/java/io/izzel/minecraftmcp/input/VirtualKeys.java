package io.izzel.minecraftmcp.input;

import java.util.concurrent.ConcurrentHashMap;

/**
 * GLFW key codes held down by MCP input. Loaders report these as pressed from {@code InputConstants.isKeyDown}, so
 * code that polls the keyboard (Screen.hasShiftDown, F3 debug combos) sees MCP-held keys like real ones.
 */
public final class VirtualKeys {
    private static final ConcurrentHashMap<Integer, Integer> HELD = new ConcurrentHashMap<>();

    private VirtualKeys() {}

    public static void press(int key) {
        HELD.merge(key, 1, Integer::sum);
    }

    public static void release(int key) {
        HELD.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
    }

    public static boolean isDown(int key) {
        return HELD.containsKey(key);
    }

    public static void releaseAll() {
        HELD.clear();
    }
}
