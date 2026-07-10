package dev.leonetic.mixin.input;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.input.MouseInputEvent;
import dev.leonetic.features.modules.render.FreecamModule;
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
    private void homovore$freecamLook(LocalPlayer player, double horizontal, double vertical) {
        FreecamModule freecam = Homovore.moduleManager.getModuleByClass(FreecamModule.class);
        if (freecam != null && freecam.isEnabled() && freecam.isReady()) {
            freecam.turn(horizontal, vertical);
        } else {
            player.turn(horizontal, vertical);
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (EVENT_BUS.post(new MouseInputEvent(input.button(), action))) {
            ci.cancel();
        }
    }
}
