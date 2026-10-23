package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.patodois.rewritelink.Quat;
import java.util.Random;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class MappingTest {
    private static final double EPS = 1e-5;

    private static void assertVec(double[] expected, double[] actual, double eps) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i], eps, "component " + i);
    }

    private static double[] rotated(float[] q, double x, double y, double z) {
        float[] r = Quat.rotate(q, new float[] {(float) x, (float) y, (float) z});
        return new double[] {r[0], r[1], r[2]};
    }

    @Test
    void yawZeroFacesPositiveZ() {
        // Minecraft: yaw 0 faces +Z (south). The link entity front is +Z. Identity rotation.
        assertVec(new double[] {0, 0, 1}, Mapping.lookDirection(0, 0), EPS);
        float[] q = Mapping.IDENTITY.toLinkRotation(0, 0);
        assertArrayEquals(new float[] {0, 0, 0, 1}, q, 1e-6f);
        assertVec(new double[] {0, 0, 1}, rotated(q, 0, 0, 1), EPS);
    }

    @Test
    void yawGrowsClockwiseSeenFromAbove() {
        // 90 -> west (-X), 180 -> north (-Z), -90 -> east (+X): Minecraft's F3 screen says the same.
        assertVec(new double[] {-1, 0, 0}, rotated(Mapping.IDENTITY.toLinkRotation(90, 0), 0, 0, 1), EPS);
        assertVec(new double[] {0, 0, -1}, rotated(Mapping.IDENTITY.toLinkRotation(180, 0), 0, 0, 1), EPS);
        assertVec(new double[] {1, 0, 0}, rotated(Mapping.IDENTITY.toLinkRotation(-90, 0), 0, 0, 1), EPS);
        assertVec(new double[] {-1, 0, 0}, Mapping.lookDirection(90, 0), EPS);
        assertVec(new double[] {1, 0, 0}, Mapping.lookDirection(-90, 0), EPS);
    }

    @Test
    void pitchPositiveLooksDown() {
        assertVec(new double[] {0, -1, 0}, rotated(Mapping.IDENTITY.toLinkRotation(0, 90), 0, 0, 1), EPS);
        assertVec(new double[] {0, 1, 0}, rotated(Mapping.IDENTITY.toLinkRotation(0, -90), 0, 0, 1), EPS);
        // Pitching down 90 tips the entity's up axis onto its old front (+Z at yaw 0).
        assertVec(new double[] {0, 0, 1}, rotated(Mapping.IDENTITY.toLinkRotation(0, 90), 0, 1, 0), EPS);
    }

    @Test
    void rotationMatchesViewDirectionForAnyYawAndPitch() {
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            double yaw = rnd.nextDouble() * 720 - 360, pitch = rnd.nextDouble() * 180 - 90;
            float[] q = Mapping.IDENTITY.toLinkRotation(yaw, pitch);
            assertEquals(1.0, Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), 1e-6);
            assertVec(Mapping.lookDirection(yaw, pitch), rotated(q, 0, 0, 1), 2e-6);
            assertVec(Mapping.upVector(yaw, pitch), rotated(q, 0, 1, 0), 2e-6);
        }
    }

    @Test
    void rotationRoundTripsToYawAndPitch() {
        Random rnd = new Random(11);
        for (int i = 0; i < 500; i++) {
            double yaw = rnd.nextDouble() * 358 - 179, pitch = rnd.nextDouble() * 170 - 85;
            double[] back = Mapping.IDENTITY.fromLinkRotation(Mapping.IDENTITY.toLinkRotation(yaw, pitch));
            assertEquals(yaw, back[0], 1e-3, "yaw");
            assertEquals(pitch, back[1], 1e-3, "pitch");
        }
    }

    @Test
    void agreesWithMinecraftsOwnCameraQuaternion() {
        // Camera.setRotation (1.20.1): rotation.rotationYXZ(-yaw, pitch, 0); that quaternion is the camera's orientation looking down +Z.
        Random rnd = new Random(3);
        for (int i = 0; i < 200; i++) {
            double yaw = rnd.nextDouble() * 360 - 180, pitch = rnd.nextDouble() * 160 - 80;
            Quaternionf cam = new Quaternionf().rotationYXZ((float) -Math.toRadians(yaw), (float) Math.toRadians(pitch), 0);
            Vector3f fwd = new Vector3f(0, 0, 1).rotate(cam);
            assertVec(Mapping.lookDirection(yaw, pitch), new double[] {fwd.x, fwd.y, fwd.z}, 1e-4); // the quaternion is built in float
        }
    }

    @Test
    void positionsAreTheSameAndVelocityIsPerSecond() {
        assertVec(new double[] {12.5, 64, -3.25}, Mapping.IDENTITY.toLinkPos(12.5, 64, -3.25), 0);
        // 0.2 blocks per tick = 4 m/s.
        assertVec(new double[] {4, -1.6, 0}, Mapping.IDENTITY.toLinkVelocityPerTick(0.2, -0.08, 0), 1e-9);
        // 90 degrees per second of clockwise yaw is -pi/2 rad/s about +Y.
        assertEquals(-Math.PI / 2, Mapping.yawRateToAngularY(90.0 / 20), 1e-9);
    }

    @Test
    void flipsScaleAndOffsetInvert() {
        Mapping m = new Mapping(true, true, 2.0, new double[] {100, 5, -7});
        double[] p = m.toLinkPos(1, 2, 3);
        assertVec(new double[] {100 - 2, 5 + 4, -7 - 6}, p, 1e-12);
        assertVec(new double[] {1, 2, 3}, m.fromLinkPos(p[0], p[1], p[2]), 1e-12);
        // two flips = a half turn about Y: yaw 0 now faces -Z
        assertVec(new double[] {0, 0, -1}, rotated(m.toLinkRotation(0, 0), 0, 0, 1), EPS);
        double[] back = m.fromLinkRotation(m.toLinkRotation(40, 10));
        assertEquals(40, back[0], 1e-3);
        assertEquals(10, back[1], 1e-3);
    }

    @Test
    void oneFlipMirrorsTheHeading() {
        Mapping m = new Mapping(false, true, 1.0, new double[] {0, 0, 0});
        // facing +Z becomes facing -Z; facing east (+X) stays east
        assertVec(new double[] {0, 0, -1}, rotated(m.toLinkRotation(0, 0), 0, 0, 1), EPS);
        assertVec(new double[] {1, 0, 0}, rotated(m.toLinkRotation(-90, 0), 0, 0, 1), EPS);
    }
}
