package io.izzel.minecraftmcp.stdio;

import io.izzel.minecraftmcp.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioMcpServerTest {
    @TempDir Path gameDir;
    private final List<String> output = Collections.synchronizedList(new ArrayList<>());
    private BridgeTools tools;

    @Test
    void initializeEchoesSupportedProtocolVersionsAndOtherwiseAnswersLatest() {
        StdioMcpServer server = server();

        for (String version : List.of("2024-11-05", "2025-03-26", "2025-06-18")) assertEquals(version, initialize(server, version).get("protocolVersion"));
        assertEquals("2025-06-18", initialize(server, "2030-01-01").get("protocolVersion"));
        Map<?, ?> result = initialize(server, null);
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertEquals(Map.of("tools", Map.of("listChanged", true)), result.get("capabilities"));
        assertEquals("minecraft-mcp-bridge", ((Map<?, ?>) result.get("serverInfo")).get("name"));
    }

    @Test
    void notificationsAndResponsesProduceNoOutput() {
        StdioMcpServer server = server();

        assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}"));
        assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}"));
        assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}"));
        assertTrue(output.isEmpty());
    }

    @Test
    void pingAnswersEmptyResultWithTheSameId() {
        Map<?, ?> response = parse(server().handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}"));

        assertEquals("2.0", response.get("jsonrpc"));
        assertEquals("abc", response.get("id"));
        assertEquals(Map.of(), response.get("result"));
    }

    @Test
    void unknownMethodsAndBadInputAreJsonRpcErrors() {
        StdioMcpServer server = server();

        Map<?, ?> unknown = parse(server.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}"));
        assertEquals(3, ((Number) unknown.get("id")).intValue());
        assertEquals(-32601, code(unknown));
        Map<?, ?> garbage = parse(server.handle("{not json"));
        assertTrue(garbage.containsKey("id"));
        assertNull(garbage.get("id"));
        assertEquals(-32700, code(garbage));
        assertEquals(-32602, code(parse(server.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{}}"))));
    }

    @Test
    void toolsListHasOnlyBridgeToolsWhileTheClientIsNotRunning() {
        Map<?, ?> response = parse(server().handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        assertEquals(List.of("client.launch", "client.status", "client.stop", "client.logs"), toolNames(response));
    }

    @Test
    void toolsListAddsClientToolsWhenReachableAndChangesAreNotifiedAfterInitialized() throws Exception {
        StdioMcpServer server = server();
        server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        try (StubClient client = new StubClient(gameDir, "secret")) {
            tools.poll();
            assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"), output);
            List<String> names = toolNames(parse(server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")));
            assertEquals(List.of("client.launch", "client.status", "client.stop", "client.logs", "mc.echo"), names);
            tools.poll();
            assertEquals(1, output.size());
        }
        tools.poll();
        assertEquals(2, output.size());
    }

    @Test
    void noListChangedNotificationBeforeInitialized() throws Exception {
        server();
        try (StubClient client = new StubClient(gameDir, "secret")) {
            tools.poll();
        }
        assertTrue(output.isEmpty());
    }

    @Test
    void serveAnswersEachRequestLineAndSkipsNotificationsAndBlankLines() throws Exception {
        StdioMcpServer server = server();
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}

                {"jsonrpc":"2.0","id":2,"method":"ping"}
                """;
        ExecutorService executor = Executors.newCachedThreadPool();
        server.serve(new BufferedReader(new StringReader(input)), executor);
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(2, output.size());
        Set<Integer> ids = new HashSet<>();
        for (String line : output) { assertFalse(line.contains("\n")); ids.add(((Number) parse(line).get("id")).intValue()); }
        assertEquals(Set.of(1, 2), ids);
    }

    private StdioMcpServer server() {
        BridgeOptions options = BridgeOptions.parse(new String[]{"--game-dir", gameDir.toString()}, Map.of());
        tools = new BridgeTools(options, new ClientEndpoint(options.serverJson(), null, null), new ClientProcess(null, null, options.launchLog(), Map.of()));
        return new StdioMcpServer(tools, output::add, "test");
    }

    private static Map<?, ?> initialize(StdioMcpServer server, String version) {
        String params = version == null ? "{}" : "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}";
        return (Map<?, ?>) parse(server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + params + "}")).get("result");
    }

    static List<String> toolNames(Map<?, ?> response) {
        List<String> names = new ArrayList<>();
        for (Object tool : (List<?>) ((Map<?, ?>) response.get("result")).get("tools")) names.add(String.valueOf(((Map<?, ?>) tool).get("name")));
        return names;
    }

    static Map<?, ?> parse(String json) { return (Map<?, ?>) Json.parse(json); }

    private static int code(Map<?, ?> response) { return ((Number) ((Map<?, ?>) response.get("error")).get("code")).intValue(); }
}
