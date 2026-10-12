package io.izzel.minecraftmcp.film;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Output settings shared by {@code mc.camera.play} and {@code mc.record.start}. {@code width}/{@code height} of 0 keep the
 * game's framebuffer size. Relative output paths are resolved against {@code <gameDir>/}.
 */
public record FilmSettings(Path output, String format, int fps, int width, int height, int crf, String preset, String ffmpeg, int contactSheetFrames, boolean draft, List<String> warnings) {
    public static final int DEFAULT_FPS = 30;
    public static final int DRAFT_FPS = 10;
    public static final int DRAFT_WIDTH = 640;
    public static final int DRAFT_HEIGHT = 360;
    public static final int DEFAULT_CONTACT_SHEET = 16;

    public static FilmSettings from(Map<String, Object> args, Path gameDir, String defaultName) {
        return from(args, gameDir, defaultName, Ffmpeg::locate);
    }

    static FilmSettings from(Map<String, Object> args, Path gameDir, String defaultName, java.util.function.Function<Object, String> ffmpegLocator) {
        List<String> warnings = new ArrayList<>();
        boolean draft = Boolean.parseBoolean(String.valueOf(args.getOrDefault("draft", false)));
        int fps = draft ? DRAFT_FPS : integer(args.getOrDefault("fps", DEFAULT_FPS), "fps");
        int width = draft ? DRAFT_WIDTH : integer(args.getOrDefault("width", 0), "width");
        int height = draft ? DRAFT_HEIGHT : integer(args.getOrDefault("height", 0), "height");
        if (fps <= 0 || fps > 240) throw new IllegalArgumentException("fps must be between 1 and 240");
        if (width < 0 || height < 0 || (width == 0) != (height == 0)) throw new IllegalArgumentException("width and height must both be given, or neither");
        int crf = draft ? 28 : integer(args.getOrDefault("crf", 18), "crf");
        String preset = draft ? "ultrafast" : String.valueOf(args.getOrDefault("preset", "veryfast"));
        if (!preset.matches("[a-z]+")) throw new IllegalArgumentException("preset must be an x264 preset name such as veryfast");
        String format = String.valueOf(args.getOrDefault("format", "mp4")).toLowerCase(Locale.ROOT);
        if (!format.equals("mp4") && !format.equals("png")) throw new IllegalArgumentException("format must be mp4 or png");
        Object requestedPath = args.containsKey("output") ? args.get("output") : args.get("path");
        String name = requestedPath == null || String.valueOf(requestedPath).isBlank()
                ? "films/" + safeName(defaultName) + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".mp4"
                : String.valueOf(requestedPath);
        Path output = Path.of(name);
        if (!output.isAbsolute()) output = gameDir.resolve(output);
        output = output.normalize();
        String ffmpeg = null;
        if (format.equals("mp4")) {
            ffmpeg = ffmpegLocator.apply(args.get("ffmpeg"));
            if (ffmpeg == null) {
                format = "png";
                warnings.add("ffmpeg not found, wrote a PNG sequence instead; pass ffmpeg or set -D" + Ffmpeg.PROPERTY + " / " + Ffmpeg.ENV);
            }
        }
        if (format.equals("mp4") && !output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            output = output.resolveSibling(output.getFileName() + ".mp4");
        }
        // a PNG sequence is a directory: an .mp4 name (the default, or the ffmpeg fallback) becomes <name>_frames
        if (format.equals("png") && output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4")) output = output.resolveSibling(stripExtension(output.getFileName().toString()) + "_frames");
        int contactSheet = integer(args.getOrDefault("contactSheet", DEFAULT_CONTACT_SHEET), "contactSheet");
        return new FilmSettings(output, format, fps, width, height, crf, preset, ffmpeg, Math.max(0, contactSheet), draft, List.copyOf(warnings));
    }

    public Path contactSheetPath() {
        String base = format.equals("png") ? output.getFileName().toString() : stripExtension(output.getFileName().toString());
        return output.resolveSibling(base + ".contact.png");
    }

    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "take" : cleaned;
    }

    static int integer(Object value, String field) {
        return (int) Math.round(Shot.number(value, field));
    }
}
