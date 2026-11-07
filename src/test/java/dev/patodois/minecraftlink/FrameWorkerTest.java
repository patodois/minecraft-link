package dev.patodois.minecraftlink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FrameWorkerTest {
    private static final Wire.Camera PRESENT = Wire.Camera.lookAt(new float[] {0, 1, 0}, new float[] {0, 1, -1}, (float) Math.toRadians(70), 0.1f, 100f, 64, 48);

    @Test
    void aWorkerThatIsStoppedWhileBusyStaysStoppedWhenAnotherIsStarted() throws Exception {
        Path dir = Files.createTempDirectory("frameworker");
        Path file = dir.resolve("a.frames");
        PixelFile.Writer w = new PixelFile.Writer(file, 2, 1, 4, false);
        try {
            FrameSource first = new FrameSource();
            first.channel(file.toString());
            long f = w.write(new byte[8], null, 2, 1, 0, 1L);
            first.announce(new Wire.FrameReady(f, (int) (f % w.slots), 0, 2, 1));
            CountDownLatch inside = new CountDownLatch(1), go = new CountDownLatch(1);
            FrameSource.Cameras slow = seq -> {
                inside.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // the stop request interrupts the worker; it must stop anyway when it comes back
                }
                return null;
            };
            FrameWorker a = new FrameWorker(first, () -> PRESENT, slow, e -> { }).start();
            assertTrue(inside.await(10, TimeUnit.SECONDS), "the first worker is busy");
            // the session ends and another begins while the first is still inside its step
            a.stop();
            FrameWorker b = new FrameWorker(new FrameSource(), () -> PRESENT, seq -> null, e -> { }).start();
            go.countDown();
            a.join(5000);
            assertFalse(a.isAlive(), "the first worker ended; the second one's start did not revive it");
            assertTrue(b.isAlive());
            b.stop();
            b.join(5000);
            assertFalse(b.isAlive());
        } finally {
            w.close();
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
