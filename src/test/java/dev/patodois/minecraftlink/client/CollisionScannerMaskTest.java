package dev.patodois.minecraftlink.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CollisionScannerMaskTest {
    @Test
    void theMaskFollowsTheScansOwnCentre() {
        int radius = 16;
        // A scan that started on block (0, 64, 0): its area runs from -16 to +16, so the boxes of the blocks at the edges
        // (up to one block further) must lie inside the mask.
        double[] m = CollisionScanner.maskBounds(0, 64, 0, radius);
        assertTrue(m[0] <= -radius && m[3] >= radius + 1, "x covers the scan area and the block beyond its edge");
        assertTrue(m[1] <= 64 - radius && m[4] >= 64 + radius + 1);
        assertTrue(m[2] <= -radius && m[5] >= radius + 1);
        // The player walks 7 blocks while the scan runs: the scan's centre has not moved, so neither has the mask. A mask rebuilt around
        // the player (x = 7) would start at x = -12 and cut the trailing edge (x = -16 to -13) off the boxes of partial blocks.
        assertArrayEquals(m, CollisionScanner.maskBounds(0, 64, 0, radius));
        double[] around = CollisionScanner.maskBounds(7, 64, 0, radius);
        assertTrue(around[0] > -radius, "the player-centred mask is the one that loses the trailing edge");
    }
}
