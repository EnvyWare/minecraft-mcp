package io.izzel.minecraftmcp.background;

import io.izzel.minecraftmcp.config.MinecraftMcpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the client usable while a human works in other applications: the window stays hidden and unfocused,
 * the cursor is never grabbed, the game never pauses on lost focus and nothing is written to options.txt.
 * All methods except {@link #enabled()} and {@link #suppressPauseOnLostFocus()} must run on the render thread.
 */
public final class BackgroundWindow {
    private static final MinecraftMcpConfig CONFIG = MinecraftMcpConfig.load();
    private static final BackgroundWindow INSTANCE = new BackgroundWindow();

    private volatile boolean hidden;

    private BackgroundWindow() {}

    public static BackgroundWindow get() {
        return INSTANCE;
    }

    public static boolean enabled() {
        return CONFIG.background();
    }

    public static boolean suppressPauseOnLostFocus() {
        return CONFIG.background();
    }

    /** Effective in-world frame rate limit while hidden; {@code maxFps <= 0} disables the cap. */
    public static int cappedFramerate(int optionLimit, int maxFps) {
        return maxFps <= 0 ? optionLimit : Math.min(optionLimit, maxFps);
    }

    /** Called once on the render thread after the window exists. */
    public void init() {
        if (!enabled()) return;
        long window = Minecraft.getInstance().getWindow().getWindow();
        GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        hide();
    }

    /** Called every client tick; undoes any stray focus while hidden so vanilla never grabs the mouse. */
    public void tick() {
        if (!hidden) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.isWindowActive()) mc.setWindowActive(false);
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
    }

    /** Called after the sound engine (re)loads, which resets the master gain from options. */
    public void onSoundEngineLoaded(SoundEngine engine) {
        if (enabled() && CONFIG.backgroundMute()) {
            engine.updateCategoryVolume(SoundSource.MASTER, 0.0F);
        }
    }

    public Map<String, Object> show() {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        hidden = false;
        GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwShowWindow(window);
        mc.getWindow().setFramerateLimit(mc.options.framerateLimit().get());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "shown");
        result.putAll(state());
        return result;
    }

    public Map<String, Object> hide() {
        Minecraft mc = Minecraft.getInstance();
        hidden = true;
        GLFW.glfwHideWindow(mc.getWindow().getWindow());
        mc.mouseHandler.releaseMouse();
        mc.setWindowActive(false);
        mc.getWindow().setFramerateLimit(cappedFramerate(mc.options.framerateLimit().get(), CONFIG.backgroundMaxFps()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "hidden");
        result.putAll(state());
        return result;
    }

    public Map<String, Object> state() {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visible", GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE);
        result.put("focused", GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE);
        result.put("active", mc.isWindowActive());
        result.put("mouseGrabbed", mc.mouseHandler.isMouseGrabbed());
        result.put("width", mc.getWindow().getWidth());
        result.put("height", mc.getWindow().getHeight());
        result.put("framerateLimit", mc.getWindow().getFramerateLimit());
        result.put("fps", mc.getFps());
        result.put("muted", enabled() && CONFIG.backgroundMute());
        return result;
    }
}
