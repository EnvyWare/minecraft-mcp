package io.izzel.minecraftmcp.mcp;

import io.izzel.minecraftmcp.config.MinecraftMcpConfig;
import io.izzel.minecraftmcp.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcHandlerTest {
    @Test
    void listsRegisteredTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        JsonRpcHandler handler = new JsonRpcHandler(registry);

        Map<?, ?> response = (Map<?, ?>) Json.parse(handler.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        Map<?, ?> result = (Map<?, ?>) response.get("result");

        assertTrue(Json.stringify(result).contains("mc.echo"));
    }

    @Test
    void callsRegisteredToolAndWrapsResultAsMcpTextContent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        JsonRpcHandler handler = new JsonRpcHandler(registry);

        String request = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"mc.echo\",\"arguments\":{\"message\":\"hello\"}}}";
        String responseJson = handler.handle(request);

        assertTrue(responseJson.contains("hello"));
        assertFalse(responseJson.contains("error"));
    }

    @Test
    void initializeEchoesSupportedProtocolVersionAndFallsBackToLatest() {
        JsonRpcHandler handler = new JsonRpcHandler(new ToolRegistry());

        assertEquals("2025-03-26", initialize(handler, "{\"protocolVersion\":\"2025-03-26\"}").get("protocolVersion"));
        assertEquals("2024-11-05", initialize(handler, "{\"protocolVersion\":\"2024-11-05\"}").get("protocolVersion"));
        assertEquals("2025-06-18", initialize(handler, "{\"protocolVersion\":\"1999-01-01\"}").get("protocolVersion"));
        assertEquals("2025-06-18", initialize(handler, "{}").get("protocolVersion"));
    }

    @Test
    void notificationsGetNoResponseAndPingAnswersEmptyResult() {
        JsonRpcHandler handler = new JsonRpcHandler(new ToolRegistry());

        assertNull(handler.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull(handler.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}"));
        Map<?, ?> ping = (Map<?, ?>) Json.parse(handler.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}"));
        assertEquals(7, ((Number) ping.get("id")).intValue());
        assertEquals(Map.of(), ping.get("result"));
    }

    @Test
    void requestsWithoutIdThatAreNotNotificationsStillGetAnswered() {
        JsonRpcHandler handler = new JsonRpcHandler(new ToolRegistry());

        Map<?, ?> response = (Map<?, ?>) Json.parse(handler.handle("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}"));

        assertEquals(0, ((Number) response.get("id")).intValue());
        assertNotNull(response.get("result"));
    }

    @Test
    void unknownMethodIsMethodNotFound() {
        Map<?, ?> response = (Map<?, ?>) Json.parse(new JsonRpcHandler(new ToolRegistry()).handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/templates/list\"}"));

        assertEquals(-32601, ((Number) ((Map<?, ?>) response.get("error")).get("code")).intValue());
    }

    @Test
    void httpServerAnswersNotificationsWith202AndEmptyBody(@TempDir Path gameDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        try (LocalHttpMcpServer server = new LocalHttpMcpServer(new MinecraftMcpConfig("127.0.0.1", 0, "secret", "", false), new JsonRpcHandler(registry))) {
            server.start(gameDir, "test", "1.21.1");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> notification = post(client, server.port(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            HttpResponse<String> call = post(client, server.port(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

            assertEquals(202, notification.statusCode());
            assertEquals("", notification.body());
            assertEquals(200, call.statusCode());
            assertTrue(call.body().contains("mc.echo"));
        }
    }

    private static Map<?, ?> initialize(JsonRpcHandler handler, String params) {
        Map<?, ?> response = (Map<?, ?>) Json.parse(handler.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + params + "}"));
        return (Map<?, ?>) response.get("result");
    }

    private static HttpResponse<String> post(HttpClient client, int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Authorization", "Bearer secret")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class EchoTool implements McpTool {
        public String name() { return "mc.echo"; }
        public String description() { return "Echo test tool"; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public Object call(Map<String, Object> arguments) { return Map.of("echo", arguments.get("message")); }
    }
}
