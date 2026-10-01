package io.izzel.minecraftmcp.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftMcpConfigTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty("minecraftMcp.background");
        System.clearProperty("minecraftMcp.backgroundMaxFps");
        System.clearProperty("minecraftMcp.backgroundMute");
    }

    @Test
    void backgroundModeIsOffByDefault() {
        MinecraftMcpConfig config = MinecraftMcpConfig.load();

        assertFalse(config.background());
        assertEquals(30, config.backgroundMaxFps());
        assertTrue(config.backgroundMute());
    }

    @Test
    void backgroundSettingsAreReadFromSystemProperties() {
        System.setProperty("minecraftMcp.background", "true");
        System.setProperty("minecraftMcp.backgroundMaxFps", "15");
        System.setProperty("minecraftMcp.backgroundMute", "false");

        MinecraftMcpConfig config = MinecraftMcpConfig.load();

        assertTrue(config.background());
        assertEquals(15, config.backgroundMaxFps());
        assertFalse(config.backgroundMute());
    }

    @Test
    void legacyConstructorKeepsBackgroundOff() {
        MinecraftMcpConfig config = new MinecraftMcpConfig("127.0.0.1", 0, "token", "", false);

        assertFalse(config.background());
        assertEquals(MinecraftMcpConfig.DEFAULT_BACKGROUND_MAX_FPS, config.backgroundMaxFps());
    }
}
