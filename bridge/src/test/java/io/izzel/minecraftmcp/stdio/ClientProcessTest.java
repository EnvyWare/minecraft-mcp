package io.izzel.minecraftmcp.stdio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientProcessTest {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    @TempDir Path dir;

    @Test
    void destroyTreeKillsTheShellAndTheProcessesItStarted() throws Exception {
        // the shell waits on a child, like gradlew waiting on the game JVM
        String command = WINDOWS ? "ping -n 60 127.0.0.1 > nul & echo done" : "sleep 60; echo done";
        ClientProcess process = new ClientProcess(command, dir, dir.resolve("launch.log"), Map.of("MINECRAFT_MCP_TEST", "1"));
        process.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (((List<?>) process.status().get("processes")).size() < 2 && System.nanoTime() < deadline) Thread.sleep(50);
        List<?> before = (List<?>) process.status().get("processes");
        assertTrue(before.size() >= 2, "expected the shell and its child, got " + before);

        assertThrows(IllegalStateException.class, process::start);
        List<Long> killed = process.destroyTree();

        assertTrue(process.waitForExit(Duration.ofSeconds(10)));
        assertEquals(before.size(), killed.size());
        assertFalse(process.alive());
        assertEquals(false, process.status().get("alive"));
        assertTrue(Files.exists(dir.resolve("launch.log")));
    }

    @Test
    void recordsTheExitCode() throws Exception {
        ClientProcess process = new ClientProcess("exit 7", dir, dir.resolve("launch.log"), Map.of());
        process.start();

        assertTrue(process.waitForExit(Duration.ofSeconds(10)));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (process.status().get("lastExitCode") == null && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(7, process.status().get("lastExitCode"));
    }
}
