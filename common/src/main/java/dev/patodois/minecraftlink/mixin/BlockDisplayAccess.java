package dev.patodois.minecraftlink.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** See {@link DisplayAccess}: the private block-state setter of {@code block_display}. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccess {
    @Invoker("setBlockState")
    void minecraftlink$setBlockState(BlockState state);
}
