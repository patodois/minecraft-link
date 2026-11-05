package dev.patodois.minecraftlink;

/**
 * Decides whether a collision snapshot is worth offering to the link, so that one that has not changed is not sent again
 * and again. An unchanged snapshot is repeated every {@code refreshMs} (a peer that missed it recovers), and always sent to a
 * peer that has not been given one yet: the memory of what was sent belongs to the link session it was sent in, because a
 * guest that leaves and a new one that joins the same host share nothing but the geometry.
 *
 * <p>The scanner's worker thread asks and records; the game thread resets. Knows nothing about Minecraft.
 */
public final class CollisionSendGate {
    private long lastHash;
    private long lastSentMs;
    /** The session in which the last snapshot was offered, or -1 for none. */
    private long lastSession = -1;

    /** Whether a snapshot with this hash, built at {@code nowMs} while link session number {@code session} is current, is to be offered. */
    public synchronized boolean due(long hash, long nowMs, long session, long refreshMs) {
        return session != lastSession || hash != lastHash || nowMs - lastSentMs >= refreshMs;
    }

    /** Records that the snapshot was offered. */
    public synchronized void sent(long hash, long nowMs, long session) {
        lastHash = hash;
        lastSentMs = nowMs;
        lastSession = session;
    }

    /** Forgets what was sent: the next snapshot is offered. */
    public synchronized void reset() {
        lastHash = 0;
        lastSentMs = 0;
        lastSession = -1;
    }
}
