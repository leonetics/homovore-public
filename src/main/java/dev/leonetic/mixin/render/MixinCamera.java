package dev.leonetic.mixin.render;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.render.FreecamModule;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void homovore$freecam(Level level, Entity entity, boolean detached,
                                  boolean thirdPersonReverse, float tickDelta, CallbackInfo ci) {
        FreecamModule freecam = Homovore.moduleManager.getModuleByClass(FreecamModule.class);
        if (freecam == null || !freecam.isEnabled() || !freecam.isReady()) return;

        setRotation(freecam.getYaw(), freecam.getPitch());
        setPosition(freecam.getPosition(tickDelta));
    }

    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void homovore$renderPlayer(CallbackInfoReturnable<Boolean> cir) {
        FreecamModule freecam = Homovore.moduleManager.getModuleByClass(FreecamModule.class);
        if (freecam != null && freecam.isEnabled() && freecam.isReady()) {
            cir.setReturnValue(true);
        }
    }
}
