package dev.patodois.minecraftlink.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.patodois.rewritelink.Control.Role;
import net.minecraft.network.chat.Component;

/**
 * {@code /link host|guest [port]}, {@code /link stop}, {@code /link status}, {@code /link focus host|guest}.
 *
 * <p>The tree is plain Brigadier and generic over the command source type, so each loader registers
 * it on its own client command dispatcher and says how a line of feedback reaches the player.
 */
public final class LinkCommands {
    private LinkCommands() {}

    /** How a loader shows a line to the player who ran the command. */
    @FunctionalInterface
    public interface Feedback<S> {
        void send(S source, Component message);
    }

    public static <S> LiteralArgumentBuilder<S> build(Feedback<S> feedback) {
        LinkManager m = LinkManager.INSTANCE;
        return LiteralArgumentBuilder.<S>literal("link")
                .executes(c -> say(c, feedback, m.status()))
                .then(LiteralArgumentBuilder.<S>literal("host")
                        .executes(c -> say(c, feedback, m.start(Role.HOST, null)))
                        .then(LinkCommands.<S>port().executes(c -> say(c, feedback, m.start(Role.HOST, IntegerArgumentType.getInteger(c, "port"))))))
                .then(LiteralArgumentBuilder.<S>literal("guest")
                        .executes(c -> say(c, feedback, m.start(Role.GUEST, null)))
                        .then(LinkCommands.<S>port().executes(c -> say(c, feedback, m.start(Role.GUEST, IntegerArgumentType.getInteger(c, "port"))))))
                .then(LiteralArgumentBuilder.<S>literal("stop").executes(c -> say(c, feedback, m.stop())))
                .then(LiteralArgumentBuilder.<S>literal("status").executes(c -> say(c, feedback, m.status())))
                .then(LiteralArgumentBuilder.<S>literal("focus")
                        .then(LiteralArgumentBuilder.<S>literal("host").executes(c -> say(c, feedback, m.focus(Role.HOST))))
                        .then(LiteralArgumentBuilder.<S>literal("guest").executes(c -> say(c, feedback, m.focus(Role.GUEST)))));
    }

    private static <S> RequiredArgumentBuilder<S, Integer> port() {
        return RequiredArgumentBuilder.argument("port", IntegerArgumentType.integer(1, 65535));
    }

    private static <S> int say(CommandContext<S> c, Feedback<S> feedback, String text) {
        LinkManager.LOG.info("/link: {}", text);
        feedback.send(c.getSource(), Component.literal("[link] " + text));
        return 1;
    }
}
