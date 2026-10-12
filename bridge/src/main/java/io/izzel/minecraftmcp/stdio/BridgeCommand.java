package io.izzel.minecraftmcp.stdio;

import io.izzel.minecraftmcp.json.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One-shot commands for a client that keeps running between them, so iterating on a scene takes seconds instead of a
 * client launch: {@code launch} starts the client and leaves it running, {@code call} runs one tool on it.
 * Each prints the tool result and exits with 0, or 1 when the result is an error.
 */
final class BridgeCommand {
    static final String USAGE = """
            Commands (run once and exit; without a command the bridge serves MCP on stdio):
              launch [json]             start the client with --launch-command, wait until it answers, leave it running
              status                    client.status
              call <tool> [json|@file]  call one tool on the running client, e.g. call mc.film.run '{"scene":"scene.json"}'
              logs [json]               client.logs, e.g. logs '{"source":"launch","lines":50}'
              stop [json]               stop the client (gracefully, through mc.client.stop)
            """;

    private BridgeCommand() {}

    static int run(List<String> command, BridgeTools tools, PrintStream out) throws InterruptedException, IOException {
        String name = command.get(0);
        Map<String, Object> result = switch (name) {
            case "launch" -> tools.call("client.launch", arguments(command, 1));
            case "status" -> tools.call("client.status", Map.of());
            case "stop" -> tools.call("client.stop", arguments(command, 1));
            case "logs" -> tools.call("client.logs", arguments(command, 1));
            case "call" -> {
                if (command.size() < 2) throw new IllegalArgumentException("call needs a tool name, e.g. call mc.client.state");
                yield tools.call(command.get(1), arguments(command, 2));
            }
            default -> throw new IllegalArgumentException("Unknown command: " + name);
        };
        if (result.get("content") instanceof List<?> content) {
            for (Object item : content) if (item instanceof Map<?, ?> m && m.get("text") != null) out.println(m.get("text"));
        }
        out.flush();
        return Boolean.TRUE.equals(result.get("isError")) ? 1 : 0;
    }

    /** The JSON object at {@code index}: inline, or {@code @path} to read it from a file. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> arguments(List<String> command, int index) throws IOException {
        if (command.size() <= index) return Map.of();
        if (command.size() > index + 1) throw new IllegalArgumentException("Unexpected arguments after " + command.get(index) + "; quote the JSON as one argument");
        String text = command.get(index);
        if (text.startsWith("@")) text = Files.readString(Path.of(text.substring(1)), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Arguments must be a JSON object: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("Arguments must be a JSON object");
        return (Map<String, Object>) map;
    }
}
