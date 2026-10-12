package io.izzel.minecraftmcp.film;

import java.util.List;

/**
 * A Catmull-Rom spline with an arc-length table, so a camera can travel it at constant speed however unevenly
 * the keyframes are spaced.
 */
public final class CameraPath {
    private static final int SAMPLES_PER_SEGMENT = 64;

    private final List<double[]> keys;
    private final double[] cumulative;

    public CameraPath(List<double[]> keys) {
        if (keys.isEmpty()) throw new IllegalArgumentException("a path needs at least one keyframe");
        this.keys = List.copyOf(keys);
        int samples = Math.max(1, (keys.size() - 1) * SAMPLES_PER_SEGMENT);
        cumulative = new double[samples + 1];
        double[] previous = CameraMath.catmullRom(this.keys, 0);
        for (int i = 1; i <= samples; i++) {
            double[] point = CameraMath.catmullRom(this.keys, (double) i / samples);
            cumulative[i] = cumulative[i - 1] + CameraMath.distance(previous, point);
            previous = point;
        }
    }

    public double[] at(double u) {
        return CameraMath.catmullRom(keys, u);
    }

    public double length() {
        return cumulative[cumulative.length - 1];
    }

    /** The spline parameter at which {@code fraction} of the path's length has been travelled. */
    public double parameterAt(double fraction) {
        double total = length();
        if (total <= 0) return CameraMath.clamp(fraction, 0, 1);
        double target = CameraMath.clamp(fraction, 0, 1) * total;
        int lo = 0, hi = cumulative.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] < target) lo = mid; else hi = mid;
        }
        double span = cumulative[hi] - cumulative[lo];
        double within = span <= 0 ? 0 : (target - cumulative[lo]) / span;
        return (lo + within) / (cumulative.length - 1);
    }
}
