package dev.leonetic.mixin.client;

import dev.leonetic.gui.account.AccountsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    protected MixinTitleScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int l = height / 4 + 48;
        addRenderableWidget(Button.builder(
                Component.literal("Accounts"),
                btn -> minecraft.setScreen(new AccountsScreen(this))
        ).bounds(width / 2 - 100, l + 120, 200, 20).build());
    }
}
