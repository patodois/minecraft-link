package dev.patodois.minecraftlink.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Link;
import dev.patodois.rewritelink.LinkConfig;
import dev.patodois.rewritelink.LinkEvent;
import dev.patodois.rewritelink.Wire;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guest role against a real link-java host over loopback, without a window: revision 4 receivers keep pose
 * state only for entities the sender has spawned, so the session must send {@code spawn} before the first pose
 * even when the game offers a pose the moment the link connects (which is what {@code GuestPublisher} does).
 */
class GuestSessionTest {
    private static int count(List<LinkEvent> l, Class<?> c) {
        int n = 0;
        for (LinkEvent e : l) if (c.isInstance(e)) n++;
        return n;
    }

    private static int indexOf(List<LinkEvent> l, Class<?> c) {
        for (int i = 0; i < l.size(); i++) if (c.isInstance(l.get(i))) return i;
        return -1;
    }

    @Test
    void theGuestSpawnsItsPlayerBeforeTheFirstPoseAndDespawnsWhenItStops() throws Exception {
        LinkConfig hc = new LinkConfig("Test Host");
        hc.controlPort = 0;
        Link host = Link.host(hc);
        LinkSettings settings = new LinkSettings();
        settings.port = host.controlPort();
        settings.guestEntity = 5;
        Session s = Session.open(Role.GUEST, settings, "tester");
        List<LinkEvent> seen = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() + 5000;
            boolean askedFocus = false;
            while (System.currentTimeMillis() < end && count(seen, LinkEvent.Poses.class) < 5) {
                seen.addAll(host.poll());
                if (s.isConnected()) {
                    // a pose the instant the session is up, every few milliseconds, as the game's tick would
                    s.offerPose(Wire.Pose.identity(5, Wire.nowUs()));
                    if (!askedFocus) {
                        askedFocus = true;
                        s.setFocus(Role.GUEST); // queued to the link thread; the game thread never touches the link
                    }
                }
                Thread.sleep(3);
            }
            assertTrue(count(seen, LinkEvent.Poses.class) >= 5, "poses arrived: " + seen);
            int spawn = indexOf(seen, LinkEvent.Spawn.class), pose = indexOf(seen, LinkEvent.Poses.class);
            assertTrue(spawn >= 0 && spawn < pose, "spawn (" + spawn + ") comes before the first pose (" + pose + ")");
            LinkEvent.Spawn sp = (LinkEvent.Spawn) seen.get(spawn);
            assertEquals(5, sp.spawn().entity());
            assertEquals("minecraft:player", sp.spawn().asset());
            assertEquals(0, host.unknownEntity, "the host ignored no pose");
            // /link status shows the link's anomaly counters, the unknown_entity and spawn_ignored ones included
            assertTrue(s.describe().endsWith(", foreign 0, unknown_entity 0, spawn_ignored 0"), s.describe());
            s.link.unknownEntity = 3;
            s.link.spawnIgnored = 2;
            assertTrue(s.describe().endsWith(", unknown_entity 3, spawn_ignored 2"), s.describe());
            long until = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < until && count(seen, LinkEvent.Focus.class) == 0) {
                seen.addAll(host.poll());
                Thread.sleep(3);
            }
            assertTrue(count(seen, LinkEvent.Focus.class) >= 1, "the focus request reached the host");

            s.closeBlocking(); // despawn, bye, half-close
            until = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < until && count(seen, LinkEvent.Despawn.class) == 0) {
                seen.addAll(host.poll());
                Thread.sleep(3);
            }
            assertEquals(1, count(seen, LinkEvent.Despawn.class), "the player is despawned on stop");
        } finally {
            s.closeBlocking();
            host.close();
        }
    }

    /**
     * Only when RUST_FAKE_HOST_PORT is set (a manual interop run): the same Session as a guest of the Rust reference host
     * ({@code fake_host --port N}). Passing means it connected and sent poses; the evidence is the host's own log
     * (poses accepted, {@code unknown_entity 0}).
     */
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUST_FAKE_HOST_PORT", matches = "[0-9]+")
    @Test
    void theGuestSessionAgainstTheRustFakeHost() throws Exception {
        LinkSettings settings = new LinkSettings();
        settings.port = Integer.parseInt(System.getenv("RUST_FAKE_HOST_PORT"));
        settings.guestEntity = 1;
        Session s = Session.open(Role.GUEST, settings, "interop");
        try {
            long end = System.currentTimeMillis() + 8000;
            long sent = 0;
            while (System.currentTimeMillis() < end) {
                if (s.isConnected()) {
                    s.offerPose(Wire.Pose.identity(1, Wire.nowUs()));
                    sent++;
                }
                Thread.sleep(50); // 20 poses a second, as the game's tick
            }
            assertTrue(sent > 50, "connected to the Rust host and offered poses: " + sent);
        } finally {
            s.closeBlocking();
        }
    }

    /**
     * Only when RUST_FAKE_GUEST_PORT is set (a manual interop run): the same Session as the host of the Rust reference guest
     * ({@code fake_guest --port N}, started by the person running this). Passing means the guest's poses were accepted: the guest
     * spawned its entity first, the host knows it, and no pose was ignored (the evidence is also the guest's own log).
     */
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUST_FAKE_GUEST_PORT", matches = "[0-9]+")
    @Test
    void theHostSessionAgainstTheRustFakeGuest() throws Exception {
        LinkSettings settings = new LinkSettings();
        settings.port = Integer.parseInt(System.getenv("RUST_FAKE_GUEST_PORT"));
        Session s = Session.open(Role.HOST, settings, "interop");
        try {
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && s.posesReceived.get() < 40) {
                // the reference guest renders (and sends its poses) for the cameras it receives
                if (s.isConnected()) s.offerCamera(Wire.Camera.lookAt(new float[] {0, 1, 0}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 640, 360));
                Thread.sleep(20);
            }
            assertTrue(s.posesReceived.get() >= 40, "poses of the Rust guest were accepted: " + s.posesReceived.get());
            assertTrue(!s.remotes.isEmpty(), "the guest's entity is known to the host");
            assertEquals(0, s.link.unknownEntity, "no pose was ignored");
        } finally {
            s.closeBlocking();
        }
    }
}
