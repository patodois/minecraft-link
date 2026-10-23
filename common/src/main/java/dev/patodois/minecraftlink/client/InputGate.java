package dev.patodois.minecraftlink.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;

/**
 * Honours the link's input focus (CONTRACT.md section 8, {@code input}): while the other side holds
 * the focus, Minecraft must not act on movement keys. The player's {@code input} object is the thing
 * that turns key states into movement each tick; while the focus is elsewhere it is replaced by an
 * empty {@link Input} (no keys pressed, its {@code tick} does nothing), and the original is put back when the focus returns,
 * the session ends or the player object changes. No mixin is needed. Mouse look and the other keys
 * (chat, inventory) stay with Minecraft: the contract speaks of "the player's keyboard, mouse and
 * gamepad", and a game that keeps its UI is still usable; only movement is handed over.
 */
final class InputGate {
    private LocalPlayer gatedPlayer;
    private Input original;
    private final Input blocker = new Input();

    void tick(Minecraft mc, Session s) {
        LocalPlayer p = mc.player;
        if (p == null) {
            gatedPlayer = null;
            original = null;
            return;
        }
        if (gatedPlayer != null && gatedPlayer != p) {
            gatedPlayer = null;
            original = null;
        }
        boolean want = s.minecraftHasInput();
        if (!want && gatedPlayer == null && p.input != blocker) {
            gatedPlayer = p;
            original = p.input;
            p.input = blocker;
        } else if (want && gatedPlayer != null) {
            release();
        }
    }

    boolean isBlocking() {
        return gatedPlayer != null;
    }

    void release() {
        if (gatedPlayer != null && original != null && gatedPlayer.input == blocker) gatedPlayer.input = original;
        gatedPlayer = null;
        original = null;
    }
}
