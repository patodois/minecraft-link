package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CameraMath;
import dev.patodois.rewritelink.Wire;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;

/**
 * Samples the camera of the frame being drawn. Runs on the render thread, allocates a few small
 * arrays and hands the result to the link thread through an {@code AtomicReference}; it never waits.
 */
final class CameraSampler {
    private CameraSampler() {}

    static void sample(Minecraft mc, Session s, CameraRenderState cs) {
        if (!cs.initialized) return;
        float[] viewRot = new float[16];
        cs.viewRotationMatrix.get(viewRot);
        float[] clean = new float[16];
        cs.projectionMatrix.get(clean);
        float[] drawn = CameraCapture.drawnProjection;
        CameraCapture.drawnProjection = null;

        double k = s.mapping.scale;
        double near = Camera.PROJECTION_Z_NEAR; // fixed by Camera.update: setupPerspective(0.05F, depthFar, ...)
        double far = cs.depthFar;                // max(4 * render distance * 16, cloud range * 16), blocks

        float[] view = CameraMath.view(new double[] {cs.pos.x, cs.pos.y, cs.pos.z}, viewRot, s.mapping);
        view = CameraMath.applyBob(view, clean, drawn, k);
        CameraMath.Projection p = CameraMath.normalize(clean, near, far, k);

        int w = mc.gameRenderer.mainRenderTarget().width, h = mc.gameRenderer.mainRenderTarget().height;
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
    static volatile int hostDepthKind = CameraMath.DEPTH_REVERSED;
    static volatile boolean lastRewritten;
    static volatile double lastNear = 0.05, lastFar = 512;
}
