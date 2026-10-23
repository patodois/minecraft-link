package dev.patodois.minecraftlink.client;

import java.nio.file.Path;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/**
 * The loader-neutral entry surface of the mod. Each loader module (Fabric, Forge, NeoForge) does
 * four things and nothing else: calls {@link #init} once, forwards the client tick, the two
 * level-render points and the game-stopping event to the methods below, and builds the
 * {@code /link} command tree with {@link LinkCommands#build}. Everything else lives in this common code;
 * there is no mixin.
 */
public final class LinkMod {
    public static final String MOD_ID = "minecraft_link";

    private LinkMod() {}

    /** Call once at client start-up. {@code configDir} is the loader's config directory. */
    public static void init(Path configDir, String loaderName) {
        LinkManager m = LinkManager.INSTANCE;
        m.setConfigFile(configDir.resolve("minecraft-link.json"));
        m.loadSettings();
        AutoTest.maybeStart();
        LinkManager.LOG.info("Minecraft Link loaded on {}; commands: /link host, /link guest, /link stop, /link status, /link focus <host|guest>", loaderName);
    }

    /** End of every client tick. */
    public static void onClientTick(Minecraft mc) {
        LinkManager.INSTANCE.onClientTick(mc);
        AutoTest.tick(mc);
    }

    /**
     * Start of the level render, before the entities are drawn (Forge: {@code RenderLevelStageEvent} AFTER_SKY, Fabric:
     * {@code WorldRenderEvents.START}): places the guest entities for this frame at frame rate instead of the 20 Hz tick.
     */
    public static void onLevelStart(Minecraft mc) {
        LinkManager.INSTANCE.beforeEntities(mc);
    }

    /**
     * End of the level render, after the world and before the hand and the GUI (Forge: AFTER_LEVEL, Fabric:
     * {@code WorldRenderEvents.LAST}), with the camera the game drew the level with and the projection matrix it used, which
     * includes view bobbing and damage tilt.
     */
    public static void onLevelEnd(Minecraft mc, Camera camera, Matrix4f projection) {
        LinkManager.INSTANCE.onLevelEnd(mc, camera, projection);
    }

    /** The game is closing: end a running link politely. */
    public static void onClientStopping() {
        LinkManager.INSTANCE.shutdown();
    }
}
