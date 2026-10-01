package io.izzel.minecraftmcp.stdio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry point: {@code java -jar minecraft_mcp-bridge.jar --game-dir <dir> [--launch-command <cmd>] ...}. */
public final class BridgeMain {
    private BridgeMain() {}

    public static void main(String[] args) throws Exception {
        // stdout carries protocol messages only; anything else printed by accident goes to stderr
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err);
        BridgeOptions options;
        try {
            options = BridgeOptions.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("[minecraft-mcp-bridge] " + e.getMessage());
            System.err.print(BridgeOptions.USAGE);
            System.exit(2);
            return;
        }
        String version = BridgeMain.class.getPackage().getImplementationVersion();
        Map<String, String> childEnv = new HashMap<>();
        // the mod reads these too; note that a launch command which sets -DminecraftMcp.port (like :neoforge:runClient) wins over them
        if (options.port() != null) childEnv.put("MINECRAFT_MCP_PORT", String.valueOf(options.port()));
        if (options.token() != null) childEnv.put("MINECRAFT_MCP_AUTH_TOKEN", options.token());
        BridgeTools tools = new BridgeTools(options, new ClientEndpoint(options.serverJson(), options.port(), options.token()),
                new ClientProcess(options.launchCommand(), options.launchCwd(), options.launchLog(), childEnv));
        StdioMcpServer server = new StdioMcpServer(tools, line -> { synchronized (protocol) { protocol.print(line); protocol.print('\n'); protocol.flush(); } }, version == null ? "dev" : version);
        System.err.println("[minecraft-mcp-bridge] " + (version == null ? "dev" : version) + " serving MCP on stdio; game dir " + options.gameDir()
                + (options.launchCommand() == null ? ", no launch command" : ", launch command: " + options.launchCommand()));

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(daemon("minecraft-mcp-bridge-poll"));
        poller.scheduleWithFixedDelay(() -> {
            try { tools.poll(); } catch (InterruptedException ignored) { } catch (RuntimeException e) { e.printStackTrace(); }
        }, 0, 3, TimeUnit.SECONDS);
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable shutdown = () -> {
            if (!stopped.compareAndSet(false, true)) return;
            try { tools.shutdown(); } catch (Exception e) { e.printStackTrace(); }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "minecraft-mcp-bridge-shutdown"));
        ExecutorService requests = Executors.newCachedThreadPool(daemon("minecraft-mcp-bridge-request"));
        server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), requests);
        System.err.println("[minecraft-mcp-bridge] stdin closed, exiting");
        requests.shutdown();
        // let requests that were already read finish (e.g. piped input), but do not hang on a long tool call
        requests.awaitTermination(10, TimeUnit.SECONDS);
        poller.shutdownNow();
        shutdown.run();
        System.exit(0);
    }

    private static ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }
}
