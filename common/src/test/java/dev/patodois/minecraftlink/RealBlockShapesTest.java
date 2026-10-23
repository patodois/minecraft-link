package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The same checks as {@link CollisionBuilderTest}, but with the collision shapes Minecraft itself
 * has for a stone block, a stone slab and oak stairs (the game's registries are bootstrapped; no world
 * and no window is needed).
 */
class RealBlockShapesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static double[][] shapeOf(BlockState state) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        List<AABB> boxes = shape.toAabbs();
        double[][] out = new double[boxes.size()][];
        for (int i = 0; i < out.length; i++) {
            AABB b = boxes.get(i);
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
        double[][] shape = shapeOf(Blocks.STONE.defaultBlockState());
        assertEquals(1, shape.length);
        List<float[]> tris = single(shape);
        assertEquals(12, tris.size());
        assertEquals(6.0, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }

    @Test
    void bottomSlabIsHalfAHigh() {
        double[][] shape = shapeOf(Blocks.STONE_SLAB.defaultBlockState()); // type=bottom, the default
        List<float[]> tris = single(shape);
        assertEquals(12, tris.size());
        assertEquals(4.0, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }

    @Test
    void oakStairsHaveTheStepAndNoInternalFaces() {
        double[][] shape = shapeOf(Blocks.OAK_STAIRS.defaultBlockState());
        assertTrue(shape.length >= 2, "a stair is a slab plus a half-height step: " + shape.length + " boxes");
        List<float[]> tris = single(shape);
        // the surface area of a straight stair is 5.5 m2 whatever way it faces
        assertEquals(5.5, CollisionBuilderTest.totalArea(tris), 1e-9);
        CollisionBuilderTest.assertOutward(tris, 0, 0, 0, shape);
    }
}
