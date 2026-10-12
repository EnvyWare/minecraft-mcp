package io.izzel.minecraftmcp.film;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CameraMathTest {
    private static final double EPS = 1e-9;

    @Test
    void catmullRomPassesThroughKeyframes() {
        List<double[]> keys = List.of(new double[] {0, 64, 0}, new double[] {10, 70, 0}, new double[] {20, 64, 10}, new double[] {30, 60, 30});
        for (int i = 0; i < keys.size(); i++) {
            assertArrayEquals(keys.get(i), CameraMath.catmullRom(keys, (double) i / (keys.size() - 1)), EPS);
        }
    }

    @Test
    void catmullRomOfCollinearEvenKeysIsLinear() {
        List<double[]> keys = List.of(new double[] {0, 0, 0}, new double[] {6, 0, 0}, new double[] {12, 0, 0});
        assertArrayEquals(new double[] {3, 0, 0}, CameraMath.catmullRom(keys, 0.25), EPS);
        assertArrayEquals(new double[] {12, 0, 0}, CameraMath.catmullRom(keys, 2.0), EPS);
        assertArrayEquals(new double[] {5, 5, 5}, CameraMath.catmullRom(List.of(new double[] {5, 5, 5}), 0.7), EPS);
    }

    @Test
    void anglesFollowMinecraftConventions() {
        double[] origin = {0, 0, 0};
        assertArrayEquals(new double[] {0, -0.0}, CameraMath.anglesOf(origin, new double[] {0, 0, 5}), 1e-6); // south
        assertEquals(90, CameraMath.anglesOf(origin, new double[] {-5, 0, 0})[0], 1e-6); // west
        assertEquals(-90, CameraMath.anglesOf(origin, new double[] {5, 0, 0})[0], 1e-6); // east
        assertEquals(45, CameraMath.anglesOf(origin, new double[] {0, -5, 5})[1], 1e-6); // looking down is positive pitch
    }

    @Test
    void wrapAndUnwrapTakeTheShortWay() {
        assertEquals(-170, CameraMath.wrapDegrees(190), EPS);
        assertEquals(170, CameraMath.wrapDegrees(-190), EPS);
        List<double[]> angles = new ArrayList<>(List.of(new double[] {170, 0}, new double[] {-170, 0}, new double[] {-10, 0}));
        CameraMath.unwrapYaw(angles);
        assertEquals(190, angles.get(1)[0], EPS);
        assertEquals(350, angles.get(2)[0], EPS);
    }

    @Test
    void easingsStartAndEndInPlace() {
        for (Easing easing : Easing.values()) {
            assertEquals(0, easing.apply(0), EPS, easing.id());
            assertEquals(1, easing.apply(1), EPS, easing.id());
        }
        assertEquals(0.5, Easing.EASE_IN_OUT.apply(0.5), EPS);
        assertEquals(0.125, Easing.EASE_IN_OUT.apply(0.25), EPS);
        assertEquals(Easing.EASE_IN_OUT, Easing.parse(null));
        assertEquals(Easing.EASE_IN_OUT_CUBIC, Easing.parse("ease-in-out-cubic"));
        assertEquals(Easing.LINEAR, Easing.parse("linear"));
        assertThrows(IllegalArgumentException.class, () -> Easing.parse("bouncy"));
    }

    @Test
    void arcLengthParameterGivesConstantSpeedOnUnevenKeys() {
        // keyframes 1 block apart, then 9: uniform parameters would spend half the time on the first block
        CameraPath path = new CameraPath(List.of(new double[] {0, 0, 0}, new double[] {1, 0, 0}, new double[] {10, 0, 0}));
        assertEquals(10, path.length(), 0.05);
        assertEquals(5, path.at(path.parameterAt(0.5))[0], 0.05);
        assertEquals(1, path.at(path.parameterAt(0.1))[0], 0.05);
        for (int i = 1; i <= 20; i++) {
            double step = CameraMath.distance(path.at(path.parameterAt((i - 1) / 20.0)), path.at(path.parameterAt(i / 20.0)));
            assertEquals(path.length() / 20, step, 0.01, "step " + i);
        }
        assertEquals(0, path.parameterAt(0), EPS);
        assertEquals(1, path.parameterAt(1), EPS);
    }

    @Test
    void clearanceLiftsTheWholeMoveAboveObstacles() {
        // solid ground up to y=69 at x>=5, air elsewhere
        Clearance.AirQuery air = (x, y, z) -> x < 5 || y >= 70;
        List<double[]> samples = List.of(new double[] {0.5, 65, 0.5}, new double[] {6.5, 66.2, 0.5});
        assertEquals(0, Clearance.rise(samples.get(0), 3, air));
        assertEquals(5, Clearance.rise(samples.get(1), 3, air)); // clear from y-0.5 = 65.7 up
        assertEquals(6, Clearance.rise(new double[] {4.6, 65, 0.5}, 3, air)); // the box reaches the solid column at x=5
        assertEquals(7, Clearance.lift(samples, 3, air));
        assertEquals(0, Clearance.lift(samples.subList(0, 1), 3, air));
        assertEquals(0, Clearance.lift(samples, 0, air));
        assertEquals(Clearance.MAX_RISE, Clearance.rise(samples.get(0), 3, (x, y, z) -> false));
    }
}
