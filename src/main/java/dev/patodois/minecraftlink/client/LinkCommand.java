package dev.patodois.minecraftlink.client;

import dev.patodois.rewritelink.Control.Role;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import javax.annotation.Nullable;

/**
 * {@code /link host|guest [port]}, {@code /link stop}, {@code /link status}, {@code /link focus host|guest}.
 * Registered with Forge's {@code ClientCommandHandler}: it runs in the client and the text is never sent to a server.
 */
final class LinkCommand extends CommandBase {
    @Override
    public String getName() {
        return "link";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/link host|guest [port] | stop | status | focus host|guest";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        LinkManager m = LinkManager.INSTANCE;
        String out;
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "host":
                out = m.start(Role.HOST, port(args, 1));
                break;
            case "guest":
                out = m.start(Role.GUEST, port(args, 1));
                break;
            case "stop":
                out = m.stop();
                break;
            case "status":
                out = m.status();
                break;
            case "focus":
                if (args.length < 2 || !(args[1].equals("host") || args[1].equals("guest"))) {
                    out = "usage: /link focus host|guest";
                } else {
                    out = m.focus(args[1].equals("guest") ? Role.GUEST : Role.HOST);
                }
                break;
            default:
                out = getUsage(sender);
                break;
        }
        say(sender, out);
    }

    /** The optional port argument, 1..65535, or null. */
    private static Integer port(String[] args, int i) throws CommandException {
        if (args.length <= i) return null;
        return Integer.valueOf(parseInt(args[i], 1, 65535));
    }

    static void say(ICommandSender sender, String text) {
        LinkManager.LOG.info("/link: {}", text);
        sender.sendMessage(new TextComponentString("[link] " + text));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "host", "guest", "stop", "status", "focus");
        if (args.length == 2 && args[0].equalsIgnoreCase("focus")) return getListOfStringsMatchingLastWord(args, "host", "guest");
        return Collections.emptyList();
    }
}
