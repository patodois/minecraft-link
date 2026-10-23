package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CameraMath;
import dev.patodois.rewritelink.Wire;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Samples the camera of the frame being drawn. Runs on the render thread, allocates a few small
 * arrays and hands the result to the link thread through an {@code AtomicReference}; it never waits.
 */
final class CameraSampler {
    private CameraSampler() {}

    /**
     * @param camera          the camera the level was drawn with: its position (Minecraft renders relative to it) and its rotation
     * @param drawnProjection what Minecraft drew the level with: the perspective times the bobbing and damage tilt
     */
    static void sample(Minecraft mc, Session s, Camera camera, Matrix4f drawnProjection) {
        if (!camera.isInitialized()) return;
        // The view rotation is what GameRenderer.renderLevel puts on the pose stack it hands to the level renderer, Rx(pitch) * Ry(yaw + 180),
        // built here from the camera's angles: Forge's AFTER_LEVEL event does not carry that pose stack, and the camera's own quaternion
        // looks down +Z instead of -Z.
        float[] viewRot = CameraMath.viewRotation(camera.getYRot(), camera.getXRot());
        float[] drawn = new float[16];
        drawnProjection.get(drawn);
        float[][] split = CameraMath.splitProjectionBob(drawn);
        float[] clean = split != null ? split[0] : drawn;

        double k = s.mapping.scale;
        // The planes are the constants Minecraft builds its projection from: GameRenderer.PROJECTION_Z_NEAR and getDepthFar()
        // = 4 * render distance * 16 blocks. If the matrix does not have them (another mod changed the projection) they are
        // read back from the matrix instead, which is only good to about a tenth of a percent in float.
        double near = GameRenderer.PROJECTION_Z_NEAR;
        double far = mc.gameRenderer.getDepthFar();
        if (CameraMath.classify(clean, near, far) != CameraMath.DEPTH_OPENGL) {
            double[] nf = CameraMath.glNearFar(clean);
            if (!Double.isNaN(nf[0])) {
                near = nf[0];
                far = nf[1];
            }
        }

        Vec3 pos = camera.getPosition();
        float[] view = CameraMath.view(new double[] {pos.x, pos.y, pos.z}, viewRot, s.mapping);
        view = CameraMath.applyBob(view, clean, drawn);
        CameraMath.Projection p = CameraMath.normalize(clean, near * k, far * k);

        var target = mc.getMainRenderTarget();
        int w = target.width, h = target.height;
        Wire.DepthRange dr = switch (p.depthRange()) {
            case CameraMath.DEPTH_OPENGL -> Wire.DepthRange.NEG_ONE_TO_ONE;
            case CameraMath.DEPTH_ZERO_TO_ONE -> Wire.DepthRange.ZERO_TO_ONE;
            default -> Wire.DepthRange.REVERSED;
        };
        Wire.Camera cam = new Wire.Camera(view, p.matrix(), CameraMath.fovY(p.matrix()), (float) (near * k), (float) (far * k), w, h, dr);
        s.offerCamera(cam);
        LinkManager.INSTANCE.frames.offerPresentCamera(cam);
        // Minecraft's own depth buffer convention, for the frame shader: measured on the matrix it really uses.
        hostNear = near;
        hostFar = far;
        hostDepthKind = CameraMath.classify(clean, near, far);
        lastDepthRange = p.depthRange();
        lastRewritten = p.rewritten();
        lastNear = near * k;
        lastFar = far * k;
    }

    /** For the status line and the frame shader. */
    static volatile int lastDepthRange = -1;
    static volatile double hostNear = 0.05, hostFar = 512;
    static volatile int hostDepthKind = CameraMath.DEPTH_OPENGL;
    static volatile boolean lastRewritten;
    static volatile double lastNear = 0.05, lastFar = 512;
}
