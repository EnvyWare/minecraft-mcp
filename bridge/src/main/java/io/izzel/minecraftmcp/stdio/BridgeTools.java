package io.izzel.minecraftmcp.stdio;

import io.izzel.minecraftmcp.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** The bridge's own {@code client.*} tools plus forwarding of every other tool to the game client. */
public final class BridgeTools {
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CALL_TIMEOUT = Duration.ofMinutes(15);
    static final String CLIENT_STOP_TOOL = "mc.client.stop";
    private static final String NOT_RUNNING = "The Minecraft client is not running or not reachable. Call client.launch to start it (or client.status to check on a launch in progress).";

    private final BridgeOptions options; private final ClientEndpoint endpoint; private final ClientProcess process;
    private volatile List<String> knownClientTools = List.of();
    private volatile String lastUnreachableReason = "not checked yet";
    private volatile Runnable listChanged = () -> {};

    public BridgeTools(BridgeOptions options, ClientEndpoint endpoint, ClientProcess process) { this.options = options; this.endpoint = endpoint; this.process = process; }

    public void onListChanged(Runnable listener) { this.listChanged = listener; }

    public List<Map<String, Object>> list() throws InterruptedException {
        List<Map<String, Object>> tools = new ArrayList<>(definitions());
        List<Map<String, Object>> clientTools = tryListClientTools();
        observe(clientTools, false);
        if (clientTools != null) tools.addAll(clientTools);
        return tools;
    }

    /** An MCP {@code CallToolResult}. */
    public Map<String, Object> call(String name, Map<String, Object> args) throws InterruptedException {
        try {
            return switch (name) {
                case "client.launch" -> launch(args);
                case "client.status" -> json(status(), false);
                case "client.stop" -> stop(args);
                case "client.logs" -> logs(args);
                default -> forward(name, args);
            };
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            return text(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), true);
        }
    }

    /** Background check: notices the client coming up or going away and keeps track of the launched process tree. */
    public void poll() throws InterruptedException {
        process.alive();
        observe(tryListClientTools(), true);
    }

    /** Called when the bridge exits: stops a client it launched unless told to keep it. */
    public void shutdown() throws InterruptedException {
        if (options.keepClient() || !process.alive()) return;
        System.err.println("[minecraft-mcp-bridge] stopping the launched client before exiting");
        stop(Map.of("timeoutSeconds", 30));
    }

    private Map<String, Object> launch(Map<String, Object> args) throws IOException, InterruptedException {
        boolean wait = bool(args.get("wait"), true);
        Duration timeout = args.get("timeoutSeconds") instanceof Number n ? Duration.ofSeconds(n.longValue()) : options.startupTimeout();
        List<Map<String, Object>> tools = tryListClientTools();
        if (tools != null) {
            observe(tools, true);
            return text("A client is already reachable at " + endpointDescription() + " with " + tools.size() + " tools; not launching another. Call client.stop first to restart it.", true);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (process.alive()) {
            result.put("status", "starting");
            result.put("note", "the launched client is still starting; not launching another");
        } else {
            if (!process.configured()) return text("No launch command configured. Start the bridge with --launch-command (or MINECRAFT_MCP_LAUNCH_COMMAND), or start the client yourself.", true);
            // an old server.json would point at the previous instance
            Files.deleteIfExists(options.serverJson());
            long pid = process.start();
            System.err.println("[minecraft-mcp-bridge] launched client, pid " + pid + ", output in " + options.launchLog());
            result.put("status", "starting");
        }
        result.put("pid", process.status().get("pid"));
        result.put("launchLog", options.launchLog().toString());
        if (!wait) return json(result, false);
        long started = System.nanoTime(), deadline = started + timeout.toNanos();
        while (true) {
            tools = tryListClientTools();
            if (tools != null) {
                observe(tools, true);
                result.put("status", "ready");
                result.put("endpoint", endpoint.target().map(ClientEndpoint.Target::toMap).orElse(null));
                result.put("toolCount", tools.size());
                result.put("waitedSeconds", Duration.ofNanos(System.nanoTime() - started).toSeconds());
                result.put("note", "the endpoint answers; resources may still be loading, check mc.client.state 'loading' before joining a world");
                return json(result, false);
            }
            if (!process.alive()) {
                result.put("status", "exited");
                result.put("exitCode", process.status().get("lastExitCode"));
                result.put("launchLogTail", tailOrEmpty(options.launchLog(), 40));
                return json(result, true);
            }
            if (System.nanoTime() > deadline) {
                result.put("status", "timeout");
                result.put("note", "the client is still running but its endpoint did not answer within " + timeout.toSeconds() + "s; check client.logs or call client.launch again to keep waiting");
                result.put("lastError", lastUnreachableReason);
                return json(result, true);
            }
            Thread.sleep(1000);
        }
    }

    Map<String, Object> status() throws InterruptedException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("process", process.status());
        List<Map<String, Object>> tools = tryListClientTools();
        observe(tools, true);
        m.put("reachable", tools != null);
        m.put("endpoint", endpoint.target().map(ClientEndpoint.Target::toMap).orElse(null));
        m.put("toolCount", tools == null ? 0 : tools.size());
        if (tools == null) m.put("unreachableReason", lastUnreachableReason);
        m.put("gameDir", options.gameDir().toString());
        return m;
    }

    private Map<String, Object> stop(Map<String, Object> args) throws InterruptedException {
        Duration timeout = Duration.ofSeconds(args.get("timeoutSeconds") instanceof Number n ? n.longValue() : 60);
        List<String> steps = new ArrayList<>();
        boolean asked = false;
        List<Map<String, Object>> tools = tryListClientTools();
        boolean ours = process.alive();
        if (tools == null && !ours) return text("No client is running: nothing was launched by this bridge and the endpoint is not reachable (" + lastUnreachableReason + ").", false);
        if (tools != null && tools.stream().anyMatch(t -> CLIENT_STOP_TOOL.equals(t.get("name")))) {
            try {
                Map<String, Object> response = endpoint.request("tools/call", Map.of("name", CLIENT_STOP_TOOL, "arguments", Map.of()), Duration.ofSeconds(15));
                asked = !response.containsKey("error");
                steps.add(asked ? "asked the client to stop with mc.client.stop" : "mc.client.stop failed: " + Json.stringify(response.get("error")));
            } catch (IOException e) {
                steps.add("mc.client.stop failed: " + e.getMessage());
            }
        } else if (tools != null) {
            steps.add("the client has no " + CLIENT_STOP_TOOL + " tool");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        boolean graceful;
        if (ours) {
            graceful = process.waitForExit(asked ? timeout : Duration.ZERO);
            if (!graceful) {
                List<Long> killed = process.destroyTree();
                steps.add((asked ? "process tree still running after " + timeout.toSeconds() + "s" : "no graceful stop possible") + "; killed pids " + killed);
                result.put("killedPids", killed);
                process.waitForExit(Duration.ofSeconds(15));
            } else {
                steps.add("launched process tree exited");
            }
        } else {
            graceful = waitUnreachable(timeout);
            steps.add(graceful ? "the client endpoint went away" : "the client was not launched by this bridge, so it cannot be killed; it is still reachable");
        }
        List<Map<String, Object>> after = tryListClientTools();
        observe(after, true);
        result.put("stopped", after == null && !process.alive());
        result.put("graceful", graceful);
        result.put("steps", steps);
        result.put("process", process.status());
        return json(result, after != null || process.alive());
    }

    private Map<String, Object> logs(Map<String, Object> args) throws IOException {
        String source = String.valueOf(args.getOrDefault("source", "game"));
        Path file = switch (source) {
            case "game" -> options.gameLog();
            case "launch" -> options.launchLog();
            default -> throw new IllegalArgumentException("source must be 'game' or 'launch', got '" + source + "'");
        };
        if (!Files.isRegularFile(file)) return text("No log at " + file, true);
        int lines = args.get("lines") instanceof Number n ? n.intValue() : 200;
        String contains = args.get("contains") == null ? null : String.valueOf(args.get("contains"));
        return text(String.join("\n", LogTail.tail(file, lines, contains)), false);
    }

    private Map<String, Object> forward(String name, Map<String, Object> args) throws InterruptedException {
        Map<String, Object> response;
        try {
            response = endpoint.request("tools/call", Map.of("name", name, "arguments", args), CALL_TIMEOUT);
        } catch (IOException e) {
            return text(NOT_RUNNING + " (" + e.getMessage() + ")", true);
        }
        if (response.get("result") instanceof Map<?, ?> result) {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) result;
            return r;
        }
        Object error = response.get("error");
        String message = error instanceof Map<?, ?> e ? e.get("message") + " (code " + e.get("code") + ")" : Json.stringify(response);
        return text("Client error for " + name + ": " + message, true);
    }

    private boolean waitUnreachable(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (tryListClientTools() != null) {
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(500);
        }
        return true;
    }

    /** The client's tools, or null if it is not reachable. */
    private List<Map<String, Object>> tryListClientTools() throws InterruptedException {
        try {
            return endpoint.listTools(LIST_TIMEOUT);
        } catch (IOException e) {
            lastUnreachableReason = e.getMessage();
            return null;
        }
    }

    private synchronized void observe(List<Map<String, Object>> clientTools, boolean notify) {
        List<String> names = clientTools == null ? List.of() : clientTools.stream().map(t -> String.valueOf(t.get("name"))).sorted().toList();
        if (names.equals(knownClientTools)) return;
        System.err.println("[minecraft-mcp-bridge] client tools changed: " + knownClientTools.size() + " -> " + names.size());
        knownClientTools = names;
        if (notify) listChanged.run();
    }

    private String endpointDescription() { return endpoint.target().map(t -> t.uri().toString()).orElse("(unknown)"); }

    private static List<String> tailOrEmpty(Path file, int lines) {
        try { return Files.isRegularFile(file) ? LogTail.tail(file, lines, null) : List.of(); } catch (IOException e) { return List.of("cannot read " + file + ": " + e.getMessage()); }
    }

    static Map<String, Object> text(String text, boolean isError) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", text)));
        m.put("isError", isError);
        return m;
    }

    private static Map<String, Object> json(Object value, boolean isError) { return text(Json.stringify(value), isError); }

    private static boolean bool(Object value, boolean fallback) { return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value)); }

    static List<Map<String, Object>> definitions() {
        return List.of(
                tool("client.launch", "Start the Minecraft client with the bridge's launch command if none is running or reachable. With wait=true (default) blocks until the client's MCP endpoint answers (a dev build takes a few minutes), the process exits, or the startup timeout passes. Its mc.* tools then appear in tools/list.",
                        Map.of("wait", prop("boolean", "Wait for the endpoint to answer (default true)"), "timeoutSeconds", prop("integer", "Override the startup timeout"))),
                tool("client.status", "Whether a launched client process is alive (pid, uptime, last exit code), whether the client endpoint is reachable, its port and the number of client tools.", Map.of()),
                tool("client.stop", "Stop the client: asks it to quit through mc.client.stop, then kills the launched process tree (launcher and game JVM) if it has not exited within timeoutSeconds.",
                        Map.of("timeoutSeconds", prop("integer", "Seconds to wait for a graceful exit before killing (default 60)"))),
                tool("client.logs", "Tail the game log (logs/latest.log) or the launch output (mcp/launch.log, compiler and launcher output).",
                        Map.of("lines", prop("integer", "Number of lines (default 200, max " + LogTail.MAX_LINES + ")"), "contains", prop("string", "Only lines containing this text"),
                                "source", Map.of("type", "string", "enum", List.of("game", "launch"), "description", "game (default) or launch"))));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("description", description); m.put("inputSchema", Map.of("type", "object", "properties", new TreeMap<>(properties)));
        return m;
    }

    private static Map<String, Object> prop(String type, String description) { return Map.of("type", type, "description", description); }
}
