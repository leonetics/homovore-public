package dev.leonetic.mixin.input;

import dev.leonetic.event.impl.input.KeyInputEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.leonetic.util.traits.Util.EVENT_BUS;

@Mixin(KeyboardHandler.class)
public class MixinKeyboard {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKey(long window, int action, KeyEvent input, CallbackInfo ci) {
        if (action != 0 && action != 1) return;

        if (action == 1 && EVENT_BUS.post(new KeyInputEvent(input.key(), action))) {
            ci.cancel();
        } else if (action == 0) {
            EVENT_BUS.post(new KeyInputEvent(input.key(), action));
        }
    }
}

