package io.izzel.minecraftmcp.film;

import java.util.List;

/**
 * Lifts a whole camera move above anything it would pass through: at every sample the camera needs {@code clearBlocks}
 * air blocks at and above it. The lift is the largest rise any sample needs, plus a 2 block margin when it is not 0.
 */
public final class Clearance {
    public static final int MAX_RISE = 40;
    static final int MARGIN = 2;
    /** Half-width of the box around the camera that must be clear. */
    static final double BOX = 0.5;

    private Clearance() {}

    @FunctionalInterface
    public interface AirQuery {
        boolean isAir(int x, int y, int z);
    }

    /**
     * How far one camera position must rise to have {@code clearBlocks} air blocks starting half a block below it, in
     * every block column within half a block of it (a block right next to the lens fills the picture);
     * {@link #MAX_RISE} if it never does.
     */
    public static int rise(double[] camera, int clearBlocks, AirQuery air) {
        int x0 = (int) Math.floor(camera[0] - BOX), x1 = (int) Math.floor(camera[0] + BOX);
        int z0 = (int) Math.floor(camera[2] - BOX), z1 = (int) Math.floor(camera[2] + BOX);
        int y = (int) Math.floor(camera[1] - BOX);
        for (int up = 0; up < MAX_RISE; up++) {
            if (clear(x0, x1, y + up, z0, z1, clearBlocks, air)) return up;
        }
        return MAX_RISE;
    }

    private static boolean clear(int x0, int x1, int y, int z0, int z1, int clearBlocks, AirQuery air) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int k = 0; k < clearBlocks; k++) if (!air.isAir(x, y + k, z)) return false;
            }
        }
        return true;
    }

    public static int lift(List<double[]> samples, int clearBlocks, AirQuery air) {
        if (clearBlocks <= 0) return 0;
        int need = 0;
        for (double[] sample : samples) need = Math.max(need, rise(sample, clearBlocks, air));
        return finish(need);
    }

    /** The lift for the largest per-sample rise found. */
    public static int finish(int maxRise) {
        return maxRise > 0 ? maxRise + MARGIN : 0;
    }
}
