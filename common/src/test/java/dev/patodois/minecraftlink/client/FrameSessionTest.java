package dev.patodois.minecraftlink.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.minecraftlink.FrameSource;
import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Link;
import dev.patodois.rewritelink.LinkConfig;
import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A real host {@link Session} and a real link-java guest over loopback: the session hands what the link says (a pixel file, a Frame
 * datagram, the end of the session) to its {@link FrameSource}, and a channel change or the loss drops the image and the freshness
 * that keeps the guest's picture on screen. The test steps the source the way the frame worker does.
 */
class FrameSessionTest {
    @BeforeAll
    static void version() {
        SharedConstants.tryDetectVersion();
    }

    private static final Wire.Camera PRESENT = Wire.Camera.lookAt(new float[] {0, 1, 0}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 64, 48);

    private static void until(String what, Session s, Link guest, java.util.function.BooleanSupplier cond) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            guest.poll();
            s.frames.step(PRESENT, seq -> null);
            if (cond.getAsBoolean()) return;
            Thread.sleep(2);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void send(Link guest, PixelFile.Writer w, int mark) throws Exception {
        long frame = w.write(new byte[] {(byte) mark, 0, 0, (byte) 255, (byte) mark, 0, 0, (byte) 255}, null, 2, 1, 0, 1000L + mark);
        guest.sendFrame(new Wire.FrameReady(frame, (int) (frame % w.slots), 0, 2, 1));
    }

    private static int mark(Session s) {
        FrameSource.Composed c = s.frames.composed();
        return c == null ? -1 : c.rgba[0];
    }

    @Test
    void theSessionFollowsTheChannelAndTheLossOfTheGuest() throws Exception {
        LinkSettings settings = new LinkSettings();
        settings.port = 0;
        Session s = Session.open(Role.HOST, settings, "tester");
        Path dir = Files.createTempDirectory("framesession");
        Path f1 = dir.resolve("a.frames"), f2 = dir.resolve("b.frames");
        Link guest = null;
        PixelFile.Writer w1 = null, w2 = null;
        try {
            LinkConfig cfg = new LinkConfig("Test Guest");
            cfg.controlPort = s.controlPort;
            guest = Link.guest(cfg);
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end && !(s.isConnected() && guest.isConnected())) {
                guest.poll();
                Thread.sleep(3);
            }
            assertTrue(s.isConnected(), "the host session connected to the guest");

            w1 = new PixelFile.Writer(f1, 2, 1, 4, false);
            guest.announceFrames(w1);
            final Link g = guest;
            final PixelFile.Writer first = w1;
            until("the first frame", s, guest, () -> {
                try {
                    if (s.frames.composed() == null) send(g, first, 11);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return s.frames.composed() != null;
            });
            assertEquals(11, mark(s));
            assertTrue(s.framesFresh(), "a frame of the channel was read: the guest's picture is on screen");

            // another pixel file: the first one's image and freshness are gone, and its frames are not shown again
            w2 = new PixelFile.Writer(f2, 2, 1, 4, false);
            guest.announceFrames(w2);
            long generation = s.frames.generation();
            until("the new channel", s, guest, () -> s.frames.generation() != generation);
            assertNull(s.frames.composed(), "the previous channel's image is dropped");
            assertFalse(s.framesFresh(), "and so is its freshness");
            final PixelFile.Writer second = w2;
            until("the frame of the new channel", s, guest, () -> {
                try {
                    if (s.frames.composed() == null) send(g, second, 22);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return s.frames.composed() != null;
            });
            assertEquals(22, mark(s));

            // the guest leaves: nothing of it stays
            guest.close();
            guest = null;
            until("the end of the session", s, g, () -> !s.isConnected());
            assertNull(s.frames.composed());
            assertFalse(s.framesFresh());
        } finally {
            if (guest != null) guest.close();
            s.closeBlocking();
            if (w1 != null) w1.close();
            if (w2 != null) w2.close();
            Files.deleteIfExists(f1);
            Files.deleteIfExists(f2);
            Files.deleteIfExists(dir);
        }
    }
}
