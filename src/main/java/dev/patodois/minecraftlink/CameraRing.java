package dev.patodois.minecraftlink;

/**
 * The cameras sent to the guest, keyed by the {@code seq} of the datagram that carried them
 * (CONTRACT.md 9.7). A frame names the camera it was rendered with by that number, so the host can
 * look it up and reproject the frame to the camera it presents now. The contract asks for at least half a
 * second; the capacity is sized by time ({@link #capacityFor}), not by a frame count that only fits one
 * frame rate: {@link #DEFAULT_CAPACITY} covers a second at 240 fps.
 */
public final class CameraRing {
    /** A camera as sent: world to view and view to clip, column-major, link space. */
    public static final class Entry {
        private final int seq;
        private final float[] view;
        private final float[] proj;
        private final float near;
        private final float far;

        public Entry(int seq, float[] view, float[] proj, float near, float far) {
            this.seq = seq;
            this.view = view;
            this.proj = proj;
            this.near = near;
            this.far = far;
        }

        public int seq() {
            return seq;
        }

        public float[] view() {
            return view;
        }

        public float[] proj() {
            return proj;
        }

        public float near() {
            return near;
        }

        public float far() {
            return far;
        }
    }

    /** One second at 240 fps (twice the contract's half second), rounded up to a power of two. */
    public static final int DEFAULT_CAPACITY = capacityFor(1.0, 240);

    private final Entry[] ring;
    private int next;

    public CameraRing(int capacity) {
        ring = new Entry[capacity];
    }

    /** Entries needed to keep {@code seconds} of cameras sent at {@code fps} per second, rounded up to a power of two. */
    public static int capacityFor(double seconds, double fps) {
        double n = Math.ceil(seconds * fps);
        if (!(n >= 1)) return 1;
        if (n > (1 << 20)) return 1 << 20;
        int c = 1;
        while (c < n) c <<= 1;
        return c;
    }

    public synchronized void put(Entry e) {
        ring[next] = e;
        next = (next + 1) % ring.length;
    }

    /** The entry with this {@code seq}, or null (never sent, or too old). {@code seq == 0} means "no camera". */
    public synchronized Entry get(int seq) {
        if (seq == 0) return null;
        for (Entry e : ring) {
            if (e != null && e.seq == seq) return e;
        }
        return null;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        next = 0;
    }
}
