package io.izzel.minecraftmcp.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import io.izzel.minecraftmcp.input.KeyAliases;
import io.izzel.minecraftmcp.input.KeyChord;
import io.izzel.minecraftmcp.input.VirtualKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keyboard and screen mouse input for the NeoForge client bridge. Render thread only. */
final class NeoForgeInput {
    private NeoForgeInput() {}

    static void pressKey(Minecraft mc, String key) {
        InputConstants.Key keyMapping = InputConstants.getKey(KeyAliases.normalize(key));
        if (mc.screen != null) {
            mc.screen.keyPressed(keyMapping.getValue(), 0, 0);
            if (mc.screen != null) mc.screen.keyReleased(keyMapping.getValue(), 0, 0);
        } else {
            KeyMapping.set(keyMapping, true);
            KeyMapping.click(keyMapping);
            KeyMapping.set(keyMapping, false);
        }
    }

    static Map<String, Object> pressKey(Minecraft mc, String key, KeyChord chord, boolean viaKeyboardHandler) {
        InputConstants.Key main = InputConstants.getKey(KeyAliases.normalize(key));
        List<InputConstants.Key> held = new ArrayList<>();
        for (String name : chord.heldKeys()) held.add(InputConstants.getKey(KeyAliases.normalize(name)));
        int modifiers = chord.modifiers();
        Map<String, Object> result = new LinkedHashMap<>();
        held.forEach(k -> VirtualKeys.press(k.getValue()));
        try {
            if (viaKeyboardHandler) {
                if (main.getType() != InputConstants.Type.KEYSYM) throw new IllegalArgumentException("keyboard_handler route only supports keyboard keys: " + key);
                long window = mc.getWindow().getWindow();
                for (InputConstants.Key k : held) mc.keyboardHandler.keyPress(window, k.getValue(), scancode(k), GLFW.GLFW_PRESS, modifiers);
                mc.keyboardHandler.keyPress(window, main.getValue(), scancode(main), GLFW.GLFW_PRESS, modifiers);
                mc.keyboardHandler.keyPress(window, main.getValue(), scancode(main), GLFW.GLFW_RELEASE, modifiers);
                for (int i = held.size() - 1; i >= 0; i--) mc.keyboardHandler.keyPress(window, held.get(i).getValue(), scancode(held.get(i)), GLFW.GLFW_RELEASE, modifiers);
            } else if (mc.screen != null) {
                Screen screen = mc.screen;
                result.put("handled", screen.keyPressed(main.getValue(), scancode(main), modifiers));
                if (mc.screen != null) mc.screen.keyReleased(main.getValue(), scancode(main), modifiers);
                result.put("screen", screen.getClass().getName());
            } else {
                held.forEach(k -> KeyMapping.set(k, true));
                KeyMapping.set(main, true);
                KeyMapping.click(main);
                KeyMapping.set(main, false);
                for (int i = held.size() - 1; i >= 0; i--) KeyMapping.set(held.get(i), false);
            }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) VirtualKeys.release(held.get(i).getValue());
        }
        return result;
    }

    private static int scancode(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.KEYSYM ? Math.max(0, GLFW.glfwGetKeyScancode(key.getValue())) : 0;
    }

    /** Releases on the current screen, which may differ from the one that received the press (e.g. a button opened a new screen). */
    static boolean release(Minecraft mc, double x, double y, int button) {
        return mc.screen != null && mc.screen.mouseReleased(x, y, button);
    }

    static Map<String, Object> drag(Minecraft mc, double fromX, double fromY, double toX, double toY, int button, int steps) {
        Screen screen = mc.screen;
        if (screen == null) return Map.of("status", "no_screen", "button", button);
        boolean pressed = screen.mouseClicked(fromX, fromY, button);
        double lastX = fromX;
        double lastY = fromY;
        int handledMoves = 0;
        for (int i = 1; i <= steps && mc.screen != null; i++) {
            double x = fromX + (toX - fromX) * i / steps;
            double y = fromY + (toY - fromY) * i / steps;
            if (mc.screen.mouseDragged(x, y, button, x - lastX, y - lastY)) handledMoves++;
            lastX = x;
            lastY = y;
        }
        boolean released = release(mc, toX, toY, button);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "dragged");
        result.put("pressed", pressed);
        result.put("handledMoves", handledMoves);
        result.put("released", released);
        result.put("fromX", fromX);
        result.put("fromY", fromY);
        result.put("toX", toX);
        result.put("toY", toY);
        result.put("button", button);
        result.put("steps", steps);
        result.put("screen", screen.getClass().getName());
        return result;
    }

    static Map<String, Object> scroll(Minecraft mc, double x, double y, double horizontal, double vertical) {
        Screen screen = mc.screen;
        if (screen == null) return Map.of("status", "no_screen", "x", x, "y", y);
        boolean handled = screen.mouseScrolled(x, y, horizontal, vertical);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "scrolled");
        result.put("handled", handled);
        result.put("x", x);
        result.put("y", y);
        result.put("amount", vertical);
        result.put("horizontal", horizontal);
        result.put("screen", screen.getClass().getName());
        return result;
    }
}
