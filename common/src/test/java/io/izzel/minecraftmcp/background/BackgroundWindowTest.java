package io.izzel.minecraftmcp.background;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackgroundWindowTest {
    @Test
    void capsFramerateAtBackgroundLimit() {
        assertEquals(30, BackgroundWindow.cappedFramerate(120, 30));
        assertEquals(30, BackgroundWindow.cappedFramerate(260, 30));
    }

    @Test
    void keepsLowerOptionLimit() {
        assertEquals(20, BackgroundWindow.cappedFramerate(20, 30));
    }

    @Test
    void nonPositiveLimitDisablesCap() {
        assertEquals(120, BackgroundWindow.cappedFramerate(120, 0));
        assertEquals(120, BackgroundWindow.cappedFramerate(120, -1));
    }
}
