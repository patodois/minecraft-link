package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.Wire;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The thread that steps a {@link FrameSource}. Each worker has its own stop flag, so a worker that is still busy (inside a
 * reprojection, say) when another one is started is stopped for good: a flag shared by every worker, set back to false by the
 * next start, would let the old one run on beside the new one.
 */
public final class FrameWorker {
    private final FrameSource source;
    private final Supplier<Wire.Camera> present;
    private final FrameSource.Cameras cameras;
    private final Consumer<RuntimeException> onFailure;
    private volatile boolean stop;
    private final Thread thread;

    public FrameWorker(FrameSource source, Supplier<Wire.Camera> present, FrameSource.Cameras cameras, Consumer<RuntimeException> onFailure) {
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

    /** Asks this worker, and only this one, to end after the step it is in. */
    public void stop() {
        stop = true;
        thread.interrupt();
    }

    public boolean isAlive() {
        return thread.isAlive();
    }

    /** Waits up to {@code ms} for the thread to end. */
    public void join(long ms) throws InterruptedException {
        thread.join(ms);
    }

    private void loop() {
        try {
            while (!stop) {
                source.step(present.get(), cameras);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            onFailure.accept(e);
        } finally {
            source.release();
        }
    }
}
