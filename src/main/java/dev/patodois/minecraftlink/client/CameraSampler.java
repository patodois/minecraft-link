package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CameraMath;
import dev.patodois.rewritelink.Wire;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Samples the camera of the frame being drawn, at {@code RenderWorldLastEvent}, i.e. after the game has set up
 * its projection and its camera transform for the world pass and drawn the world, and before it draws the hand.
 * Runs on the render thread, allocates a few small arrays and hands the result to the link thread through an
 * {@code AtomicReference}; it never waits.
 *
 * <p>What Minecraft 1.12.2 does (read from {@code EntityRenderer.setupCameraTransform}, {@code orientCamera} and
 * {@code renderWorldPass}, nothing assumed):
 * <ul>
 *   <li>The projection is {@code gluPerspective(fov, aspect, 0.05, farPlaneDistance * sqrt 2)}: standard OpenGL, clip
 *       depth -1..1, not reversed. It is read back from {@code GL_PROJECTION_MATRIX}, so whatever the game or another
 *       mod made of it is what is sent. Near and far are recovered from the matrix.</li>
 *   <li>The modelview matrix is the rotation of the camera (plus hurt tilt, view bobbing and the eye height), without any
 *       translation: the world is drawn relative to the render view entity's interpolated position
 *       ({@code lastTickPos + (pos - lastTickPos) * partialTicks}). The full world-to-view matrix is therefore
 *       {@code modelview * translate(-thatPosition)}, which is what {@link CameraMath#view} builds.</li>
 * </ul>
 */
final class CameraSampler {
    private CameraSampler() {}

    private static final FloatBuffer PROJ = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);

    static void sample(Minecraft mc, Session s, float partialTicks) {
        Entity e = mc.getRenderViewEntity();
        if (e == null) return;
        PROJ.clear();
        MODELVIEW.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJ);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        float[] proj = new float[16];
        float[] mv = new float[16];
        PROJ.get(proj);
        MODELVIEW.get(mv);

        double k = s.mapping.scale;
        double[] nf = CameraMath.glNearFar(proj);
        double near = nf[0], far = nf[1];
        if (Double.isNaN(near)) return; // not a perspective projection

        double ox = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTicks;
        double oy = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTicks;
        double oz = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTicks;

        float[] view = CameraMath.view(new double[] {ox, oy, oz}, mv, s.mapping);
        CameraMath.Projection p = CameraMath.normalize(proj, near, far, k);

        int w, h;
        Framebuffer fb = mc.getFramebuffer();
        if (OpenGlHelper.isFramebufferEnabled() && fb != null) {
            w = fb.framebufferWidth;
            h = fb.framebufferHeight;
        } else {
            w = mc.displayWidth;
            h = mc.displayHeight;
        }
        Wire.DepthRange dr;
        switch (p.depthRange()) {
            case CameraMath.DEPTH_OPENGL:
                dr = Wire.DepthRange.NEG_ONE_TO_ONE;
                break;
            case CameraMath.DEPTH_ZERO_TO_ONE:
                dr = Wire.DepthRange.ZERO_TO_ONE;
                break;
            default:
                dr = Wire.DepthRange.REVERSED;
                break;
        }
        Wire.Camera cam = new Wire.Camera(view, p.matrix(), CameraMath.fovY(p.matrix()), (float) (near * k), (float) (far * k), w, h, dr);
        lastCameraPosition = CameraMath.cameraPosition(view);
        s.offerCamera(cam);
        LinkManager.INSTANCE.frames.offerPresentCamera(cam);
        // The game's own depth buffer convention, for the frame shader: measured on the matrix it really uses.
        hostNear = near;
        hostFar = far;
        hostDepthKind = CameraMath.classify(proj, near, far);
        lastDepthRange = p.depthRange();
        lastRewritten = p.rewritten();
        lastNear = near * k;
        lastFar = far * k;
    }

    /** Where the last camera sent is, in link space (for the scripted tests). */
    static volatile double[] lastCameraPosition;

    /** For the status line and the frame shader. */
    static volatile int lastDepthRange = -1;
    static volatile double hostNear = 0.05, hostFar = 512;
    static volatile int hostDepthKind = CameraMath.DEPTH_OPENGL;
    static volatile boolean lastRewritten;
    static volatile double lastNear = 0.05, lastFar = 512;
}
