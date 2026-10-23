package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The same checks as {@link CollisionBuilderTest}, but with the collision boxes Minecraft 1.12.2 itself
 * has for a stone block, a stone slab and oak stairs ({@code IBlockState.addCollisionBoxToList}, the list the game's
 * entity collision uses; the game's registries are bootstrapped, no world and no window is needed).
 */
class RealBlockShapesTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    private static double[][] shapeOf(IBlockState state) {
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
        // isActualState = true: the state is used as it is, so no world is read.
        state.addCollisionBoxToList(null, BlockPos.ORIGIN, new AxisAlignedBB(-1, -1, -1, 2, 2, 2), boxes, null, true);
        double[][] out = new double[boxes.size()][];
        for (int i = 0; i < out.length; i++) {
            AxisAlignedBB b = boxes.get(i);
            out[i] = new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        }
        return out;
    }

    private static List<float[]> single(double[][] shape) {
        CollisionBuilderTest.Map3 w = new CollisionBuilderTest.Map3().put(0, 0, 0, shape);
        return CollisionBuilderTest.build(w);
    }

    @Test
    void stoneIsAFullBlock() {
        double[][] shape = shapeOf(Blocks.STONE.getDefaultState());
        assertEquals(1, shape.length);
        List<float[]> tris = single(shape);
        assertEquals(12, tris.size());
        assertEquals(6.0, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }

    @Test
    void bottomSlabIsHalfAHigh() {
        double[][] shape = shapeOf(Blocks.STONE_SLAB.getDefaultState()); // half=bottom, the default
        assertEquals(1, shape.length);
        List<float[]> tris = single(shape);
        assertEquals(12, tris.size());
        assertEquals(4.0, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }

    @Test
    void oakStairsHaveTheStepAndNoInternalFaces() {
        double[][] shape = shapeOf(Blocks.OAK_STAIRS.getDefaultState());
        assertTrue(shape.length >= 2, "a stair is a slab plus a half-height step: " + shape.length + " boxes");
        List<float[]> tris = single(shape);
        // the surface area of a straight stair is 5.5 m2 whatever way it faces
        assertEquals(5.5, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }
}
