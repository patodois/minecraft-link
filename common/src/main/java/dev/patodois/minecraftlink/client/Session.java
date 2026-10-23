package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.minecraftlink.Mapping;
import dev.patodois.minecraftlink.PoseTrack;
import dev.patodois.rewritelink.Control;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Link;
import dev.patodois.rewritelink.LinkConfig;
import dev.patodois.rewritelink.LinkEvent;
import dev.patodois.rewritelink.LinkRunner;
import dev.patodois.rewritelink.Wire;
import dev.patodois.rewritelink.Wire.WireException;
import java.io.IOException;
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

    // ---- game thread -> link thread
    private final AtomicReference<Wire.Camera> cameraOut = new AtomicReference<>();
    private final AtomicReference<CollisionOut> collisionOut = new AtomicReference<>();
    private final AtomicReference<Wire.Pose> poseOut = new AtomicReference<>();
    private final AtomicReference<Role> focusOut = new AtomicReference<>();

    // ---- link thread -> game thread
    final Map<Long, Remote> remotes = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Remote> discarded = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
    final AtomicReference<Control.FrameChannel> frameChannel = new AtomicReference<>();
    final AtomicReference<LinkEvent.Frame> latestFrame = new AtomicReference<>();
    volatile LinkEvent.PeerInfo peer;
    volatile Role focus = Role.HOST;
    volatile boolean closed;
    volatile int lastCameraSeq;
    /** Wall-clock ms of the last Frame datagram; while frames are drawn the placeholder block is hidden. */
    volatile long lastFrameMs;

    /** Whether the guest's own frames are on screen: they are arriving and {@code show_frames} lets the host draw them. */
    boolean framesFresh() {
        return settings.showFrames && System.currentTimeMillis() - lastFrameMs < 1000;
    }

    final AtomicLong camerasSent = new AtomicLong();
    final AtomicLong collisionsSent = new AtomicLong();
    final AtomicLong collisionTriangles = new AtomicLong();
    final AtomicLong posesSent = new AtomicLong();
    final AtomicLong posesReceived = new AtomicLong();
    final AtomicLong framesReceived = new AtomicLong();
    final AtomicLong camerasReceived = new AtomicLong();
    final AtomicLong sessionsSeen = new AtomicLong();

    private volatile boolean spawnPending;

    private Session(Role role, LinkSettings settings, Link link, String localName) {
        this.role = role;
        this.settings = settings;
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
        Link link = role == Role.HOST ? Link.host(cfg) : Link.guest(cfg);
        Session s = new Session(role, settings, link, localName);
        s.runner.start();
        return s;
    }

    // ------------------------------------------------------------------ game thread API

    void offerCamera(Wire.Camera c) {
        cameraOut.set(c);
    }

    void offerCollision(CollisionOut c) {
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
                link.sendCollision(col.center(), col.radius(), col.triangles());
                collisionsSent.incrementAndGet();
                collisionTriangles.set(col.triangles().size());
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
            frameChannel.set(null);
            latestFrame.set(null);
            collisionOut.set(null);
            cameraOut.set(null);
            poseOut.set(null);
            focusOut.set(null);
            messages.add("link lost: " + l.reason());
        } else if (e instanceof LinkEvent.HandshakeFailed h) {
            messages.add("handshake failed: " + h.reason());
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
            frameChannel.set(fc.channel());
            messages.add("peer announced a pixel file: " + fc.channel().path() + " " + fc.channel().width() + "x"
                    + fc.channel().height());
        } else if (e instanceof LinkEvent.Frame f) {
            framesReceived.incrementAndGet();
            lastFrameMs = System.currentTimeMillis();
            latestFrame.set(f);
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

    String describe() {
        StringBuilder b = new StringBuilder();
        b.append(role.wire).append(" on 127.0.0.1:");
        b.append(controlPort);
        LinkEvent.PeerInfo p = peer;
        if (p == null) {
            b.append(role == Role.HOST ? ", waiting for a guest" : ", looking for a host");
        } else {
            b.append(", connected to ").append(p.game()).append(" (session ").append(Integer.toHexString(p.session())).append(")");
            b.append(", input focus with the ").append(focus.wire);
        }
        if (role == Role.HOST) {
            b.append("; sent ").append(camerasSent.get()).append(" cameras, ").append(collisionsSent.get())
                    .append(" collision snapshots (").append(collisionTriangles.get()).append(" triangles); received ")
                    .append(posesReceived.get()).append(" poses, ").append(framesReceived.get()).append(" frames; ")
                    .append(remotes.size()).append(" guest entities");
        } else {
            b.append("; sent ").append(posesSent.get()).append(" poses; received ").append(camerasReceived.get()).append(" cameras");
        }
        b.append("; datagrams in/out ").append(link.datagramsReceived).append("/").append(link.datagramsSent)
                .append(", malformed ").append(link.malformed).append(", stale ").append(link.stale).append(", foreign ").append(link.foreign);
        return b.toString();
    }
}
