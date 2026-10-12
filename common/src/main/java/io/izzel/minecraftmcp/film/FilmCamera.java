package io.izzel.minecraftmcp.film;

/**
 * The pose the game camera is forced to while a shot plays. Written by the camera player on the render thread and read by
 * the loader's camera hook when the camera is set up for a frame; {@code null} leaves the camera alone.
 */
public final class FilmCamera {
    private static volatile CameraPose pose;

    private FilmCamera() {}

    public static CameraPose pose() {
        return pose;
    }

    public static void set(CameraPose value) {
        pose = value;
    }

    public static void clear() {
        pose = null;
    }
}
