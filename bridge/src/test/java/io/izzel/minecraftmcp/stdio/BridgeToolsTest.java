package io.izzel.minecraftmcp.stdio;

import io.izzel.minecraftmcp.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BridgeToolsTest {
    @TempDir Path gameDir;

    @Test
    void forwardsUnknownToolsToTheClientWithTheTokenAndReturnsTheResultUnchanged() throws Exception {
        try (StubClient client = new StubClient(gameDir, "secret")) {
            Map<String, Object> result = tools(null).call("mc.echo", Map.of("message", "hello"));

            assertEquals(false, result.get("isError"));
            assertEquals(List.of(Map.of("type", "text", "text", "{\"message\":\"hello\"}")), result.get("content"));
            Map<String, Object> forwarded = client.requests.get(client.requests.size() - 1);
            assertEquals("tools/call", forwarded.get("method"));
            assertEquals(Map.of("name", "mc.echo", "arguments", Map.of("message", "hello")), forwarded.get("params"));
        }
    }

    @Test
    void clientErrorsBecomeToolErrors() throws Exception {
        try (StubClient ignored = new StubClient(gameDir, "secret")) {
            Map<String, Object> result = tools(null).call("mc.missing", Map.of());

            assertEquals(true, result.get("isError"));
            assertTrue(text(result).contains("Unknown tool: mc.missing"), text(result));
        }
    }

    @Test
    void callsWhileTheClientIsNotRunningTellTheAgentToLaunchIt() throws Exception {
        Map<String, Object> noServerJson = tools(null).call("mc.client.state", Map.of());
        assertEquals(true, noServerJson.get("isError"));
        assertTrue(text(noServerJson).contains("client.launch"));

        new StubClient(gameDir, "secret").close(); // leaves a stale server.json behind, like a crashed client
        Map<String, Object> stale = tools(null).call("mc.client.state", Map.of());
        assertEquals(true, stale.get("isError"));
        assertTrue(text(stale).contains("client.launch"));
    }

    @Test
    void wrongTokenIsReportedAsUnreachable() throws Exception {
        try (StubClient ignored = new StubClient(gameDir, "secret")) {
            Files.writeString(gameDir.resolve("mcp/server.json"), Files.readString(gameDir.resolve("mcp/server.json")).replace("secret", "wrong"));
            Map<String, Object> result = tools(null).call("mc.echo", Map.of());

            assertEquals(true, result.get("isError"));
            assertTrue(text(result).contains("401"), text(result));
        }
    }

    @Test
    void fixedPortAndTokenSkipServerJson() throws Exception {
        try (StubClient client = new StubClient(gameDir, "secret")) {
            Files.delete(gameDir.resolve("mcp/server.json"));
            BridgeOptions options = BridgeOptions.parse(new String[]{"--game-dir", gameDir.toString(), "--port", String.valueOf(client.port()), "--token", "secret"}, Map.of());
            BridgeTools tools = new BridgeTools(options, new ClientEndpoint(options.serverJson(), options.port(), options.token()), new ClientProcess(null, null, options.launchLog(), Map.of()));

            assertEquals(false, tools.call("mc.echo", Map.of()).get("isError"));
            Map<?, ?> status = (Map<?, ?>) Json.parse(text(tools.call("client.status", Map.of())));
            assertEquals(true, status.get("reachable"));
            assertEquals(1, ((Number) status.get("toolCount")).intValue());
            assertEquals("fixed", ((Map<?, ?>) status.get("endpoint")).get("source"));
        }
    }

    @Test
    void launchRefusesWhileAClientIsReachable() throws Exception {
        try (StubClient ignored = new StubClient(gameDir, "secret")) {
            Map<String, Object> result = tools("never-run-this").call("client.launch", Map.of());

            assertEquals(true, result.get("isError"));
            assertTrue(text(result).contains("already reachable"), text(result));
            assertFalse(Files.exists(gameDir.resolve("mcp/launch.log")));
        }
    }

    @Test
    void launchWithoutCommandExplainsHowToConfigureIt() throws Exception {
        Map<String, Object> result = tools(null).call("client.launch", Map.of());

        assertEquals(true, result.get("isError"));
        assertTrue(text(result).contains("--launch-command"));
    }

    @Test
    void launchDeletesStaleServerJsonAndReportsAnEarlyExit() throws Exception {
        Files.createDirectories(gameDir.resolve("mcp"));
        Files.writeString(gameDir.resolve("mcp/server.json"), "{\"port\":1,\"authToken\":\"old\"}");
        Map<String, Object> result = tools("echo launching && exit 3").call("client.launch", Map.of());

        Map<?, ?> body = (Map<?, ?>) Json.parse(text(result));
        assertEquals(true, result.get("isError"));
        assertEquals("exited", body.get("status"));
        assertEquals(3, ((Number) body.get("exitCode")).intValue());
        assertTrue(String.valueOf(body.get("launchLogTail")).contains("launching"));
        assertFalse(Files.exists(gameDir.resolve("mcp/server.json")));
    }

    @Test
    void statusAndStopWithNothingRunning() throws Exception {
        BridgeTools tools = tools(null);
        Map<?, ?> status = (Map<?, ?>) Json.parse(text(tools.call("client.status", Map.of())));

        assertEquals(false, status.get("reachable"));
        assertEquals(false, ((Map<?, ?>) status.get("process")).get("launched"));
        Map<String, Object> stop = tools.call("client.stop", Map.of());
        assertEquals(false, stop.get("isError"));
        assertTrue(text(stop).contains("No client is running"));
    }

    @Test
    void logsTailTheGameOrLaunchLogWithAFilter() throws Exception {
        Files.createDirectories(gameDir.resolve("logs"));
        Files.createDirectories(gameDir.resolve("mcp"));
        Files.writeString(gameDir.resolve("logs/latest.log"), "a INFO one\nb WARN two\nc INFO three\nd WARN four\n", StandardCharsets.UTF_8);
        Files.writeString(gameDir.resolve("mcp/launch.log"), "> Task :neoforge:compileJava\n", StandardCharsets.UTF_8);
        BridgeTools tools = tools(null);

        assertEquals("b WARN two\nd WARN four", text(tools.call("client.logs", Map.of("contains", "WARN"))));
        assertEquals("d WARN four", text(tools.call("client.logs", Map.of("lines", 1))));
        assertEquals("> Task :neoforge:compileJava", text(tools.call("client.logs", Map.of("source", "launch"))));
        assertEquals(true, tools.call("client.logs", Map.of("source", "nope")).get("isError"));
    }

    private BridgeTools tools(String launchCommand) {
        BridgeOptions options = BridgeOptions.parse(launchCommand == null ? new String[]{"--game-dir", gameDir.toString()} : new String[]{"--game-dir", gameDir.toString(), "--launch-command", launchCommand}, Map.of());
        return new BridgeTools(options, new ClientEndpoint(options.serverJson(), options.port(), options.token()), new ClientProcess(options.launchCommand(), gameDir, options.launchLog(), Map.of()));
    }

    static String text(Map<String, Object> result) { return String.valueOf(((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text")); }
}
