package io.izzel.minecraftmcp.input;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keys held while another key is pressed. Modifier names (shift, ctrl, alt, super) set the matching GLFW modifier
 * bit and hold the left-hand key; any other key name (for example F3) is held without a modifier bit.
 */
public record KeyChord(int modifiers, List<String> heldKeys, List<String> names) {
    public static final int MOD_SHIFT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_ALT = 0x0004;
    public static final int MOD_SUPER = 0x0008;
    public static final KeyChord NONE = new KeyChord(0, List.of(), List.of());

    public KeyChord {
        heldKeys = List.copyOf(heldKeys);
        names = List.copyOf(names);
    }

    public boolean isEmpty() {
        return heldKeys.isEmpty();
    }

    /** Accepts null, a single name, a "shift+ctrl" string or a list of names. */
    public static KeyChord parse(Object value) {
        List<String> raw = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) raw.add(String.valueOf(item));
        } else if (value != null && !String.valueOf(value).isBlank()) {
            raw.addAll(List.of(String.valueOf(value).split("[+,]")));
        }
        int modifiers = 0;
        Set<String> held = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String entry : raw) {
            String name = entry.trim();
            if (name.isEmpty()) continue;
            switch (name.toLowerCase(Locale.ROOT)) {
                case "shift" -> { modifiers |= MOD_SHIFT; held.add("LEFT_SHIFT"); names.add("shift"); }
                case "ctrl", "control" -> { modifiers |= MOD_CONTROL; held.add("LEFT_CONTROL"); names.add("ctrl"); }
                case "alt" -> { modifiers |= MOD_ALT; held.add("LEFT_ALT"); names.add("alt"); }
                case "super", "meta", "cmd", "win" -> { modifiers |= MOD_SUPER; held.add("LEFT_SUPER"); names.add("super"); }
                default -> {
                    String upper = name.toUpperCase(Locale.ROOT).replace('-', '_');
                    modifiers |= modifierBit(upper);
                    held.add(upper);
                    names.add(upper);
                }
            }
        }
        return held.isEmpty() ? NONE : new KeyChord(modifiers, new ArrayList<>(held), names);
    }

    private static int modifierBit(String keyName) {
        return switch (keyName) {
            case "LEFT_SHIFT", "RIGHT_SHIFT" -> MOD_SHIFT;
            case "LEFT_CONTROL", "RIGHT_CONTROL" -> MOD_CONTROL;
            case "LEFT_ALT", "RIGHT_ALT" -> MOD_ALT;
            case "LEFT_SUPER", "RIGHT_SUPER" -> MOD_SUPER;
            default -> 0;
        };
    }
}
