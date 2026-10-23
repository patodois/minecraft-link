package dev.patodois.minecraftlink.forge;

import com.mojang.blaze3d.framegraph.FramePass;
import dev.patodois.minecraftlink.client.LinkCommands;
import dev.patodois.minecraftlink.client.LinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point: wires the loader's events into the common code. Client only. */
@Mod(LinkMod.MOD_ID)
public final class MinecraftLinkForge {
    public MinecraftLinkForge(FMLJavaModLoadingContext context) {
        if (!FMLEnvironment.dist.isClient()) return; // the jar may sit in a server's mods folder; it does nothing there
        LinkMod.init(FMLPaths.CONFIGDIR.get(), "Forge");
        RegisterClientCommandsEvent.BUS.addListener(MinecraftLinkForge::onRegisterCommands);
        TickEvent.ClientTickEvent.Post.BUS.addListener(MinecraftLinkForge::onClientTick);
        AddFramePassEvent.BUS.addListener(MinecraftLinkForge::onAddFramePass);
        GameShuttingDownEvent.BUS.addListener(MinecraftLinkForge::onShutdown);
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(LinkCommands.<CommandSourceStack>build(
                (source, message) -> source.sendSuccess(() -> message, false)));
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        LinkMod.onClientTick(Minecraft.getInstance());
    }

    /**
     * Forge has no level-stage event; its render hook is a frame-graph pass. The pass is added once, runs every frame, and
     * binds to the main render target, which the frame graph orders after the main pass (Forge inserts its passes after the
     * vanilla ones). While the graph is built ({@code extracts}) the camera of the frame is sampled, the equivalent of
     * Fabric's START_MAIN; when it executes, with no render pass open, the guest's frame is drawn where it is nearer than
     * the world, the equivalent of END_MAIN.
     */
    private static void onAddFramePass(AddFramePassEvent event) {
        event.addPass(Identifier.fromNamespaceAndPath(LinkMod.MOD_ID, "guest_frame"), new FramePassManager.PassDefinition() {
            @Override
            public void extracts(LevelTargetBundle bundle, FramePass pass, LevelRenderState state) {
                bundle.main = pass.readsAndWrites(bundle.main);
                LinkMod.onLevelStart(Minecraft.getInstance(), state.cameraRenderState);
            }

            @Override
            public void executes(LevelRenderState state) {
                LinkMod.onLevelEnd(Minecraft.getInstance());
            }
        });
    }

    private static void onShutdown(GameShuttingDownEvent event) {
        LinkMod.onClientStopping();
    }
}
