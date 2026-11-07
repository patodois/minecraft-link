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
    void onlyAnnouncementsEqualInEveryFieldAreTheSameOne() {
        List<Wire.FrameReady> l = new ArrayList<>();
        Wire.FrameReady a = new Wire.FrameReady(1, 1, 0, 1, 1), b = new Wire.FrameReady(1, 1, 0, 2, 1), c = new Wire.FrameReady(1, 2, 0, 2, 1);
        for (Wire.FrameReady f : new Wire.FrameReady[] {a, b, a, c, b}) FrameSource.insert(l, f, 8);
        // one number, three different announcements: all three are kept, in the order they arrived
        assertEquals(java.util.Arrays.asList(a, b, c), l);
    }

    @Test
    void anAnnouncementOfThePreviousFileWithTheSameNumberCannotHideTheOnlyFrameOfTheNewOne() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        Wire.FrameReady valid = write(writer, 44);
        // a late frame 1 of a previous 1 x 1 file, then frame 1 of this 2 x 1 file
        s.announce(new Wire.FrameReady(valid.frame(), valid.slot(), 0, 1, 1));
        s.announce(valid);
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(44, mark(s), "the valid frame was not discarded before any valid copy");
        s.release();
    }

    @Test
    void recoveringCannotForgetTheOrderAlreadyPresented() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        Wire.FrameReady one = write(writer, 11), two = write(writer, 22), three = write(writer, 33);
        s.announce(three);
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(33, mark(s));
        long version = s.composed().version;
        // what the worker does after a failure: the pixel file is opened again, and a late older frame arrives
        s.recover();
        s.announce(two);
        s.announce(one);
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(33, mark(s), "a late older frame is not presented after the reader was reopened");
        assertEquals(version, s.composed().version);
        assertEquals(2, s.readersOpened());
        s.release();
    }

    @Test
    void aFailureWhileComposingKeepsTheCopiedFrameForTheNextStep() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11)); // a guest that sent one frame and will send no other
        int[] calls = {0};
        FrameSource.Cameras failOnce = seq -> {
            if (calls[0]++ == 0) throw new IllegalStateException("test");
            return null;
        };
        try {
            s.step(PRESENT, failOnce);
            org.junit.jupiter.api.Assertions.fail("the failure should have been thrown");
        } catch (IllegalStateException expected) {
            // the worker reports it, opens the file again and goes on
        }
        s.recover();
        s.step(PRESENT, failOnce);
        assertEquals(11, mark(s), "the only frame of a static guest is composed again, not lost");
        s.release();
    }

    @Test
    void announcementsAFailedStepHadNotTriedGoBackToTheQueue() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        int[] calls = {0};
        s.readWith((reader, announced) -> {
            if (calls[0]++ == 0) throw new OutOfMemoryError("test");
            return reader.readLatest(announced, true);
        });
        try {
            s.step(PRESENT, NO_CAMERAS);
            org.junit.jupiter.api.Assertions.fail("the failure should have been thrown");
        } catch (OutOfMemoryError expected) {
            // reported by the worker
        }
        s.recover();
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(11, mark(s), "the announcement the failed step took is read on the next one");
        s.release();
    }

    @Test
    void announcementsAFailedStepGivesBackAreNotKeptForAChannelThatIsNoMore() throws Exception {
        FrameSource s = new FrameSource();
        Path other = dir.resolve("b.frames");
        PixelFile.Writer w2 = new PixelFile.Writer(other, 2, 1, 4, false);
        try {
            write(w2, 77); // frame 1 of the new file, never announced
            s.channel(file.toString());
            s.announce(write(writer, 11));
            s.readWith((reader, announced) -> {
                s.channel(other.toString()); // the channel changes while the read is under way
                throw new IllegalStateException("test");
            });
            try {
                s.step(PRESENT, NO_CAMERAS);
                org.junit.jupiter.api.Assertions.fail("the failure should have been thrown");
            } catch (IllegalStateException expected) {
                // reported by the worker
            }
            s.recover();
            s.readWith((reader, announced) -> reader.readLatest(announced, true));
            s.step(PRESENT, NO_CAMERAS);
            assertNull(s.composed(), "frame 1 of the old file is not read as frame 1 of the new one");
        } finally {
            s.release();
            w2.close();
            Files.deleteIfExists(other);
        }
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
            assertTrue(s.fresh());
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
        assertTrue(s.fresh());
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
            assertFalse(s.fresh(), "and so is its freshness");
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
        assertFalse(s.fresh());
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
        assertFalse(s.fresh(), "and it did not make the new channel look fresh");
        s.release();
    }

    @Test
    void aNewCameraAloneDoesNotRenewTheFreshness() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        long first = s.validAt();
        assertTrue(first > 0);
        Thread.sleep(30);
        // the host moves (a new camera object) while the guest sends nothing: the picture is recomposed but is not fresher
        Wire.Camera moved = Wire.Camera.lookAt(new float[] {0, 1, 1}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 64, 48);
        long version = s.composed().version;
        s.step(moved, NO_CAMERAS);
        assertTrue(s.composed().version > version, "recomposed for the new camera");
        assertEquals(first, s.validAt());
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
        assertFalse(s.fresh());
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

    /** A guest camera and a present camera that differ, and a pixel file with a depth plane, for the reprojection. */
    private static final Wire.Camera GUEST_CAM = Wire.Camera.lookAt(new float[] {0, 1, 5}, new float[] {0, 1, 0}, 1f, 0.1f, 100f, 32, 24);
    private static final Wire.Camera MOVED_CAM = Wire.Camera.lookAt(new float[] {1, 1, 5}, new float[] {0, 1, 0}, 1f, 0.1f, 100f, 32, 24);

    private PixelFile.Writer depthWriter(Path p) throws Exception {
        return new PixelFile.Writer(p, 2, 1, 4, true);
    }

    private static Wire.FrameReady writeDepth(PixelFile.Writer w, int mark, float depth) {
        long n = w.write(new byte[] {(byte) mark, 0, 0, (byte) 255, (byte) mark, 0, 0, (byte) 255}, new float[] {depth, depth}, 2, 1, 7, 1000L + mark);
        return new Wire.FrameReady(n, (int) (n % w.slots), 7, 2, 1);
    }

    private static final FrameSource.Cameras GUEST_RING = seq -> seq == 7 ? new CameraRing.Entry(7, GUEST_CAM.view(), GUEST_CAM.proj(), 0.1f, 100f) : null;

    @Test
    void aFrameWithDepthIsReprojectedToTheCameraBeingPresented() throws Exception {
        Path f2 = dir.resolve("d.frames");
        PixelFile.Writer w = depthWriter(f2);
        try {
            FrameSource s = new FrameSource();
            s.channel(f2.toString());
            s.announce(writeDepth(w, 55, 5f));
            s.step(GUEST_CAM, GUEST_RING); // rendered with this very camera: the picture is where it was, with its depth
            FrameSource.Composed same = s.composed();
            assertNotNull(same);
            assertTrue(same.reprojected, "the guest's camera is in the ring and the file has depth");
            assertEquals(2 * 1, same.depth.length);
            assertEquals(55, same.rgba[0]);
            // the host moved: the same frame is composed again, for the new camera
            s.step(MOVED_CAM, GUEST_RING);
            FrameSource.Composed moved = s.composed();
            assertTrue(moved.version > same.version);
            assertTrue(moved.reprojected);
            assertFalse(java.util.Arrays.equals(same.rgba, moved.rgba) && java.util.Arrays.equals(same.depth, moved.depth),
                    "a 1 m move of the camera changes where the pixels land");
            s.release();
        } finally {
            w.close();
            Files.deleteIfExists(f2);
        }
    }

    @Test
    void anOutOfMemoryBetweenTheTwoWorkingArraysDoesNotBreakTheReprojectionForGood() throws Exception {
        Path f2 = dir.resolve("d.frames");
        PixelFile.Writer w = depthWriter(f2);
        try {
            FrameSource s = new FrameSource();
            s.channel(f2.toString());
            int[] calls = {0};
            s.allocateDepthWith(n -> {
                if (calls[0]++ == 0) throw new OutOfMemoryError("test");
                return new float[n];
            });
            s.announce(writeDepth(w, 55, 5f));
            try {
                s.step(GUEST_CAM, GUEST_RING);
                org.junit.jupiter.api.Assertions.fail("the OutOfMemoryError should have been thrown");
            } catch (OutOfMemoryError expected) {
                // reported by the worker, which goes on
            }
            s.step(GUEST_CAM, GUEST_RING); // memory is back
            assertNotNull(s.composed(), "the frame is reprojected on the next step");
            assertTrue(s.composed().reprojected);
            s.announce(writeDepth(w, 66, 5f));
            s.step(GUEST_CAM, GUEST_RING);
            assertEquals(66, mark(s), "and so is the next one");
            s.release();
        } finally {
            w.close();
            Files.deleteIfExists(f2);
        }
    }

    @Test
    void aGuestThatIsConnectedAndSilentIsNotReprojectedAtEveryCameraMove() throws Exception {
        Path f2 = dir.resolve("d.frames");
        PixelFile.Writer w = depthWriter(f2);
        try {
            FrameSource s = new FrameSource();
            long[] now = {1_000_000_000L};
            s.clockWith(() -> now[0]);
            s.channel(f2.toString());
            s.announce(writeDepth(w, 55, 5f));
            s.step(GUEST_CAM, GUEST_RING);
            long version = s.composed().version;
            assertTrue(s.fresh());
            // inside the second the picture follows the camera
            now[0] += 900_000_000L;
            s.step(MOVED_CAM, GUEST_RING);
            assertTrue(s.composed().version > version, "recomposed while it is on screen");
            // a second after the last frame the guest is gone and the camera moves on: nothing is composed
            now[0] += 2_000_000_000L;
            assertFalse(s.fresh());
            long expired = s.composed().version;
            for (int i = 0; i < 5; i++) {
                s.step(Wire.Camera.lookAt(new float[] {i, 1, 5}, new float[] {0, 1, 0}, 1f, 0.1f, 100f, 32, 24), GUEST_RING);
            }
            assertEquals(expired, s.composed().version, "no image is made for a picture nobody draws");
            // the guest sends again: the picture is correct for the camera of that moment, and fresh
            s.announce(writeDepth(w, 66, 5f));
            s.step(MOVED_CAM, GUEST_RING);
            assertEquals(66, mark(s));
            assertTrue(s.composed().version > expired);
            assertTrue(s.fresh());
            s.release();
        } finally {
            w.close();
            Files.deleteIfExists(f2);
        }
    }

    @Test
    void theDefaultClockOfTheFreshnessIsTheMonotonicOne() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        long before = System.nanoTime();
        s.step(PRESENT, NO_CAMERAS);
        long after = System.nanoTime();
        assertTrue(s.validAt() - before >= 0 && after - s.validAt() >= 0, "read from System.nanoTime, not from the wall clock");
        s.release();
    }

    @Test
    void theFreshnessFollowsAMonotonicClockNotTheWallClock() throws Exception {
        FrameSource s = new FrameSource();
        long[] now = {5_000_000_000L};
        s.clockWith(() -> now[0]);
        s.channel(file.toString());
        s.announce(write(writer, 11));
        s.step(PRESENT, NO_CAMERAS);
        assertTrue(s.fresh());
        now[0] += 999_000_000L;
        assertTrue(s.fresh(), "just inside the second");
        now[0] += 2_000_000L;
        assertFalse(s.fresh(), "just past it");
        s.release();
    }

    @Test
    void aPassingFailureToOpenThePixelFileIsThrownAndTriedAgainNotTheEndOfTheChannel() throws Exception {
        for (boolean io : new boolean[] {true, false}) {
            FrameSource s = new FrameSource();
            int[] calls = {0};
            s.openWith(p -> {
                if (calls[0]++ == 0) {
                    if (io) throw new java.io.IOException("too many open files");
                    throw new IllegalStateException("passing");
                }
                return PixelFile.Reader.open(p);
            });
            s.channel(file.toString());
            s.announce(write(writer, 77));
            try {
                s.step(PRESENT, NO_CAMERAS);
                org.junit.jupiter.api.Assertions.fail("a failure that may pass is thrown to the worker");
            } catch (java.io.UncheckedIOException | IllegalStateException expected) {
                assertTrue(s.note().contains("trying again"), s.note());
            }
            s.recover();
            s.step(PRESENT, NO_CAMERAS); // the announcement went back to the queue and the file opens now
            assertEquals(77, mark(s), "the frames of the same file are not lost to a passing error (io " + io + ")");
            s.release();
            writer.close();
            writer = new PixelFile.Writer(file, 2, 1, 4, false); // a fresh file for the next round: the writer deletes its own on close
        }
    }

    @Test
    void aMissingFileOrAFileTheContractRefusesEndsTheChannelWithANote() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(dir.resolve("nothing.frames").toString());
        s.announce(ready(1));
        s.step(PRESENT, NO_CAMERAS); // no exception: the file the peer announced is not there, as the contract says to skip
        assertTrue(s.note().contains("no such file"), s.note());
        assertNull(s.composed());
        Path junk = dir.resolve("junk.frames");
        Files.write(junk, new byte[100]);
        s.channel(junk.toString());
        s.step(PRESENT, NO_CAMERAS);
        assertTrue(s.note().contains("cannot use the pixel file"), s.note());
        s.channel("relative/path.frames");
        s.step(PRESENT, NO_CAMERAS);
        assertTrue(s.note().contains("not absolute"), s.note());
        s.channel(dir.toString() + "/with\0nul.frames");
        s.step(PRESENT, NO_CAMERAS);
        assertTrue(s.note().contains("cannot use the pixel file"), "a path that can never open ends the channel: " + s.note());
        s.release();
        Files.deleteIfExists(junk);
    }

    @Test
    void aFailureThrownAsAnErrorIsComposedAgainOnTheNextStep() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        int[] calls = {0};
        FrameSource.Cameras failOnce = seq -> {
            if (calls[0]++ == 0) throw new OutOfMemoryError("test");
            return null;
        };
        try {
            s.step(PRESENT, failOnce);
            org.junit.jupiter.api.Assertions.fail("thrown");
        } catch (OutOfMemoryError expected) {
            // the worker reports it
        }
        s.step(PRESENT, failOnce);
        assertEquals(11, mark(s), "the frame copied before the Error is composed again, with the same camera");
        s.release();
    }

    @Test
    void announcementsAFailedStepTookBackAreRequeuedOnARuntimeExceptionToo() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        Wire.FrameReady a = write(writer, 11), b = write(writer, 22);
        s.announce(a);
        s.announce(b);
        int[] calls = {0};
        s.readWith((reader, announced) -> {
            if (calls[0]++ == 0) throw new IllegalStateException("test");
            return reader.readLatest(announced, true);
        });
        try {
            s.step(PRESENT, NO_CAMERAS);
            org.junit.jupiter.api.Assertions.fail("thrown");
        } catch (IllegalStateException expected) {
            // the worker reports it
        }
        s.step(PRESENT, NO_CAMERAS);
        assertEquals(22, mark(s), "the newest announcement is read first again, the failing one included");
        s.release();
    }
}
