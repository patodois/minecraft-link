package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.Mapping;
import dev.patodois.minecraftlink.PoseTrack;
import dev.patodois.rewritelink.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Host side: shows each guest entity as a placeholder cube (Minecraft has no model for what the guest
 * announced). The cube is drawn straight into the world pass ({@code RenderWorldLastEvent}), depth-tested
 * against the world, so it is hidden behind blocks and exists only in this client: no entity is added to the level,
 * nothing is sent to a server and nothing is saved.
 *
 * <p>It runs once per rendered frame, so the cube is placed from the pose track at {@code now - interp_delay_ms},
 * interpolated between the timestamped poses (or extrapolated with the pose velocity, capped), not snapped to
 * the game's 20 Hz tick.
 *
 * <p>The cube is magenta, 1 m wide before the pose's scale is applied, centred on the pose position, and its
 * front face (the entity's +Z in link space, the heading) is yellow. Only the heading (yaw and pitch of the front)
 * is shown, not roll. While a guest's frames are on screen the placeholder is hidden.
 */
final class RemoteEntities {
    private boolean announced;

    /** Called in the world pass: the camera transform is loaded and the world is in the colour and depth buffers. */
    void draw(Minecraft mc, Session s, float partialTicks) {
        s.discarded.clear(); // nothing to remove: the cube only exists while it is drawn
        if (LinkManager.INSTANCE.frames.visible(s) || s.remotes.isEmpty()) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        long atUs = Wire.nowUs() - s.settings.interpDelayMs * 1000L;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glDisable(GL11.GL_TEXTURE_2D); // the lightmap unit
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            for (Session.Remote r : s.remotes.values()) {
                PoseTrack.Sample smp = r.track.at(atUs);
                if (smp == null) continue;
                double[] p = s.mapping.fromLinkPos(smp.translation()[0], smp.translation()[1], smp.translation()[2]);
                double[] yp = s.mapping.fromLinkRotation(smp.rotation());
                double[] f = Mapping.lookDirection(yp[0], yp[1]);
                double[] u = Mapping.upVector(yp[0], yp[1]);
                double[] x = Mapping.norm(Mapping.cross(u, f));
                double[] y = Mapping.cross(f, x);
                float k = (float) (1.0 / s.mapping.scale);
                float[] sc = smp.scale();
                // column-major: the cube's local x, y, z axes in world space, then the translation relative to the camera
                java.nio.DoubleBuffer m = org.lwjgl.BufferUtils.createDoubleBuffer(16);
                m.put(new double[] {x[0], x[1], x[2], 0, y[0], y[1], y[2], 0, f[0], f[1], f[2], 0, p[0] - cx, p[1] - cy, p[2] - cz, 1});
                m.flip();
                GL11.glPushMatrix();
                GL11.glMultMatrix(m);
                GL11.glScalef(sc[0] * k, sc[1] * k, sc[2] * k);
                cube();
                GL11.glPopMatrix();
                if (!announced) {
                    announced = true;
                    LinkManager.LOG.info("showing guest entity {} ({} {}) as a placeholder cube", r.id, r.provider, r.asset);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** A unit cube centred on the origin, flat shaded; the +Z face (the front) is yellow. */
    private static void cube() {
        GL11.glBegin(GL11.GL_QUADS);
        face(1.0f, 0.2f, 1.0f, 0, 1, 0, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f); // top
        face(0.5f, 0.1f, 0.5f, 0, -1, 0, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f); // bottom
        face(1.0f, 1.0f, 0.1f, 0, 0, 1, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f); // front +Z
        face(0.8f, 0.16f, 0.8f, 0, 0, -1, 0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f); // back
        face(0.7f, 0.14f, 0.7f, 1, 0, 0, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f); // +X
        face(0.7f, 0.14f, 0.7f, -1, 0, 0, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f); // -X
        GL11.glEnd();
    }

    private static void face(float r, float g, float b, float nx, float ny, float nz, float... v) {
        GL11.glColor3f(r, g, b);
        GL11.glNormal3f(nx, ny, nz);
        for (int i = 0; i < 12; i += 3) GL11.glVertex3f(v[i], v[i + 1], v[i + 2]);
    }

    void discardAll(Session s) {
        s.discarded.clear();
        announced = false;
    }
}
