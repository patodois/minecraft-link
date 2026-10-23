package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class CameraMathTest {
    private static final float NEAR = 0.05f, FAR = 512f;

    private static float[] arr(Matrix4f m) {
        float[] a = new float[16];
        m.get(a);
        return a;
    }

    /** Minecraft's Camera.setRotation + getViewRotationMatrix, for a yaw and pitch in degrees. */
    private static float[] mcViewRotation(double yaw, double pitch) {
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) (Math.PI - Math.toRadians(yaw)), (float) -Math.toRadians(pitch), 0);
        return arr(new Matrix4f().rotation(rotation.conjugate(new Quaternionf())));
    }

    @Test
    void viewPutsTheCameraAtTheOriginLookingDownMinusZ() {
        double[] pos = {100.5, 70.25, -30.0};
        float[] view = CameraMath.view(pos, mcViewRotation(0, 0), Mapping.IDENTITY);
        double[] o = CameraMath.transform(view, pos[0], pos[1], pos[2], 1);
        assertEquals(0, o[0], 1e-4);
        assertEquals(0, o[1], 1e-4);
        assertEquals(0, o[2], 1e-4);
        // yaw 0 faces +Z in Minecraft, so a point 5 m south of the camera is 5 m in front of it: view z = -5.
        double[] ahead = CameraMath.transform(view, pos[0], pos[1], pos[2] + 5, 1);
        assertEquals(-5, ahead[2], 1e-4);
        assertEquals(0, ahead[0], 1e-4);
        assertEquals(0, ahead[1], 1e-4);
    }

    @Test
    void viewFollowsYawAndPitchSigns() {
        double[] pos = {0, 0, 0};
        // yaw 90 faces west (-X): a point at -X is straight ahead.
        float[] v90 = CameraMath.view(pos, mcViewRotation(90, 0), Mapping.IDENTITY);
        assertEquals(-3, CameraMath.transform(v90, -3, 0, 0, 1)[2], 1e-4);
        // yaw -90 faces east (+X).
        float[] vm90 = CameraMath.view(pos, mcViewRotation(-90, 0), Mapping.IDENTITY);
        assertEquals(-3, CameraMath.transform(vm90, 3, 0, 0, 1)[2], 1e-4);
        // pitch 90 looks down: a point below is ahead, and a point above is behind.
        float[] vd = CameraMath.view(pos, mcViewRotation(0, 90), Mapping.IDENTITY);
        assertEquals(-2, CameraMath.transform(vd, 0, -2, 0, 1)[2], 1e-4);
        assertEquals(+2, CameraMath.transform(vd, 0, 2, 0, 1)[2], 1e-4);
        // yaw 0: +X (east) is to the camera's LEFT (camera +X is right), so its view x is negative.
        float[] v0 = CameraMath.view(pos, mcViewRotation(0, 0), Mapping.IDENTITY);
        assertTrue(CameraMath.transform(v0, 1, 0, 3, 1)[0] < 0, "east is left when facing south");
        // and up stays up
        assertTrue(CameraMath.transform(v0, 0, 1, 3, 1)[1] > 0);
    }

    @Test
    void viewRespectsMappingFlipScaleAndOffset() {
        Mapping m = new Mapping(false, true, 2.0, new double[] {10, 0, 0});
        double[] pos = {1, 2, 3};
        float[] view = CameraMath.view(pos, mcViewRotation(0, 0), m);
        double[] camLink = m.toLinkPos(pos[0], pos[1], pos[2]);
        double[] o = CameraMath.transform(view, camLink[0], camLink[1], camLink[2], 1);
        assertEquals(0, o[0], 1e-4);
        assertEquals(0, o[1], 1e-4);
        assertEquals(0, o[2], 1e-4);
    }

    @Test
    void bobIsTheDifferenceBetweenDrawnAndCleanProjection() {
        Matrix4f clean = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, FAR, NEAR, true);
        Matrix4f bob = new Matrix4f().translation(0.02f, -0.03f, 0).rotateZ(0.01f);
        Matrix4f drawn = new Matrix4f(clean).mul(bob);
        float[] view = CameraMath.view(new double[] {0, 0, 0}, mcViewRotation(20, 5), Mapping.IDENTITY);
        float[] withBob = CameraMath.applyBob(view, arr(clean), arr(drawn));
        // clean * (bob * view) must equal drawn * view
        float[] lhs = CameraMath.mul(arr(clean), withBob);
        float[] rhs = CameraMath.mul(arr(drawn), view);
        for (int i = 0; i < 16; i++) assertEquals(rhs[i], lhs[i], 1e-4, "m[" + i + "]");
        // no bob information: the view is unchanged
        assertEquals(view[12], CameraMath.applyBob(view, null, arr(drawn))[12]);
    }

    @Test
    void classifiesMinecraftsReversedProjectionOnBothBackends() {
        float[] reversedZeroToOne = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, FAR, NEAR, true));
        assertEquals(CameraMath.DEPTH_REVERSED, CameraMath.classify(reversedZeroToOne, NEAR, FAR));
        // OpenGL 4.1 (macOS) has no clip control: the same swapped call with zZeroToOne = false.
        float[] reversedMinusOne = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, FAR, NEAR, false));
        assertEquals(CameraMath.DEPTH_REVERSED_MINUS_ONE, CameraMath.classify(reversedMinusOne, NEAR, FAR));
        float[] gl = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, NEAR, FAR, false));
        assertEquals(CameraMath.DEPTH_OPENGL, CameraMath.classify(gl, NEAR, FAR));
        float[] zo = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, NEAR, FAR, true));
        assertEquals(CameraMath.DEPTH_ZERO_TO_ONE, CameraMath.classify(zo, NEAR, FAR));
    }

    @Test
    void normalizeKeepsContractMatricesAndRewritesTheOthers() {
        float[] rev = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, FAR, NEAR, true));
        CameraMath.Projection kept = CameraMath.normalize(rev, NEAR, FAR);
        assertEquals(CameraMath.DEPTH_REVERSED, kept.depthRange());
        assertFalse(kept.rewritten());

        float[] odd = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, FAR, NEAR, false));
        CameraMath.Projection fixed = CameraMath.normalize(odd, NEAR, FAR);
        assertEquals(CameraMath.DEPTH_OPENGL, fixed.depthRange());
        assertTrue(fixed.rewritten());
        // same frustum shape (x and y scales), contract depth: near -> -1, far -> +1
        assertEquals(odd[0], fixed.matrix()[0], 1e-6);
        assertEquals(odd[5], fixed.matrix()[5], 1e-6);
        assertEquals(-1, CameraMath.clipDepthAt(fixed.matrix(), NEAR), 1e-3);
        assertEquals(1, CameraMath.clipDepthAt(fixed.matrix(), FAR), 1e-3);
        assertEquals(Math.toRadians(70), CameraMath.fovY(fixed.matrix()), 1e-5);
        // and it matches what JOML builds for plain OpenGL
        float[] gl = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, NEAR, FAR, false));
        for (int i = 0; i < 16; i++) assertEquals(gl[i], fixed.matrix()[i], 1e-4, "m[" + i + "]");
    }

    @Test
    void linearDepthInvertsEveryConvention() {
        float[][] mats = {
            arr(new Matrix4f().perspective(1.2f, 1.5f, NEAR, FAR, false)),
            arr(new Matrix4f().perspective(1.2f, 1.5f, NEAR, FAR, true)),
            arr(new Matrix4f().perspective(1.2f, 1.5f, FAR, NEAR, true)),
            arr(new Matrix4f().perspective(1.2f, 1.5f, FAR, NEAR, false)),
        };
        int[] kinds = {CameraMath.DEPTH_OPENGL, CameraMath.DEPTH_ZERO_TO_ONE, CameraMath.DEPTH_REVERSED, CameraMath.DEPTH_REVERSED_MINUS_ONE};
        for (int k = 0; k < mats.length; k++) {
            for (double dist : new double[] {0.1, 1, 7.5, 60, 300}) {
                double ndc = CameraMath.clipDepthAt(mats[k], dist);
                // window depth: (ndc + 1) / 2 for -1..1 ranges, ndc itself for 0..1
                boolean minusOne = kinds[k] == CameraMath.DEPTH_OPENGL || kinds[k] == CameraMath.DEPTH_REVERSED_MINUS_ONE;
                double d = minusOne ? (ndc + 1) / 2 : ndc;
                assertEquals(dist, CameraMath.linearDepth(d, NEAR, FAR, kinds[k]), dist * 2e-3, "kind " + kinds[k] + " at " + dist);
            }
        }
    }

    @Test
    void nearAndFarAreRecoveredFromAGluPerspectiveMatrix() {
        // gluPerspective(70, 16/9, 0.05, 181.02): what Minecraft 1.12.2 loads for a render distance of 8 chunks (128 * sqrt 2)
        float[] gl = arr(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 181.02f, false));
        double[] nf = CameraMath.glNearFar(gl);
        assertEquals(0.05, nf[0], 1e-4);
        assertEquals(181.02, nf[1], 0.2); // the matrix is float32: far is recovered to about 1e-3 relative
        assertEquals(CameraMath.DEPTH_OPENGL, CameraMath.classify(gl, nf[0], nf[1]));
        // an orthographic matrix (a GUI) is not a perspective one
        float[] ortho = arr(new Matrix4f().ortho(0, 854, 480, 0, 1000, 3000, false));
        assertTrue(Double.isNaN(CameraMath.glNearFar(ortho)[0]));
    }

    @Test
    void cameraPositionIsWhereTheViewMapsToTheOrigin() {
        double[] pos = {100.5, 70.25, -30.0};
        float[] view = CameraMath.view(pos, mcViewRotation(37, 12), Mapping.IDENTITY);
        double[] c = CameraMath.cameraPosition(view);
        assertEquals(pos[0], c[0], 1e-3);
        assertEquals(pos[1], c[1], 1e-3);
        assertEquals(pos[2], c[2], 1e-3);
    }
}
