package io.izzel.minecraftmcp.film;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilmEncoderTest {
    @TempDir
    Path dir;

    /** A GL-order frame of one grey level with a bright column at {@code stripe}. */
    static byte[] frame(int width, int height, int grey, int stripe) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int v = Math.abs(x - stripe) < 4 ? 255 : grey;
                rgba[i] = (byte) v;
                rgba[i + 1] = (byte) v;
                rgba[i + 2] = (byte) v;
                rgba[i + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    static final class MemorySink implements FrameSink {
        final List<byte[]> frames = new ArrayList<>();
        public void write(byte[] rgba) { frames.add(rgba.clone()); }
        public Map<String, Object> finish() { return Map.of("format", "memory", "frames", frames.size()); }
        public void abort() {}
    }

    @Test
    void smoothPanIsReportedClean() throws Exception {
        MemorySink sink = new MemorySink();
        FilmEncoder encoder = new FilmEncoder(sink, 160, 90, 30, 4);
        for (int n = 0; n < 30; n++) {
            byte[] buffer = encoder.buffer();
            System.arraycopy(frame(160, 90, 60, 10 + n * 4), 0, buffer, 0, buffer.length);
            encoder.submit(buffer, false);
        }
        Map<String, Object> report = encoder.finish(dir.resolve("sheet.png"));
        assertEquals(30, sink.frames.size());
        assertEquals(30, report.get("frames"));
        assertEquals(1.0, report.get("seconds"));
        Map<?, ?> quality = (Map<?, ?>) report.get("quality");
        assertEquals(0, quality.get("frozenFrames"));
        assertEquals(0, quality.get("blackFrames"));
        assertEquals("ok", quality.get("verdict"), String.valueOf(quality));
        BufferedImage sheet = ImageIO.read(dir.resolve("sheet.png").toFile());
        assertEquals(4 * Frames.THUMB_WIDTH, sheet.getWidth());
        assertEquals(180 + ContactSheet.LABEL_HEIGHT, sheet.getHeight());
    }

    @Test
    void stutterFrozenAndBlackFramesAreFlagged() throws Exception {
        MemorySink sink = new MemorySink();
        FilmEncoder encoder = new FilmEncoder(sink, 160, 90, 30, 0);
        int[] stripes = {10, 14, 18, 22, 22, 30, 34, 38, 42, 46, 50, 54};
        for (int stripe : stripes) encoder.submit(frame(160, 90, 60, stripe), false);
        encoder.submit(frame(160, 90, 0, -100), true);
        encoder.duplicate();
        Map<String, Object> report = encoder.finish(null);
        Map<?, ?> quality = (Map<?, ?>) report.get("quality");
        assertEquals(14, report.get("frames"));
        assertEquals(1, report.get("duplicatedFrames"));
        assertEquals(2, quality.get("frozenFrames")); // the repeated stripe and the duplicate
        assertEquals(2, quality.get("blackFrames"));
        assertTrue(((Number) quality.get("motionSpikes")).intValue() >= 1);
        assertEquals("check", quality.get("verdict"));
        assertFalse(report.containsKey("contactSheet"));
    }

    @Test
    void unchangedFramesWhileTheCameraHoldsStillAreNotFrozen() {
        double[] means = {100, 100, 100, 100};
        double[] diffs = {Double.NaN, 3, 0.1, 0.1};
        Map<String, Object> report = FrameAnalyzer.summarize(means, diffs, new boolean[] {false, false, true, false});
        assertEquals(1, report.get("frozenFrames"));
        assertEquals(1, report.get("stillFrames"));
    }

    @Test
    void analyzerIgnoresCutsBetweenShots() {
        double[] means = {100, 100, 100, 100};
        double[] diffs = {Double.NaN, 3, Double.NaN, 3};
        Map<String, Object> report = FrameAnalyzer.summarize(means, diffs);
        assertEquals(0, report.get("frozenFrames"));
        assertEquals(0.0, report.get("jitter"));
        assertEquals("ok", report.get("verdict"));
    }

    @Test
    void contactSheetKeepsFirstAndLastAcrossStrideDoubling() {
        ContactSheet sheet = new ContactSheet(4);
        int[] thumb = new int[8 * 4];
        for (int frame = 0; frame < 101; frame++) sheet.offer(frame, thumb, 8, 4);
        List<Integer> chosen = sheet.chosenFrames();
        assertEquals(4, chosen.size());
        assertEquals(0, chosen.get(0));
        assertEquals(100, chosen.get(3));
    }

    @Test
    void thumbnailsFlipGlRowOrder() {
        // bottom half (first rows in GL order) white, top half black
        byte[] rgba = new byte[4 * 4 * 4];
        Arrays.fill(rgba, 0, rgba.length / 2, (byte) 255);
        int[] thumb = Frames.thumbnail(rgba, 4, 4, 2, 2);
        assertEquals(0, thumb[0]);
        assertEquals(0xFFFFFF, thumb[2]);
        assertEquals(255f, Frames.luma(thumb, 2, 2, 1, 2)[1], 0.01f);
    }

    @Test
    void pngFallbackWritesUprightFrames() throws Exception {
        PngSequenceSink sink = new PngSequenceSink(dir.resolve("frames"), 4, 4);
        byte[] rgba = new byte[4 * 4 * 4];
        Arrays.fill(rgba, 0, rgba.length / 2, (byte) 255);
        sink.write(rgba);
        Map<String, Object> result = sink.finish();
        assertEquals("png", result.get("format"));
        BufferedImage image = ImageIO.read(dir.resolve("frames").resolve(PngSequenceSink.frameName(0)).toFile());
        assertEquals(0, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0xFFFFFF, image.getRGB(0, 3) & 0xFFFFFF);
    }

    @Test
    void ffmpegCommandFlipsScalesAndKeepsEvenSizes() {
        List<String> plain = FfmpegSink.command("ffmpeg", Path.of("out.mp4"), 1600, 900, 30, 1600, 900, 18, "veryfast");
        assertEquals(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "1600x900", "-framerate", "30", "-i", "pipe:0", "-an",
                "-vf", "vflip", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "out.mp4"), plain);
        List<String> scaled = FfmpegSink.command("ffmpeg", Path.of("out.mp4"), 1601, 901, 10, FfmpegSink.even(1601), FfmpegSink.even(901), 28, "ultrafast");
        assertTrue(scaled.contains("vflip,scale=1600:900:flags=lanczos"), String.valueOf(scaled));
    }

    @Test
    void settingsResolvePathsDraftAndFallback() {
        Path game = dir;
        FilmSettings draft = FilmSettings.from(Map.of("draft", true, "output", "films/take.mp4", "fps", 60), game, "x", requested -> "ffmpeg");
        assertEquals(game.resolve("films/take.mp4"), draft.output());
        assertEquals(10, draft.fps());
        assertEquals(640, draft.width());
        assertEquals("ultrafast", draft.preset());
        assertEquals(game.resolve("films/take.contact.png"), draft.contactSheetPath());
        FilmSettings noFfmpeg = FilmSettings.from(Map.of("output", "films/take.mp4"), game, "x", requested -> null);
        assertEquals("png", noFfmpeg.format());
        assertEquals(game.resolve("films/take_frames"), noFfmpeg.output());
        assertEquals(1, noFfmpeg.warnings().size());
        assertEquals(game.resolve("films/stills"), FilmSettings.from(Map.of("format", "png", "output", "films/stills"), game, "x", requested -> "ffmpeg").output());
        assertThrows(IllegalArgumentException.class, () -> FilmSettings.from(Map.of("width", 640), game, "x", requested -> "ffmpeg"));
        assertThrows(IllegalArgumentException.class, () -> FilmSettings.from(Map.of("preset", "fast; rm"), game, "x", requested -> "ffmpeg"));
    }

    /** Runs only where an ffmpeg is configured (MINECRAFT_MCP_FFMPEG or -DminecraftMcp.ffmpeg). */
    @Test
    void encodesAnMp4WithARealFfmpeg() throws Exception {
        String ffmpeg = Ffmpeg.locate(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg configured");
        Path out = dir.resolve("take.mp4");
        FilmEncoder encoder = new FilmEncoder(new FfmpegSink(ffmpeg, out, 161, 91, 30, 0, 0, 23, "ultrafast"), 161, 91, 30, 4);
        for (int n = 0; n < 15; n++) encoder.submit(frame(161, 91, 80, n * 8), false);
        Map<String, Object> report = encoder.finish(null);
        Map<?, ?> output = (Map<?, ?>) report.get("output");
        assertEquals("mp4", output.get("format"));
        assertEquals(160, output.get("width"));
        assertTrue(Files.size(out) > 0);
        assertFalse(Files.exists(dir.resolve("take.mp4.ffmpeg.log")));
    }

    @Test
    void requestedFfmpegMustBeAnFfmpegFile() throws IOException {
        Path fake = Files.createFile(dir.resolve("ffmpeg-test.exe"));
        assertEquals(fake.toAbsolutePath().toString(), Ffmpeg.checkRequested(fake.toString()));
        Path other = Files.createFile(dir.resolve("calc.exe"));
        assertThrows(IllegalArgumentException.class, () -> Ffmpeg.checkRequested(other.toString()));
        assertThrows(IllegalArgumentException.class, () -> Ffmpeg.checkRequested(dir.resolve("ffmpeg-missing.exe").toString()));
    }
}
