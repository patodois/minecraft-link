package dev.patodois.minecraftlink.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.minecraftlink.CollisionCap;
import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Link;
import dev.patodois.rewritelink.LinkConfig;
import dev.patodois.rewritelink.LinkEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The collision size limit of contract revision 5 (4096 chunks, 925,696 triangles) through a real host {@link Session} and a real
 * link-java guest over loopback: a snapshot over the limit is cut to the nearest triangles, reaches the guest, is reported to the
 * player (the message queue that goes to the log and the chat) and shows in {@code /link status}. It never disappears silently.
 */
class CollisionLimitSessionTest {
    @BeforeAll
    static void version() {
        SharedConstants.tryDetectVersion();
    }

    /** A flat strip of tiny triangles along +x: triangle i is about i / 100 metres from the origin (so the radius asked for below, 20000 m, is more than the strip is long and the cut must shrink it). */
    private static List<float[]> strip(int n) {
        List<float[]> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float x = i / 100f;
            l.add(new float[] {x, 0, 0, x + 0.001f, 0.001f, 0, x + 0.001f, 0, 0.001f});
        }
        return l;
    }

    @Test
    void aSnapshotOverTheLimitIsCutToTheNearestTrianglesReportedAndStillArrives() throws Exception {
        LinkSettings settings = new LinkSettings();
        settings.port = 0;
        settings.collisionRadius = 64;
        Session s = Session.open(Role.HOST, settings, "tester");
        Link guest = null;
        try {
            LinkConfig cfg = new LinkConfig("Test Guest");
            cfg.controlPort = s.controlPort;
            guest = Link.guest(cfg);
            List<LinkEvent> seen = new ArrayList<>();
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end && !s.isConnected()) {
                seen.addAll(guest.poll());
                Thread.sleep(3);
            }
            assertTrue(s.isConnected(), "the host session connected to the guest");

            int over = CollisionCap.MAX_TRIANGLES + 1234;
            s.offerCollision(new Session.CollisionOut(new float[] {0, 0, 0}, 20000f, strip(over)));

            // the player is told (this queue is what the game thread writes to the log and the chat)
            String msg = s.messages.poll();
            while (msg != null && !msg.startsWith("collision snapshot too big")) msg = s.messages.poll();
            assertNotNull(msg, "the oversize snapshot is reported to the player");
            assertTrue(msg.contains(Integer.toString(over)) && msg.contains("925696") && msg.contains("1234"), msg);
            assertTrue(msg.contains("collision_radius"), msg);
            // and /link status says so too
            assertEquals(1, s.collisionsTrimmed.get());
            assertTrue(s.describe().contains("limit 925696, 1 cut to the nearest triangles, latest " + over + " -> 925696 (1234 dropped"), s.describe());

            // the cut snapshot is sent, not skipped: the guest assembles exactly 4096 chunks
            LinkEvent.Collision got = null;
            end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && got == null) {
                for (LinkEvent e : guest.poll()) if (e instanceof LinkEvent.Collision) got = (LinkEvent.Collision) e;
                Thread.sleep(1);
            }
            assertNotNull(got, "the trimmed snapshot arrives");
            assertEquals(CollisionCap.MAX_TRIANGLES, got.triangles().size());
            float farthest = 0;
            for (float[] t : got.triangles()) farthest = Math.max(farthest, t[0]);
            assertTrue(farthest < CollisionCap.MAX_TRIANGLES / 100f, "only the nearest triangles were kept: " + farthest);
            assertTrue(got.radius() < 20000f && got.radius() >= farthest - 0.01f, "announced radius " + got.radius() + ", farthest kept " + farthest);
            assertEquals(1, s.collisionsSent.get());
            assertEquals(CollisionCap.MAX_TRIANGLES, s.collisionTriangles.get());
            assertEquals(0, s.collisionsRefused.get());

            // a snapshot that fits again clears the state without a new message, and the next oversize one is reported anew
            s.offerCollision(new Session.CollisionOut(new float[] {0, 0, 0}, 64f, strip(10)));
            assertTrue(s.messages.isEmpty() || !s.messages.peek().startsWith("collision snapshot too big"));
            s.messages.clear();
            s.offerCollision(new Session.CollisionOut(new float[] {0, 0, 0}, 64f, strip(CollisionCap.MAX_TRIANGLES + 1)));
            String again = s.messages.poll();
            assertNotNull(again);
            assertTrue(again.startsWith("collision snapshot too big"), again);
            assertFalse(s.describe().contains("none cut"));
        } finally {
            if (guest != null) guest.close();
            s.closeBlocking();
        }
    }
}
