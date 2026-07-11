package dev.leonetic.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.leonetic.features.modules.render.ViewModelModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntitySwing {
    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void homovore$swingSpeed(CallbackInfoReturnable<Integer> cir) {
        ViewModelModule module = homovore$activeModule();
        if (module == null) return;

        int duration = Math.max(1, (int) Math.round(cir.getReturnValue() / module.swingSpeed.getValue()));
        cir.setReturnValue(duration);
    }

    @ModifyExpressionValue(
        method = "swing(Lnet/minecraft/world/InteractionHand;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getCurrentSwingDuration()I"
        )
    )
    private int homovore$swingDelay(int duration) {
        ViewModelModule module = homovore$activeModule();
        return module == null ? duration : module.swingDelay.getValue() * 2;
    }

    private ViewModelModule homovore$activeModule() {
        if (!((Object) this instanceof LocalPlayer)) return null;
        ViewModelModule module = ViewModelModule.getInstance();
        return module != null && module.isEnabled() ? module : null;
    }
}
