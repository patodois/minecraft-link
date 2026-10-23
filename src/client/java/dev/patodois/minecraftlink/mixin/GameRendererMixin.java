package dev.patodois.minecraftlink.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.patodois.minecraftlink.client.LinkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two read-only hooks into {@link GameRenderer}; nothing is modified.
 *
 * <ol>
 *   <li>{@code extract} (head): lets the link place guest entities for this frame before Minecraft
 *       extracts entity render state, at frame rate instead of tick rate.</li>
 *   <li>{@code renderLevel} (before the projection matrix is handed to the renderer): copies
 *       the final projection, which is the clean projection times the view-bobbing and damage-tilt
 *       transform. Minecraft computes it in a local variable and exposes no getter, and the bobbing
 *       is visible on screen, so a guest rendering with the clean camera would drift against the
 *       world while the player walks.</li>
 * </ol>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void minecraftlink$beforeExtract(CallbackInfo ci) {
        LinkManager.INSTANCE.beforeExtract(Minecraft.getInstance());
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
    private void minecraftlink$captureProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
        float[] a = new float[16];
        projectionMatrix.get(a);
        dev.patodois.minecraftlink.client.CameraCapture.drawnProjection = a;
    }
}
