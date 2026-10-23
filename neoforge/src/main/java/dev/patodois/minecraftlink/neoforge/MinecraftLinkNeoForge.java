package dev.patodois.minecraftlink.neoforge;

import dev.patodois.minecraftlink.client.LinkCommands;
import dev.patodois.minecraftlink.client.LinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** NeoForge entry point: wires the loader's events into the common code. Client only. */
@Mod(value = LinkMod.MOD_ID, dist = Dist.CLIENT)
public final class MinecraftLinkNeoForge {
    public MinecraftLinkNeoForge(IEventBus modBus, ModContainer container) {
        LinkMod.init(FMLPaths.CONFIGDIR.get(), "NeoForge");
        NeoForge.EVENT_BUS.addListener(MinecraftLinkNeoForge::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(MinecraftLinkNeoForge::onClientTick);
        NeoForge.EVENT_BUS.addListener(MinecraftLinkNeoForge::onAfterLevel);
        NeoForge.EVENT_BUS.addListener(MinecraftLinkNeoForge::onShutdown);
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(LinkCommands.<CommandSourceStack>build(
                (source, message) -> source.sendSuccess(() -> message, false)));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        LinkMod.onClientTick(Minecraft.getInstance());
    }

    /**
     * Fired once per frame, inside GameRenderer#renderLevel right after the level has been drawn into the
     * main render target and before the hand and the GUI, with no render pass open. That is the spot Fabric's
     * END_MAIN gives the common code: the camera of this frame is sampled first, then the guest's frame is
     * drawn where it is nearer than the world.
     */
    private static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        Minecraft mc = Minecraft.getInstance();
        LinkMod.onLevelStart(mc, event.getLevelRenderState().cameraRenderState);
        LinkMod.onLevelEnd(mc);
    }

    private static void onShutdown(GameShuttingDownEvent event) {
        LinkMod.onClientStopping();
    }
}
