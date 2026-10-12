package io.izzel.minecraftmcp.film;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Finds the ffmpeg executable: an explicit path (tool argument or scene), else the {@code minecraftMcp.ffmpeg} system
 * property or {@code MINECRAFT_MCP_FFMPEG} environment variable, else {@code ffmpeg} on the PATH.
 */
public final class Ffmpeg {
    public static final String PROPERTY = "minecraftMcp.ffmpeg";
    public static final String ENV = "MINECRAFT_MCP_FFMPEG";
    private static final Map<String, Boolean> RUNS = new ConcurrentHashMap<>();

    private Ffmpeg() {}

    /** The ffmpeg to use, or {@code null} when none works. */
    public static String locate(Object requested) {
        if (requested != null && !String.valueOf(requested).isBlank()) {
            String path = checkRequested(String.valueOf(requested).trim());
            if (!runs(path)) throw new IllegalArgumentException("ffmpeg does not run: " + path);
            return path;
        }
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(ENV);
        if (configured != null && !configured.isBlank()) return runs(configured.trim()) ? configured.trim() : null;
        return runs("ffmpeg") ? "ffmpeg" : null;
    }

    /**
     * A path passed in a tool call must name an existing file called ffmpeg*, so the MCP endpoint cannot be used to start
     * arbitrary programs. Configured paths (system property, environment) are trusted.
     */
    static String checkRequested(String path) {
        Path file = Path.of(path);
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.startsWith("ffmpeg")) throw new IllegalArgumentException("ffmpeg must point to an executable named ffmpeg*: " + path);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("ffmpeg not found: " + path);
        return file.toAbsolutePath().toString();
    }

    static boolean runs(String executable) {
        return RUNS.computeIfAbsent(executable, exe -> {
            try {
                Process process = new ProcessBuilder(exe, "-hide_banner", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        });
    }
}
