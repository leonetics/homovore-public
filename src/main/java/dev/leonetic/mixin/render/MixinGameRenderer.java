package dev.leonetic.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leonetic.Homovore;
import dev.leonetic.features.modules.render.NoRenderModule;
import dev.leonetic.features.modules.render.ShadersModule;
import dev.leonetic.features.modules.player.FreecamModule;
import dev.leonetic.util.render.HandShaderChain;
import dev.leonetic.util.render.HandShaderRender;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void homovore$freecamPick(float tickDelta, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        FreecamModule freecam = Homovore.moduleManager.getModuleByClass(FreecamModule.class);
        if (freecam == null || !freecam.isEnabled() || mc.level == null || mc.player == null) return;

        Vec3 start = freecam.getCameraPosition(tickDelta);
        Vec3 direction = Vec3.directionFromRotation(
                freecam.getCameraPitch(tickDelta), freecam.getCameraYaw(tickDelta));
        Vec3 end = start.add(direction.scale(256.0));
        BlockHitResult result = mc.level.clip(new ClipContext(
                start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

        mc.hitResult = result;
        mc.crosshairPickEntity = null;
        ci.cancel();
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V",
            shift = At.Shift.AFTER,
            ordinal = 0
        )
    )
    private void handShader$composite(DeltaTracker deltaTracker, CallbackInfo ci) {
        ShadersModule mod = Homovore.moduleManager.getModuleByClass(ShadersModule.class);
        if (mod == null || !mod.wantsHandShader()) return;
        HandShaderRender.composite(
                HandShaderChain.get(mod.handOutline.getValue(), mod.getHandThickness(), mod.handFill.getValue(),
                        mod.handGlow.getValue(), mod.getHandGlowRadius(), mod.getHandGlowIntensity()));
    }
    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void homovore$noTotem(ItemStack floatingItem, CallbackInfo ci) {
        if (floatingItem.is(Items.TOTEM_OF_UNDYING) && NoRenderModule.isActive(m -> m.noTotem.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void homovore$noBob(PoseStack matrices, float tickDelta, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noBob.getValue())) {
            ci.cancel();
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void homovore$noTilt(PoseStack matrices, float tickDelta, CallbackInfo ci) {
        if (NoRenderModule.isActive(m -> m.noTilt.getValue())) {
            ci.cancel();
        }
    }
}
