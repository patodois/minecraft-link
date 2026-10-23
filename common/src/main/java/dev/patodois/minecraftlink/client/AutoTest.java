package dev.patodois.minecraftlink.client;

import dev.patodois.rewritelink.Control.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development aid, inactive unless the JVM property {@code minecraftlink.autotest} names a script.
 * It drives the real client through a scripted run (create a flat world, run link commands, move,
 * take screenshots) so the mod can be tested without a person at the keyboard. It uses only the
 * game's own screens and commands and writes only to the run directory and to
 * {@code minecraftlink.shots}. Script lines, one per line ({@code #} starts a comment):
 *
 * <pre>
 *   create_world            create a new creative Superflat world with commands on and open it (the game's own world-creation call, no screen)
 *   wait_world              wait until the player is in the world and the first chunks are there
 *   wait N                  wait N client ticks
 *   cmd /gamemode creative  send a chat command as the player (leading slash optional)
 *   link host [port]        same as /link host
 *   link guest [port]
 *   link stop | status | focus host | focus guest
 *   key forward down|up     press or release a movement key (forward back left right jump)
 *   look YAW PITCH          set the player's view
 *   hud on|off              show or hide the HUD and chat (F1), so screenshots show only the world
 *   shot NAME               save NAME.png into minecraftlink.shots
 *   log TEXT                write a line to the log
 *   leave                   leave the world to the title screen
 *   quit                    close the game
 * </pre>
 */
final class AutoTest {
    private static List<String> lines;
    private static int index;
    private static int waitTicks;
    private static int stage;
    private static boolean worldRequested;
    private static Path shots;

    static void maybeStart() {
        String script = System.getProperty("minecraftlink.autotest");
        if (script == null || script.isBlank()) return;
        try {
            lines = Files.readAllLines(Path.of(script));
        } catch (IOException e) {
            LinkManager.LOG.error("autotest: cannot read {}", script, e);
            return;
        }
        shots = Path.of(System.getProperty("minecraftlink.shots", "run/shots"));
        LinkManager.LOG.info("autotest: {} lines from {}, screenshots to {}", lines.size(), script, shots);
    }

    private static int ticks;

    /** Called from {@link LinkMod#onClientTick} at the end of every client tick; does nothing unless a script was loaded. */
    static void tick(Minecraft mc) {
        if (lines == null) return;
        if (++ticks % 200 == 0) {
            LinkManager.LOG.info("autotest: tick {} line {}/{} screen={} level={}", ticks, index, lines.size(),
                    mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(), mc.level != null);
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (index < lines.size()) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                index++;
                continue;
            }
            if (!step(mc, line)) return; // not finished: look at this line again next tick
            index++;
            if (waitTicks > 0) return;
        }
    }

    /** Runs one script line; false when it has to wait and be called again. */
    private static boolean step(Minecraft mc, String line) {
        String[] w = line.split("\\s+", 2);
        String arg = w.length > 1 ? w[1] : "";
        LocalPlayer p = mc.player;
        switch (w[0]) {
            case "create_world" -> {
                if (!worldRequested) {
                    // wait for the title screen and for the resource reload's loading overlay to be gone: a world that opens while the block
                    // models are still being baked gets block updates it cannot draw yet (a vanilla race, logged as "Error executing task")
                    if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return false;
                    worldRequested = true;
                    // The same call the world-creation screen makes, with the Superflat preset; creative, commands on, peaceful.
                    LevelSettings settings = new LevelSettings("linktest-" + System.currentTimeMillis() / 1000, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, WorldOptions.defaultWithRandomSeed(),
                            access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                }
                return true;
            }
            case "wait_world" -> {
                if (mc.level == null || p == null || mc.screen != null) return false;
                if (stage++ < 100) return false; // five seconds of ticks for the chunks around the spawn
                stage = 0;
                return true;
            }
            case "wait" -> {
                waitTicks = Integer.parseInt(arg.trim());
                return true;
            }
            case "cmd" -> {
                if (p == null) return false;
                String c = arg.startsWith("/") ? arg.substring(1) : arg;
                p.connection.sendUnsignedCommand(c);
                LinkManager.LOG.info("autotest: /{}", c);
                return true;
            }
            case "link" -> {
                String[] a = arg.split("\\s+");
                LinkManager m = LinkManager.INSTANCE;
                String out = switch (a[0]) {
                    case "host" -> m.start(Role.HOST, a.length > 1 ? Integer.valueOf(a[1]) : null);
                    case "guest" -> m.start(Role.GUEST, a.length > 1 ? Integer.valueOf(a[1]) : null);
                    case "stop" -> m.stop();
                    case "status" -> m.status();
                    case "focus" -> m.focus(a[1].equals("guest") ? Role.GUEST : Role.HOST);
                    default -> "unknown link command " + arg;
                };
                LinkManager.LOG.info("autotest: link {} -> {}", arg, out);
                return true;
            }
            case "key" -> {
                String[] a = arg.split("\\s+");
                var o = mc.options;
                var k = switch (a[0]) {
                    case "forward" -> o.keyUp;
                    case "back" -> o.keyDown;
                    case "left" -> o.keyLeft;
                    case "right" -> o.keyRight;
                    case "jump" -> o.keyJump;
                    default -> null;
                };
                if (k != null) k.setDown(a[1].equals("down"));
                return true;
            }
            case "look" -> {
                if (p == null) return false;
                String[] a = arg.split("\\s+");
                p.setYRot(Float.parseFloat(a[0]));
                p.setXRot(Float.parseFloat(a[1]));
                p.yRotO = p.getYRot();
                p.xRotO = p.getXRot();
                return true;
            }
            case "hud" -> {
                mc.options.hideGui = arg.trim().equals("off");
                return true;
            }
            case "shot" -> {
                Path out = shots.resolve(arg.trim() + ".png");
                try {
                    Files.createDirectories(shots);
                } catch (IOException e) {
                    LinkManager.LOG.warn("autotest: {}", e.toString());
                }
                // the game's own screenshot: a glReadPixels of the main render target
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(out);
                    LinkManager.LOG.info("autotest: screenshot {} ({}x{})", out, image.getWidth(), image.getHeight());
                } catch (IOException | RuntimeException e) {
                    LinkManager.LOG.warn("autotest: screenshot failed", e);
                }
                waitTicks = 5;
                return true;
            }
            case "pos" -> {
                if (p == null) return false;
                int h = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
                LinkManager.LOG.info("autotest: player at {} {} {} yaw {} pitch {}; first free block above the ground at y={}; block below: {}", p.getX(), p.getY(),
                        p.getZ(), p.getYRot(), p.getXRot(), h, mc.level.getBlockState(p.blockPosition().below()).getBlock().getName().getString());
                return true;
            }
            case "log" -> {
                LinkManager.LOG.info("autotest: {}", arg);
                return true;
            }
            case "leave" -> {
                LinkManager.LOG.info("autotest: leaving the world (no /link stop)");
                mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
                mc.setScreen(new TitleScreen());
                return true;
            }
            case "quit" -> {
                LinkManager.LOG.info("autotest: done, closing the game");
                mc.stop();
                return true;
            }
            default -> {
                LinkManager.LOG.warn("autotest: unknown line '{}'", line);
                return true;
            }
        }
    }
}
