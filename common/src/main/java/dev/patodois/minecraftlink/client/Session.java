package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.CollisionCap;
import dev.patodois.minecraftlink.FrameSource;
import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.minecraftlink.Mapping;
import dev.patodois.minecraftlink.PoseTrack;
import dev.patodois.minecraftlink.TokenAuth;
import dev.patodois.rewritelink.Control;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Endpoint;
import dev.patodois.rewritelink.Link;
import dev.patodois.rewritelink.LinkConfig;
import dev.patodois.rewritelink.LinkEvent;
import dev.patodois.rewritelink.LinkRunner;
import dev.patodois.rewritelink.Wire;
import dev.patodois.rewritelink.Wire.WireException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One running link (host or guest).
 *
 * <p>Threads: the game thread (which is also the render thread in Minecraft 1.20.1: one thread
 * ticks, builds and draws) never calls into {@link Link}; it drops its samples into the
 * {@code AtomicReference}s below ({@link #offerCamera}, {@link #offerCollision}, {@link #offerPose})
 * and reads {@link #remotes} and the counters. The link thread ({@link LinkRunner}, one
 * daemon thread, 2 ms period) polls the link, sends whatever was offered, and puts what arrives into
 * thread-safe holders. A sample not yet sent when a newer one arrives is replaced, as the protocol's
 * "latest wins" rule allows, so nothing ever queues up behind a slow peer.
 */
final class Session {
    /** Collision snapshot ready to send: centre and radius in link space, triangles in link space. */
    record CollisionOut(float[] center, float radius, List<float[]> triangles) {}

    /** A guest entity as seen by the host. */
    static final class Remote {
        final long id;
        volatile String name;
        volatile String asset;
        volatile String provider;
        final PoseTrack track = new PoseTrack();
        /** Game thread only. */
        Object display;
        float lastScaleX = Float.NaN, lastScaleY, lastScaleZ;

        Remote(long id, String provider, String asset, String name) {
            this.id = id;
            this.provider = provider;
            this.asset = asset;
            this.name = name;
        }
    }

    final Role role;
    final LinkSettings settings;
    final Mapping mapping;
    final Link link;
    final dev.patodois.minecraftlink.CameraRing cameraRing = new dev.patodois.minecraftlink.CameraRing(dev.patodois.minecraftlink.CameraRing.DEFAULT_CAPACITY);
    final LinkRunner runner;
    final String localName;
    final long startedMs = System.currentTimeMillis();
    /** The control port the host listens on (or the guest connects to), read once so the game thread never asks the link. */
    final int controlPort;
    /** Contract revision 6: whether this session uses the link token, and the endpoint file it writes (host) or reads (guest). */
    final boolean tokenAuth;
    final Path endpointFile;
    /** Handshakes refused for the token (host: guests turned away; guest: times the host turned us away), and when the player was last told. */
    final AtomicLong unauthorized = new AtomicLong();
    private volatile long lastUnauthorizedNoticeMs;
    /** The player is told about an unauthorized refusal at most this often; a guest retries every half second. */
    static final long UNAUTHORIZED_NOTICE_MS = 30_000;

    // ---- game thread -> link thread
    private final AtomicReference<Wire.Camera> cameraOut = new AtomicReference<>();
    private final AtomicReference<CollisionOut> collisionOut = new AtomicReference<>();
    private final AtomicReference<Wire.Pose> poseOut = new AtomicReference<>();
    private final AtomicReference<Role> focusOut = new AtomicReference<>();

    // ---- link thread -> game thread
    final Map<Long, Remote> remotes = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Remote> discarded = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
    /** The guest's frames, from the announcements to the finished image (see {@link FrameSource}); the frame worker steps it. */
    final FrameSource frames = new FrameSource();
    volatile LinkEvent.PeerInfo peer;
    volatile Role focus = Role.HOST;
    volatile boolean closed;
    volatile int lastCameraSeq;
    /** Whether the guest's own frames are on screen: a frame of the current channel was read lately and {@code show_frames} lets the host draw them. */
    boolean framesFresh() {
        return settings.showFrames && frames.fresh();
    }

    final AtomicLong camerasSent = new AtomicLong();
    final AtomicLong collisionsSent = new AtomicLong();
    final AtomicLong collisionTriangles = new AtomicLong();
    /** Snapshots that had more than {@link CollisionCap#MAX_TRIANGLES} triangles and were cut to the nearest ones (CONTRACT 9.5, revision 5). */
    final AtomicLong collisionsTrimmed = new AtomicLong();
    /** Triangles of the latest trimmed snapshot before and after the cut, and the radius it announced; 0 until one was trimmed. */
    volatile int lastTrimOffered, lastTrimDropped;
    volatile float lastTrimRadius;
    /** Whether the latest snapshot offered had to be cut (the player is told when this turns true, not for every snapshot). */
    private volatile boolean trimming;
    /** Snapshots that link-java refused although the link was up; the reason of the latest one. */
    final AtomicLong collisionsRefused = new AtomicLong();
    volatile String lastRefusal = "";
    final AtomicLong posesSent = new AtomicLong();
    final AtomicLong posesReceived = new AtomicLong();
    final AtomicLong framesReceived = new AtomicLong();
    final AtomicLong camerasReceived = new AtomicLong();
    final AtomicLong sessionsSeen = new AtomicLong();

    private volatile boolean spawnPending;

    private Session(Role role, LinkSettings settings, Link link, String localName, Path endpointFile) {
        this.role = role;
        this.settings = settings;
        this.tokenAuth = endpointFile != null;
        this.endpointFile = endpointFile;
        this.mapping = settings.mapping();
        this.link = link;
        this.controlPort = role == Role.HOST ? link.controlPort() : settings.port;
        this.localName = localName;
        this.runner = new LinkRunner(link, this::onEvent, this::tick, 2);
    }

    static Session open(Role role, LinkSettings settings, String localName) throws IOException {
        LinkConfig cfg = new LinkConfig("Minecraft Java " + net.minecraft.SharedConstants.getCurrentVersion().getName());
        cfg.controlPort = settings.port;
        cfg.udpPort = settings.udpPort;
        cfg.heartbeatMs = settings.heartbeatMs;
        cfg.timeoutMs = settings.timeoutMs;
        Path endpointFile = null;
        if (settings.tokenAuth) {
            // link-java makes the token (host), writes the file after binding and removes it on close; a guest reads the file before each
            // attempt. This class never sees the token.
            endpointFile = settings.endpointPath();
            cfg.endpointFile = endpointFile;
            if (role == Role.HOST) TokenAuth.prepareDirectory(endpointFile);
        }
        Link link = role == Role.HOST ? Link.host(cfg) : Link.guest(cfg);
        Session s = new Session(role, settings, link, localName, endpointFile);
        s.runner.start();
        return s;
    }

    // ------------------------------------------------------------------ game thread API

    void offerCamera(Wire.Camera c) {
        cameraOut.set(c);
    }

    /**
     * Queues a collision snapshot for the link thread. One over {@link CollisionCap#MAX_TRIANGLES} triangles is cut here to
     * the triangles nearest to its centre (the contract's rule for a sender) and the player is told, in the log and in the chat; it
     * is never handed to link-java whole, because link-java throws for it and {@link #tick} would take that for a lost link. Runs on the
     * caller's thread (the collision worker), which does the sort, not on the link thread.
     */
    void offerCollision(CollisionOut c) {
        CollisionCap.Result r = CollisionCap.limit(c.triangles(), c.center(), c.radius());
        if (r.trimmed()) {
            c = new CollisionOut(c.center(), r.radius, r.triangles);
            collisionsTrimmed.incrementAndGet();
            lastTrimOffered = r.offered;
            lastTrimDropped = r.dropped;
            lastTrimRadius = r.radius;
            if (!trimming) {
                trimming = true;
                messages.add("collision snapshot too big: " + r.offered + " triangles, the contract allows " + CollisionCap.MAX_TRIANGLES
                        + " (revision 5). Sending the " + CollisionCap.MAX_TRIANGLES + " nearest to you and dropping " + r.dropped
                        + "; it is complete within " + String.format(java.util.Locale.ROOT, "%.1f", r.radius) + " m of you, nothing is claimed beyond. "
                        + "A smaller collision_radius (now " + settings.collisionRadius + ") avoids this.");
            }
        } else {
            trimming = false;
        }
        collisionOut.set(c);
    }

    void offerPose(Wire.Pose p) {
        poseOut.set(p);
    }

    boolean isConnected() {
        return peer != null;
    }

    /** Whether Minecraft currently has the player's input: the focus is with our role, or there is no session. */
    boolean minecraftHasInput() {
        return peer == null || focus == role;
    }

    /**
     * Asks (guest) or gives (host) the input focus. Never touches {@link Link} (its monitor is held by the link thread's poll
     * and by {@code close}): the request is queued and the link thread sends it on its next tick.
     */
    void setFocus(Role to) {
        if (role == Role.HOST) focus = to;
        focusOut.set(to);
    }

    // ------------------------------------------------------------------ link thread

    private void tick() {
        if (closed || !link.isConnected()) return;
        try {
            if (spawnPending) {
                spawnPending = false;
                link.spawn(new Control.Spawn(settings.guestEntity, settings.guestProvider, settings.guestAsset, localName));
            }
            Role wantFocus = focusOut.getAndSet(null);
            if (wantFocus != null) link.setFocus(wantFocus);
            Wire.Camera cam = cameraOut.getAndSet(null);
            if (cam != null && role == Role.HOST) {
                lastCameraSeq = link.sendCamera(cam);
                cameraRing.put(new dev.patodois.minecraftlink.CameraRing.Entry(lastCameraSeq, cam.view(), cam.proj(), cam.near(), cam.far()));
                camerasSent.incrementAndGet();
            }
            CollisionOut col = collisionOut.getAndSet(null);
            if (col != null && role == Role.HOST) {
                try {
                    link.sendCollision(col.center(), col.radius(), col.triangles());
                    collisionsSent.incrementAndGet();
                    collisionTriangles.set(col.triangles().size());
                } catch (WireException e) {
                    // Not connected any more is the poll's business (Lost). With the link up, the library refused the snapshot
                    // itself: say so, once per distinct reason, instead of dropping it silently.
                    if (!link.isConnected()) throw e;
                    collisionsRefused.incrementAndGet();
                    String why = String.valueOf(e.getMessage());
                    if (!why.equals(lastRefusal)) {
                        lastRefusal = why;
                        messages.add("collision snapshot not sent: " + why);
                    }
                }
            }
            Wire.Pose pose = poseOut.getAndSet(null);
            if (pose != null && role == Role.GUEST) {
                link.sendPoses(List.of(pose));
                posesSent.incrementAndGet();
            }
        } catch (WireException e) {
            // Not connected any more; the poll that follows reports Lost.
        }
    }

    private void onEvent(LinkEvent e) {
        if (e instanceof LinkEvent.Connected c) {
            peer = c.peer();
            focus = Role.HOST; // the host has the focus when a session starts
            sessionsSeen.incrementAndGet();
            if (role == Role.GUEST) spawnPending = true;
            messages.add("connected to " + c.peer().game() + " (" + c.peer().role().wire + ", protocol v" + c.peer().protocol() + ")");
        } else if (e instanceof LinkEvent.Lost l) {
            peer = null;
            focus = Role.HOST;
            for (Remote r : remotes.values()) discarded.add(r);
            remotes.clear();
            frames.lost();
            collisionOut.set(null);
            cameraOut.set(null);
            poseOut.set(null);
            focusOut.set(null);
            messages.add("link lost: " + l.reason());
        } else if (e instanceof LinkEvent.HandshakeFailed h) {
            if (TokenAuth.isUnauthorized(h.reason())) {
                noticeUnauthorized();
            } else {
                messages.add("handshake failed: " + h.reason());
            }
        } else if (e instanceof LinkEvent.Spawn s) {
            Control.Spawn sp = s.spawn();
            if (role == Role.HOST) {
                Remote old = remotes.put(sp.entity(), new Remote(sp.entity(), sp.provider(), sp.asset(), sp.name()));
                if (old != null) discarded.add(old);
                messages.add("guest spawned entity " + sp.entity() + " (" + sp.provider() + " " + sp.asset() + ")");
            }
        } else if (e instanceof LinkEvent.Despawn d) {
            Remote r = remotes.remove(d.entity());
            if (r != null) discarded.add(r);
        } else if (e instanceof LinkEvent.Poses p) {
            posesReceived.addAndGet(p.poses().size());
            if (role == Role.HOST) {
                for (Wire.Pose pose : p.poses()) {
                    Remote r = remotes.get(pose.entity());
                    if (r != null) r.track.add(pose);
                }
            }
        } else if (e instanceof LinkEvent.Camera) {
            camerasReceived.incrementAndGet();
        } else if (e instanceof LinkEvent.Focus f) {
            if (f.requested()) {
                messages.add("the guest asks for the input focus (/link focus guest to give it)");
            } else {
                focus = f.focus();
                messages.add("input focus is now with the " + f.focus().wire);
            }
        } else if (e instanceof LinkEvent.FrameChannel fc) {
            frames.channel(fc.channel().path());
            messages.add("peer announced a pixel file: " + fc.channel().path() + " " + fc.channel().width() + "x"
                    + fc.channel().height());
        } else if (e instanceof LinkEvent.Frame f) {
            framesReceived.incrementAndGet();
            frames.announce(f.frame());
        }
    }

    /** A refused token (CONTRACT 6.2): counted every time, told to the player now and then. The text never holds a token. */
    private void noticeUnauthorized() {
        unauthorized.incrementAndGet();
        long now = System.currentTimeMillis();
        if (lastUnauthorizedNoticeMs != 0 && now - lastUnauthorizedNoticeMs < UNAUTHORIZED_NOTICE_MS) return;
        lastUnauthorizedNoticeMs = now;
        if (role == Role.HOST) {
            messages.add("refused a guest as unauthorized: it sent no token or a wrong one");
        } else if (tokenAuth) {
            messages.add("the host refused this guest as unauthorized: it did not accept the token in the endpoint file; the file is read again before each try");
        } else {
            messages.add("the host refused this guest as unauthorized: it wants a token. Set token_auth to true (and endpoint_file to the host's file) in config/minecraft-link.json");
        }
    }

    /** Guest with a token: the control port the endpoint file names right now, or -1 with the reason in {@code endpointProblem}. */
    private volatile String endpointProblem = "";

    private int guestPortFromEndpoint() {
        try {
            int p = Endpoint.read(endpointFile).controlPort();
            endpointProblem = "";
            return p;
        } catch (IOException | RuntimeException e) {
            endpointProblem = String.valueOf(e.getMessage()); // link-java's messages never quote the file; none holds the token
            return -1;
        }
    }

    // ------------------------------------------------------------------ closing

    /** Says despawn (guest) and bye, half-closes and drains; blocks for up to a fraction of a second. Call off the game thread. */
    void closeBlocking() {
        if (closed) return;
        closed = true;
        try {
            if (role == Role.GUEST && link.isConnected()) link.despawn(settings.guestEntity);
        } catch (WireException ignored) {
            // already gone
        }
        runner.close();
    }

    /** What {@code /link status} says about the collision size limit: it is never silent that triangles were left out. */
    String collisionLimitNote() {
        StringBuilder b = new StringBuilder();
        b.append("limit ").append(CollisionCap.MAX_TRIANGLES);
        long t = collisionsTrimmed.get();
        if (t == 0) {
            b.append(", none cut");
        } else {
            b.append(", ").append(t).append(" cut to the nearest triangles, latest ").append(lastTrimOffered).append(" -> ")
                    .append(lastTrimOffered - lastTrimDropped).append(" (").append(lastTrimDropped).append(" dropped, complete within ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", lastTrimRadius)).append(" m)");
            if (trimming) b.append(", cutting now; lower collision_radius, now ").append(settings.collisionRadius);
        }
        long r = collisionsRefused.get();
        if (r > 0) b.append(", ").append(r).append(" refused by link-java: ").append(lastRefusal);
        return b.toString();
    }

    String describe() {
        StringBuilder b = new StringBuilder();
        b.append(role.wire).append(" on 127.0.0.1:");
        int shownPort = controlPort;
        if (tokenAuth && role == Role.GUEST) {
            int fromFile = guestPortFromEndpoint();
            if (fromFile > 0) shownPort = fromFile;
        }
        b.append(shownPort);
        if (tokenAuth) b.append(", token auth on");
        LinkEvent.PeerInfo p = peer;
        if (p == null) {
            b.append(role == Role.HOST ? ", waiting for a guest" : ", looking for a host");
            if (tokenAuth && role == Role.GUEST && !endpointProblem.isEmpty()) b.append(" (endpoint file: ").append(endpointProblem).append(")");
        } else {
            b.append(", connected to ").append(p.game()).append(" (session ").append(Integer.toHexString(p.session())).append(")");
            b.append(", input focus with the ").append(focus.wire);
        }
        if (role == Role.HOST) {
            b.append("; sent ").append(camerasSent.get()).append(" cameras, ").append(collisionsSent.get())
                    .append(" collision snapshots (").append(collisionTriangles.get()).append(" triangles, ").append(collisionLimitNote())
                    .append("); received ")
                    .append(posesReceived.get()).append(" poses, ").append(framesReceived.get()).append(" frames; ")
                    .append(remotes.size()).append(" guest entities");
        } else {
            b.append("; sent ").append(posesSent.get()).append(" poses; received ").append(camerasReceived.get()).append(" cameras");
        }
        if (unauthorized.get() > 0) b.append("; ").append(unauthorized.get()).append(" refused as unauthorized");
        b.append("; datagrams in/out ").append(link.datagramsReceived).append("/").append(link.datagramsSent)
                .append(", malformed ").append(link.malformed).append(", stale ").append(link.stale).append(", foreign ").append(link.foreign)
                .append(", unknown_entity ").append(link.unknownEntity).append(", spawn_ignored ").append(link.spawnIgnored);
        return b.toString();
    }
}
