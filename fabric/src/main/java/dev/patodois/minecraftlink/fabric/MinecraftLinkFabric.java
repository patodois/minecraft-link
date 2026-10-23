package dev.patodois.minecraftlink.fabric;

import dev.patodois.minecraftlink.client.LinkCommands;
import dev.patodois.minecraftlink.client.LinkMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Fabric entrypoint: wires the loader's events into the common code. Quilt loads this same class through its Fabric compatibility. */
public final class MinecraftLinkFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LinkMod.init(FabricLoader.getInstance().getConfigDir(), "Fabric");
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(LinkCommands.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>build(
						(source, message) -> source.sendFeedback(message))));
		ClientTickEvents.END_CLIENT_TICK.register(LinkMod::onClientTick);
		LevelRenderEvents.START_MAIN.register(ctx -> LinkMod.onLevelStart(Minecraft.getInstance(), ctx.levelState().cameraRenderState));
		LevelRenderEvents.END_MAIN.register(ctx -> LinkMod.onLevelEnd(Minecraft.getInstance()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LinkMod.onClientStopping());
	}
}
