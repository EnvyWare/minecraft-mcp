package io.izzel.minecraftmcp.input;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyChordTest {
    @Test
    void emptyInputsHaveNoChord() {
        assertTrue(KeyChord.parse(null).isEmpty());
        assertTrue(KeyChord.parse("").isEmpty());
        assertTrue(KeyChord.parse(List.of()).isEmpty());
    }

    @Test
    void modifierNamesSetGlfwBitsAndHoldLeftKeys() {
        KeyChord chord = KeyChord.parse(List.of("shift", "CTRL", "alt"));

        assertEquals(KeyChord.MOD_SHIFT | KeyChord.MOD_CONTROL | KeyChord.MOD_ALT, chord.modifiers());
        assertEquals(List.of("LEFT_SHIFT", "LEFT_CONTROL", "LEFT_ALT"), chord.heldKeys());
        assertEquals(List.of("shift", "ctrl", "alt"), chord.names());
    }

    @Test
    void plusSeparatedStringIsAccepted() {
        KeyChord chord = KeyChord.parse("ctrl+shift");

        assertEquals(KeyChord.MOD_CONTROL | KeyChord.MOD_SHIFT, chord.modifiers());
        assertEquals(List.of("LEFT_CONTROL", "LEFT_SHIFT"), chord.heldKeys());
    }

    @Test
    void otherKeysAreHeldWithoutModifierBits() {
        KeyChord chord = KeyChord.parse(List.of("F3"));

        assertEquals(0, chord.modifiers());
        assertEquals(List.of("F3"), chord.heldKeys());
    }

    @Test
    void explicitModifierKeysStillSetBits() {
        KeyChord chord = KeyChord.parse(List.of("right_shift", "super"));

        assertEquals(KeyChord.MOD_SHIFT | KeyChord.MOD_SUPER, chord.modifiers());
        assertEquals(List.of("RIGHT_SHIFT", "LEFT_SUPER"), chord.heldKeys());
    }
}
