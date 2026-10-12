package io.izzel.minecraftmcp.film;

/**
 * Pixel helpers. Captured frames are RGBA bytes in OpenGL's row order (bottom row first), exactly as read back from the
 * framebuffer; thumbnails are packed {@code 0xRRGGBB} ints, top row first.
 */
public final class Frames {
    public static final int THUMB_WIDTH = 320;
    public static final int LUMA_WIDTH = 64;

    private Frames() {}

    /** A box-filtered thumbnail ({@code 2x2} samples per output pixel), flipped to top row first. */
    public static int[] thumbnail(byte[] rgba, int width, int height, int tw, int th) {
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                int r = 0, g = 0, b = 0;
                for (int sy = 0; sy < 2; sy++) {
                    // flip: top thumbnail row samples the top of the image, which is the last rows of GL data
                    int y = height - 1 - Math.min(height - 1, (int) (((ty * 2 + sy) + 0.5) * height / (th * 2.0)));
                    for (int sx = 0; sx < 2; sx++) {
                        int x = Math.min(width - 1, (int) (((tx * 2 + sx) + 0.5) * width / (tw * 2.0)));
                        int i = (y * width + x) * 4;
                        r += rgba[i] & 0xFF;
                        g += rgba[i + 1] & 0xFF;
                        b += rgba[i + 2] & 0xFF;
                    }
                }
                out[ty * tw + tx] = ((r >> 2) << 16) | ((g >> 2) << 8) | (b >> 2);
            }
        }
        return out;
    }

    /** Thumbnail height for a frame's aspect ratio. */
    public static int thumbHeight(int width, int height, int tw) {
        return Math.max(1, (int) Math.round((double) tw * height / width));
    }

    /** Box-averaged luma (0-255) grid of a thumbnail. */
    public static float[] luma(int[] thumb, int tw, int th, int lw, int lh) {
        float[] sum = new float[lw * lh];
        int[] count = new int[lw * lh];
        for (int y = 0; y < th; y++) {
            int ly = Math.min(lh - 1, y * lh / th);
            for (int x = 0; x < tw; x++) {
                int lx = Math.min(lw - 1, x * lw / tw);
                int p = thumb[y * tw + x];
                sum[ly * lw + lx] += 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
                count[ly * lw + lx]++;
            }
        }
        for (int i = 0; i < sum.length; i++) sum[i] = count[i] == 0 ? 0 : sum[i] / count[i];
        return sum;
    }

    /** Nearest-neighbour resize of a GL-order RGBA frame, for the rare frame whose size differs from the take's. */
    public static byte[] resize(byte[] rgba, int width, int height, int newWidth, int newHeight, byte[] into) {
        byte[] out = into != null && into.length >= newWidth * newHeight * 4 ? into : new byte[newWidth * newHeight * 4];
        for (int y = 0; y < newHeight; y++) {
            int sy = Math.min(height - 1, y * height / newHeight);
            for (int x = 0; x < newWidth; x++) {
                int sx = Math.min(width - 1, x * width / newWidth);
                System.arraycopy(rgba, (sy * width + sx) * 4, out, (y * newWidth + x) * 4, 4);
            }
        }
        return out;
    }
}
