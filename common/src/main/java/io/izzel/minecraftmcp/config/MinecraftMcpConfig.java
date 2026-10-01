package io.izzel.minecraftmcp.config;

import java.util.UUID;

public record MinecraftMcpConfig(String bindHost, int port, String authToken, String scenarioDir, boolean batchExit, boolean background, int backgroundMaxFps, boolean backgroundMute) {
    public static final int DEFAULT_BACKGROUND_MAX_FPS = 30;

    public MinecraftMcpConfig(String bindHost, int port, String authToken, String scenarioDir, boolean batchExit) {
        this(bindHost, port, authToken, scenarioDir, batchExit, false, DEFAULT_BACKGROUND_MAX_FPS, true);
    }

    public static MinecraftMcpConfig load() {
        String host = prop("minecraftMcp.bind", "MINECRAFT_MCP_BIND", "127.0.0.1");
        int port = Integer.parseInt(prop("minecraftMcp.port", "MINECRAFT_MCP_PORT", "0"));
        String token = prop("minecraftMcp.authToken", "MINECRAFT_MCP_AUTH_TOKEN", UUID.randomUUID().toString());
        String scenarioDir = prop("minecraftMcp.scenarioDir", "MINECRAFT_MCP_SCENARIO_DIR", "");
        boolean batchExit = Boolean.parseBoolean(prop("minecraftMcp.batchExit", "MINECRAFT_MCP_BATCH_EXIT", "false"));
        boolean background = Boolean.parseBoolean(prop("minecraftMcp.background", "MINECRAFT_MCP_BACKGROUND", "false"));
        int backgroundMaxFps = Integer.parseInt(prop("minecraftMcp.backgroundMaxFps", "MINECRAFT_MCP_BACKGROUND_MAX_FPS", String.valueOf(DEFAULT_BACKGROUND_MAX_FPS)));
        boolean backgroundMute = Boolean.parseBoolean(prop("minecraftMcp.backgroundMute", "MINECRAFT_MCP_BACKGROUND_MUTE", "true"));
        return new MinecraftMcpConfig(host, port, token, scenarioDir, batchExit, background, backgroundMaxFps, backgroundMute);
    }
    private static String prop(String property, String env, String fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
