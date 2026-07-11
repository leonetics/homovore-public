package dev.leonetic.mixin.input;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.input.MouseInputEvent;
import dev.leonetic.features.modules.player.FreecamModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.leonetic.util.traits.Util.EVENT_BUS;

@Mixin(MouseHandler.class)
public class MixinMouse {
    @Redirect(
        method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")
    )
    private void homovore$turnFreecam(LocalPlayer player, double yaw, double pitch) {
        FreecamModule freecam = Homovore.moduleManager.getModuleByClass(FreecamModule.class);
        if (freecam != null && freecam.isEnabled()) {
            freecam.turnCamera(yaw, pitch);
        } else {
            player.turn(yaw, pitch);
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (EVENT_BUS.post(new MouseInputEvent(input.button(), action))) {
            ci.cancel();
        }
    }
}
