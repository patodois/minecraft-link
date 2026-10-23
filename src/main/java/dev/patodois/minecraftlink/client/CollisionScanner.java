package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CollisionBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Host side: keeps a snapshot of the block collision shapes around the player and offers it to the
 * link. Reading the world must happen on the game thread, so a scan is spread over several ticks
 * (a time budget per tick, about one millisecond); building the triangles, mapping them to link
 * space and hashing them happens on a helper thread, and the link thread sends the result.
 *
 * <p>A new scan starts when the player has moved {@code collision_move_blocks} from the last
 * snapshot's centre, or {@code collision_interval_ms} after the last scan started (this is how
 * block changes are noticed: Minecraft 1.12.2 has no client-side block-change event worth hooking, so the area is
 * re-read and an unchanged result is not sent again, except as a refresh every five seconds).
 *
 * <p>The shapes are what the game itself uses for collision: {@code IBlockState.getCollisionBoundingBox} for a
 * block that is a plain full cube or has no collision, and {@code addCollisionBoxToList} (the list of boxes the
 * player collides with: stairs, slabs, fences, ...) for everything else.
 */
final class CollisionScanner implements CollisionBuilder.Shapes {
    private static final long BUDGET_NS = 1_000_000;
    private static final long REFRESH_MS = 5000;

    private static final double[][] FULL = {{0, 0, 0, 1, 1, 1}};

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minecraft-link-collision");
        t.setDaemon(true);
        return t;
    });

    private CollisionBuilder building;
    private int layer;
    private int bx, by, bz;
    private long startedMs;
    private double lastX, lastY, lastZ;
    private boolean haveLast;
    private long lastHash;
    private long lastSentMs;
    private World level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final List<AxisAlignedBB> boxList = new ArrayList<AxisAlignedBB>();
    private volatile boolean busy;

    void reset() {
        building = null;
        haveLast = false;
        lastHash = 0;
        lastSentMs = 0;
        startedMs = 0;
    }

    void tick(Minecraft mc, Session s) {
        if (!s.isConnected() || mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        level = mc.world;
        double px = mc.player.posX, py = mc.player.posY, pz = mc.player.posZ;
        if (building == null) {
            if (busy) return;
            boolean moved = !haveLast || Math.sqrt(sq(px - lastX) + sq(py - lastY) + sq(pz - lastZ)) >= s.settings.collisionMoveBlocks;
            boolean due = now - startedMs >= s.settings.collisionIntervalMs;
            if (!(moved && now - startedMs >= 100) && !due) return;
            bx = (int) Math.floor(px);
            by = (int) Math.floor(py);
            bz = (int) Math.floor(pz);
            building = new CollisionBuilder(bx, by, bz, s.settings.collisionRadius);
            layer = 0;
            startedMs = now;
            lastX = px;
            lastY = py;
            lastZ = pz;
            haveLast = true;
        }
        long t0 = System.nanoTime();
        while (layer < building.layers() && System.nanoTime() - t0 < BUDGET_NS) {
            building.scanLayer(this, layer++);
        }
        if (layer < building.layers()) return;
        CollisionBuilder done = building;
        building = null;
        busy = true;
        double cx = px, cy = py, cz = pz;
        worker.execute(() -> {
            try {
                List<float[]> world = done.triangles();
                List<float[]> link = CollisionBuilder.map(world, s.mapping);
                long hash = CollisionBuilder.hash(link);
                long t = System.currentTimeMillis();
                if (hash != lastHash || t - lastSentMs >= REFRESH_MS) {
                    double[] c = s.mapping.toLinkPos(cx, cy, cz);
                    s.offerCollision(new Session.CollisionOut(new float[] {(float) c[0], (float) c[1], (float) c[2]},
                            (float) (s.settings.collisionRadius * s.mapping.scale), link));
                    lastHash = hash;
                    lastSentMs = t;
                }
            } catch (RuntimeException e) {
                LinkManager.LOG.warn("collision snapshot failed", e);
            } finally {
                busy = false;
            }
        });
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    public double[][] boxesAt(int x, int y, int z) {
        pos.setPos(x, y, z);
        IBlockState st = level.getBlockState(pos);
        if (st.getMaterial() == Material.AIR) return null;
        AxisAlignedBB bb = st.getCollisionBoundingBox(level, pos);
        if (bb == Block.NULL_AABB) return null; // no collision: grass, flowers, water, torches, ...
        if (bb == Block.FULL_BLOCK_AABB) return FULL; // the plain cube, by identity: the common case costs nothing more
        boxList.clear();
        // The mask only has to cover the block: every box of the block that intersects it is added (in world coordinates).
        AxisAlignedBB mask = new AxisAlignedBB(x - 1.0, y - 1.0, z - 1.0, x + 2.0, y + 2.0, z + 2.0);
        st.addCollisionBoxToList(level, pos, mask, boxList, null, false);
        if (boxList.isEmpty()) return null;
        double[][] boxes = new double[boxList.size()][];
        for (int i = 0; i < boxes.length; i++) {
            AxisAlignedBB b = boxList.get(i);
            boxes[i] = new double[] {b.minX - x, b.minY - y, b.minZ - z, b.maxX - x, b.maxY - y, b.maxZ - z};
        }
        return boxes;
    }
}
