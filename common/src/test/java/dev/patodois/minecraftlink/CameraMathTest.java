package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class CameraMathTest {
    private static final float NEAR = 0.05f, FAR = 512f;

    private static float[] arr(Matrix4f m) {
        float[] a = new float[16];
        m.get(a);
        return a;
    }

    /** What GameRenderer.renderLevel does to the pose stack it hands to the level renderer (Minecraft 1.20.1), for a yaw and pitch in degrees. */
    private static float[] mcViewRotation(double yaw, double pitch) {
        PoseStack stack = new PoseStack();
        stack.mulPose(Axis.XP.rotationDegrees((float) pitch));
        stack.mulPose(Axis.YP.rotationDegrees((float) yaw + 180.0F));
        return arr(stack.last().pose());
    }

    @Test
    void viewRotationFromAnglesIsWhatTheGameRendererBuilds() {
        for (double yaw : new double[] {0, 33, 90, 180, -90, -170, 359.5}) {
            for (double pitch : new double[] {0, 12.5, -45, 89, -89}) {
                float[] expected = mcViewRotation(yaw, pitch);
                float[] got = CameraMath.viewRotation(yaw, pitch);
                for (int i = 0; i < 16; i++) assertEquals(expected[i], got[i], 1e-5, "yaw " + yaw + " pitch " + pitch + " m[" + i + "]");
            }
        }
        // the camera's own quaternion (Camera.setRotation: rotationYXZ(-yaw, pitch, 0)) looks down +Z; the view is that turned around and inverted
        org.joml.Quaternionf q = new org.joml.Quaternionf().rotationYXZ((float) Math.toRadians(-40), (float) Math.toRadians(25), 0);
        float[] fromQuaternion = arr(new Matrix4f().rotationY((float) Math.PI).rotate(q.conjugate(new org.joml.Quaternionf())));
        float[] fromAngles = CameraMath.viewRotation(40, 25);
        for (int i = 0; i < 16; i++) assertEquals(fromQuaternion[i], fromAngles[i], 1e-5, "quaternion m[" + i + "]");
    }

    /** The perspective Minecraft 1.20.1 builds (GameRenderer.getProjectionMatrix): JOML, near before far, clip depth -1..1. */
    private static Matrix4f mcPerspective(double fovDegrees, float aspect, float far) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspect, 0.05F, far);
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
    void minecraft1201ProjectionIsPlainOpenGl() {
        float[] p = arr(mcPerspective(70, 16f / 9f, 512f));
        assertEquals(CameraMath.DEPTH_OPENGL, CameraMath.classify(p, 0.05, 512));
        double[] nf = CameraMath.glNearFar(p);
        assertEquals(0.05, nf[0], 1e-5);
        assertEquals(512, nf[1], 1.0); // float matrix: far is only known to about 0.1 %
        // not a plain OpenGL perspective: no planes
        assertTrue(Double.isNaN(CameraMath.glNearFar(arr(new Matrix4f().perspective(1.2f, 1.5f, FAR, NEAR, true)))[0]));
    }

    @Test
    void splitsTheDrawnProjectionIntoPerspectiveAndBob() {
        // bobHurt and bobView: a rotation about z and x, a small translation (they are applied after the projection, like this)
        PoseStack stack = new PoseStack();
        stack.mulPoseMatrix(mcPerspective(75, 1.6f, 640f));
        stack.translate(0.031f, -0.047f, 0f);
        stack.mulPose(Axis.ZP.rotationDegrees(1.7f));
        stack.mulPose(Axis.XP.rotationDegrees(-2.3f));
        float[] drawn = arr(stack.last().pose());
        float[][] pb = CameraMath.splitProjectionBob(drawn);
        assertTrue(pb != null);
        float[] clean = arr(mcPerspective(75, 1.6f, 640f));
        for (int i = 0; i < 16; i++) assertEquals(clean[i], pb[0][i], 1e-4, "projection m[" + i + "]");
        float[] back = CameraMath.mul(pb[0], pb[1]);
        for (int i = 0; i < 16; i++) assertEquals(drawn[i], back[i], 1e-4, "product m[" + i + "]");
        // and the split feeds applyBob: clean * (bob * view) == drawn * view
        float[] view = CameraMath.view(new double[] {3, 4, 5}, mcViewRotation(33, -12), Mapping.IDENTITY);
        float[] lhs = CameraMath.mul(pb[0], CameraMath.applyBob(view, pb[0], drawn));
        float[] rhs = CameraMath.mul(drawn, view);
        for (int i = 0; i < 16; i++) assertEquals(rhs[i], lhs[i], 1e-3, "view m[" + i + "]");
        // without any bobbing the split is the identity bob
        float[][] plain = CameraMath.splitProjectionBob(clean);
        for (int i = 0; i < 16; i++) assertEquals(i % 5 == 0 ? 1 : 0, plain[1][i], 1e-5, "plain bob m[" + i + "]");
        // not a perspective at all
        assertEquals(null, CameraMath.splitProjectionBob(CameraMath.identity()));
        assertEquals(null, CameraMath.splitProjectionBob(null));
    }

    @Test
    void classifiesTheDepthConventionsOfOtherRenderers() {
        // not 1.20.1 (it is plain OpenGL), but the mod measures what it is given: Minecraft 26.x reverses depth.
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
}
