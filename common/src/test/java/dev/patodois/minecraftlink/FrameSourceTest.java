package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The guest's frames from announcement to finished image (link-java 0.3.0 presentation rules), with real pixel files. */
class FrameSourceTest {
    private static final Wire.Camera PRESENT = Wire.Camera.lookAt(new float[] {0, 1, 0}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 64, 48);
    private static final FrameSource.Cameras NO_CAMERAS = seq -> null;

    private Path dir;
    private PixelFile.Writer writer;
    private Path file;

    @BeforeEach
    void files() throws Exception {
        dir = Files.createTempDirectory("framesource");
        file = dir.resolve("a.frames");
        writer = new PixelFile.Writer(file, 2, 1, 4, false);
    }

    @AfterEach
    void cleanUp() throws Exception {
        writer.close();
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }

    /** Writes a 2 x 1 frame whose first byte is {@code mark} and announces it as the Frame datagram would. */
    private Wire.FrameReady write(PixelFile.Writer w, int mark) {
        long frame = w.write(new byte[] {(byte) mark, 0, 0, (byte) 255, (byte) mark, 0, 0, (byte) 255}, null, 2, 1, 0, 1000L + mark);
        return new Wire.FrameReady(frame, (int) (frame % w.slots), 0, 2, 1);
    }

    private static int mark(FrameSource s) {
        FrameSource.Composed c = s.composed();
        return c == null ? -1 : c.rgba[0];
    }

    @Test
    void anOlderAnnouncementArrivingAfterANewerOneIsNotWhatGetsRead() {
        FrameSource s = new FrameSource();
        Wire.FrameReady f1 = write(writer, 11), f2 = write(writer, 22), f3 = write(writer, 33);
        s.channel(file.toString());
        // the datagrams arrive out of order while the worker is busy: 3, then 2 late
        s.announce(f3);
        s.announce(f2);
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(33, mark(s), "the newest announcement is the one that is read");
        // and one that arrives late after it was shown is not presented
        long version = s.composed().version;
        s.announce(f2);
        s.announce(f1);
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(33, mark(s));
        assertEquals(version, s.composed().version, "nothing newer was composed");
        s.release();
    }

    @Test
    void aNewChannelDropsTheImageThePendingAnnouncementAndTheReader() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(11, mark(s));
        assertTrue(s.lastValidMs() > 0);
        long generation = s.generation();
        // an announcement of the old file is waiting when the peer announces a new one
        s.announce(write(writer, 22));
        Path other = dir.resolve("b.frames");
        PixelFile.Writer w2 = new PixelFile.Writer(other, 2, 1, 4, false);
        try {
            s.channel(other.toString());
            assertNull(s.composed(), "the image of the previous channel is gone at once");
            assertEquals(0, s.lastValidMs(), "and so is its freshness");
            assertEquals(generation + 1, s.generation());
            s.step(PRESENT, NO_CAMERAS);
            assertNull(s.composed(), "the old announcement is not read from the new file");
            // the new channel numbers its frames from 1 again and they are shown
            s.announce(write(w2, 44));
            s.step(PRESENT, NO_CAMERAS);
            assertEquals(44, mark(s));
        } finally {
            s.release();
            w2.close();
            Files.deleteIfExists(other);
        }
    }

    @Test
    void theLossOfTheSessionDropsTheImage() {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(11, mark(s));
        s.lost();
        assertNull(s.composed());
        assertEquals(0, s.lastValidMs());
        s.announce(write(writer, 22));
        s.step(PRESENT, NO_CAMERAS);
        assertNull(s.composed(), "nothing is read without a channel");
        s.release();
    }

    @Test
    void aWorkerStillComposingWhenTheChannelChangesCannotPublishIntoTheNewOne() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        CountDownLatch inside = new CountDownLatch(1), go = new CountDownLatch(1);
        FrameSource.Cameras slow = seq -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
        Thread worker = new Thread(() -> s.step(PRESENT, slow));
        worker.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the worker is composing");
        s.channel(dir.resolve("never-opened.frames").toString());
        go.countDown();
        worker.join(10_000);
        assertNull(s.composed(), "the image of the old channel did not appear in the new one");
        s.release();
    }

    @Test
    void aPixelFileThatIsGoneDropsTheImage() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertNotNull(s.composed());
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(16);
        }
        s.step(PRESENT, NO_CAMERAS);
        assertNull(s.composed(), "the file shrank: its picture is not kept");
        assertEquals(0, s.lastValidMs());
        s.release();
    }
}
