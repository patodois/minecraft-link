package dev.patodois.minecraftlink.client;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * The mod entry point (Minecraft 1.7.10, Forge 10.13.4.1614). This class touches nothing of the client, so a dedicated
 * server that has the jar in its mods folder loads it and does nothing; on the client it hands over to
 * {@link ClientSetup}. A client with the mod can join any server: {@code acceptableRemoteVersions = "*"}.
 *
 * <p>Hooks, all plain Forge events and client commands (no coremod, no mixin, no access transformer):
 * <ul>
 *   <li>{@code ClientCommandHandler}: {@code /link ...};</li>
 *   <li>{@code ClientTickEvent} (end): the collision scan, the guest pose, the input focus, messages;</li>
 *   <li>{@code RenderWorldLastEvent}: after the world is drawn and before the hand and the GUI: camera, placeholder, guest frame.</li>
 * </ul>
 */
@Mod(modid = MinecraftLinkMod.MODID, name = "Rewrite Link for Minecraft", version = "0.1.2", acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*")
public final class MinecraftLinkMod {
    public static final String MODID = "minecraft_link";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide() == Side.CLIENT) ClientSetup.init();
    }
}
