package io.izzel.minecraftmcp.stdio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BridgeCommandTest {
    @TempDir Path gameDir;

    private BridgeTools tools() {
        BridgeOptions options = BridgeOptions.parse(new String[]{"--game-dir", gameDir.toString()}, Map.of());
        return new BridgeTools(options, new ClientEndpoint(options.serverJson(), null, null), new ClientProcess(null, null, options.launchLog(), Map.of()));
    }

    @Test
    void optionsAreFollowedByTheCommandAndItsArguments() {
        BridgeOptions options = BridgeOptions.parse(new String[]{"--game-dir", "g", "call", "mc.film.run", "{\"scene\":\"s.json\"}"}, Map.of());
        assertEquals(List.of("call", "mc.film.run", "{\"scene\":\"s.json\"}"), options.command());
        assertEquals(List.of(), BridgeOptions.parse(new String[]{"--game-dir", "g"}, Map.of()).command());
    }

    @Test
    void callRunsOneToolOnTheRunningClientAndPrintsItsResult() throws Exception {
        try (StubClient client = new StubClient(gameDir, "secret")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = BridgeCommand.run(List.of("call", "mc.echo", "{\"message\":\"hi\"}"), tools(), new PrintStream(out, true, StandardCharsets.UTF_8));

            assertEquals(0, code);
            assertEquals("{\"message\":\"hi\"}", out.toString(StandardCharsets.UTF_8).trim());
            assertEquals(Map.of("name", "mc.echo", "arguments", Map.of("message", "hi")), client.requests.get(client.requests.size() - 1).get("params"));
        }
    }

    @Test
    void argumentsCanComeFromAFileAndErrorsExitWithOne() throws Exception {
        try (StubClient ignored = new StubClient(gameDir, "secret")) {
            Path args = Files.writeString(gameDir.resolve("args.json"), "﻿{\"scene\":\"x\"}", StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(0, BridgeCommand.run(List.of("call", "mc.echo", "@" + args), tools(), new PrintStream(out, true, StandardCharsets.UTF_8)));
            assertEquals("{\"scene\":\"x\"}", out.toString(StandardCharsets.UTF_8).trim());
            assertEquals(1, BridgeCommand.run(List.of("call", "mc.missing"), tools(), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        }
    }

    @Test
    void rejectsUnknownCommandsAndBadArguments() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> BridgeCommand.run(List.of("dance"), tools(), out));
        assertThrows(IllegalArgumentException.class, () -> BridgeCommand.run(List.of("call"), tools(), out));
        assertThrows(IllegalArgumentException.class, () -> BridgeCommand.run(List.of("call", "mc.echo", "[1]"), tools(), out));
        assertThrows(IllegalArgumentException.class, () -> BridgeCommand.run(List.of("call", "mc.echo", "{\"a\":", "1}"), tools(), out));
    }

    @Test
    void launchWithoutALaunchCommandIsAnError() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(1, BridgeCommand.run(List.of("launch", "{\"wait\":false}"), tools(), new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("No launch command"), out.toString(StandardCharsets.UTF_8));
    }
}
