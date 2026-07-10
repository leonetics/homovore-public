package dev.leonetic.features.modules.player;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.input.MouseInputEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapRequest;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import static dev.leonetic.util.inventory.InventoryUtil.FULL_SCOPE;

public class MiddleClickExtraModule extends Module {

    private static final int SWAP_PRIORITY = 1000;
    private static final int ROTATION_PRIORITY = 1000;

    private final Setting<Boolean> fireworkInAir = bool("FireworkInAir", true);

    public MiddleClickExtraModule() {
        super("MiddleClick", "Throws a pearl or firework on middle click.", Category.PLAYER);
    }

    @Subscribe
    private void onMouse(MouseInputEvent event) {
        if (nullCheck() || mc.screen != null) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        if (fireworkInAir.getValue() && mc.player.isFallFlying()) {
            Result firework = InventoryUtil.find(Items.FIREWORK_ROCKET, FULL_SCOPE);
            if (firework.found()) {
                Homovore.swapManager.submit(new SwapRequest("MiddleClick.firework", SWAP_PRIORITY, firework,
                        () -> mc.gameMode.useItem(mc.player, firework.hand())));
            }
            return;
        }

        Result pearl = InventoryUtil.find(Items.ENDER_PEARL, FULL_SCOPE);
        if (pearl.found()) {

            Homovore.rotationManager.submit(new RotationRequest("MiddleClick.pearl", ROTATION_PRIORITY,
                    Homovore.rotationManager.getRealYaw(), Homovore.rotationManager.getRealPitch(),
                    RotationRequest.Mode.SILENT));
            Homovore.swapManager.submit(new SwapRequest("MiddleClick.pearl", SWAP_PRIORITY, pearl,
                    () -> {

                        Homovore.rotationManager.setBypassUseSpoof(true);
                        try {
                            mc.gameMode.useItem(mc.player, pearl.hand());
                        } finally {
                            Homovore.rotationManager.setBypassUseSpoof(false);
                        }
                    }));
        }
    }
}
