package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CollisionSendGateTest {
    private static final long REFRESH = 5000;

    @Test
    void anUnchangedSnapshotIsNotOfferedAgainInTheSameSessionUntilTheRefresh() {
        CollisionSendGate g = new CollisionSendGate();
        assertTrue(g.due(42, 10_000, 1, REFRESH), "the first one");
        g.sent(42, 10_000, 1);
        assertFalse(g.due(42, 10_500, 1, REFRESH), "same geometry, same peer, too soon");
        assertTrue(g.due(43, 10_500, 1, REFRESH), "changed geometry");
        assertTrue(g.due(42, 15_000, 1, REFRESH), "the refresh");
    }

    @Test
    void aNewPeerGetsTheSameGeometryAtOnce() {
        CollisionSendGate g = new CollisionSendGate();
        g.sent(42, 10_000, 1);
        // the guest left and another joined the same host: nothing moved, nothing changed, and it has had no snapshot yet
        assertTrue(g.due(42, 10_100, 2, REFRESH), "first snapshot of the new session");
        g.sent(42, 10_100, 2);
        assertFalse(g.due(42, 10_200, 2, REFRESH), "and then it is not repeated");
    }

    @Test
    void resetForgetsWhatWasSent() {
        CollisionSendGate g = new CollisionSendGate();
        g.sent(42, 10_000, 1);
        g.reset();
        assertTrue(g.due(42, 10_001, 1, REFRESH));
    }
}
