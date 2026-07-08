package dev.leonetic.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.leonetic.Homovore;
import dev.leonetic.features.modules.render.CrystalHandModule;
import dev.leonetic.features.modules.render.ShadersModule;
import dev.leonetic.features.modules.render.ViewModelModule;
import dev.leonetic.mixin.entity.EntityRotationAccessor;
import dev.leonetic.util.render.HandShaderRender;
import dev.leonetic.util.render.HandSilhouetteCollector;
import dev.leonetic.util.render.TeeSubmitCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {

    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;

    @Unique
    private boolean handShader$capturing;

    @Unique
    private boolean viewModel$scaled;

    private float noSway$savedXBob;
    private float noSway$savedXBobO;
    private float noSway$savedYBob;
    private float noSway$savedYBobO;

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void noSway$pre(CallbackInfo ci) {
        if (!ViewModelModule.isActive(m -> m.noSway.getValue())) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        EntityRotationAccessor acc = (EntityRotationAccessor) player;
        noSway$savedXBob = acc.homovore$getXBob();
        noSway$savedXBobO = acc.homovore$getXBobO();
        noSway$savedYBob = acc.homovore$getYBob();
        noSway$savedYBobO = acc.homovore$getYBobO();
        float xRot = player.getXRot();
        float yRot = player.getYRot();
        acc.homovore$setXBob(xRot);
        acc.homovore$setXBobO(xRot);
        acc.homovore$setYBob(yRot);
        acc.homovore$setYBobO(yRot);
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void noSway$post(CallbackInfo ci) {
        if (!ViewModelModule.isActive(m -> m.noSway.getValue())) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        EntityRotationAccessor acc = (EntityRotationAccessor) player;
        acc.homovore$setXBob(noSway$savedXBob);
        acc.homovore$setXBobO(noSway$savedXBobO);
        acc.homovore$setYBob(noSway$savedYBob);
        acc.homovore$setYBobO(noSway$savedYBobO);
    }

    @ModifyArg(
        method = "renderHandsWithItems",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"
        ),
        index = 5
    )
    private ItemStack crystalHand$modifyRenderedItem(ItemStack original) {
        CrystalHandModule mod = Homovore.moduleManager.getModuleByClass(CrystalHandModule.class);
        if (mod == null || !mod.isEnabled()) return original;
        return mod.getDisplayStack(original);
    }

    @ModifyVariable(method = "renderHandsWithItems", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector handShader$wrapCollector(SubmitNodeCollector original) {
        handShader$capturing = false;
        ShadersModule mod = Homovore.moduleManager.getModuleByClass(ShadersModule.class);
        if (mod == null || !mod.wantsHandShader()
                || (!mod.handOutline.getValue() && !mod.handFill.getValue())) {
            return original;
        }
        HandSilhouetteCollector secondary = HandShaderRender.beginCapture(mod.getHandRgb());
        if (secondary == null) return original;

        handShader$capturing = true;
        HandShaderRender.capturePaused = false;
        return new TeeSubmitCollector(original, secondary);
    }

    @Inject(method = "renderHandsWithItems", at = @At("TAIL"))
    private void handShader$flush(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
                                  LocalPlayer player, int light, CallbackInfo ci) {
        if (!handShader$capturing) return;
        handShader$capturing = false;
        HandShaderRender.capturePaused = false;
        HandShaderRender.flush();
    }

    @Inject(
        method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
        at = @At("HEAD")
    )
    private void viewModel$push(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                               InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                               PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        viewModel$scaled = false;
        ViewModelModule mod = ViewModelModule.getInstance();
        if (mod == null || !mod.isEnabled()) return;
        viewModel$scaled = true;

        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        float sign = arm == HumanoidArm.RIGHT ? 1f : -1f;

        poseStack.pushPose();
        poseStack.translate(mod.posX.getValue() * sign, mod.posY.getValue(), mod.posZ.getValue());
        float s = mod.scale.getValue().floatValue();
        poseStack.translate(0.56 * sign, -0.52, -0.72);
        poseStack.scale(s, s, s);
        poseStack.translate(-0.56 * sign, 0.52, 0.72);
    }

    @Inject(
        method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
        at = @At("RETURN")
    )
    private void viewModel$pop(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                              InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                              PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (!viewModel$scaled) return;
        viewModel$scaled = false;
        poseStack.popPose();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void viewModel$noSwapAnimation(CallbackInfo ci) {
        if (!ViewModelModule.isActive(m -> m.noSwapAnimation.getValue())) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        mainHandItem = player.getMainHandItem();
        offHandItem = player.getOffhandItem();
    }

    @ModifyExpressionValue(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F")
    )
    private float viewModel$noSwapScale(float original) {
        if (ViewModelModule.isActive(m -> m.noSwapAnimation.getValue())) return 1.0f;
        return original;
    }
}
