package dev.leonetic.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.leonetic.features.modules.render.NoRenderModule;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(BlockRenderDispatcher.class)
public class MixinBlockRenderDispatcher {

    @Inject(method = "renderBatched", at = @At("HEAD"), cancellable = true)
    private void homovore$noCrystalFire(BlockState state, BlockPos pos, BlockAndTintGetter level,
                                        PoseStack poseStack, VertexConsumer consumer, boolean checkSides,
                                        List<BlockModelPart> parts, CallbackInfo ci) {
        if (state.getBlock() instanceof BaseFireBlock
                && NoRenderModule.isActive(m -> m.noCrystalFire.getValue())) {
            ci.cancel();
        }
    }
}
