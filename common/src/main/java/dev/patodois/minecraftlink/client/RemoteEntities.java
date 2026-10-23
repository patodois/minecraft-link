package dev.patodois.minecraftlink.client;

import com.mojang.math.Transformation;
import dev.patodois.minecraftlink.PoseTrack;
import dev.patodois.minecraftlink.mixin.BlockDisplayAccess;
import dev.patodois.minecraftlink.mixin.DisplayAccess;
import dev.patodois.rewritelink.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Host side: shows each guest entity as a client-side {@code block_display} entity (a placeholder:
 * Minecraft has no model for what the guest announced). Display entities exist only in this client's
 * level; nothing is sent to a server and nothing is saved.
 *
 * <p>Runs on the render thread at the start of every frame ({@link LinkManager#beforeExtract}) so
 * the entity is placed from the pose track at {@code now - interp_delay_ms}, interpolated between
 * the timestamped poses (or extrapolated with the pose velocity, capped), not snapped to the game's
 * 20 Hz tick. The old position is set equal to the new one, so Minecraft's own partial-tick
 * interpolation does not add a second smoothing step.
 *
 * <p>The block is a magenta glazed terracotta facing the entity's front (+Z in link space), 1 m
 * wide before the pose's scale is applied, centred on the pose position. Only the heading (yaw and
 * pitch of the front) is shown, not roll.
 */
final class RemoteEntities {
    private final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger();

    void update(Minecraft mc, Session s) {
        ClientLevel level = mc.level;
        for (Session.Remote r; (r = s.discarded.poll()) != null; ) remove(r);
        long atUs = Wire.nowUs() - s.settings.interpDelayMs * 1000L;
        for (Session.Remote r : s.remotes.values()) {
            PoseTrack.Sample smp = r.track.at(atUs);
            if (smp == null) continue;
            if (s.framesFresh()) {
                // the guest's own rendering is on screen: no placeholder
                if (r.display != null) remove(r);
                continue;
            }
            Display.BlockDisplay d = (Display.BlockDisplay) r.display;
            if (d == null || d.level() != level || d.isRemoved()) {
                d = create(level, r);
                r.display = d;
            }
            double[] mc3 = s.mapping.fromLinkPos(smp.translation()[0], smp.translation()[1], smp.translation()[2]);
            double[] yp = s.mapping.fromLinkRotation(smp.rotation());
            d.setPos(mc3[0], mc3[1], mc3[2]);
            d.setYRot((float) yp[0]);
            d.setXRot((float) yp[1]);
            d.setOldPosAndRot();
            float[] sc = smp.scale();
            if (sc[0] != r.lastScaleX || sc[1] != r.lastScaleY || sc[2] != r.lastScaleZ) {
                r.lastScaleX = sc[0];
                r.lastScaleY = sc[1];
                r.lastScaleZ = sc[2];
                float k = (float) (1.0 / s.mapping.scale);
                Vector3f scale = new Vector3f(sc[0] * k, sc[1] * k, sc[2] * k);
                ((DisplayAccess) d).minecraftlink$setTransformation(new Transformation(new Vector3f(-scale.x / 2, -scale.y / 2, -scale.z / 2), new Quaternionf(), scale,
                        new Quaternionf()));
            }
        }
    }

    private Display.BlockDisplay create(ClientLevel level, Session.Remote r) {
        Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
        ((BlockDisplayAccess) d).minecraftlink$setBlockState(Blocks.GLAZED_TERRACOTTA.pick(DyeColor.MAGENTA).defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        ((DisplayAccess) d).minecraftlink$setViewRange(8.0f);
        d.noPhysics = true;
        // Server entity ids are positive; client-only entities get negative ones so they can never clash.
        d.setId(-1_000_000 - nextId.incrementAndGet());
        if (r.name != null) {
            d.setCustomName(Component.literal(r.name));
            d.setCustomNameVisible(true);
        }
        r.lastScaleX = Float.NaN;
        level.addEntity(d);
        LinkManager.LOG.info("showing guest entity {} ({} {}) as a block display", r.id, r.provider, r.asset);
        return d;
    }

    private void remove(Session.Remote r) {
        if (r.display instanceof Entity e && !e.isRemoved()) {
            e.setRemoved(Entity.RemovalReason.DISCARDED);
            if (e.level() instanceof ClientLevel cl) cl.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
        }
        r.display = null;
    }

    void discardAll(Session s) {
        // Called from the game thread when the session ends; the link thread may still add to `discarded`.
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            for (Session.Remote r : s.remotes.values()) remove(r);
            for (Session.Remote r; (r = s.discarded.poll()) != null; ) remove(r);
        });
    }
}
