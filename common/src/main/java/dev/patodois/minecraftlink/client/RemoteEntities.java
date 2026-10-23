package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.PoseTrack;
import dev.patodois.rewritelink.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Host side: shows each guest entity as a client-side {@code block_display} entity (a placeholder:
 * Minecraft has no model for what the guest announced). Display entities exist only in this client's
 * level; nothing is sent to a server and nothing is saved. Their block state, scale and view range are
 * set the way a command would, through the entity's NBT ({@link Entity#load}); the setters are private in 1.20.1
 * and the mod uses no mixin.
 *
 * <p>Runs on the render thread at the start of every frame's level render ({@link LinkManager#beforeEntities}) so
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
            if (LinkManager.INSTANCE.frames.visible(s)) {
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
            // A guest could send NaN or infinity (NaN != NaN would reload the display every frame): ignore such a scale.
            if (!Float.isFinite(sc[0]) || !Float.isFinite(sc[1]) || !Float.isFinite(sc[2])) continue;
            if (sc[0] != r.lastScaleX || sc[1] != r.lastScaleY || sc[2] != r.lastScaleZ) {
                r.lastScaleX = sc[0];
                r.lastScaleY = sc[1];
                r.lastScaleZ = sc[2];
                float k = (float) (1.0 / s.mapping.scale);
                d.load(displayTag(sc[0] * k, sc[1] * k, sc[2] * k, r.name));
                // load() also reads (absent) position and rotation: put the pose back
                d.setPos(mc3[0], mc3[1], mc3[2]);
                d.setYRot((float) yp[0]);
                d.setXRot((float) yp[1]);
                d.setOldPosAndRot();
            }
        }
    }

    private Display.BlockDisplay create(ClientLevel level, Session.Remote r) {
        Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        d.noPhysics = true;
        // Server entity ids are positive; client-only entities get negative ones so they can never clash.
        d.setId(-1_000_000 - nextId.incrementAndGet());
        if (r.name != null) {
            d.setCustomName(Component.literal(r.name));
            d.setCustomNameVisible(true);
        }
        r.lastScaleX = Float.NaN;
        level.putNonPlayerEntity(d.getId(), d);
        LinkManager.LOG.info("showing guest entity {} ({} {}) as a block display", r.id, r.provider, r.asset);
        return d;
    }

    /**
     * The NBT of the placeholder: a magenta glazed terracotta (its pattern points along the block's front), scaled around
     * its centre, visible from far away. The transformation is {@code translation = -scale / 2} so the block is centred on
     * the entity.
     */
    private static CompoundTag displayTag(float sx, float sy, float sz, String name) {
        CompoundTag t = new CompoundTag();
        if (name != null) {
            // load() resets the name tag along with everything else an entity saves
            t.putString("CustomName", Component.Serializer.toJson(Component.literal(name)));
            t.putBoolean("CustomNameVisible", true);
        }
        t.put("block_state", NbtUtils.writeBlockState(
                Blocks.MAGENTA_GLAZED_TERRACOTTA.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH)));
        t.putFloat("view_range", 8.0f);
        CompoundTag tr = new CompoundTag();
        tr.put("translation", floats(-sx / 2, -sy / 2, -sz / 2));
        tr.put("scale", floats(sx, sy, sz));
        tr.put("left_rotation", floats(0, 0, 0, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        t.put("transformation", tr);
        return t;
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) l.add(FloatTag.valueOf(f));
        return l;
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
