package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.rewritelink.Wire;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The size limit of a collision snapshot (CONTRACT 9.5, revision 5): 4096 chunks of 226 triangles, 925,696 triangles. */
class CollisionCapTest {
    private static final float[] ORIGIN = {0, 0, 0};

    /** A tiny triangle whose nearest point to the origin is at distance {@code d} along +x. */
    private static float[] triAt(float d) {
        return new float[] {d, 0, 0, d + 0.1f, 0.1f, 0, d + 0.1f, 0, 0.1f};
    }

    @Test
    void theLimitIsTheContractsAndMatchesTheLibrary() {
        assertEquals(925_696, CollisionCap.MAX_TRIANGLES);
        assertEquals(4096 * 226, CollisionCap.MAX_TRIANGLES);
        assertEquals(Wire.MAX_COLLISION_CHUNKS * Wire.MAX_TRIANGLES, CollisionCap.MAX_TRIANGLES);
    }

    @Test
    void theLibraryRefusesOneTriangleMoreThanTheLimitAndTheCapNeverHandsItOne() throws Exception {
        List<float[]> over = new ArrayList<>(CollisionCap.MAX_TRIANGLES + 1);
        float[] t = triAt(1);
        for (int i = 0; i <= CollisionCap.MAX_TRIANGLES; i++) over.add(t);
        // what the session must never hand to link-java: it throws (and Session would have read that as "not connected")
        assertThrows(Wire.WireException.class, () -> Wire.chunks(1, ORIGIN, 16, over));
        CollisionCap.Result r = CollisionCap.limit(over, ORIGIN, 16);
        assertEquals(CollisionCap.MAX_TRIANGLES, r.triangles.size());
        assertEquals(1, r.dropped);
        assertEquals(CollisionCap.MAX_TRIANGLES + 1, r.offered);
        // and what it hands over is accepted: exactly 4096 chunks
        assertEquals(Wire.MAX_COLLISION_CHUNKS, Wire.chunks(1, ORIGIN, r.radius, r.triangles).size());
    }

    @Test
    void aSnapshotThatFitsIsReturnedAsItIsAndNothingIsDropped() {
        List<float[]> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) l.add(triAt(i));
        for (int max : new int[] {10, 11, 1000}) {
            CollisionCap.Result r = CollisionCap.limit(l, ORIGIN, 16, max);
            assertSame(l, r.triangles);
            assertFalse(r.trimmed());
            assertEquals(0, r.dropped);
            assertEquals(10, r.offered);
            assertEquals(16f, r.radius);
        }
    }

    @Test
    void anOversizeSnapshotKeepsTheNearestTrianglesInTheirOrderAndShrinksTheRadius() {
        // distances 0..99, shuffled by a fixed permutation so the order in the list is not the order by distance
        List<float[]> l = new ArrayList<>();
        for (int i = 0; i < 100; i++) l.add(triAt((i * 37) % 100));
        CollisionCap.Result r = CollisionCap.limit(l, ORIGIN, 64, 30);
        assertEquals(30, r.triangles.size());
        assertEquals(70, r.dropped);
        assertEquals(100, r.offered);
        assertTrue(r.trimmed());
        float farthestKept = 0;
        int last = -1;
        for (float[] t : r.triangles) {
            assertTrue(t[0] < 30, "kept a triangle at " + t[0]);
            farthestKept = Math.max(farthestKept, t[0]);
            int at = l.indexOf(t);
            assertTrue(at > last, "order of the input is kept");
            last = at;
        }
        assertEquals(29f, farthestKept);
        // the nearest dropped triangle is at 30: the snapshot is complete inside that sphere and claims nothing beyond
        assertTrue(r.radius <= 30f && r.radius > 29.99f, "radius " + r.radius);
    }

    @Test
    void theRadiusIsNeverRaisedAboveTheOneAskedFor() {
        List<float[]> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) l.add(triAt(i * 10));
        CollisionCap.Result r = CollisionCap.limit(l, ORIGIN, 5, 9);
        assertEquals(5f, r.radius);
    }

    @Test
    void tiesAreCutByInputOrderSoTheSameInputGivesTheSameSnapshot() {
        List<float[]> l = new ArrayList<>();
        for (int i = 0; i < 20; i++) l.add(new float[] {5, 0, 0, 5.1f, 0.1f, 0, 5.1f, 0, 0.1f}); // all equally near, distinct objects
        CollisionCap.Result a = CollisionCap.limit(l, ORIGIN, 16, 7);
        CollisionCap.Result b = CollisionCap.limit(l, ORIGIN, 16, 7);
        assertEquals(7, a.triangles.size());
        for (int i = 0; i < 7; i++) {
            assertSame(l.get(i), a.triangles.get(i), "the first of the tied triangles stay");
            assertSame(a.triangles.get(i), b.triangles.get(i));
        }
        assertEquals(13, a.dropped);
    }

    @Test
    void distanceIsToTheClosestPointOfTheTriangleNotItsCornersOrCentroid() {
        // a big triangle in the plane y = 3 whose face is right above the origin: closest point is inside it, distance 3
        float[] big = {-50, 3, -50, 50, 3, -50, 0, 3, 80};
        assertEquals(9f, CollisionCap.distSq(big, ORIGIN), 1e-4f);
        // a thin sliver whose nearest point is mid-edge, not at a corner: edge from (-10,0,4) to (10,0,4) -> distance 4
        float[] sliver = {-10, 0, 4, 10, 0, 4, 10, 0, 4.0001f};
        assertEquals(16f, CollisionCap.distSq(sliver, ORIGIN), 1e-3f);
        // a degenerate triangle (all corners equal) is a point
        float[] point = {3, 4, 0, 3, 4, 0, 3, 4, 0};
        assertEquals(25f, CollisionCap.distSq(point, ORIGIN), 1e-4f);
        // the big face is nearer than a small triangle at distance 2 sideways? no: 3 > 2, so the small one wins
        List<float[]> l = new ArrayList<>();
        l.add(big);
        l.add(triAt(2));
        CollisionCap.Result r = CollisionCap.limit(l, ORIGIN, 64, 1);
        assertSame(l.get(1), r.triangles.get(0));
        assertTrue(r.radius <= 3f && r.radius > 2.99f, "radius " + r.radius);
    }

    @Test
    void zeroAsALimitDropsEverything() {
        List<float[]> l = new ArrayList<>();
        l.add(triAt(1));
        CollisionCap.Result r = CollisionCap.limit(l, ORIGIN, 16, 0);
        assertEquals(0, r.triangles.size());
        assertEquals(1, r.dropped);
        assertTrue(r.radius <= 1f);
    }
}
