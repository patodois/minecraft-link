package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CameraRingTest {
    private static CameraRing.Entry cam(int seq) {
        return new CameraRing.Entry(seq, CameraMath.identity(), CameraMath.identity(), 0.05f, 512f);
    }

    @Test
    void capacityIsSizedByTime() {
        assertTrue(CameraRing.capacityFor(0.5, 240) >= 128, "the contract's half second at 240 fps");
        assertEquals(128, CameraRing.capacityFor(0.5, 240));
        assertEquals(32, CameraRing.capacityFor(0.5, 60));
        assertEquals(256, CameraRing.capacityFor(1.0, 240));
        assertTrue(CameraRing.DEFAULT_CAPACITY >= 128);
    }

    @Test
    void theDefaultRingKeepsHalfASecondAt240FpsAndDropsOlderOnes() {
        CameraRing r = new CameraRing(CameraRing.DEFAULT_CAPACITY);
        for (int s = 1; s <= 1000; s++) r.put(cam(s));
        assertNotNull(r.get(1000));
        assertNotNull(r.get(1000 - 120), "0.5 s at 240 fps back");
        assertNull(r.get(1000 - CameraRing.DEFAULT_CAPACITY), "older than the capacity is gone");
        assertNull(r.get(0), "0 means no camera");
    }
}
