package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.Wire;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The thread that steps a {@link FrameSource}.
 *
 * <ul>
 *   <li>Each worker has its own stop flag, so a worker that is still busy (inside a reprojection, say) when another one is
 *       started is stopped for good: a flag shared by every worker, set back to false by the next start, would let the old one
 *       run on beside the new one.</li>
 *   <li>A stop is a flag, never an interrupt: the pixel file is read through a {@code FileChannel}, which an interrupt closes, and
 *       the worker would then report a problem with a file that is fine. The loop looks at the flag every couple of milliseconds.</li>
 *   <li>A failure of a step, an {@link Error} such as an {@link OutOfMemoryError} included, is reported to {@code onFailure} and the
 *       worker goes on: it starts over from the current channel after a pause that grows from 250 ms to 5 s while the failures
 *       continue, so a persistent one is neither a hot loop nor the end of the guest's frames.</li>
 * </ul>
 */
public final class FrameWorker {
    static final long FIRST_PAUSE_MS = 250, LONGEST_PAUSE_MS = 5000;

    private final FrameSource source;
    private final Supplier<Wire.Camera> present;
    private final FrameSource.Cameras cameras;
    private final Consumer<Throwable> onFailure;
    private volatile boolean stop;
    private final Thread thread;

    public FrameWorker(FrameSource source, Supplier<Wire.Camera> present, FrameSource.Cameras cameras, Consumer<Throwable> onFailure) {
        this.source = source;
        this.present = present;
        this.cameras = cameras;
        this.onFailure = onFailure;
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "minecraft-link-frames");
        this.thread.setDaemon(true);
    }

    public FrameWorker start() {
        thread.start();
        return this;
    }

    /** Asks this worker, and only this one, to end after the step it is in. Does not interrupt it. */
    public void stop() {
        stop = true;
    }

    public boolean isAlive() {
        return thread.isAlive();
    }

    /** Waits up to {@code ms} for the thread to end. */
    public void join(long ms) throws InterruptedException {
        thread.join(ms);
    }

    private void loop() {
        long pause = 0;
        try {
            while (!stop) {
                try {
                    source.step(present.get(), cameras);
                    pause = 0;
                } catch (Throwable e) {
                    try {
                        onFailure.accept(e);
                    } catch (Throwable ignored) {
                        // reporting must not end the worker either
                    }
                    try {
                        source.recover();
                    } catch (Throwable ignored) {
                        // nothing more to do: the next step tries again
                    }
                    pause = pause == 0 ? FIRST_PAUSE_MS : Math.min(pause * 2, LONGEST_PAUSE_MS);
                    if (!sleep(pause)) break;
                    continue;
                }
                if (!sleep(2)) break;
            }
        } finally {
            source.release();
        }
    }

    /** Sleeps {@code ms} in short slices, so that a stop is seen at once; false when the worker should end (stopped or interrupted from outside). */
    private boolean sleep(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        try {
            do {
                if (stop) return false;
                Thread.sleep(Math.min(ms, 10));
            } while (System.nanoTime() < end);
        } catch (InterruptedException e) {
            return false;
        }
        return !stop;
    }
}
