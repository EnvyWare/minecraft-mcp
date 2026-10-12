package io.izzel.minecraftmcp.film;

import io.izzel.minecraftmcp.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShotSamplerTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        return (Map<String, Object>) Json.parse(text);
    }

    @Test
    void frameCountAndTimingAreFixedByFps() {
        Shot shot = Shot.parse(json("{\"seconds\":2,\"camera\":[[0,70,0],[20,70,0]],\"lookAt\":[10,64,30],\"easing\":\"linear\"}"), 0);
        ShotSampler sampler = new ShotSampler(shot, 30, 0);
        assertEquals(60, sampler.frames());
        assertEquals(0, sampler.progress(0));
        assertEquals(1, sampler.progress(59));
        CameraPose first = sampler.sample(0, null), second = sampler.sample(1, null);
        assertEquals(0, first.x(), 1e-6);
        assertEquals(20.0 / 59, second.x(), 1e-3);
        assertEquals(70, first.y(), 1e-9);
        assertTrue(Float.isNaN(first.fov()));
    }

    @Test
    void linearShotMovesAtConstantSpeed() {
        Shot shot = Shot.parse(json("{\"seconds\":1,\"linear\":true,\"camera\":[[0,70,0],[2,70,0],[30,70,0]]}"), 0);
        ShotSampler sampler = new ShotSampler(shot, 30, 0);
        double previous = sampler.sample(0, null).x();
        for (int n = 1; n < sampler.frames(); n++) {
            double x = sampler.sample(n, null).x();
            assertEquals(30.0 / 29, x - previous, 0.05, "step at frame " + n);
            previous = x;
        }
    }

    @Test
    void forwardLookFacesAlongThePath() {
        Shot shot = Shot.parse(json("{\"seconds\":1,\"camera\":[[0,70,0],[0,70,50]]}"), 0);
        CameraPose pose = new ShotSampler(shot, 30, 0).sample(10, null);
        assertEquals(0, pose.yaw(), 1e-3); // moving south
        assertEquals(0, pose.pitch(), 1e-3);
    }

    @Test
    void smoothingLagsBehindAnInstantTurnAndClampsPitch() {
        Shot shot = Shot.parse(json("{\"seconds\":1,\"camera\":[[0,70,0],[0,70,1]],\"look\":[[0,70,10],[0,70,10],[-10,40,0]],\"pitchMax\":20}"), 0);
        ShotSampler sampler = new ShotSampler(shot, 30, 0);
        CameraPose last = null;
        for (int n = 0; n < sampler.frames(); n++) last = sampler.sample(n, null);
        assertTrue(last.pitch() <= 20.0001f);
        assertTrue(last.yaw() > 0 && last.yaw() < 90, "smoothed yaw should not reach the final 90 instantly: " + last.yaw());
    }

    @Test
    void liftRaisesTheCamera() {
        Shot shot = Shot.parse(json("{\"camera\":[[0,70,0],[10,70,0]]}"), 0);
        assertEquals(76, new ShotSampler(shot, 30, 6).sample(0, null).y(), 1e-9);
    }

    @Test
    void orbitCirclesItsCentreLookingAtIt() {
        Shot shot = Shot.parse(json("{\"preset\":\"orbit\",\"center\":[100,64,100],\"radius\":10,\"height\":5,\"degrees\":180,\"seconds\":1,\"easing\":\"linear\"}"), 0);
        ShotSampler sampler = new ShotSampler(shot, 10, 0);
        CameraPose start = sampler.sample(0, null);
        assertArrayEquals(new double[] {100, 69, 90}, start.position(), 1e-6); // startAngle 0: north of the centre, looking south
        assertEquals(0, start.yaw(), 1e-3);
        assertTrue(start.pitch() > 0);
        CameraPose end = sampler.sample(sampler.frames() - 1, null);
        assertArrayEquals(new double[] {100, 69, 110}, end.position(), 1e-6);
        assertEquals(-180, Math.abs(end.yaw()) * -1, 1e-3);
    }

    @Test
    void orbitAroundATargetFollowsIt() {
        Shot shot = Shot.parse(json("{\"preset\":\"orbit\",\"target\":{\"type\":\"pig\"},\"radius\":4,\"height\":0}"), 0);
        assertTrue(shot.cameraFollowsTarget());
        ShotSampler sampler = new ShotSampler(shot, 30, 0);
        assertArrayEquals(new double[] {10, 65, 6}, sampler.sample(0, new double[] {10, 65, 10}).position(), 1e-6);
        assertThrows(IllegalStateException.class, () -> new ShotSampler(shot, 30, 0).sample(0, null));
    }

    @Test
    void followLooksAheadAlongThePath() {
        Shot shot = Shot.parse(json("{\"preset\":\"follow\",\"path\":[[0,64,0],[0,64,100]],\"height\":4,\"back\":6,\"lookAhead\":10,\"lookHeight\":0}"), 0);
        CameraPose pose = new ShotSampler(shot, 30, 0).sample(0, null);
        assertArrayEquals(new double[] {0, 68, -6}, pose.position(), 1e-3);
        assertEquals(0, pose.yaw(), 1e-3);
        assertEquals(Math.toDegrees(Math.atan2(4, 16)), pose.pitch(), 0.01);
    }

    @Test
    void anglesAndFovKeyframes() {
        Shot shot = Shot.parse(json("{\"camera\":[0,70,0],\"angles\":[[170,0],[-170,10]],\"fov\":[70,50],\"roll\":5,\"seconds\":1,\"easing\":\"linear\"}"), 0);
        ShotSampler sampler = new ShotSampler(shot, 11, 0);
        CameraPose mid = sampler.sample(5, null);
        assertEquals(-180, Math.abs(mid.yaw()) * -1, 1e-3); // turned through 180, not back through 0
        assertEquals(60, mid.fov(), 1e-3);
        assertEquals(5, mid.roll(), 1e-6);
    }

    @Test
    void parsingAcceptsRoadsFilmShotsAndRejectsBadInput() {
        List<Shot> shots = Shot.parseAll(json("{\"shots\":[{\"name\":\"fly\",\"seconds\":8,\"linear\":true,\"pitchMin\":-22,\"pitchMax\":12,\"cam\":[[1,2,3],[4,5,6]],\"look\":[[1,2,9],[4,5,12]],\"clearance\":true}]}"));
        assertEquals(1, shots.size());
        Shot fly = shots.get(0);
        assertEquals("fly", fly.name());
        assertEquals(240, fly.frames(30));
        assertEquals(3, fly.clearance());
        assertEquals(Shot.Look.POINTS, fly.look());
        assertEquals(Shot.Look.POINT, Shot.parse(json("{\"camera\":[[0,0,0]],\"lookAt\":{\"x\":1,\"y\":2,\"z\":3}}"), 0).look());
        assertEquals(Shot.Look.TARGET, Shot.parse(json("{\"camera\":[[0,0,0]],\"target\":{\"id\":5}}"), 0).look());
        assertThrows(IllegalArgumentException.class, () -> Shot.parse(json("{\"seconds\":1}"), 0));
        assertThrows(IllegalArgumentException.class, () -> Shot.parse(json("{\"camera\":[[0,0]]}"), 0));
        assertThrows(IllegalArgumentException.class, () -> Shot.parse(json("{\"preset\":\"orbit\"}"), 0));
        assertThrows(IllegalArgumentException.class, () -> Shot.parse(json("{\"preset\":\"spin\",\"camera\":[[0,0,0]]}"), 0));
        assertThrows(IllegalArgumentException.class, () -> Shot.parse(json("{\"camera\":[[0,0,0]],\"seconds\":0}"), 0));
    }
}
