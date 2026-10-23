package dev.patodois.minecraftlink.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.patodois.rewritelink.Control.Role;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** {@code /link host|guest [port]}, {@code /link stop}, {@code /link status}, {@code /link focus host|guest}. */
final class LinkCommands {
    private LinkCommands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommands.literal("link")
                .executes(c -> say(c, LinkManager.INSTANCE.status()))
                .then(ClientCommands.literal("host")
                        .executes(c -> say(c, LinkManager.INSTANCE.start(Role.HOST, null)))
                        .then(ClientCommands.argument("port", IntegerArgumentType.integer(1, 65535))
                                .executes(c -> say(c, LinkManager.INSTANCE.start(Role.HOST, IntegerArgumentType.getInteger(c, "port"))))))
                .then(ClientCommands.literal("guest")
                        .executes(c -> say(c, LinkManager.INSTANCE.start(Role.GUEST, null)))
                        .then(ClientCommands.argument("port", IntegerArgumentType.integer(1, 65535))
                                .executes(c -> say(c, LinkManager.INSTANCE.start(Role.GUEST, IntegerArgumentType.getInteger(c, "port"))))))
                .then(ClientCommands.literal("stop").executes(c -> say(c, LinkManager.INSTANCE.stop())))
                .then(ClientCommands.literal("status").executes(c -> say(c, LinkManager.INSTANCE.status())))
                .then(ClientCommands.literal("focus")
                        .then(ClientCommands.literal("host").executes(c -> say(c, LinkManager.INSTANCE.focus(Role.HOST))))
                        .then(ClientCommands.literal("guest").executes(c -> say(c, LinkManager.INSTANCE.focus(Role.GUEST))))));
    }

    private static int say(CommandContext<FabricClientCommandSource> c, String text) {
        LinkManager.LOG.info("/link: {}", text);
        c.getSource().sendFeedback(Component.literal("[link] " + text));
        return 1;
    }
}
