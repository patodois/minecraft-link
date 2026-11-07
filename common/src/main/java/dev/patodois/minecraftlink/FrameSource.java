package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * The guest's frames, from the announcements of the link to the finished image the renderer uploads. It has no OpenGL and no
 * Minecraft in it, so the rules below can be tested with real pixel files.
 *
 * <p>Three kinds of caller: the link thread says what arrived ({@link #channel}, {@link #announce}, {@link #lost}); the frame
 * worker calls {@link #step} over and over (it alone opens, reads and closes the pixel file); the render thread asks for the newest
 * result ({@link #composed}).
 *
 * <p>The rules (link-java 0.3.0, CONTRACT.md 9.6): {@code Frame} announcements are not validated by the link, so the worker reads
 * with {@link PixelFile.Reader#readLatest}, which refuses a duplicate or an older number, and only the newest announcement is kept
 * while the worker is busy (an old one that arrives late must not replace a newer one). Everything belongs to a <em>generation</em>,
 * which a new {@code frame_channel} or the loss of the session starts: the announcement, the reader and the finished image of an
 * earlier generation are dropped, and a worker that was in the middle of a reprojection when the generation changed cannot publish
 * its result (the check is made under the same lock that clears the image). The image is also dropped when the reader reports that
 * the file is gone.
 */
public final class FrameSource {
    /** The most pixels per frame this mod takes: the reprojection runs on the CPU and keeps several copies of the frame. */
    public static final long MAX_PIXELS = 4L << 20;

    /** A finished frame in the present camera's view: same size as the guest image. */
    public static final class Composed {
        public final long version;
        public final int width, height;
        public final byte[] rgba;
        public final float[] depth;
        public final boolean reprojected;
        public final long guestTimeUs;

        Composed(long version, int width, int height, byte[] rgba, float[] depth, boolean reprojected, long guestTimeUs) {
            this.version = version;
            this.width = width;
            this.height = height;
            this.rgba = rgba;
            this.depth = depth;
            this.reprojected = reprojected;
            this.guestTimeUs = guestTimeUs;
        }
    }

    /** The camera the guest rendered with, by the {@code camera_seq} of its frame. */
    public interface Cameras {
        CameraRing.Entry get(int seq);
    }

    private final long maxPixels;
    private volatile Consumer<String> log;
    private final Object lock = new Object();

    // ---- guarded by lock
    private long generation;
    private String path;
    private Wire.FrameReady pending;
    private Composed composed;
    private long version;
    private boolean closed;

    private volatile long lastValidMs;
    private volatile String note = "";
    private volatile long reprojected;

    // ---- the worker only
    private PixelFile.Reader reader;
    private long readerGeneration = -1;
    private long failedGeneration = -1;
    private PixelFile.Frame current;
    private long currentVersion, composedFor = -1;
    private int composedCamera;
    private byte[] outRgba;
    private float[] outDepth;

    public FrameSource() {
        this(MAX_PIXELS, null);
    }

    /** {@code log} gets a line when a pixel file is mapped, refused or gone (may be null). */
    public FrameSource(long maxPixels, Consumer<String> log) {
        this.maxPixels = maxPixels;
        this.log = log;
    }

    /** Where the lines about the pixel file go (the mod's log); may be null. */
    public void setLog(Consumer<String> log) {
        this.log = log;
    }

    private void say(String line) {
        Consumer<String> l = log;
        if (l != null) l.accept(line);
    }

    // ------------------------------------------------------------------ link thread

    /** A {@code frame_channel}: a new pixel file, so everything of the old one is dropped (its frames are numbered from 1 again). */
    public void channel(String newPath) {
        synchronized (lock) {
            generation++;
            path = newPath;
            pending = null;
            composed = null;
        }
        lastValidMs = 0;
    }

    /** The session ended: the file and its frames belong to a peer that is gone. */
    public void lost() {
        channel(null);
    }

    /** A {@code Frame} datagram. Only the newest announcement of the generation is kept: an older one arriving late is dropped. */
    public void announce(Wire.FrameReady f) {
        synchronized (lock) {
            if (pending == null || Long.compareUnsigned(f.frame(), pending.frame()) > 0) pending = f;
        }
    }

    // ------------------------------------------------------------------ render thread and others

    /** The newest finished frame of the current generation, or null (none yet, or it was dropped). */
    public Composed composed() {
        synchronized (lock) {
            return composed;
        }
    }

    /** The generation now: a new channel or the loss of the session starts the next one. */
    public long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** Wall-clock ms of the last frame that was copied from the current channel's file, or 0: what keeps the guest's picture on screen. */
    public long lastValidMs() {
        return lastValidMs;
    }

    public String note() {
        return note;
    }

    /** Sets the note {@code /link status} shows (the worker says why it stopped). */
    public void note(String text) {
        note = text;
    }

    public long framesReprojected() {
        return reprojected;
    }

    /** Stops publishing for good (the session is closed). */
    public void close() {
        synchronized (lock) {
            closed = true;
            pending = null;
            composed = null;
        }
        lastValidMs = 0;
    }

    // ------------------------------------------------------------------ worker

    /** One round of the worker: follow the channel, read the newest announcement, and compose it for {@code present}. */
    public void step(Wire.Camera present, Cameras cameras) {
        long gen;
        String want;
        Wire.FrameReady ready;
        synchronized (lock) {
            gen = generation;
            want = path;
            ready = pending;
            pending = null;
        }
        if (gen != readerGeneration) {
            closeReader();
            readerGeneration = gen;
            if (want != null && failedGeneration != gen) openReader(want, gen);
        }
        if (reader != null && reader.isGone()) {
            closeReader();
            failedGeneration = gen; // the same file is not opened again; the next channel is
            dropComposed(gen);
            note = "the pixel file is gone";
            say("the pixel file is gone");
        }
        if (ready != null && reader != null) {
            PixelFile.Frame f = reader.readLatest(ready, true);
            if (f != null) {
                current = f;
                currentVersion++;
                lastValidMs = System.currentTimeMillis();
                synchronized (lock) {
                    if (generation != gen) lastValidMs = 0; // the channel changed while this was read
                }
            }
        }
        if (current != null && present != null) {
            int camKey = System.identityHashCode(present);
            if (currentVersion != composedFor || camKey != composedCamera) {
                composedFor = currentVersion;
                composedCamera = camKey;
                compose(gen, present, cameras);
            }
        }
    }

    /** Closes the worker's reader (the worker's last call). */
    public void release() {
        closeReader();
    }

    private void openReader(String want, long gen) {
        try {
            reader = PixelFile.Reader.open(Paths.get(want));
            if ((long) reader.width * reader.height > maxPixels) {
                String why = reader.width + "x" + reader.height + " is more than the " + maxPixels + " pixels this mod takes";
                closeReader();
                throw new IOException(why);
            }
            note = "pixel file " + reader.width + "x" + reader.height + (reader.hasDepth() ? " with depth" : " colour only");
            say("pixel file " + want + " mapped: " + reader.width + "x" + reader.height + ", " + reader.slots + " slots, depth plane: " + reader.hasDepth());
        } catch (IOException e) {
            failedGeneration = gen;
            note = "cannot use the pixel file: " + e.getMessage();
            say("cannot use the pixel file " + want + ": " + e);
        } catch (RuntimeException e) {
            failedGeneration = gen;
            closeReader();
            note = "cannot use the pixel file: " + e.getMessage();
            say("cannot use the pixel file " + want + ": " + e);
        }
    }

    private void closeReader() {
        if (reader != null) reader.close();
        reader = null;
        current = null;
        composedFor = -1;
    }

    private void dropComposed(long gen) {
        synchronized (lock) {
            if (generation == gen) composed = null;
        }
        lastValidMs = 0;
    }

    private void compose(long gen, Wire.Camera present, Cameras cameras) {
        PixelFile.Frame cur = current;
        CameraRing.Entry rc = cameras.get(cur.cameraSeq());
        int n = cur.width() * cur.height();
        boolean reproject = rc != null && cur.depth() != null;
        Composed c;
        if (reproject) {
            if (outRgba == null || outRgba.length != n * 4) {
                outRgba = new byte[n * 4];
                outDepth = new float[n];
            }
            Reproject.run(cur.rgba(), cur.depth(), cur.width(), cur.height(), rc.view(), rc.proj(), present.view(), present.proj(), cur.width(), cur.height(),
                    outRgba, outDepth);
            c = new Composed(0, cur.width(), cur.height(), outRgba.clone(), outDepth.clone(), true, cur.timeUs());
        } else {
            c = new Composed(0, cur.width(), cur.height(), cur.rgba(), cur.depth(), false, cur.timeUs());
        }
        synchronized (lock) {
            // The reprojection can take long; if the channel changed meanwhile, or the source was closed, this image belongs to the past.
            if (generation != gen || closed) return;
            composed = new Composed(++version, c.width, c.height, c.rgba, c.depth, c.reprojected, c.guestTimeUs);
            if (reproject) reprojected++;
        }
    }
}
