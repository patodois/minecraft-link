package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import org.junit.jupiter.api.Test;

/**
 * The same checks as {@link CollisionBuilderTest}, but with the bounds of real Minecraft 1.7.10 {@code Block} objects
 * (their {@code getBlockBounds*} accessors, which {@code getCollisionBoundingBoxFromPool} turns into a world box).
 *
 * <p>The game's registries cannot be bootstrapped in a plain unit test on 1.7.10 (Forge Mod Loader insists on its own class
 * loader), so these blocks are plain {@code Block} instances, not the registered stone and slab; stairs need a world to
 * choose their shape anyway. The shapes of the registered blocks are checked in the game: the in-game run logs the boxes the
 * game reports for the first block of every kind that is not a plain cube (see docs/test-logs).
 */
class RealBlockShapesTest {
    private static Block block(double minY, double maxY) {
        Block b = new Block(Material.rock) {};
        b.setBlockBounds(0f, (float) minY, 0f, 1f, (float) maxY, 1f);
        return b;
    }

    private static double[][] boundsOf(Block b) {
        return new double[][] {{b.getBlockBoundsMinX(), b.getBlockBoundsMinY(), b.getBlockBoundsMinZ(), b.getBlockBoundsMaxX(),
                b.getBlockBoundsMaxY(), b.getBlockBoundsMaxZ()}};
    }

    private static List<float[]> single(double[][] shape) {
        CollisionBuilderTest.Map3 w = new CollisionBuilderTest.Map3().put(0, 0, 0, shape);
        return CollisionBuilderTest.build(w);
    }

    @Test
    void aDefaultBlockIsAFullCube() {
        Block b = new Block(Material.rock) {};
        double[][] shape = boundsOf(b);
        assertEquals(1.0, shape[0][3], 1e-9);
        assertEquals(1.0, shape[0][4], 1e-9);
        List<float[]> tris = single(shape);
        assertEquals(12, tris.size());
        assertEquals(6.0, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }

    @Test
    void aBottomSlabBoundsAreHalfAHigh() {
        double[][] shape = boundsOf(block(0, 0.5));
        assertEquals(0.5, shape[0][4], 1e-9);
        List<float[]> tris = single(shape);
        assertEquals(12, tris.size());
        assertEquals(4.0, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }
}
