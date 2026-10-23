package dev.patodois.minecraftlink.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public final class MinecraftLinkClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LinkManager m = LinkManager.INSTANCE;
		m.loadSettings();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> LinkCommands.register(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(m::onClientTick);
		LevelRenderEvents.START_MAIN.register(ctx -> m.onLevelStart(net.minecraft.client.Minecraft.getInstance(), ctx.levelState().cameraRenderState));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> m.shutdown());
		AutoTest.maybeStart();
		LinkManager.LOG.info("Minecraft Link loaded; commands: /link host, /link guest, /link stop, /link status, /link focus <host|guest>");
	}
}
