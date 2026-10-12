package io.izzel.minecraftmcp.film;

import java.util.Locale;

/** How a shot's progress follows time. */
public enum Easing {
    LINEAR("linear"),
    /** Quadratic ease in and out: slow start and end. The default. */
    EASE_IN_OUT("easeInOut"),
    EASE_IN_OUT_CUBIC("easeInOutCubic"),
    EASE_IN_OUT_SINE("easeInOutSine"),
    EASE_IN("easeIn"),
    EASE_OUT("easeOut");

    private final String id;

    Easing(String id) { this.id = id; }

    public String id() { return id; }

    public double apply(double t) {
        double x = CameraMath.clamp(t, 0, 1);
        return switch (this) {
            case LINEAR -> x;
            case EASE_IN_OUT -> x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
            case EASE_IN_OUT_CUBIC -> x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
            case EASE_IN_OUT_SINE -> -(Math.cos(Math.PI * x) - 1) / 2;
            case EASE_IN -> x * x;
            case EASE_OUT -> 1 - (1 - x) * (1 - x);
        };
    }

    /** Parses names such as {@code linear}, {@code easeInOut}, {@code ease-in-out}, {@code ease}, {@code cubic} or {@code sine}; {@code null} gives the default. */
    public static Easing parse(Object value) {
        if (value == null) return EASE_IN_OUT;
        String key = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (key) {
            case "linear", "none" -> LINEAR;
            case "ease", "easeinout", "smooth", "quad", "easeinoutquad" -> EASE_IN_OUT;
            case "cubic", "easeinoutcubic" -> EASE_IN_OUT_CUBIC;
            case "sine", "easeinoutsine" -> EASE_IN_OUT_SINE;
            case "easein" -> EASE_IN;
            case "easeout" -> EASE_OUT;
            default -> throw new IllegalArgumentException("Unknown easing: " + value + " (expected linear, easeInOut, easeInOutCubic, easeInOutSine, easeIn or easeOut)");
        };
    }
}
