package dev.patodois.minecraftlink;

import dev.patodois.rewritelink.PixelFile;
import dev.patodois.rewritelink.Wire;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * The guest's frames, from the announcements of the link to the finished image the renderer uploads. It has no OpenGL and no
 * Minecraft in it, so the rules below can be tested with real pixel files.
 *
 * <p>Three kinds of caller: the link thread says what arrived ({@link #channel}, {@link #announce}, {@link #lost}); the frame
 * worker calls {@link #step} over and over (it alone opens, reads and closes the pixel file); the render thread asks for the newest
 * result ({@link #composed}).
 *
 * <p>The rules (link-java 0.3.0, CONTRACT.md 9.6): {@code Frame} announcements are not validated by the link, so the worker reads
 * with {@link PixelFile.Reader#readLatest}, which refuses a duplicate or an older number. A few announcements are kept while the
 * worker is busy, newest first, and the worker tries them in that order until one reads: an old one that arrives late is never
 * preferred to a newer one, and a number that the current file cannot give (a late frame of the previous file, higher than the
 * first frames of the new one) cannot hide a valid frame of the current file. Everything belongs to a <em>generation</em>,
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
        /** The generation (channel, or session) the image belongs to. */
        public final long generation;

        Composed(long version, int width, int height, byte[] rgba, float[] depth, boolean reprojected, long guestTimeUs, long generation) {
            this.version = version;
            this.width = width;
            this.height = height;
            this.rgba = rgba;
            this.depth = depth;
            this.reprojected = reprojected;
            this.guestTimeUs = guestTimeUs;
            this.generation = generation;
        }
    }

    /** The camera the guest rendered with, by the {@code camera_seq} of its frame. */
    public interface Cameras {
        CameraRing.Entry get(int seq);
    }

    private final long maxPixels;
    private volatile Consumer<String> log;
    private final Object lock = new Object();

    /** Where the working arrays of the reprojection come from; tests replace it to make an allocation fail. */
    private volatile IntFunction<float[]> depthArrays = new IntFunction<float[]>() {
        @Override
        public float[] apply(int n) {
            return new float[n];
        }
    };

    void allocateDepthWith(IntFunction<float[]> a) {
        depthArrays = a;
    }

    void clockWith(LongSupplier c) {
        clock = c;
    }

    /** How the pixel file is opened; tests replace it to make the opening fail. */
    interface Opener {
        PixelFile.Reader open(java.nio.file.Path path) throws IOException;
    }

    private volatile Opener opener = new Opener() {
        @Override
        public PixelFile.Reader open(java.nio.file.Path path) throws IOException {
            return PixelFile.Reader.open(path);
        }
    };

    void openWith(Opener o) {
        opener = o;
    }

    /** How an announcement is read from the pixel file; tests replace it to make a read fail. */
    interface Read {
        PixelFile.Frame read(PixelFile.Reader reader, Wire.FrameReady announced);
    }

    private volatile Read read = new Read() {
        @Override
        public PixelFile.Frame read(PixelFile.Reader r, Wire.FrameReady announced) {
            return r.readLatest(announced, true);
        }
    };

    void readWith(Read r) {
        read = r;
    }

    /** How many announcements wait for the worker at most; the oldest are dropped first. */
    static final int PENDING_CAP = 8;

    // ---- guarded by lock
    private long generation;
    private String path;
    private final List<Wire.FrameReady> pending = new ArrayList<Wire.FrameReady>();
    private Composed composed;
    private long version;

    /** How long, after the last published frame, the picture counts as being on screen: the guest that stops sending is gone after this. */
    static final long FRESH_NANOS = 1_000_000_000L;
    /** The monotonic clock of the freshness (nanoseconds); tests replace it. A wall clock that goes back would keep a stale state. */
    private volatile LongSupplier clock = new LongSupplier() {
        @Override
        public long getAsLong() {
            return System.nanoTime();
        }
    };
    private volatile long validAt;
    private volatile boolean hasValid;
    private volatile String note = "";
    private volatile long reprojected;

    // ---- the worker only
    private PixelFile.Reader reader;
    private long readerGeneration = -1;
    private long failedGeneration = -1;
    /** The generation that {@code current} and {@code lastCopied} belong to. */
    private long workGeneration = -1;
    private PixelFile.Frame current;
    /** The number of the last frame copied from the current channel's file: the order of presentation, which outlives the reader. */
    private long lastCopied;
    private boolean hasCopied;
    /** How many times a pixel file was opened (for tests). */
    private int readersOpened;
    /** A frame was copied and has not been published yet: publishing it is what makes the picture fresh. */
    private boolean freshOnPublish;
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
            pending.clear();
            composed = null;
            hasValid = false;
        }
    }

    /** The session ended: the file and its frames belong to a peer that is gone. */
    public void lost() {
        channel(null);
    }

    /** A {@code Frame} datagram: kept among the few newest announcements of the generation, newest first. */
    public void announce(Wire.FrameReady f) {
        synchronized (lock) {
            insert(pending, f, PENDING_CAP);
        }
    }

    /**
     * Puts {@code f} into {@code list}: newest frame number first (compared as unsigned), the earlier arrival first among equal
     * numbers, once, and at most {@code cap}. Only an announcement that is equal in every field is the same one: two for one number
     * that differ in slot or size are both kept, because the file decides which, if either, is real; a late announcement of the
     * previous file must not hide the valid one of the current file.
     */
    static void insert(List<Wire.FrameReady> list, Wire.FrameReady f, int cap) {
        if (list.contains(f)) return; // already waiting
        int at = 0;
        while (at < list.size() && Long.compareUnsigned(list.get(at).frame(), f.frame()) >= 0) at++;
        list.add(at, f);
        while (list.size() > cap) list.remove(list.size() - 1);
    }

    /** Puts announcements that a failed step had taken but not tried back, if the channel is still the one they were announced for. */
    private void requeue(List<Wire.FrameReady> ready, int from, long gen) {
        synchronized (lock) {
            if (generation != gen) return;
            for (int i = from; i < ready.size(); i++) insert(pending, ready.get(i), PENDING_CAP);
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

    /**
     * Whether the guest's picture is on screen: a frame of the current channel was published within the last second. A guest that
     * stays connected and stops sending is not fresh after that, and its picture is not drawn. Measured on a monotonic clock.
     */
    public boolean fresh() {
        return hasValid && clock.getAsLong() - validAt < FRESH_NANOS;
    }

    /** The clock reading of the last publication that made the picture fresh (for tests). */
    long validAt() {
        return validAt;
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

    // ------------------------------------------------------------------ worker

    /**
     * One round of the worker: follow the channel, read the newest announcement that reads, and compose it for {@code present}.
     * A step that fails keeps what it has: the frame already copied stays and is composed again on the next step, and the
     * announcements it had not tried go back to the queue (unless the channel changed meanwhile).
     */
    public void step(Wire.Camera present, Cameras cameras) {
        long gen;
        String want;
        List<Wire.FrameReady> ready;
        synchronized (lock) {
            gen = generation;
            want = path;
            ready = new ArrayList<Wire.FrameReady>(pending);
            pending.clear();
        }
        int tried = 0;
        try {
            if (gen != workGeneration) {
                // A new channel or the loss of the session: what the worker holds belongs to the past, and the numbering starts over.
                workGeneration = gen;
                current = null;
                hasCopied = false;
                freshOnPublish = false;
                composedFor = -1;
            }
            if (gen != readerGeneration) {
                closeReader();
                if (want != null && failedGeneration != gen) openReader(want, gen); // a passing failure is thrown, and this is tried again
                readerGeneration = gen;
            }
            if (reader != null && reader.isGone()) {
                closeReader();
                failedGeneration = gen; // the same file is not opened again; the next channel is
                current = null;
                freshOnPublish = false;
                composedFor = -1;
                dropComposed(gen);
                note = "the pixel file is gone";
                say("the pixel file is gone");
            }
            if (reader != null) {
                // Newest first, until one reads: the ones after it are older and would be refused as stale.
                for (; tried < ready.size(); tried++) {
                    Wire.FrameReady r = ready.get(tried);
                    if (hasCopied && Long.compareUnsigned(r.frame(), lastCopied) <= 0) continue; // not newer than what was copied
                    PixelFile.Frame f = read.read(reader, r);
                    if (f != null) {
                        current = f;
                        currentVersion++;
                        freshOnPublish = true;
                        lastCopied = f.frame(); // only a valid copy sets the order
                        hasCopied = true;
                        tried = ready.size(); // the rest are older
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            requeue(ready, tried, gen);
            throw e;
        } catch (Error e) {
            requeue(ready, tried, gen);
            throw e;
        }
        if (current != null && present != null) {
            int camKey = System.identityHashCode(present);
            // A frame not composed yet is composed; a new camera recomposes the last one only while it is on screen. A guest that stays
            // connected and silent is not drawn, and reprojecting a picture nobody sees at every camera move costs a core and gigabytes.
            if (currentVersion != composedFor || (camKey != composedCamera && fresh())) {
                composedFor = currentVersion;
                composedCamera = camKey;
                try {
                    compose(gen, present, cameras);
                } catch (RuntimeException e) {
                    composedFor = -1; // composed again on the next step
                    throw e;
                } catch (Error e) {
                    composedFor = -1;
                    throw e;
                }
            }
        }
    }

    /** Closes the worker's reader (the worker's last call). */
    public void release() {
        closeReader();
    }

    /**
     * After a failure of a step: open the pixel file again, in case the failure left the reader in doubt. Nothing else is
     * forgotten: the frame copied, the order of presentation and the announcements not tried yet are kept.
     */
    public void recover() {
        closeReader();
        readerGeneration = -1;
    }

    /** How many times a pixel file was opened (for tests). */
    int readersOpened() {
        return readersOpened;
    }

    /** Whether the worker has a pixel file open (for tests). */
    boolean hasReader() {
        return reader != null;
    }

    /**
     * Opens the pixel file of the channel. The channel ends (it is not tried again until the peer announces another) only for a
     * refusal that the contract itself names: a file that fails the checks of section 9.6 ("Opening and creating": not absolute, not
     * a regular file, impossible sizes, ...), one over this mod's pixel limit, or one that does not exist (a peer announces a file it
     * has created, and the receiver skips what it finds gone). Any other failure to open (permissions, descriptors, the disk) may be
     * passing: it is thrown to the worker, which reports it, waits and starts over from this channel.
     */
    private void openReader(String want, long gen) {
        try {
            reader = opener.open(Paths.get(want));
            readersOpened++;
            if ((long) reader.width * reader.height > maxPixels) {
                String why = reader.width + "x" + reader.height + " is more than the " + maxPixels + " pixels this mod takes";
                closeReader();
                throw new PixelFile.FormatException(why);
            }
            note = "pixel file " + reader.width + "x" + reader.height + (reader.hasDepth() ? " with depth" : " colour only");
            say("pixel file " + want + " mapped: " + reader.width + "x" + reader.height + ", " + reader.slots + " slots, depth plane: " + reader.hasDepth());
        } catch (PixelFile.FormatException e) {
            refuse(want, gen, e);
        } catch (NoSuchFileException e) {
            refuse(want, gen, new IOException("no such file"));
        } catch (IOException e) {
            closeReader();
            note = "cannot open the pixel file (trying again): " + e.getMessage();
            say("cannot open the pixel file " + want + ", trying again: " + e);
            throw new java.io.UncheckedIOException(e);
        } catch (java.nio.file.InvalidPathException e) {
            refuse(want, gen, new IOException(e.getMessage())); // a path that can never be opened
        } catch (RuntimeException e) {
            closeReader();
            note = "cannot open the pixel file (trying again): " + e;
            say("cannot open the pixel file " + want + ", trying again: " + e);
            throw e;
        }
    }

    private void refuse(String want, long gen, IOException e) {
        failedGeneration = gen;
        note = "cannot use the pixel file: " + e.getMessage();
        say("cannot use the pixel file " + want + ": " + e);
    }

    private void closeReader() {
        if (reader != null) reader.close();
        reader = null;
    }

    private void dropComposed(long gen) {
        synchronized (lock) {
            if (generation == gen) {
                composed = null;
                hasValid = false;
            }
        }
    }

    private void compose(long gen, Wire.Camera present, Cameras cameras) {
        PixelFile.Frame cur = current;
        CameraRing.Entry rc = cameras.get(cur.cameraSeq());
        int n = cur.width() * cur.height();
        boolean reproject = rc != null && cur.depth() != null;
        Composed c;
        if (reproject) {
            byte[] rgbaOut = outRgba;
            float[] depthOut = outDepth;
            if (rgbaOut == null || rgbaOut.length != n * 4 || depthOut == null || depthOut.length != n) {
                // Both are allocated before either is kept: an OutOfMemoryError between the two must not leave a pair that looks complete.
                rgbaOut = new byte[n * 4];
                depthOut = depthArrays.apply(n);
                outRgba = rgbaOut;
                outDepth = depthOut;
            }
            Reproject.run(cur.rgba(), cur.depth(), cur.width(), cur.height(), rc.view(), rc.proj(), present.view(), present.proj(), cur.width(), cur.height(),
                    rgbaOut, depthOut);
            c = new Composed(0, cur.width(), cur.height(), rgbaOut.clone(), depthOut.clone(), true, cur.timeUs(), gen);
        } else {
            c = new Composed(0, cur.width(), cur.height(), cur.rgba(), cur.depth(), false, cur.timeUs(), gen);
        }
        synchronized (lock) {
            // The reprojection can take long; if the channel changed meanwhile, this image belongs to the past. The check and the
            // publication are one step under the lock that a channel change clears the image with.
            if (generation != gen) return;
            composed = new Composed(++version, c.width, c.height, c.rgba, c.depth, c.reprojected, c.guestTimeUs, gen);
            if (reproject) reprojected++;
            // Fresh only for a frame that was copied since the last publication: a new camera alone is not the guest sending.
            if (freshOnPublish) {
                freshOnPublish = false;
                validAt = clock.getAsLong();
                hasValid = true;
            }
        }
    }
}
