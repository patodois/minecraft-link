package dev.patodois.minecraftlink.client;

import dev.patodois.minecraftlink.LinkSettings;
import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Wire.WireException;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the (at most one) running {@link Session} and everything that follows the player's world. Game thread only, except where noted. */
public final class LinkManager {
    static final Logger LOG = LoggerFactory.getLogger("minecraft-link");
    public static final LinkManager INSTANCE = new LinkManager();

    LinkSettings settings = new LinkSettings();
    private volatile Session session;
    final CollisionScanner scanner = new CollisionScanner();
    final RemoteEntities remoteEntities = new RemoteEntities();
    final GuestPublisher guestPublisher = new GuestPublisher();
    final InputGate inputGate = new InputGate();
    final FrameOverlay frames = new FrameOverlay();

    private LinkManager() {}

    Session session() {
        return session;
    }

    private Path configFile = Path.of("minecraft-link.json");

    void setConfigFile(Path file) {
        configFile = file;
    }

    void loadSettings() {
        settings = LinkSettings.load(configFile, LOG::warn);
    }

    /** {@code /link host} / {@code /link guest}. Returns a line for the player. */
    String start(Role role, Integer port) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "join a world first";
        if (session != null) return "already running (" + session.describe() + "); /link stop first";
        loadSettings();
        if (port != null) settings.port = port;
        try {
            session = Session.open(role, settings, mc.getUser().getName());
        } catch (IOException e) {
            LOG.warn("could not start the link", e);
            return "could not start the " + role.wire + ": " + e.getMessage();
        }
        scanner.reset();
        if (role == Role.HOST && settings.showFrames) frames.start(session);
        guestPublisher.reset();
        LOG.info("link started: {}", session.describe());
        String warning = loopbackWarning();
        if (warning != null) LOG.warn(warning);
        return "link " + session.describe() + (warning == null ? "" : " [!] " + warning);
    }

    /**
     * link-java binds and connects to {@code InetAddress.getLoopbackAddress()}, and the protocol needs 127.0.0.1. A JVM started with
     * {@code -Djava.net.preferIPv6Addresses=system} (what some loaders' launch profiles pass) makes that {@code ::1}, so the other
     * program, which uses 127.0.0.1, never finds us. Returns a warning in that case, null otherwise.
     */
    static String loopbackWarning() {
        byte[] a = java.net.InetAddress.getLoopbackAddress().getAddress();
        if (a.length == 4) return null;
        return "this JVM's loopback address is " + java.net.InetAddress.getLoopbackAddress().getHostAddress() + ", not 127.0.0.1, so a peer on 127.0.0.1 cannot connect;"
                + " add -Djava.net.preferIPv6Addresses=false to the game's JVM arguments";
    }

    /** Stops the session without blocking the game thread: the bye and the drain happen on a helper thread. */
    String stop() {
        Session s = session;
        if (s == null) return "not running";
        session = null;
        finish(s, false);
        return "stopping the link (bye, then half-close)";
    }

    private void finish(Session s, boolean wait) {
        inputGate.release();
        remoteEntities.discardAll(s);
        frames.close();
        Thread t = new Thread(() -> {
            s.closeBlocking();
            LOG.info("link closed ({} cameras, {} collision snapshots, {} poses sent)", s.camerasSent.get(), s.collisionsSent.get(), s.posesSent.get());
        }, "minecraft-link-close");
        t.setDaemon(true);
        t.start();
        if (wait) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Game exit: close and wait a moment so the peer gets its bye. */
    public void shutdown() {
        Session s = session;
        if (s == null) return;
        session = null;
        finish(s, true);
    }

    String status() {
        Session s = session;
        if (s == null) return "not running (config role: " + settings.role + ", port " + settings.port + ")";
        String f = s.role == Role.HOST ? "; frames drawn " + frames.framesShown + ", reprojected " + frames.framesReprojected
                + (frames.note.isEmpty() ? "" : " (" + frames.note + ")") + ", host depth convention " + CameraSampler.hostDepthKind
                + (CameraSampler.lastRewritten ? ", camera depth_range rewritten to " + CameraSampler.lastDepthRange : ", camera depth_range " + CameraSampler.lastDepthRange) : "";
        return s.describe() + f;
    }

    String focus(Role to) {
        Session s = session;
        if (s == null) return "not running";
        if (!s.isConnected()) return "no peer yet";
        try {
            s.setFocus(to);
        } catch (WireException e) {
            return "not connected";
        }
        return s.role == Role.HOST ? "input focus given to the " + to.wire : "asked the host for the input focus";
    }

    // ------------------------------------------------------------------ events (game thread)

    public void onClientTick(Minecraft mc) {
        Session s = session;
        if (s == null) return;
        if (mc.level == null || mc.player == null) {
            // left the world: end the session politely
            session = null;
            LOG.info("left the world, closing the link");
            finish(s, false);
            return;
        }
        for (String m; (m = s.messages.poll()) != null; ) {
            LOG.info("{}", m);
            mc.player.sendSystemMessage(Component.literal("[link] " + m));
        }
        inputGate.tick(mc, s);
        if (s.role == Role.HOST) {
            scanner.tick(mc, s);
        } else {
            guestPublisher.tick(mc, s);
        }
    }

    /** Called on the render thread at the start of every frame's extraction, before entities are extracted. */
    public void beforeExtract(Minecraft mc) {
        Session s = session;
        if (s == null || s.role != Role.HOST || mc.level == null) return;
        remoteEntities.update(mc, s);
        frames.upload(mc, s);
    }

    /** Called on the render thread once per drawn frame, with the camera Minecraft is about to render with. */
    public void onLevelStart(Minecraft mc, net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        Session s = session;
        if (s == null || s.role != Role.HOST || !s.isConnected()) return;
        CameraSampler.sample(mc, s, camera);
    }

    /** Render thread, end of the level's main pass. */
    public void onLevelEnd(Minecraft mc) {
        Session s = session;
        if (s == null || s.role != Role.HOST || !s.isConnected()) return;
        frames.draw(mc, s);
    }
}
