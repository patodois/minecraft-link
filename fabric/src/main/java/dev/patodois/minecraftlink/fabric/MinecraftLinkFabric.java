package dev.patodois.minecraftlink.fabric;

import dev.patodois.minecraftlink.client.LinkCommands;
import dev.patodois.minecraftlink.client.LinkMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Fabric entrypoint: wires the loader's events into the common code. Quilt loads this same class through its Fabric compatibility. */
public final class MinecraftLinkFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LinkMod.init(FabricLoader.getInstance().getConfigDir(), "Fabric");
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(LinkCommands.<FabricClientCommandSource>build((source, message) -> source.sendFeedback(message))));
		ClientTickEvents.END_CLIENT_TICK.register(LinkMod::onClientTick);
		// Start of the level render: before the entities are drawn, so the guest placeholders are placed for this frame.
		WorldRenderEvents.START.register(ctx -> LinkMod.onLevelStart(Minecraft.getInstance()));
		// After the world (blocks, entities, translucency, weather) and before the hand and the GUI: the main target holds the
		// finished world and its depth, and the context has the camera and the projection with view bobbing.
		WorldRenderEvents.LAST.register(ctx -> LinkMod.onLevelEnd(Minecraft.getInstance(), ctx.camera(), ctx.projectionMatrix()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LinkMod.onClientStopping());
	}
}
