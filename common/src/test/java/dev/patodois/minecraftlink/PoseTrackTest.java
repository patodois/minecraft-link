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
    void aNonFiniteScaleIsIgnored() {
        PoseTrack t = new PoseTrack();
        float[] rot = {0, 0, 0, 1};
        t.add(new Wire.Pose(1, 0, 1_000_000, new float[3], rot, new float[] {2, 2, 2}, new float[3], new float[3]));
        t.add(new Wire.Pose(1, 0, 1_100_000, new float[3], rot, new float[] {Float.NaN, 1, 1}, new float[3], new float[3]));
        t.add(new Wire.Pose(1, 0, 1_200_000, new float[3], rot, new float[] {1, Float.POSITIVE_INFINITY, 1}, new float[3], new float[3]));
        for (long us = 900_000; us < 1_400_000; us += 50_000) {
            for (float v : t.at(us).scale()) assertEquals(2f, v, "scale at " + us + " stays the last finite one");
        }
        // before any finite scale the entity is drawn at scale 1
        PoseTrack u = new PoseTrack();
        u.add(new Wire.Pose(1, 0, 1_000_000, new float[3], rot, new float[] {Float.NaN, Float.NaN, Float.NaN}, new float[3], new float[3]));
        assertEquals(1f, u.at(1_000_000).scale()[0]);
    }

    @Test
    void aPoseWithANonFinitePositionIsDropped() {
        PoseTrack t = new PoseTrack();
        float[] one = {1, 1, 1};
        t.add(new Wire.Pose(1, 0, 1_000_000, new float[] {Float.NaN, 0, 0}, new float[] {0, 0, 0, 1}, one, new float[3], new float[3]));
        t.add(new Wire.Pose(1, 0, 1_000_000, new float[3], new float[] {0, 0, 0, Float.NaN}, one, new float[3], new float[3]));
        assertNull(t.at(1_000_000));
        // an infinite velocity must not poison the extrapolation
        t.add(new Wire.Pose(1, 0, 1_000_000, new float[3], new float[] {0, 0, 0, 1}, one, new float[] {Float.POSITIVE_INFINITY, 0, 0}, new float[3]));
        assertEquals(0.0, t.at(1_100_000).translation()[0], 1e-9);
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
