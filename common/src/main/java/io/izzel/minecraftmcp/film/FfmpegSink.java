package io.izzel.minecraftmcp.film;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Pipes raw frames into an ffmpeg process that writes an H.264 mp4. ffmpeg flips the GL row order itself. */
public final class FfmpegSink implements FrameSink {
    private final Path output;
    private final Path log;
    private final Process process;
    private final OutputStream stdin;
    private final int outWidth;
    private final int outHeight;

    public FfmpegSink(String ffmpeg, Path output, int width, int height, int fps, int outWidth, int outHeight, int crf, String preset) throws IOException {
        this.output = output;
        this.outWidth = even(outWidth > 0 ? outWidth : width);
        this.outHeight = even(outHeight > 0 ? outHeight : height);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        this.log = output.resolveSibling(output.getFileName() + ".ffmpeg.log");
        List<String> command = command(ffmpeg, output, width, height, fps, this.outWidth, this.outHeight, crf, preset);
        this.process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log.toFile()).start();
        this.stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 20);
    }

    static List<String> command(String ffmpeg, Path output, int width, int height, int fps, int outWidth, int outHeight, int crf, String preset) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", String.valueOf(fps), "-i", "pipe:0", "-an"));
        String filter = "vflip";
        if (outWidth != width || outHeight != height) filter += String.format(Locale.ROOT, ",scale=%d:%d:flags=lanczos", outWidth, outHeight);
        command.addAll(List.of("-vf", filter, "-c:v", "libx264", "-preset", preset, "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart", output.toString()));
        return command;
    }

    /** H.264 in yuv420p needs even dimensions. */
    static int even(int value) {
        return Math.max(2, value & ~1);
    }

    @Override
    public void write(byte[] rgba) throws IOException {
        try {
            stdin.write(rgba);
        } catch (IOException e) {
            throw new IOException("ffmpeg stopped accepting frames: " + logTail(), e);
        }
    }

    @Override
    public Map<String, Object> finish() throws IOException, InterruptedException {
        try {
            stdin.close();
        } catch (IOException e) {
            throw new IOException("ffmpeg failed: " + logTail(), e);
        }
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg did not finish within 10 minutes");
        }
        if (process.exitValue() != 0) throw new IOException("ffmpeg exited with " + process.exitValue() + ": " + logTail());
        Files.deleteIfExists(log);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "mp4");
        result.put("path", output.toAbsolutePath().toString());
        result.put("width", outWidth);
        result.put("height", outHeight);
        result.put("bytes", Files.size(output));
        return result;
    }

    @Override
    public void abort() {
        try { stdin.close(); } catch (IOException ignored) {}
        process.destroyForcibly();
    }

    private String logTail() {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size()));
        } catch (IOException e) {
            return "(no ffmpeg log)";
        }
    }
}
