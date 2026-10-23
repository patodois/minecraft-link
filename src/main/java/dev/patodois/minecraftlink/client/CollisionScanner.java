package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CollisionBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Host side: keeps a snapshot of the block collision shapes around the player and offers it to the
 * link. Reading the world must happen on the game thread, so a scan is spread over several ticks
 * (a time budget per tick, about one millisecond); building the triangles, mapping them to link
 * space and hashing them happens on a helper thread, and the link thread sends the result.
 *
 * <p>A new scan starts when the player has moved {@code collision_move_blocks} from the last
 * snapshot's centre, or {@code collision_interval_ms} after the last scan started (this is how
 * block changes are noticed: Minecraft 1.7.10 has no client-side block-change event worth hooking, so the area is
 * re-read and an unchanged result is not sent again, except as a refresh every five seconds).
 *
 * <p>The shapes are what the game itself uses for collision: {@code Block.getCollisionBoundingBoxFromPool} for an opaque
 * block (a plain full cube, or none), and {@code Block.addCollisionBoxesToList} (the list of boxes the player collides
 * with: stairs, slabs, fences, ...) for everything else. The first block of each class that is not a plain cube is
 * logged with its boxes.
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
    /** One mask per tick (1.7.10 boxes come from a per-tick pool): it covers the whole scan area, so every box of every block intersects it. */
    private AxisAlignedBB mask;
    private final List<AxisAlignedBB> boxList = new ArrayList<AxisAlignedBB>();
    private final java.util.Set<Class<?>> logged = new java.util.HashSet<Class<?>>();
    private volatile boolean busy;

    void reset() {
        building = null;
        haveLast = false;
        lastHash = 0;
        lastSentMs = 0;
        startedMs = 0;
    }

    void tick(Minecraft mc, Session s) {
        if (!s.isConnected() || mc.thePlayer == null || mc.theWorld == null) return;
        long now = System.currentTimeMillis();
        level = mc.theWorld;
        // the feet position (in 1.7.10 the client player's posY is the eye position)
        double px = mc.thePlayer.posX, py = mc.thePlayer.boundingBox.minY, pz = mc.thePlayer.posZ;
        int r = s.settings.collisionRadius + 3;
        mask = AxisAlignedBB.getBoundingBox(Math.floor(px) - r, Math.floor(py) - r, Math.floor(pz) - r, Math.floor(px) + r, Math.floor(py) + r, Math.floor(pz) + r);
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public double[][] boxesAt(int x, int y, int z) {
        Block b = level.getBlock(x, y, z);
        if (b.getMaterial() == Material.air) return null;
        if (b.isOpaqueCube()) {
            AxisAlignedBB bb = b.getCollisionBoundingBoxFromPool(level, x, y, z);
            if (bb == null) return null;
            if (bb.minX - x < 1e-6 && bb.minY - y < 1e-6 && bb.minZ - z < 1e-6 && bb.maxX - x > 1 - 1e-6 && bb.maxY - y > 1 - 1e-6 && bb.maxZ - z > 1 - 1e-6) {
                return FULL; // the plain cube: the common case
            }
        }
        boxList.clear();
        b.addCollisionBoxesToList(level, x, y, z, mask, (List) boxList, null);
        if (boxList.isEmpty()) return null; // no collision: grass, flowers, water, torches, ...
        double[][] boxes = new double[boxList.size()][];
        for (int i = 0; i < boxes.length; i++) {
            AxisAlignedBB bb = boxList.get(i);
            boxes[i] = new double[] {bb.minX - x, bb.minY - y, bb.minZ - z, bb.maxX - x, bb.maxY - y, bb.maxZ - z};
        }
        if (logged.size() < 12 && !(boxes.length == 1 && boxes[0][0] == 0 && boxes[0][2] == 0 && boxes[0][3] == 1 && boxes[0][5] == 1 && boxes[0][1] == 0 && boxes[0][4] == 1)
                && logged.add(b.getClass())) {
            LinkManager.LOG.info("collision shape of {} ({}) at {} {} {}: {}", Block.blockRegistry.getNameForObject(b), b.getClass().getSimpleName(), x, y, z,
                    java.util.Arrays.deepToString(boxes));
        }
        return boxes;
    }
}
