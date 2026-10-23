package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.patodois.rewritelink.Quat;
import dev.patodois.rewritelink.Wire;
import org.junit.jupiter.api.Test;

class PoseTrackTest {
    private static Wire.Pose pose(long timeUs, float x, float vx, int flags) {
        return new Wire.Pose(1, flags, timeUs, new float[] {x, 0, 0}, new float[] {0, 0, 0, 1}, new float[] {1, 1, 1},
                new float[] {vx, 0, 0}, new float[3]);
    }

    @Test
    void interpolatesBetweenTheTwoSamplesAroundTheTime() {
        PoseTrack t = new PoseTrack();
        t.add(pose(1_000_000, 0, 10, 0));
        t.add(pose(1_100_000, 1, 10, 0));
        assertEquals(0.5, t.at(1_050_000).translation()[0], 1e-6);
        assertEquals(0.0, t.at(900_000).translation()[0], 1e-6, "before the first sample");
    }

    @Test
    void extrapolatesWithVelocityButNotForever() {
        PoseTrack t = new PoseTrack();
        t.add(pose(1_000_000, 5, 10, 0));
        assertEquals(5 + 10 * 0.1, t.at(1_100_000).translation()[0], 1e-6);
        assertEquals(5 + 10 * 0.25, t.at(9_000_000).translation()[0], 1e-6, "capped at 250 ms");
    }

    @Test
    void teleportIsNotInterpolated() {
        PoseTrack t = new PoseTrack();
        t.add(pose(1_000_000, 0, 0, 0));
        t.add(pose(1_100_000, 100, 0, Wire.POSE_TELEPORT));
        assertEquals(0, t.at(1_099_000).translation()[0], 1e-6);
        assertEquals(100, t.at(1_100_000).translation()[0], 1e-6);
    }

    @Test
    void outOfOrderSamplesAreSorted() {
        PoseTrack t = new PoseTrack();
        t.add(pose(1_100_000, 1, 0, 0));
        t.add(pose(1_000_000, 0, 0, 0));
        assertEquals(0.25, t.at(1_025_000).translation()[0], 1e-6);
        assertEquals(1_100_000, t.newest().timeUs());
    }

    @Test
    void rotationsSlerp() {
        PoseTrack t = new PoseTrack();
        Wire.Pose a = pose(0, 0, 0, 0);
        float[] qb = Quat.axisAngle(new float[] {0, 1, 0}, (float) Math.PI / 2);
        Wire.Pose b = new Wire.Pose(1, 0, 1_000_000, new float[3], qb, new float[] {1, 1, 1}, new float[3], new float[3]);
        t.add(a);
        t.add(b);
        float[] mid = t.at(500_000).rotation();
        float[] f = Quat.rotate(mid, new float[] {0, 0, 1});
        assertEquals(Math.sin(Math.PI / 4), f[0], 1e-5);
        assertEquals(Math.cos(Math.PI / 4), f[2], 1e-5);
    }

    @Test
    void emptyTrackHasNoSample() {
        assertNull(new PoseTrack().at(1));
    }
}
