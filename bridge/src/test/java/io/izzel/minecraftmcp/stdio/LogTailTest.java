package io.izzel.minecraftmcp.stdio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogTailTest {
    @TempDir Path dir;

    @Test
    void returnsTheLastLinesInOrder() throws Exception {
        Path log = write("1\n2\n3\n4\n5\n");

        assertEquals(List.of("4", "5"), LogTail.tail(log, 2, null));
        assertEquals(List.of("1", "2", "3", "4", "5"), LogTail.tail(log, 200, ""));
    }

    @Test
    void filtersBeforeCountingLines() throws Exception {
        Path log = write("[main/INFO] a\r\n[main/WARN] b\r\n[main/INFO] c\r\n[main/WARN] d\r\n[main/WARN] e\r\n");

        assertEquals(List.of("[main/WARN] d", "[main/WARN] e"), LogTail.tail(log, 2, "WARN"));
        assertEquals(List.of(), LogTail.tail(log, 10, "ERROR"));
    }

    @Test
    void clampsLineCountAndToleratesMalformedUtf8() throws Exception {
        Path log = dir.resolve("bad.log");
        Files.write(log, new byte[]{'o', 'k', '\n', (byte) 0xC3, (byte) 0x28, '\n', 'e', 'n', 'd'});

        assertEquals(List.of("end"), LogTail.tail(log, 0, null));
        assertEquals(List.of("ok", "�(", "end"), LogTail.tail(log, 100, null));
    }

    private Path write(String content) throws Exception {
        Path log = dir.resolve("latest.log");
        Files.writeString(log, content, StandardCharsets.UTF_8);
        return log;
    }
}
