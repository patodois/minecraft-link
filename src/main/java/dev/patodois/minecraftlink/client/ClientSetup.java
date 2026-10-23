package dev.patodois.minecraftlink.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/** Client-only wiring: only loaded on the client side. */
public final class ClientSetup {
    private ClientSetup() {}

    static void init() {
        LinkManager m = LinkManager.INSTANCE;
        m.loadSettings();
        ClientCommandHandler.instance.registerCommand(new LinkCommand());
        FMLCommonHandler.instance().bus().register(new TickHandler());
        MinecraftForge.EVENT_BUS.register(new RenderHandler());
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

    /** On the FML event bus. */
    public static final class TickHandler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase == TickEvent.Phase.END) LinkManager.INSTANCE.onClientTick(Minecraft.getMinecraft());
        }
    }

    /** On the Forge event bus. */
    public static final class RenderHandler {
        @SubscribeEvent
        public void onWorldLast(RenderWorldLastEvent e) {
            LinkManager.INSTANCE.onWorldRendered(Minecraft.getMinecraft(), e.partialTicks);
        }
    }
}
