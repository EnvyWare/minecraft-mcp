package io.izzel.minecraftmcp.stdio;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Command line options, each with an environment variable fallback. */
public record BridgeOptions(Path gameDir, String launchCommand, Path launchCwd, Integer port, String token, Duration startupTimeout, boolean keepClient) {
    static final String USAGE = """
            Usage: java -jar minecraft_mcp-bridge.jar --game-dir <dir> [options]
              --game-dir <dir>          game directory with mcp/server.json and logs/latest.log (MINECRAFT_MCP_GAME_DIR)
              --launch-command <cmd>    command that starts the client, run through cmd /c or sh -c (MINECRAFT_MCP_LAUNCH_COMMAND)
              --launch-cwd <dir>        working directory for the launch command (MINECRAFT_MCP_LAUNCH_CWD)
              --port <n>                fixed client endpoint port instead of mcp/server.json (MINECRAFT_MCP_PORT)
              --token <t>               fixed client auth token instead of mcp/server.json (MINECRAFT_MCP_AUTH_TOKEN)
              --startup-timeout <s>     seconds client.launch waits for the endpoint, default 600 (MINECRAFT_MCP_STARTUP_TIMEOUT)
              --keep-client             leave a launched client running when the bridge exits (MINECRAFT_MCP_KEEP_CLIENT=true)
            """;

    public static BridgeOptions parse(String[] args, Map<String, String> env) {
        Map<String, String> values = new HashMap<>();
        boolean keepClient = Boolean.parseBoolean(env.getOrDefault("MINECRAFT_MCP_KEEP_CLIENT", "false"));
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--keep-client")) { keepClient = true; continue; }
            if (!List.of("--game-dir", "--launch-command", "--launch-cwd", "--port", "--token", "--startup-timeout").contains(arg)) throw new IllegalArgumentException("Unknown option: " + arg);
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
            values.put(arg, args[++i]);
        }
        String gameDir = value(values, env, "--game-dir", "MINECRAFT_MCP_GAME_DIR");
        if (gameDir == null) throw new IllegalArgumentException("--game-dir is required");
        String launchCwd = value(values, env, "--launch-cwd", "MINECRAFT_MCP_LAUNCH_CWD");
        String port = value(values, env, "--port", "MINECRAFT_MCP_PORT");
        String timeout = value(values, env, "--startup-timeout", "MINECRAFT_MCP_STARTUP_TIMEOUT");
        Integer fixedPort = port == null || port.equals("0") ? null : Integer.valueOf(port);
        return new BridgeOptions(Path.of(gameDir).toAbsolutePath().normalize(), value(values, env, "--launch-command", "MINECRAFT_MCP_LAUNCH_COMMAND"),
                launchCwd == null ? null : Path.of(launchCwd).toAbsolutePath().normalize(), fixedPort, value(values, env, "--token", "MINECRAFT_MCP_AUTH_TOKEN"),
                Duration.ofSeconds(timeout == null ? 600 : Long.parseLong(timeout)), keepClient);
    }

    public Path serverJson() { return gameDir.resolve("mcp/server.json"); }
    public Path launchLog() { return gameDir.resolve("mcp/launch.log"); }
    public Path gameLog() { return gameDir.resolve("logs/latest.log"); }

    private static String value(Map<String, String> values, Map<String, String> env, String option, String envName) {
        String v = values.get(option);
        if (v == null || v.isBlank()) v = env.get(envName);
        return v == null || v.isBlank() ? null : v;
    }
}
