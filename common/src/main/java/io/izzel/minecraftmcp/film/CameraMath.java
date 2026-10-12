package io.izzel.minecraftmcp.film;

import java.util.List;

/** Loader-independent camera maths for shots: Catmull-Rom splines and Minecraft view angles. */
public final class CameraMath {
    private CameraMath() {}

    /**
     * Centripetal Catmull-Rom spline through the keyframes; {@code u} in [0,1] is spread evenly over the segments, so
     * keyframe {@code i} is reached at {@code u = i / (n - 1)}. Centripetal knots keep the curve from overshooting,
     * looping or doubling back when keyframes are unevenly spaced; the ends use reflected phantom points so a straight
     * run of keyframes is a straight, evenly paced line.
     */
    public static double[] catmullRom(List<double[]> keys, double u) {
        int n = keys.size();
        if (n == 0) throw new IllegalArgumentException("a spline needs at least one keyframe");
        if (n == 1) return keys.get(0).clone();
        double s = clamp(u, 0, 1) * (n - 1);
        int i = Math.min((int) Math.floor(s), n - 2);
        double local = s - i;
        double[] p1 = keys.get(i), p2 = keys.get(i + 1);
        double[] p0 = i > 0 ? keys.get(i - 1) : reflect(p1, p2);
        double[] p3 = i + 2 < n ? keys.get(i + 2) : reflect(p2, p1);
        double t0 = 0, t1 = t0 + Math.sqrt(length(p0, p1)), t2 = t1 + Math.sqrt(length(p1, p2)), t3 = t2 + Math.sqrt(length(p2, p3));
        double[] out = new double[p1.length];
        if (t1 - t0 < 1e-9 || t2 - t1 < 1e-9 || t3 - t2 < 1e-9) {
            // repeated keyframes: centripetal knots degenerate, so fall back to uniform
            for (int k = 0; k < out.length; k++) {
                double t = local;
                out[k] = 0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t * t + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t * t * t);
            }
            return out;
        }
        double t = t1 + local * (t2 - t1);
        for (int k = 0; k < out.length; k++) {
            double a1 = ((t1 - t) * p0[k] + (t - t0) * p1[k]) / (t1 - t0);
            double a2 = ((t2 - t) * p1[k] + (t - t1) * p2[k]) / (t2 - t1);
            double a3 = ((t3 - t) * p2[k] + (t - t2) * p3[k]) / (t3 - t2);
            double b1 = ((t2 - t) * a1 + (t - t0) * a2) / (t2 - t0);
            double b2 = ((t3 - t) * a2 + (t - t1) * a3) / (t3 - t1);
            out[k] = ((t2 - t) * b1 + (t - t1) * b2) / (t2 - t1);
        }
        return out;
    }

    private static double[] reflect(double[] point, double[] other) {
        double[] out = new double[point.length];
        for (int k = 0; k < out.length; k++) out[k] = 2 * point[k] - other[k];
        return out;
    }

    private static double length(double[] a, double[] b) {
        double sum = 0;
        for (int k = 0; k < a.length; k++) sum += (a[k] - b[k]) * (a[k] - b[k]);
        return Math.sqrt(sum);
    }

    /** Yaw and pitch in degrees, in Minecraft's convention (yaw 0 = south, positive pitch = down), of a camera at {@code from} looking at {@code to}. */
    public static double[] anglesOf(double[] from, double[] to) {
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        double horizontal = Math.hypot(dx, dz);
        return new double[] {Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, horizontal))};
    }

    /** The angle in [-180, 180). */
    public static double wrapDegrees(double degrees) {
        return ((degrees + 180) % 360 + 360) % 360 - 180;
    }

    /** Keyframe yaws rewritten so each differs from the previous by at most 180 degrees, so a spline turns the short way. */
    public static void unwrapYaw(List<double[]> angles) {
        for (int i = 1; i < angles.size(); i++) {
            double[] previous = angles.get(i - 1), current = angles.get(i);
            current[0] = previous[0] + wrapDegrees(current[0] - previous[0]);
        }
    }

    public static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
