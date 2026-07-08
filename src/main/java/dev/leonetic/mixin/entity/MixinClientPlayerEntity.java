package dev.leonetic.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.leonetic.Homovore;
import dev.leonetic.event.Stage;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.entity.player.UpdateWalkingPlayerEvent;
import dev.leonetic.features.modules.movement.VelocityModule;
import dev.leonetic.features.modules.movement.NoSlowModule;
import dev.leonetic.features.modules.movement.SprintModule;
import dev.leonetic.features.modules.render.NoRenderModule;
import dev.leonetic.features.modules.world.ScaffoldModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.leonetic.util.traits.Util.EVENT_BUS;

@Mixin(LocalPlayer.class)
public class MixinClientPlayerEntity {

    @Inject(method = "tick", at = @At("HEAD"))
    private void preTickHook(CallbackInfo ci) {
        EVENT_BUS.post(new PreTickEvent());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void tickHook(CallbackInfo ci) {
        EVENT_BUS.post(new TickEvent());
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.AFTER))
    private void tickHook2(CallbackInfo ci) {
        EVENT_BUS.post(new UpdateWalkingPlayerEvent(Stage.PRE));
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;sendPosition()V", shift = At.Shift.AFTER))
    private void tickHook3(CallbackInfo ci) {
        EVENT_BUS.post(new UpdateWalkingPlayerEvent(Stage.POST));
    }

    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER)
    )
    private void scaffold$suppressSneakAfterInputTick(CallbackInfo ci) {
        ScaffoldModule scaffold = Homovore.moduleManager.getModuleByClass(ScaffoldModule.class);
        if (scaffold == null || !scaffold.isEnabled()) return;

        LocalPlayer self = (LocalPlayer) (Object) this;
        Input kp = self.input.keyPresses;
        if (!kp.shift()) return;

        self.input.keyPresses = new Input(
                kp.forward(), kp.backward(), kp.left(), kp.right(), kp.jump(), false, kp.sprint());
    }

    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER)
    )
    private void sprint$applyBeforeJump(CallbackInfo ci) {
        SprintModule sprint = Homovore.moduleManager.getModuleByClass(SprintModule.class);
        if (sprint != null && sprint.wantsSprint()) {
            ((LocalPlayer) (Object) this).setSprinting(true);
        }
    }

    @Inject(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER)
    )
    private void homovore$moveFixAfterInputTick(CallbackInfo ci) {
        var rotation = Homovore.rotationManager;
        if (rotation == null || !rotation.isRotating() || !rotation.isMoveFixEnabled()) return;

        LocalPlayer self = (LocalPlayer)(Object) this;
        Input real = self.input.keyPresses;
        Input fixed = rotation.computeMoveFixInput(real, self.getYRot());
        if (fixed == real) return;

        self.input.keyPresses = fixed;

        float leftImpulse = fixed.left() == fixed.right() ? 0f : (fixed.left() ? 1f : -1f);
        float forwardImpulse = fixed.forward() == fixed.backward() ? 0f : (fixed.forward() ? 1f : -1f);
        self.input.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void cancelBlockPush(double x, double z, CallbackInfo ci) {
        VelocityModule velocity = Homovore.moduleManager.getModuleByClass(VelocityModule.class);
        if (velocity != null && velocity.isEnabled() && velocity.blockPush.getValue() && velocity.phaseConditionMet()) {
            ci.cancel();
        }
    }

    @ModifyExpressionValue(
        method = "modifyInput",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;itemUseSpeedMultiplier()F")
    )
    private float noSlow$eatSpeed(float original) {
        return NoSlowModule.shouldCancelConsumeSlow() ? 1.0f : original;
    }

    @Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
    private void fastCrawl(CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isVisuallyCrawling() && NoSlowModule.isActive(m -> m.crawl.getValue())) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
        method = "handlePortalTransitionEffect",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isAllowedInPortal()Z")
    )
    private boolean homovore$portalGui(Screen screen) {
        if (NoRenderModule.isActive(m -> m.portalGui.getValue())) {
            return true;
        }
        return screen.isAllowedInPortal();
    }
}
