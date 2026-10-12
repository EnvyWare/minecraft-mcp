package io.izzel.minecraftmcp.film;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One image with evenly spaced thumbnails of a take, so an agent can check it by reading a single picture.
 * The take's length need not be known in advance: thumbnails are kept at a stride that doubles whenever too many are kept.
 */
public final class ContactSheet {
    public static final int COLUMNS = 4;
    static final int LABEL_HEIGHT = 18;

    private final int wanted;
    private final TreeMap<Integer, int[]> kept = new TreeMap<>();
    private int stride = 1;
    private int lastFrame = -1;
    private int[] last;
    private int width;
    private int height;

    public ContactSheet(int wanted) {
        this.wanted = Math.max(0, wanted);
    }

    public boolean enabled() {
        return wanted > 0;
    }

    /** Offers frame {@code frame}'s thumbnail (top row first); keeps it if it falls on the current stride. */
    public void offer(int frame, int[] thumb, int tw, int th) {
        if (wanted == 0) return;
        width = tw;
        height = th;
        lastFrame = frame;
        last = thumb;
        if (frame % stride != 0) return;
        kept.put(frame, thumb);
        if (kept.size() > wanted * 2) {
            stride *= 2;
            kept.keySet().removeIf(f -> f % stride != 0);
        }
    }

    /** The frames shown: evenly spaced over the kept ones, always including the first and the last frame. */
    public List<Integer> chosenFrames() {
        TreeMap<Integer, int[]> all = new TreeMap<>(kept);
        if (last != null) all.put(lastFrame, last);
        List<Integer> frames = new ArrayList<>(all.keySet());
        if (frames.size() <= wanted) return frames;
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < wanted; i++) {
            int index = (int) Math.round((double) i * (frames.size() - 1) / (wanted - 1));
            int frame = frames.get(index);
            if (chosen.isEmpty() || chosen.get(chosen.size() - 1) != frame) chosen.add(frame);
        }
        return chosen;
    }

    public BufferedImage render(int fps) {
        TreeMap<Integer, int[]> all = new TreeMap<>(kept);
        if (last != null) all.put(lastFrame, last);
        List<Integer> frames = chosenFrames();
        if (frames.isEmpty()) return null;
        int columns = Math.min(COLUMNS, frames.size());
        int rows = (frames.size() + columns - 1) / columns;
        int cellHeight = height + LABEL_HEIGHT;
        BufferedImage sheet = new BufferedImage(columns * width, rows * cellHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(new Color(24, 24, 24));
            g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            for (int i = 0; i < frames.size(); i++) {
                int frame = frames.get(i);
                int x = (i % columns) * width, y = (i / columns) * cellHeight;
                sheet.setRGB(x, y, width, height, all.get(frame), 0, width);
                label(g, String.format(Locale.ROOT, "#%d  %.2fs", frame, fps > 0 ? (double) frame / fps : 0), x + 4, y + height + LABEL_HEIGHT - 5);
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

    /** Writes the sheet as PNG; returns {@code false} when there is nothing to write. */
    public boolean write(Path file, int fps) throws IOException {
        BufferedImage image = render(fps);
        if (image == null) return false;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        if (!ImageIO.write(image, "png", file.toFile())) throw new IOException("no PNG writer");
        return true;
    }

    public Map<String, Object> describe() {
        return Map.of("frames", chosenFrames(), "columns", COLUMNS);
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        try {
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.drawString(text, x, y);
        } catch (Throwable ignored) {
            // no fonts on this system: the thumbnails are still useful without labels
        }
    }
}
