package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FrameWorkerTest {
    private static final Wire.Camera PRESENT = Wire.Camera.lookAt(new float[] {0, 1, 0}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 64, 48);
    private static final FrameSource.Cameras NO_CAMERAS = seq -> null;

    private Path dir;
    private Path file;
    private PixelFile.Writer writer;

    @BeforeEach
    void files() throws Exception {
        dir = Files.createTempDirectory("frameworker");
        file = dir.resolve("a.frames");
        writer = new PixelFile.Writer(file, 2, 1, 4, false);
    }

    @AfterEach
    void cleanUp() throws Exception {
        writer.close();
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }

    private Wire.FrameReady write(PixelFile.Writer w, int mark) {
        long frame = w.write(new byte[] {(byte) mark, 0, 0, (byte) 255, (byte) mark, 0, 0, (byte) 255}, null, 2, 1, 0, 1000L + mark);
        return new Wire.FrameReady(frame, (int) (frame % w.slots), 0, 2, 1);
    }

    private static void until(String what, java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(2);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static int mark(FrameSource s) {
        FrameSource.Composed c = s.composed();
        return c == null ? -1 : c.rgba[0];
    }

    @Test
    void aWorkerThatIsStoppedWhileBusyStaysStoppedWhenAnotherIsStarted() throws Exception {
        FrameSource first = new FrameSource();
        first.channel(file.toString());
        first.announce(write(writer, 11));
        CountDownLatch inside = new CountDownLatch(1), go = new CountDownLatch(1);
        FrameSource.Cameras slow = seq -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // an interrupt must not be what stops the worker
            }
            return null;
        };
        FrameWorker a = new FrameWorker(first, () -> PRESENT, slow, e -> { }).start();
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the first worker is busy");
        a.stop();
        FrameWorker b = new FrameWorker(new FrameSource(), () -> PRESENT, NO_CAMERAS, e -> { }).start();
        go.countDown();
        a.join(5000);
        assertFalse(a.isAlive(), "the first worker ended; the second one's start did not revive it");
        assertTrue(b.isAlive());
        b.stop();
        b.join(5000);
        assertFalse(b.isAlive());
    }

    @Test
    void aWorkerStoppedBeforeItStartsEndsAtOnce() throws Exception {
        FrameWorker w = new FrameWorker(new FrameSource(), () -> PRESENT, NO_CAMERAS, e -> { });
        w.stop();
        w.start();
        w.join(5000);
        assertFalse(w.isAlive(), "starting does not undo a stop");
    }

    @Test
    void aStopIsNotAnInterruptAndReportsNoProblemWithTheFile() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        CountDownLatch inside = new CountDownLatch(1), go = new CountDownLatch(1);
        // The worker is between steps (asking for the camera) when the stop comes; the step that follows must still be sound.
        // An interrupt would make its next FileChannel call close the pixel file, and the worker would report the file as gone.
        FrameWorker w = new FrameWorker(s, () -> {
            inside.countDown();
            // parkNanos returns on an interrupt and leaves the interrupt flag set, as a thread busy in plain code would have it
            long end = System.nanoTime() + 10_000_000_000L;
            while (go.getCount() > 0 && System.nanoTime() < end) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            return PRESENT;
        }, NO_CAMERAS, e -> { }).start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        w.stop();
        go.countDown();
        w.join(5000);
        assertFalse(w.isAlive());
        assertFalse(s.note().contains("gone"), s.note());
        assertFalse(s.note().contains("cannot use"), s.note());
        assertEquals(11, mark(s), "the step that was under way completed normally");
    }

    @Test
    void theWorkerClosesItsPixelFileWhenItEnds() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        s.announce(write(writer, 11));
        FrameWorker w = new FrameWorker(s, () -> PRESENT, NO_CAMERAS, e -> { }).start();
        until("the frame", () -> s.composed() != null);
        w.stop();
        w.join(5000);
        assertFalse(w.isAlive());
        assertFalse(s.hasReader(), "the reader is closed");
    }

    @Test
    void aFailureIsReportedAndTheWorkerGoesOnToTheNextChannel() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        AtomicInteger calls = new AtomicInteger();
        // the first frame makes the camera lookup fail with an Error, as an OutOfMemoryError in the reprojection would
        FrameSource.Cameras failing = seq -> {
            if (calls.getAndIncrement() == 0) throw new OutOfMemoryError("test");
            return null;
        };
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        FrameWorker w = new FrameWorker(s, () -> PRESENT, failing, failures::add).start();
        s.announce(write(writer, 11));
        until("the failure to be reported", () -> !failures.isEmpty());
        assertTrue(failures.get(0) instanceof OutOfMemoryError, "an Error is reported too: " + failures);
        // a RuntimeException after it, and then a new channel is followed
        Path other = dir.resolve("b.frames");
        PixelFile.Writer w2 = new PixelFile.Writer(other, 2, 1, 4, false);
        try {
            s.channel(other.toString());
            s.announce(write(w2, 44));
            until("the new channel's frame", () -> mark(s) == 44);
        } finally {
            w.stop();
            w.join(5000);
            w2.close();
            Files.deleteIfExists(other);
        }
        assertFalse(w.isAlive());
    }

    @Test
    void aFailureThatKeepsComingIsNotAHotLoop() throws Exception {
        FrameSource s = new FrameSource();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        FrameWorker w = new FrameWorker(s, () -> {
            throw new IllegalStateException("always");
        }, NO_CAMERAS, failures::add).start();
        Thread.sleep(700);
        w.stop();
        w.join(5000);
        assertFalse(failures.isEmpty(), "reported");
        assertTrue(failures.size() <= 4, "the pauses grow: " + failures.size() + " reports in 0.7 s");
        assertNull(s.composed());
        assertNotNull(failures.get(0));
    }

    @Test
    void theFrameCopiedBeforeAFailureIsComposedAgainAfterThePause() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        AtomicInteger calls = new AtomicInteger();
        FrameSource.Cameras failing = seq -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("test");
            return null;
        };
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        FrameWorker w = new FrameWorker(s, () -> PRESENT, failing, failures::add).start();
        s.announce(write(writer, 11)); // the guest sends no other frame
        until("the failure to be reported", () -> !failures.isEmpty());
        until("the frame, composed again", () -> mark(s) == 11);
        w.stop();
        w.join(5000);
        assertEquals(1, failures.size());
        assertEquals(2, s.readersOpened(), "the pixel file was opened again after the failure");
    }

    @Test
    void aFailureOfTheSameFrameThatKeepsComingStaysOnThePauses() throws Exception {
        FrameSource s = new FrameSource();
        s.channel(file.toString());
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        FrameWorker w = new FrameWorker(s, () -> PRESENT, seq -> {
            throw new IllegalStateException("always");
        }, failures::add).start();
        s.announce(write(writer, 11));
        Thread.sleep(700);
        w.stop();
        w.join(5000);
        assertTrue(failures.size() >= 2 && failures.size() <= 4, "composed again after each pause, not in a loop: " + failures.size());
        assertNull(s.composed());
    }

    @Test
    void thePausesGrowToACeilingAndStartOverAfterAStepThatWentWell() throws Exception {
        assertEquals(250, FrameWorker.nextPause(0));
        assertEquals(500, FrameWorker.nextPause(250));
        assertEquals(4000, FrameWorker.nextPause(2000));
        assertEquals(5000, FrameWorker.nextPause(4000));
        assertEquals(5000, FrameWorker.nextPause(5000), "a ceiling");
        // the worker: fail, fail, go well, fail: 250, 500, then the normal pause, then 250 again (the failures start over)
        List<Long> pauses = new CopyOnWriteArrayList<>();
        AtomicInteger steps = new AtomicInteger();
        FrameWorker[] self = new FrameWorker[1];
        FrameWorker w = new FrameWorker(new FrameSource(), () -> {
            int n = steps.getAndIncrement();
            if (n == 0 || n == 1 || n == 3) throw new IllegalStateException("fails at " + n);
            return PRESENT;
        }, NO_CAMERAS, e -> { }, ms -> {
            pauses.add(ms);
            return pauses.size() < 4; // end after the fourth pause
        });
        w.start();
        w.join(5000);
        assertEquals(java.util.Arrays.asList(250L, 500L, FrameWorker.STEP_PAUSE_MS, 250L), pauses);
    }

    @Test
    void aReportThatThrowsDoesNotEndTheWorker() throws Exception {
        List<Long> pauses = new CopyOnWriteArrayList<>();
        AtomicInteger reports = new AtomicInteger();
        AtomicInteger steps = new AtomicInteger();
        FrameWorker w = new FrameWorker(new FrameSource(), () -> {
            steps.incrementAndGet();
            throw new IllegalStateException("always");
        }, NO_CAMERAS, e -> {
            reports.incrementAndGet();
            throw new IllegalStateException("the report itself fails");
        }, ms -> {
            pauses.add(ms);
            return pauses.size() < 3;
        });
        w.start();
        w.join(5000);
        assertEquals(3, steps.get(), "the worker went on after a report that threw");
        assertEquals(3, reports.get());
    }

    @Test
    void aStepThatWentWellIsFollowedByTheShortPauseNotAHotLoop() throws Exception {
        List<Long> pauses = new CopyOnWriteArrayList<>();
        FrameWorker w = new FrameWorker(new FrameSource(), () -> PRESENT, NO_CAMERAS, e -> { }, ms -> {
            pauses.add(ms);
            return pauses.size() < 3;
        });
        w.start();
        w.join(5000);
        assertEquals(java.util.Arrays.asList(FrameWorker.STEP_PAUSE_MS, FrameWorker.STEP_PAUSE_MS, FrameWorker.STEP_PAUSE_MS), pauses);
        assertTrue(FrameWorker.STEP_PAUSE_MS >= 1 && FrameWorker.STEP_PAUSE_MS <= 5, "a short pause: a frame waits at most a few milliseconds for the worker");
    }
}
