package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.Mapping;
import dev.patodois.rewritelink.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Guest side: once per client tick, offers the local player as the pose of entity
 * {@code guest_entity}. The pose is the player's state at the end of the tick:
 * <ul>
 *   <li>translation: the feet position (the entity origin) in link space;</li>
 *   <li>rotation: the view direction (yaw and pitch) as in {@link Mapping#toLinkRotation};</li>
 *   <li>velocity: the displacement over this tick times 20, in m/s (the real movement, which also
 *       covers being pushed or teleported by the server, not the intended input);</li>
 *   <li>angular velocity: the yaw change over the tick as a rotation about +Y, rad/s;</li>
 *   <li>flag TELEPORT: the first pose after the session started or a displacement of more than
 *       8 blocks in one tick.</li>
 * </ul>
 */
final class GuestPublisher {
    private boolean first = true;
    private long lastSessions = -1;

    void reset() {
        first = true;
        lastSessions = -1;
    }

    void tick(Minecraft mc, Session s) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || !s.isConnected()) return;
        long seen = s.sessionsSeen.get();
        if (seen != lastSessions) {
            lastSessions = seen;
            first = true;
        }
        Mapping m = s.mapping;
        double dx = p.posX - p.prevPosX, dy = p.posY - p.prevPosY, dz = p.posZ - p.prevPosZ;
        boolean teleport = first || dx * dx + dy * dy + dz * dz > 64;
        first = false;
        // In 1.7.10 the client player's posY is the eye position; the feet are the bottom of the bounding box.
        double[] pos = m.toLinkPos(p.posX, p.boundingBox.minY, p.posZ);
        double[] vel = m.toLinkVelocityPerTick(dx, dy, dz);
        float[] rot = m.toLinkRotation(p.rotationYaw, p.rotationPitch);
        double yawRate = MathHelper.wrapAngleTo180_float(p.rotationYaw - p.prevRotationYaw);
        double wy = Mapping.yawRateToAngularY(yawRate);
        s.offerPose(new Wire.Pose(s.settings.guestEntity, teleport ? Wire.POSE_TELEPORT : 0, Wire.nowUs(),
                new float[] {(float) pos[0], (float) pos[1], (float) pos[2]}, rot, new float[] {1, 1, 1},
                new float[] {(float) vel[0], (float) vel[1], (float) vel[2]}, new float[] {0, (float) wy, 0}));
    }
}
