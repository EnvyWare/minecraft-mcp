package io.izzel.minecraftmcp.film;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Takes frames from the render thread and, on its own thread, analyses them, keeps contact-sheet thumbnails and writes
 * them to a {@link FrameSink}. The render thread only copies the framebuffer into a pooled buffer; if encoding falls
 * behind, {@link #submit} blocks, which is harmless because shot timing does not depend on capture speed.
 */
public final class FilmEncoder {
    static final int QUEUE = 6;
    private static final Object DUPLICATE = new Object();
    private static final Object END = new Object();

    private final int width;
    private final int height;
    private final int fps;
    private final FrameSink sink;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE);
    private final BlockingQueue<byte[]> pool = new ArrayBlockingQueue<>(QUEUE + 3);
    private final FrameAnalyzer analyzer = new FrameAnalyzer();
    private final ContactSheet sheet;
    private final Thread worker;
    private final int thumbHeight;
    private final int lumaHeight;
    private volatile Throwable failure;
    private int allocated;
    private int frames;
    private int duplicates;
    private byte[] last;
    private float[] lastLuma;
    private int[] lastThumb;

    private record Frame(byte[] rgba, boolean cut, boolean still) {}

    public FilmEncoder(FrameSink sink, int width, int height, int fps, int contactSheetFrames) {
        this.sink = sink;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.sheet = new ContactSheet(contactSheetFrames);
        this.thumbHeight = Frames.thumbHeight(width, height, Frames.THUMB_WIDTH);
        this.lumaHeight = Frames.thumbHeight(width, height, Frames.LUMA_WIDTH);
        this.worker = new Thread(this::run, "minecraft-mcp-film-encoder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public int width() { return width; }
    public int height() { return height; }
    public int fps() { return fps; }

    /** A buffer of {@code width * height * 4} bytes to fill and pass to {@link #submit}. */
    public byte[] buffer() throws IOException, InterruptedException {
        checkFailure();
        byte[] buffer = pool.poll();
        if (buffer != null) return buffer;
        synchronized (this) {
            if (allocated < QUEUE + 3) {
                allocated++;
                return new byte[width * height * 4];
            }
        }
        while (true) {
            checkFailure();
            buffer = pool.poll(100, TimeUnit.MILLISECONDS);
            if (buffer != null) return buffer;
        }
    }

    /** Queues a frame (GL-order RGBA). {@code cut} marks the first frame of a new shot. */
    public void submit(byte[] rgba, boolean cut) throws IOException, InterruptedException {
        submit(rgba, cut, false);
    }

    /** As {@link #submit(byte[], boolean)}; {@code still} says the camera hardly moved since the previous frame. */
    public void submit(byte[] rgba, boolean cut, boolean still) throws IOException, InterruptedException {
        put(new Frame(rgba, cut, still));
    }

    /** Writes the previous frame again, for real-time recording when rendering is slower than the output rate. */
    public void duplicate() throws IOException, InterruptedException {
        put(DUPLICATE);
    }

    private void put(Object item) throws IOException, InterruptedException {
        while (!queue.offer(item, 100, TimeUnit.MILLISECONDS)) checkFailure();
        checkFailure();
    }

    private void checkFailure() throws IOException {
        Throwable t = failure;
        if (t != null) throw new IOException("encoding failed: " + t.getMessage(), t);
    }

    private void run() {
        try {
            while (true) {
                Object item = queue.take();
                if (item == END) return;
                if (item == DUPLICATE) {
                    if (last == null) continue;
                    sink.write(last);
                    analyzer.accept(lastLuma, false);
                    sheet.offer(frames, lastThumb, Frames.THUMB_WIDTH, thumbHeight);
                    frames++;
                    duplicates++;
                    continue;
                }
                Frame frame = (Frame) item;
                int[] thumb = Frames.thumbnail(frame.rgba, width, height, Frames.THUMB_WIDTH, thumbHeight);
                float[] luma = Frames.luma(thumb, Frames.THUMB_WIDTH, thumbHeight, Frames.LUMA_WIDTH, lumaHeight);
                analyzer.accept(luma, frame.cut, frame.still);
                sheet.offer(frames, thumb, Frames.THUMB_WIDTH, thumbHeight);
                sink.write(frame.rgba);
                frames++;
                if (last != null) pool.offer(last);
                last = frame.rgba;
                lastLuma = luma;
                lastThumb = thumb;
            }
        } catch (Throwable t) {
            failure = t;
            queue.clear();
        }
    }

    /** Finishes encoding and returns the report: output, frame count, quality checks and the contact sheet path. */
    public Map<String, Object> finish(java.nio.file.Path contactSheetPath) throws IOException, InterruptedException {
        put(END);
        worker.join();
        checkFailure();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", sink.finish());
        result.put("frames", frames);
        result.put("fps", fps);
        result.put("seconds", CameraPose.round((double) frames / fps, 3));
        if (duplicates > 0) result.put("duplicatedFrames", duplicates);
        result.put("quality", analyzer.report());
        if (contactSheetPath != null && sheet.enabled() && sheet.write(contactSheetPath, fps)) {
            result.put("contactSheet", contactSheetPath.toAbsolutePath().toString());
        }
        return result;
    }

    /** Stops without producing output. */
    public void abort() {
        failure = failure == null ? new IOException("aborted") : failure;
        worker.interrupt();
        sink.abort();
    }
}
