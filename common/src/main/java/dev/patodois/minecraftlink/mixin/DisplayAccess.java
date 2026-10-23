package dev.patodois.minecraftlink.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls two private setters of vanilla's display entities. Fabric API happens to widen them, NeoForge
 * and Forge do not, so the mod uses a mixin invoker (the same on every loader) instead of any loader's
 * access widener or transformer. Nothing is changed in the game; the private methods are only called.
 */
@Mixin(Display.class)
public interface DisplayAccess {
    @Invoker("setTransformation")
    void minecraftlink$setTransformation(Transformation transformation);

    @Invoker("setViewRange")
    void minecraftlink$setViewRange(float viewRange);
}
