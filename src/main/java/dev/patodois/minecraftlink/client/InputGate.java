package dev.patodois.minecraftlink.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;

/**
 * Honours the link's input focus (CONTRACT.md section 8, {@code input}): while the other side holds
 * the focus, Minecraft must not act on movement keys. The player's {@code movementInput} object is the thing
 * that turns key states into movement each tick (forward, strafe, jump, sneak); while the focus is elsewhere it is
 * replaced by an empty {@link MovementInput} (nothing pressed), and the original is put back when the focus returns,
 * the session ends or the player object changes. No coremod or mixin is needed. Mouse look and the other keys
 * (chat, inventory) stay with Minecraft: the contract speaks of "the player's keyboard, mouse and
 * gamepad", and a game that keeps its UI is still usable; only movement is handed over.
 */
final class InputGate {
    private EntityPlayerSP gatedPlayer;
    private MovementInput original;
    private final MovementInput blocker = new MovementInput();

    void tick(Minecraft mc, Session s) {
        EntityPlayerSP p = mc.thePlayer;
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
        if (!want && gatedPlayer == null && p.movementInput != blocker) {
            gatedPlayer = p;
            original = p.movementInput;
            p.movementInput = blocker;
        } else if (want && gatedPlayer != null) {
            release();
        }
    }

    boolean isBlocking() {
        return gatedPlayer != null;
    }

    void release() {
        if (gatedPlayer != null && original != null && gatedPlayer.movementInput == blocker) gatedPlayer.movementInput = original;
        gatedPlayer = null;
        original = null;
    }
}
