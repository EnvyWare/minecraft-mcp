package io.izzel.minecraftmcp.stdio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/** Last lines of a log file that the game may still be writing. */
public final class LogTail {
    public static final int MAX_LINES = 5000;
    private LogTail() {}

    /** The last {@code lines} lines containing {@code contains} (all lines if it is null or blank). Malformed UTF-8 is replaced, not rejected. */
    public static List<String> tail(Path file, int lines, String contains) throws IOException {
        int limit = Math.max(1, Math.min(lines, MAX_LINES));
        ArrayDeque<String> last = new ArrayDeque<>(limit);
        boolean filter = contains != null && !contains.isEmpty();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (filter && !line.contains(contains)) continue;
                if (last.size() == limit) last.removeFirst();
                last.addLast(line);
            }
        }
        return List.copyOf(last);
    }
}
