package io.izzel.minecraftmcp.film;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Writes {@code frame_00000.png}, {@code frame_00001.png}, ... into a directory: the fallback when there is no ffmpeg. */
public final class PngSequenceSink implements FrameSink {
    static final int THREADS = 3;

    private final Path directory;
    private final int width;
    private final int height;
    private final ExecutorService writers = Executors.newFixedThreadPool(THREADS, r -> {
        Thread thread = new Thread(r, "minecraft-mcp-film-png");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore inFlight = new Semaphore(THREADS * 2);
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private int next;

    public PngSequenceSink(Path directory, int width, int height) throws IOException {
        this.directory = directory;
        this.width = width;
        this.height = height;
        Files.createDirectories(directory);
    }

    public static String frameName(int index) {
        return String.format(Locale.ROOT, "frame_%05d.png", index);
    }

    @Override
    public void write(byte[] rgba) throws IOException {
        IOException failed = failure.get();
        if (failed != null) throw failed;
        // the caller reuses its buffer, so convert now and only hand the encoding to the writer threads
        BufferedImage image = toImage(rgba, width, height);
        Path file = directory.resolve(frameName(next++));
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        writers.execute(() -> {
            try {
                ImageIO.write(image, "png", file.toFile());
            } catch (IOException e) {
                failure.compareAndSet(null, e);
            } finally {
                inFlight.release();
            }
        });
    }

    static BufferedImage toImage(byte[] rgba, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int src = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++, src += 4) row[x] = ((rgba[src] & 0xFF) << 16) | ((rgba[src + 1] & 0xFF) << 8) | (rgba[src + 2] & 0xFF);
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    @Override
    public Map<String, Object> finish() throws IOException, InterruptedException {
        writers.shutdown();
        if (!writers.awaitTermination(10, TimeUnit.MINUTES)) throw new IOException("PNG writers did not finish");
        IOException failed = failure.get();
        if (failed != null) throw failed;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "png");
        result.put("path", directory.toAbsolutePath().toString());
        result.put("pattern", "frame_%05d.png");
        result.put("width", width);
        result.put("height", height);
        return result;
    }

    @Override
    public void abort() {
        writers.shutdownNow();
    }
}
