package io.izzel.minecraftmcp.film;

import java.util.List;

/**
 * Turns a {@link Shot} into one {@link CameraPose} per output frame. Frame {@code n} is the pose at time {@code n / fps},
 * whatever the capture rate. Look smoothing is stateful, so {@link #sample} must be called for frames in order.
 */
public final class ShotSampler {
    private final Shot shot;
    private final int fps;
    private final int frames;
    private final double lift;
    private final CameraPath path;
    private final CameraPath lookPath;
    private double yaw = Double.NaN;
    private double pitch;

    public ShotSampler(Shot shot, int fps, double lift) {
        if (fps <= 0) throw new IllegalArgumentException("fps must be positive");
        this.shot = shot;
        this.fps = fps;
        this.frames = shot.frames(fps);
        this.lift = lift;
        this.path = shot.camera.isEmpty() ? null : new CameraPath(shot.camera);
        this.lookPath = shot.lookPoints.isEmpty() ? null : new CameraPath(shot.lookPoints);
    }

    public Shot shot() { return shot; }
    public int frames() { return frames; }
    public int fps() { return fps; }
    public double lift() { return lift; }

    /** Eased progress through the shot, 0 to 1, of frame {@code n}. */
    public double progress(int n) {
        return frames <= 1 ? 0 : shot.easing.apply((double) n / (frames - 1));
    }

    /** The camera position at a progress (no smoothing involved), e.g. to preload chunks along the move. */
    public double[] cameraAt(double progress, double[] target) {
        double u = parameter(progress);
        double[] cam = switch (shot.preset) {
            case PATH -> path.at(u);
            case FOLLOW -> {
                double[] p = path.at(u);
                double[] dir = horizontalTangent(u);
                yield new double[] {p[0] - dir[0] * shot.back, p[1] + shot.height, p[2] - dir[1] * shot.back};
            }
            case ORBIT -> {
                double[] c = orbitCenter(target);
                double angle = Math.toRadians(shot.startAngle + shot.degrees * progress);
                yield new double[] {c[0] + Math.sin(angle) * shot.radius, c[1] + shot.height, c[2] - Math.cos(angle) * shot.radius};
            }
        };
        cam[1] += lift;
        return cam;
    }

    /**
     * The pose of frame {@code n}. {@code target} is the tracked entity's centre for shots with a {@code target}
     * (otherwise ignored, may be {@code null}).
     */
    public CameraPose sample(int n, double[] target) {
        double progress = progress(n);
        double u = parameter(progress);
        double[] cam = cameraAt(progress, target);
        double[] wanted = wantedAngles(progress, u, cam, target);
        if (Double.isNaN(yaw)) {
            yaw = wanted[0];
            pitch = wanted[1];
        } else {
            double alpha = shot.smoothing <= 0 ? 1 : 1 - Math.exp(-1.0 / (fps * shot.smoothing));
            yaw = yaw + alpha * CameraMath.wrapDegrees(wanted[0] - yaw);
            pitch = pitch + alpha * (wanted[1] - pitch);
        }
        double clampedPitch = pitch;
        if (shot.pitchMin != null) clampedPitch = Math.max(shot.pitchMin, clampedPitch);
        if (shot.pitchMax != null) clampedPitch = Math.min(shot.pitchMax, clampedPitch);
        clampedPitch = CameraMath.clamp(clampedPitch, -90, 90);
        float fov = shot.fov == null ? Float.NaN : (float) CameraMath.catmullRom(shot.fov, progress)[0];
        float roll = shot.roll == null ? 0 : (float) CameraMath.catmullRom(shot.roll, progress)[0];
        return new CameraPose(cam[0], cam[1], cam[2], (float) CameraMath.wrapDegrees(yaw), (float) clampedPitch, roll, fov);
    }

    private double[] wantedAngles(double progress, double u, double[] cam, double[] target) {
        return switch (shot.preset) {
            case ORBIT -> {
                double[] c = orbitCenter(target);
                yield CameraMath.anglesOf(cam, new double[] {c[0], c[1] + shot.lookHeight, c[2]});
            }
            case FOLLOW -> CameraMath.anglesOf(cam, followLookPoint(progress));
            case PATH -> switch (shot.look) {
                case POINTS -> CameraMath.anglesOf(cam, lookPath.at(u));
                case POINT -> CameraMath.anglesOf(cam, shot.lookPoint);
                case TARGET -> {
                    if (target == null) throw new IllegalStateException(shot.name + ": the target entity is not available");
                    yield CameraMath.anglesOf(cam, new double[] {target[0], target[1] + shot.lookHeight, target[2]});
                }
                case ANGLES -> {
                    double[] a = CameraMath.catmullRom(shot.angles, u);
                    yield new double[] {a[0], a[1]};
                }
                case FORWARD -> forwardAngles(u);
            };
        };
    }

    /** Keyframes are reached at the same progress on the camera and look splines; arc spacing reparameterises both by the camera path's length. */
    private double parameter(double progress) {
        if (path == null || !shot.arcLength) return progress;
        return path.parameterAt(progress);
    }

    private double[] orbitCenter(double[] target) {
        if (shot.center != null) return shot.center;
        if (target == null) throw new IllegalStateException(shot.name + ": the target entity is not available");
        return target;
    }

    private double[] followLookPoint(double progress) {
        double length = path.length();
        double distance = progress * length + shot.lookAhead;
        if (length <= 0) return new double[] {shot.camera.get(0)[0], shot.camera.get(0)[1] + shot.lookHeight, shot.camera.get(0)[2]};
        double[] p;
        if (distance <= length) {
            p = path.at(path.parameterAt(distance / length));
        } else {
            double[] end = path.at(1);
            double[] dir = direction(1);
            double excess = distance - length;
            p = new double[] {end[0] + dir[0] * excess, end[1] + dir[1] * excess, end[2] + dir[2] * excess};
        }
        return new double[] {p[0], p[1] + shot.lookHeight, p[2]};
    }

    private double[] forwardAngles(double u) {
        double[] dir = direction(u);
        if (dir[0] == 0 && dir[1] == 0 && dir[2] == 0) return new double[] {Double.isNaN(yaw) ? 0 : yaw, Double.isNaN(yaw) ? 0 : pitch};
        return CameraMath.anglesOf(new double[] {0, 0, 0}, dir);
    }

    /** Unit direction of travel along the camera spline at parameter {@code u}. */
    private double[] direction(double u) {
        double step = 1e-3;
        double a = Math.max(0, u - step), b = Math.min(1, u + step);
        double[] p = path.at(a), q = path.at(b);
        double[] d = {q[0] - p[0], q[1] - p[1], q[2] - p[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len < 1e-9) return new double[] {0, 0, 0};
        return new double[] {d[0] / len, d[1] / len, d[2] / len};
    }

    private double[] horizontalTangent(double u) {
        double[] d = direction(u);
        double len = Math.hypot(d[0], d[2]);
        return len < 1e-9 ? new double[] {0, 0} : new double[] {d[0] / len, d[2] / len};
    }

    /** Camera positions at {@code count + 1} evenly spaced progress values, for preloading and clearance checks. */
    public List<double[]> cameraSamples(int count, double[] target) {
        java.util.ArrayList<double[]> samples = new java.util.ArrayList<>();
        for (int i = 0; i <= count; i++) samples.add(cameraAt((double) i / count, target));
        return samples;
    }
}
