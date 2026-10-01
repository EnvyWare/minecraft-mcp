package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/** Discovers {@link ToolProvider}s and registers their tools without letting them replace existing ones. */
public final class ToolProviders {
    private static volatile List<ToolProvider> providers;

    private ToolProviders() {}

    /** Providers found on the context class loader, this class's loader and this module's layer (NeoForge mods). */
    public static List<ToolProvider> providers() {
        List<ToolProvider> result = providers;
        if (result == null) {
            synchronized (ToolProviders.class) {
                result = providers;
                if (result == null) providers = result = discover();
            }
        }
        return result;
    }

    public static List<String> providerNames() {
        return providers().stream().map(provider -> provider.getClass().getName()).toList();
    }

    public static void registerClient(ToolRegistry registry, MinecraftClientBridge bridge) {
        register(registry, providers(), provider -> target -> provider.registerClient(target, bridge));
    }

    public static void registerServer(ToolRegistry registry, MinecraftServerBridge bridge) {
        register(registry, providers(), provider -> target -> provider.registerServer(target, bridge));
    }

    static void register(ToolRegistry registry, List<ToolProvider> providers, java.util.function.Function<ToolProvider, Consumer<ToolRegistry>> registration) {
        for (ToolProvider provider : providers) {
            ToolRegistry contributed = new ToolRegistry();
            try {
                registration.apply(provider).accept(contributed);
            } catch (Throwable t) {
                System.err.println("[Minecraft MCP] Tool provider " + provider.getClass().getName() + " failed to register tools: " + t);
                continue;
            }
            for (Map<String, Object> listed : contributed.listTools()) {
                String name = String.valueOf(listed.get("name"));
                McpTool tool = contributed.find(name).orElseThrow();
                if (registry.find(name).isPresent()) {
                    System.err.println("[Minecraft MCP] Tool provider " + provider.getClass().getName() + " tried to replace existing tool " + name + "; skipped");
                } else {
                    registry.register(tool);
                }
            }
        }
    }

    static List<ToolProvider> discover() {
        Map<String, ToolProvider> found = new LinkedHashMap<>();
        Map<String, List<String>> sources = new LinkedHashMap<>();
        Map<String, ServiceLoader<ToolProvider>> loaders = new LinkedHashMap<>();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) loaders.put("context class loader", ServiceLoader.load(ToolProvider.class, context));
        loaders.put("mod class loader", ServiceLoader.load(ToolProvider.class, ToolProviders.class.getClassLoader()));
        ModuleLayer layer = ToolProviders.class.getModule().getLayer();
        if (layer != null) loaders.put("module layer", ServiceLoader.load(layer, ToolProvider.class));
        for (Map.Entry<String, ServiceLoader<ToolProvider>> entry : loaders.entrySet()) {
            Iterator<ToolProvider> iterator = entry.getValue().iterator();
            while (true) {
                try {
                    if (!iterator.hasNext()) break;
                    ToolProvider provider = iterator.next();
                    found.putIfAbsent(provider.getClass().getName(), provider);
                    sources.computeIfAbsent(provider.getClass().getName(), name -> new java.util.ArrayList<>()).add(entry.getKey());
                } catch (ServiceConfigurationError e) {
                    System.err.println("[Minecraft MCP] Failed to load tool provider: " + e);
                }
            }
        }
        sources.forEach((name, source) -> System.out.println("[Minecraft MCP] Tool provider " + name + " (found via " + String.join(", ", source) + ")"));
        return List.copyOf(found.values());
    }
}
