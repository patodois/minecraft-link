package dev.patodois.minecraftlink.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The mod entry point. Client side only: a server (or a client connecting to a server without the mod) does not need it.
 *
 * <p>Hooks, all plain Forge events (no coremod, no mixin, no access transformer):
 * <ul>
 *   <li>{@code ClientCommandHandler}: {@code /link ...};</li>
 *   <li>{@code ClientTickEvent} (end): the collision scan, the guest pose, the input focus, messages;</li>
 *   <li>{@code RenderWorldLastEvent}: after the world is drawn and before the hand and the GUI: camera, placeholder, guest frame.</li>
 * </ul>
 */
@Mod(modid = MinecraftLinkMod.MODID, name = "Rewrite Link for Minecraft", version = "0.1.0", clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public final class MinecraftLinkMod {
    public static final String MODID = "minecraft_link";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (event.getSide() != Side.CLIENT) return;
        LinkManager m = LinkManager.INSTANCE;
        m.loadSettings();
        ClientCommandHandler.instance.registerCommand(new LinkCommand());
        MinecraftForge.EVENT_BUS.register(this);
        // The game ends with System.exit: close the link politely (bye) before the JVM goes.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                m.shutdown();
            }
        }, "minecraft-link-shutdown"));
        AutoTest.maybeStart();
        LinkManager.LOG.info("Minecraft Link loaded; commands: /link host, /link guest, /link stop, /link status, /link focus <host|guest>");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) LinkManager.INSTANCE.onClientTick(Minecraft.getMinecraft());
    }

    @SubscribeEvent
    public void onWorldLast(RenderWorldLastEvent e) {
        LinkManager.INSTANCE.onWorldRendered(Minecraft.getMinecraft(), e.getPartialTicks());
    }
}
