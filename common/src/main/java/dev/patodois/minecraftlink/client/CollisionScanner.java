package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CollisionBuilder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Host side: keeps a snapshot of the block collision shapes around the player and offers it to the
 * link. Reading the world must happen on the game thread, so a scan is spread over several ticks
 * (a time budget per tick, about one millisecond); building the triangles, mapping them to link
 * space and hashing them happens on a helper thread, and the link thread sends the result.
 *
 * <p>A new scan starts when the player has moved {@code collision_move_blocks} from the last
 * snapshot's centre, or {@code collision_interval_ms} after the last scan started (this is how
 * block changes are noticed: Minecraft has no client-side block-change event to hook, so the area is
 * re-read and an unchanged result is not sent again, except as a refresh every five seconds).
 */
final class CollisionScanner implements CollisionBuilder.Shapes {
    private static final long BUDGET_NS = 1_000_000;
    private static final long REFRESH_MS = 5000;

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
    private ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final IdentityHashMap<VoxelShape, double[][]> cache = new IdentityHashMap<>();
    private volatile boolean busy;

    void reset() {
        building = null;
        haveLast = false;
        lastHash = 0;
        lastSentMs = 0;
        startedMs = 0;
        cache.clear();
    }

    /**
     * Lets go of the world: the scanner must not keep the {@link ClientLevel} (chunks, entities, block entities) alive
     * after the player left it or the link stopped. Game thread; also drops the shape cache and any scan in progress.
     */
    void release() {
        reset();
        level = null;
    }

    void tick(Minecraft mc, Session s) {
        if (!s.isConnected() || mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
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
        level = mc.level;
        long t0 = System.nanoTime();
        while (layer < building.layers() && System.nanoTime() - t0 < BUDGET_NS) {
            building.scanLayer(this, layer++);
        }
        if (layer < building.layers()) return;
        CollisionBuilder done = building;
        building = null;
        level = null; // only needed while blocks are read; do not hold the world between scans
        busy = true;
        // The geometry is centred on the block the player stood in when the scan started (bx, by, bz), so that is the
        // snapshot's centre too, not the position the player has walked to while the scan ran.
        double cx = lastX, cy = lastY, cz = lastZ;
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
        pos.set(x, y, z);
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return null;
        VoxelShape shape = st.getCollisionShape(level, pos);
        if (shape.isEmpty()) return null;
        double[][] boxes = cache.get(shape);
        if (boxes == null) {
            List<AABB> list = shape.toAabbs();
            boxes = new double[list.size()][];
            for (int i = 0; i < boxes.length; i++) {
                AABB b = list.get(i);
                boxes[i] = new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
            }
            if (cache.size() < 4096) cache.put(shape, boxes);
        }
        return boxes;
    }
}
