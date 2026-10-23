package dev.patodois.minecraftlink.client;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * The loader-neutral entry surface of the mod. Each loader module (Fabric, NeoForge, Forge) does
 * four things and nothing else: calls {@link #init} once, forwards the client tick, the two
 * level-render points and the game-stopping event to the methods below, and builds the
 * {@code /link} command tree with {@link LinkCommands#build}. Everything else, including the
 * renderer mixin, lives in this common code.
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

    /** Start of the level's main render pass, with the camera state the game is about to draw with. */
    public static void onLevelStart(Minecraft mc, CameraRenderState camera) {
        LinkManager.INSTANCE.onLevelStart(mc, camera);
    }

    /** End of the level's main render pass. */
    public static void onLevelEnd(Minecraft mc) {
        LinkManager.INSTANCE.onLevelEnd(mc);
    }

    /** The game is closing: end a running link politely. */
    public static void onClientStopping() {
        LinkManager.INSTANCE.shutdown();
    }
}
