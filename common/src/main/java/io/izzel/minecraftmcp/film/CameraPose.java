package io.izzel.minecraftmcp.film;

import java.util.LinkedHashMap;
import java.util.Map;

/** Where the camera is and where it looks for one frame. {@code fov} is {@code NaN} to keep the configured field of view. */
public record CameraPose(double x, double y, double z, float yaw, float pitch, float roll, float fov) {
    public double[] position() {
        return new double[] {x, y, z};
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", round(x, 3));
        map.put("y", round(y, 3));
        map.put("z", round(z, 3));
        map.put("yaw", round(yaw, 2));
        map.put("pitch", round(pitch, 2));
        if (roll != 0) map.put("roll", round(roll, 2));
        if (!Float.isNaN(fov)) map.put("fov", round(fov, 2));
        return map;
    }

    static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
