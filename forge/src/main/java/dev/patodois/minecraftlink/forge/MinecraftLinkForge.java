package dev.patodois.minecraftlink.forge;

import dev.patodois.minecraftlink.client.LinkCommands;
import dev.patodois.minecraftlink.client.LinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge 1.20.1 entry point: wires the loader's events into the common code. Client only. (NeoForge 1.20.1 keeps the same package
 * names; see the neoforge module for its copy of this class.)
 */
@Mod(LinkMod.MOD_ID)
public final class MinecraftLinkForge {
    public MinecraftLinkForge() {
        if (FMLEnvironment.dist != Dist.CLIENT) return; // the jar may sit in a server's mods folder; it does nothing there
        LinkMod.init(FMLPaths.CONFIGDIR.get(), "Forge");
        MinecraftForge.EVENT_BUS.addListener(MinecraftLinkForge::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(MinecraftLinkForge::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(MinecraftLinkForge::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(MinecraftLinkForge::onShutdown);
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(LinkCommands.<CommandSourceStack>build(
                (source, message) -> source.sendSuccess(() -> message, false)));
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) LinkMod.onClientTick(Minecraft.getInstance());
    }

    /**
     * AFTER_SKY comes before the entities are drawn (the guest placeholders are placed for this frame); AFTER_LEVEL is the end of
     * the level render, after the world and before the hand and the GUI: the main target holds the finished world and its depth,
     * and the event has the camera, the pose stack's rotation and the projection with view bobbing.
     */
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            LinkMod.onLevelStart(Minecraft.getInstance());
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            LinkMod.onLevelEnd(Minecraft.getInstance(), event.getCamera(), event.getProjectionMatrix());
        }
    }

    private static void onShutdown(net.minecraftforge.event.GameShuttingDownEvent event) {
        LinkMod.onClientStopping();
    }
}
