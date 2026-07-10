package dev.leonetic.features.modules.movement;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;

public class SprintModule extends Module {

    public enum Mode { NORMAL, OMNI }

    private final Setting<Mode> mode = mode("Mode", Mode.NORMAL);

    private static final String ROTATION_ID = "Sprint";

    public SprintModule() {
        super("Sprint", "Automatically sprints whenever you move. Omni sprints in any direction.", Category.MOVEMENT);
    }

    @Override
    public void onDisable() {
        Homovore.rotationManager.cancel(ROTATION_ID);
    }

    public boolean wantsSprint() {
        if (!isEnabled() || nullCheck()) return false;
        if (mc.player.isUsingItem()) return false;

        if (mode.getValue() == Mode.OMNI && !mc.player.isFallFlying()) {
            return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                    || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
        }
        return mc.player.input.getMoveVector().y > 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;

        if (mode.getValue() == Mode.OMNI && !mc.player.isFallFlying()) {
            omni();
            return;
        }

        Homovore.rotationManager.cancel(ROTATION_ID);

        if (mc.player.input.getMoveVector().y > 0) {
            mc.player.setSprinting(true);
        }
    }

    private void omni() {
        int inputX = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        int inputZ = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);

        if (inputX == 0 && inputZ == 0) {
            Homovore.rotationManager.cancel(ROTATION_ID);
            return;
        }

        float moveAngle = (float) Math.toDegrees(Math.atan2(inputX, inputZ));
        float targetYaw = mc.player.getYRot() + moveAngle;

        Homovore.rotationManager.submit(new RotationRequest(
                ROTATION_ID, Integer.MIN_VALUE, targetYaw, mc.player.getXRot(),
                RotationRequest.Mode.MOTION, true, true));

        mc.player.setSprinting(true);
    }
}
