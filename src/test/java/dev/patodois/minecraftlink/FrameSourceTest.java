package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static Wire.FrameReady ready(long frame) {
        return new Wire.FrameReady(frame, 0, 0, 2, 1);
    }

    @Test
    void announcementsAreKeptNewestFirstOnceAndComparedAsUnsigned() {
        List<Wire.FrameReady> l = new ArrayList<>();
        for (long f : new long[] {5, 7, 5, 6, 0x8000000000000000L, 1}) FrameSource.insert(l, ready(f), 8);
        List<Long> got = new ArrayList<>();
        for (Wire.FrameReady r : l) got.add(r.frame());
        // 2^63 is newer than 7 as an unsigned number; a signed comparison would put it last
        assertEquals(java.util.Arrays.asList(0x8000000000000000L, 7L, 6L, 5L, 1L), got);
        FrameSource.insert(l, ready(9), 3);
        assertEquals(3, l.size());
        assertEquals(9, l.get(1).frame());
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
    void aValidFrameOfTheCurrentFileIsNotLostToAHigherNumberTheFileCannotGive() throws Exception {
        FrameSource s = new FrameSource();
        Path other = dir.resolve("b.frames");
        PixelFile.Writer w2 = new PixelFile.Writer(other, 2, 1, 4, false);
        try {
            s.channel(file.toString());
            s.announce(write(writer, 11));
            s.step(PRESENT, NO_CAMERAS);
            // the peer moves to the second file, but frame 9 of the first one arrives after that, before frame 1 of the second
            s.channel(other.toString());
            s.announce(new Wire.FrameReady(9, 1, 0, 2, 1));
            s.announce(write(w2, 44));
            s.step(PRESENT, NO_CAMERAS);
            assertEquals(44, mark(s), "frame 1 of the new file is shown although 9 was announced after it");
            // a guest that sends one frame only still shows it
            assertTrue(s.lastValidMs() > 0);
        } finally {
            s.release();
            w2.close();
            Files.deleteIfExists(other);
        }
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
        // the new file has a frame 2 in slot 2, and an announcement of the old file's frame 2 is waiting when the new channel comes
        Path other = dir.resolve("b.frames");
        PixelFile.Writer w2 = new PixelFile.Writer(other, 2, 1, 4, false);
        write(w2, 41);
        write(w2, 42);
        try {
            s.announce(write(writer, 22));
            s.channel(other.toString());
            assertNull(s.composed(), "the image of the previous channel is gone at once");
            assertEquals(0, s.lastValidMs(), "and so is its freshness");
            assertEquals(generation + 1, s.generation());
            s.step(PRESENT, NO_CAMERAS);
            assertNull(s.composed(), "the old announcement is not read from the new file, where its number is a frame too");
            // the new channel's own frames are shown
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
        assertEquals(0, s.lastValidMs(), "and it did not make the new channel look fresh");
        s.release();
    }

    @Test
    void aNewCameraAloneDoesNotRenewTheFreshness() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        long first = s.lastValidMs();
        assertTrue(first > 0);
        Thread.sleep(30);
        // the host moves (a new camera object) while the guest sends nothing: the picture is recomposed but is not fresher
        Wire.Camera moved = Wire.Camera.lookAt(new float[] {0, 1, 1}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 64, 48);
        long version = s.composed().version;
        s.step(moved, NO_CAMERAS);
        assertTrue(s.composed().version > version, "recomposed for the new camera");
        assertEquals(first, s.lastValidMs());
        s.release();
    }

    @Test
    void aPixelFileThatIsGoneDropsTheImageAndIsNotOpenedAgain() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertNotNull(s.composed());
        long size = Files.size(file);
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(size - 1); // the header and the slots are intact; only the file is shorter than it was mapped
        }
        s.step(PRESENT, NO_CAMERAS);
        assertNull(s.composed(), "the file shrank: its picture is not kept");
        assertEquals(0, s.lastValidMs());
        assertTrue(s.note().contains("gone"), s.note());
        // the file is whole again, and the worker starts over (as it does after a failure): the channel is the same, so it stays shut
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(size);
        }
        s.recover();
        s.announce(write(writer, 22));
        s.step(PRESENT, NO_CAMERAS);
        assertNull(s.composed(), "a file that was gone is not opened again for the same channel");
        s.release();
    }

    @Test
    void aFileOverTheLimitOfPixelsIsRefusedWithANote() {
        FrameSource s = new FrameSource(1, null); // the file is 2 x 1
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertNull(s.composed());
        assertTrue(s.note().contains("more than the 1 pixels"), s.note());
        assertFalse(s.hasReader());
        s.release();
    }

    @Test
    void releaseClosesTheReader() {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertTrue(s.hasReader());
        s.release();
        assertFalse(s.hasReader());
    }
}
