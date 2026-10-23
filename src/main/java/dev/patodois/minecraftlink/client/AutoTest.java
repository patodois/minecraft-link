package dev.patodois.minecraftlink.client;

import dev.patodois.rewritelink.Control.Role;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Development aid, inactive unless the JVM property {@code minecraftlink.autotest} names a script.
 * It drives the real client through a scripted run (create a flat world, run link commands, move,
 * take screenshots) so the mod can be tested without a person at the keyboard. It uses only the
 * game's own screens and commands and writes only to the run directory and to
 * {@code minecraftlink.shots}. Script lines, one per line ({@code #} starts a comment):
 *
 * <pre>
 *   create_world            create and open a superflat creative world with commands (ground at y = 4)
 *   wait_world              wait until the player is in the world and the first chunks are there
 *   wait N                  wait N client ticks
 *   cmd /fill ...           run a command as the player (a /link command runs in the client, any other on the server)
 *   link host [port]        same as /link host, called directly
 *   link guest [port]
 *   link stop | status | focus host | focus guest
 *   key forward down|up     press or release a movement key (forward back left right jump)
 *   look YAW PITCH          set the player's view
 *   gui 0|1                 hide (0) or show (1) the HUD, hand and chat, like F1
 *   view N                  camera mode: 0 first person, 1 third person behind, 2 third person in front
 *   pos                     log the player's position
 *   shot NAME               save NAME.png (the game's framebuffer) into minecraftlink.shots
 *   log TEXT                write a line to the log (a line starting with EXTSHOT also waits 1.5 s: an outside runner
 *                           watching the log takes a screenshot of the window)
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
        if (script == null || script.trim().isEmpty()) return;
        try {
            lines = Files.readAllLines(Paths.get(script), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LinkManager.LOG.error("autotest: cannot read {}", script, e);
            return;
        }
        shots = Paths.get(System.getProperty("minecraftlink.shots", "run/shots"));
        LinkManager.LOG.info("autotest: {} lines from {}, screenshots to {}", lines.size(), script, shots);
        MinecraftForge.EVENT_BUS.register(new AutoTest());
    }

    private static int ticks;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.pauseOnLostFocus = false; // the window may not have the focus during a scripted run
        if (++ticks % 200 == 0) {
            LinkManager.LOG.info("autotest: tick {} line {}/{} screen={} world={}", ticks, index, lines.size(),
                    mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName(), mc.world != null);
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (index < lines.size()) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                index++;
                continue;
            }
            if (!step(mc, line)) return; // not finished: look at this line again next tick
            index++;
            if (waitTicks > 0) return;
        }
    }

    private static KeyBinding key(GameSettings o, String name) {
        switch (name) {
            case "forward":
                return o.keyBindForward;
            case "back":
                return o.keyBindBack;
            case "left":
                return o.keyBindLeft;
            case "right":
                return o.keyBindRight;
            case "jump":
                return o.keyBindJump;
            default:
                return null;
        }
    }

    /** Runs one script line; false when it has to wait and be called again. */
    private static boolean step(Minecraft mc, String line) {
        String[] w = line.split("\\s+", 2);
        String arg = w.length > 1 ? w[1] : "";
        EntityPlayerSP p = mc.player;
        switch (w[0]) {
            case "create_world": {
                if (worldRequested) return true;
                if (!(mc.currentScreen instanceof GuiMainMenu)) return false;
                worldRequested = true;
                WorldSettings ws = new WorldSettings(0L, GameType.CREATIVE, false, false, WorldType.FLAT).enableCommands();
                mc.launchIntegratedServer("linktest", "linktest", ws);
                return true;
            }
            case "wait_world": {
                if (mc.world == null || p == null || mc.currentScreen != null) return false;
                if (stage++ < 100) return false; // five seconds of ticks for the chunks around the spawn
                stage = 0;
                return true;
            }
            case "wait":
                waitTicks = Integer.parseInt(arg.trim());
                return true;
            case "cmd": {
                if (p == null) return false;
                String c = arg.startsWith("/") ? arg : "/" + arg;
                LinkManager.LOG.info("autotest: {}", c);
                if (c.startsWith("/link")) {
                    ClientCommandHandler.instance.executeCommand(p, c); // the same entry point as a typed chat line
                } else {
                    p.sendChatMessage(c);
                }
                return true;
            }
            case "link": {
                String[] a = arg.split("\\s+");
                LinkManager m = LinkManager.INSTANCE;
                String out;
                switch (a[0]) {
                    case "host":
                        out = m.start(Role.HOST, a.length > 1 ? Integer.valueOf(a[1]) : null);
                        break;
                    case "guest":
                        out = m.start(Role.GUEST, a.length > 1 ? Integer.valueOf(a[1]) : null);
                        break;
                    case "stop":
                        out = m.stop();
                        break;
                    case "status":
                        out = m.status();
                        break;
                    case "focus":
                        out = m.focus(a[1].equals("guest") ? Role.GUEST : Role.HOST);
                        break;
                    default:
                        out = "unknown link command " + arg;
                        break;
                }
                LinkManager.LOG.info("autotest: link {} -> {}", arg, out);
                return true;
            }
            case "key": {
                String[] a = arg.split("\\s+");
                KeyBinding k = key(mc.gameSettings, a[0]);
                if (k != null) KeyBinding.setKeyBindState(k.getKeyCode(), a[1].equals("down"));
                return true;
            }
            case "look": {
                if (p == null) return false;
                String[] a = arg.split("\\s+");
                p.rotationYaw = Float.parseFloat(a[0]);
                p.rotationPitch = Float.parseFloat(a[1]);
                p.prevRotationYaw = p.rotationYaw;
                p.prevRotationPitch = p.rotationPitch;
                return true;
            }
            case "view": {
                mc.gameSettings.thirdPersonView = Integer.parseInt(arg.trim()); // 0 first person, 1 behind, 2 in front
                return true;
            }
            case "gui": {
                mc.gameSettings.hideGUI = arg.trim().equals("0"); // gui 0 hides the HUD, the hand and the chat (F1), gui 1 shows them
                return true;
            }
            case "shot": {
                File out = shots.resolve(arg.trim() + ".png").toFile();
                try {
                    Files.createDirectories(shots);
                    BufferedImage img = ScreenShotHelper.createScreenshot(mc.getFramebuffer().framebufferTextureWidth,
                            mc.getFramebuffer().framebufferTextureHeight, mc.getFramebuffer());
                    ImageIO.write(img, "png", out);
                    LinkManager.LOG.info("autotest: screenshot {} ({}x{})", out, img.getWidth(), img.getHeight());
                } catch (IOException | RuntimeException e) {
                    LinkManager.LOG.warn("autotest: screenshot failed", e);
                }
                waitTicks = 2;
                return true;
            }
            case "pos": {
                if (p == null) return false;
                double[] cam = CameraSampler.lastCameraPosition;
                LinkManager.LOG.info("autotest: player feet at {} {} {}; last camera sent at {}; yaw {} pitch {}; block below: {}", p.posX, p.posY, p.posZ,
                        cam == null ? "none" : String.format("%.3f %.3f %.3f", cam[0], cam[1], cam[2]), p.rotationYaw, p.rotationPitch,
                        mc.world.getBlockState(p.getPosition().down()).getBlock().getLocalizedName());
                return true;
            }
            case "log":
                LinkManager.LOG.info("autotest: {}", arg);
                if (arg.startsWith("EXTSHOT")) waitTicks = 30; // the test runner takes a screenshot of the window now: keep the scene still
                return true;
            case "leave": {
                if (mc.world == null) return true;
                LinkManager.LOG.info("autotest: leaving the world (no /link stop)");
                mc.world.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
                mc.displayGuiScreen(new GuiMainMenu());
                return true;
            }
            case "quit":
                LinkManager.LOG.info("autotest: done, closing the game");
                mc.shutdown();
                return true;
            default:
                LinkManager.LOG.warn("autotest: unknown line '{}'", line);
                return true;
        }
    }
}
