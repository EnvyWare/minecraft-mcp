package io.izzel.minecraftmcp.stdio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BridgeOptionsTest {
    @Test
    void cliOptionsWinOverEnvironment() {
        BridgeOptions options = BridgeOptions.parse(new String[]{"--game-dir", "runs/client", "--launch-command", "gradlew.bat :neoforge:runClient", "--port", "25599", "--token", "t", "--startup-timeout", "30", "--keep-client"},
                Map.of("MINECRAFT_MCP_GAME_DIR", "elsewhere", "MINECRAFT_MCP_PORT", "1"));

        assertEquals(Path.of("runs/client").toAbsolutePath().normalize(), options.gameDir());
        assertEquals("gradlew.bat :neoforge:runClient", options.launchCommand());
        assertEquals(25599, options.port());
        assertEquals("t", options.token());
        assertEquals(Duration.ofSeconds(30), options.startupTimeout());
        assertTrue(options.keepClient());
        assertEquals(options.gameDir().resolve("mcp/server.json"), options.serverJson());
    }

    @Test
    void environmentFallbacksAndDefaults() {
        BridgeOptions options = BridgeOptions.parse(new String[0], Map.of("MINECRAFT_MCP_GAME_DIR", "g", "MINECRAFT_MCP_LAUNCH_COMMAND", "./gradlew runClient", "MINECRAFT_MCP_PORT", "0"));

        assertEquals("./gradlew runClient", options.launchCommand());
        assertNull(options.port());
        assertNull(options.launchCwd());
        assertEquals(Duration.ofSeconds(600), options.startupTimeout());
        assertFalse(options.keepClient());
    }

    @Test
    void rejectsMissingGameDirAndUnknownOptions() {
        assertThrows(IllegalArgumentException.class, () -> BridgeOptions.parse(new String[0], Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BridgeOptions.parse(new String[]{"--game-dir", "g", "--bogus"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BridgeOptions.parse(new String[]{"--game-dir"}, Map.of()));
    }
}
