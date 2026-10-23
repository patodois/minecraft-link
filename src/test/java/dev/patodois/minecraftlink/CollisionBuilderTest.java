package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollisionBuilderTest {
    static final double[][] FULL = {{0, 0, 0, 1, 1, 1}};
    static final double[][] SLAB = {{0, 0, 0, 1, 0.5, 1}};
    /** An oak stair facing south: a bottom slab and the upper half of the north half... (Minecraft: two boxes). */
    static final double[][] STAIRS = {{0, 0, 0, 1, 0.5, 1}, {0, 0.5, 0.5, 1, 1, 1}};

    static final class Map3 implements CollisionBuilder.Shapes {
        final Map<String, double[][]> blocks = new HashMap<>();

        Map3 put(int x, int y, int z, double[][] shape) {
            blocks.put(x + "," + y + "," + z, shape);
            return this;
        }

        @Override
        public double[][] boxesAt(int x, int y, int z) {
            return blocks.get(x + "," + y + "," + z);
        }
    }

    static List<float[]> build(Map3 world) {
        CollisionBuilder b = new CollisionBuilder(0, 0, 0, 4);
        for (int layer = 0; layer < b.layers(); layer++) b.scanLayer(world, layer);
        return b.triangles();
    }

    static double area(float[] t) {
        double[] n = CollisionBuilder.normal(t);
        return 0.5 * Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    }

    static double totalArea(List<float[]> tris) {
        double a = 0;
        for (float[] t : tris) a += area(t);
        return a;
    }

    /** Every triangle must face away from the solid: a step of 1e-3 along the normal leaves every box. */
    static void assertOutward(List<float[]> tris, int bx, int by, int bz, double[][] shape) {
        for (float[] t : tris) {
            double[] n = CollisionBuilder.normal(t);
            double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            double cx = (t[0] + t[3] + t[6]) / 3 + n[0] / len * 1e-3 - bx;
            double cy = (t[1] + t[4] + t[7]) / 3 + n[1] / len * 1e-3 - by;
            double cz = (t[2] + t[5] + t[8]) / 3 + n[2] / len * 1e-3 - bz;
            for (double[] b : shape) {
                boolean inside = cx > b[0] && cx < b[3] && cy > b[1] && cy < b[4] && cz > b[2] && cz < b[5];
                assertTrue(!inside, "triangle faces into the solid: normal " + java.util.Arrays.toString(n));
            }
        }
    }

    @Test
    void fullBlockIsTwelveOutwardTriangles() {
        List<float[]> tris = build(new Map3().put(0, 0, 0, FULL));
        assertEquals(12, tris.size());
        assertEquals(6.0, totalArea(tris), 1e-9);
        assertOutward(tris, 0, 0, 0, FULL);
        // the top face really is at y = 1 with its normal up
        long up = tris.stream().filter(t -> CollisionBuilder.normal(t)[1] > 0.5).count();
        assertEquals(2, up);
        for (float[] t : tris) {
            double[] n = CollisionBuilder.normal(t);
            if (n[1] > 0.5) {
                assertEquals(1f, t[1]);
                assertEquals(1f, t[4]);
                assertEquals(1f, t[7]);
            }
        }
    }

    @Test
    void slabHasHalfHeightSidesAndTopAtHalf() {
        List<float[]> tris = build(new Map3().put(0, 0, 0, SLAB));
        assertEquals(12, tris.size());
        assertEquals(1 + 1 + 4 * 0.5, totalArea(tris), 1e-9);
        assertOutward(tris, 0, 0, 0, SLAB);
        for (float[] t : tris) {
            if (CollisionBuilder.normal(t)[1] > 0.5) assertEquals(0.5f, t[1]);
        }
    }

    @Test
    void stairsAreTwoBoxesWithOutwardFaces() {
        List<float[]> tris = build(new Map3().put(0, 0, 0, STAIRS));
        // tread + step + riser + back + front + bottom + both L-shaped sides; the faces where the two boxes
        // touch (the slab's top under the step) are cut away
        assertEquals(1 + 0.5 + 0.5 + 0.5 + 1 + 0.5 + 1.5, totalArea(tris), 1e-9);
        assertOutward(tris, 0, 0, 0, STAIRS);
        // the step: a vertical face at z = 0.5 looking toward -Z, from y 0.5 to 1
        boolean riser = tris.stream().anyMatch(t -> CollisionBuilder.normal(t)[2] < -0.1 && t[2] == 0.5f && t[5] == 0.5f && t[8] == 0.5f);
        assertTrue(riser);
    }

    @Test
    void facesBehindFullBlocksAreCulled() {
        Map3 w = new Map3().put(0, 0, 0, FULL).put(1, 0, 0, FULL);
        List<float[]> tris = build(w);
        // two cubes side by side = a 2x1x1 box: 10 faces, 20 triangles, area 10
        assertEquals(20, tris.size());
        assertEquals(10.0, totalArea(tris), 1e-9);
        // a slab beside a full block: its side facing the block is hidden, the others stay
        List<float[]> slab = build(new Map3().put(0, 0, 0, FULL).put(1, 0, 0, SLAB));
        assertEquals(12 + 10, slab.size()); // cube 6 faces (the slab does not cover its side) + slab 5 faces (the one against the cube is hidden)
    }

    @Test
    void aGroundOfFullBlocksOnlyHasItsTopAndTheRim() {
        Map3 w = new Map3();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -2; y <= 0; y++) w.put(x, y, z, FULL);
        List<float[]> tris = build(w);
        // 7x7 slab 3 deep, volume cut by the scan radius sphere is a bit smaller than the cube: top face + sides + bottom
        long tops = tris.stream().filter(t -> CollisionBuilder.normal(t)[1] > 0.5).count();
        assertEquals(2 * 49, tops);
        // no triangle faces down inside the ground except the very bottom layer
        for (float[] t : tris) {
            if (CollisionBuilder.normal(t)[1] < -0.5) assertEquals(-2f, t[1], "downward faces only on the lowest layer");
        }
    }

    @Test
    void mirroringMapsKeepCounterClockwise() {
        List<float[]> tris = build(new Map3().put(0, 0, 0, FULL));
        Mapping flip = new Mapping(false, true, 1.0, new double[] {0, 0, 0});
        List<float[]> mapped = CollisionBuilder.map(tris, flip);
        assertEquals(tris.size(), mapped.size());
        // the block now spans z in [-1, 0]; still outward-facing triangles
        double[][] shape = {{0, 0, -1, 1, 1, 0}};
        assertOutward(mapped, 0, 0, 0, shape);
        // identity map returns the same list
        assertTrue(CollisionBuilder.map(tris, Mapping.IDENTITY) == tris);
    }

    @Test
    void hashChangesWhenTheWorldChanges() {
        long a = CollisionBuilder.hash(build(new Map3().put(0, 0, 0, FULL)));
        long b = CollisionBuilder.hash(build(new Map3().put(0, 0, 0, FULL)));
        long c = CollisionBuilder.hash(build(new Map3().put(0, 0, 0, SLAB)));
        assertEquals(a, b);
        assertTrue(a != c);
    }
}
