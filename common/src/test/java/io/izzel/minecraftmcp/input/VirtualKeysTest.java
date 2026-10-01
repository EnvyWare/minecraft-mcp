package io.izzel.minecraftmcp.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VirtualKeysTest {
    @AfterEach
    void reset() {
        VirtualKeys.releaseAll();
    }

    @Test
    void pressAndReleaseTrackHeldKeys() {
        VirtualKeys.press(340);
        assertTrue(VirtualKeys.isDown(340));

        VirtualKeys.release(340);
        assertFalse(VirtualKeys.isDown(340));
    }

    @Test
    void nestedPressesNeedMatchingReleases() {
        VirtualKeys.press(292);
        VirtualKeys.press(292);
        VirtualKeys.release(292);
        assertTrue(VirtualKeys.isDown(292));

        VirtualKeys.release(292);
        assertFalse(VirtualKeys.isDown(292));
    }

    @Test
    void releasingUnheldKeyIsHarmless() {
        VirtualKeys.release(65);
        assertFalse(VirtualKeys.isDown(65));
    }
}
