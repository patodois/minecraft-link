package dev.patodois.minecraftlink;

/**
 * The cameras sent to the guest, keyed by the {@code seq} of the datagram that carried them
 * (CONTRACT.md 9.7). A frame names the camera it was rendered with by that number, so the host can
 * look it up and reproject the frame to the camera it presents now. 32 entries cover at least half a
 * second at 60 Hz; this ring keeps 64.
 */
public final class CameraRing {
    /** A camera as sent: world to view and view to clip, column-major, link space. */
    public record Entry(int seq, float[] view, float[] proj, float near, float far) {}

    private final Entry[] ring;
    private int next;

    public CameraRing(int capacity) {
        ring = new Entry[capacity];
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
