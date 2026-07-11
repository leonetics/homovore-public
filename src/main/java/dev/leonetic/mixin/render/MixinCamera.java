package dev.leonetic.mixin.render;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.player.FreecamModule;
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
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void homovore$setupFreecam(Level level, Entity entity, boolean detached, boolean inverseView,
                                       float tickDelta, CallbackInfo ci) {
        FreecamModule freecam = getFreecam();
        if (freecam == null) return;
        setPosition(freecam.getCameraPosition(tickDelta));
        setRotation(freecam.getCameraYaw(tickDelta), freecam.getCameraPitch(tickDelta));
    }

    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void homovore$freecamIsDetached(CallbackInfoReturnable<Boolean> cir) {
        if (getFreecam() != null) cir.setReturnValue(true);
    }

    private static FreecamModule getFreecam() {
        if (Homovore.moduleManager == null) return null;
        FreecamModule freecam = Homovore.moduleManager.getModuleByClass(FreecamModule.class);
        return freecam != null && freecam.isEnabled() ? freecam : null;
    }
}
