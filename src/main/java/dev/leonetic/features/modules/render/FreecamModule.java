package dev.leonetic.features.modules.render;

import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class FreecamModule extends Module {
    private static final double TARGET_DISTANCE = 256.0;

    private final Setting<Double> horizontalSpeed = num("HorizontalSpeed", 0.5, 0.1, 5.0);
    private final Setting<Double> verticalSpeed = num("VerticalSpeed", 0.5, 0.1, 5.0);

    private Vec3 position = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private LocalPlayer owner;
    private float yaw;
    private float pitch;
    private boolean initialized;

    public FreecamModule() {
        super("Freecam", "Allows the camera to move independently from the player.", Category.RENDER);
    }

    @Override
    public void onEnable() {
        initialize();
    }

    @Override
    public void onDisable() {
        initialized = false;
        owner = null;
    }

    public void handleInput(LocalPlayer player) {
        if (mc.screen != null) return;
        if (!initialized || owner != player) initialize();
        if (!initialized) return;

        Input input = player.input.keyPresses;
        float forward = (input.forward() ? 1.0f : 0.0f) - (input.backward() ? 1.0f : 0.0f);
        float strafe = (input.right() ? 1.0f : 0.0f) - (input.left() ? 1.0f : 0.0f);

        double x = 0.0;
        double z = 0.0;
        if (forward != 0.0f || strafe != 0.0f) {
            double length = Math.sqrt(forward * forward + strafe * strafe);
            double radians = Math.toRadians(yaw);
            double sin = Math.sin(radians);
            double cos = Math.cos(radians);
            double speed = horizontalSpeed.getValue() / length;

            x = (-strafe * cos - forward * sin) * speed;
            z = (forward * cos - strafe * sin) * speed;
        }

        double y = ((input.jump() ? 1.0 : 0.0) - (input.shift() ? 1.0 : 0.0))
                * verticalSpeed.getValue();

        previousPosition = position;
        position = position.add(x, y, z);

        player.input.keyPresses = new Input(false, false, false, false, false, false, false);
        player.input.moveVector = Vec2.ZERO;
    }

    public void turn(double horizontal, double vertical) {
        yaw += (float) horizontal * 0.15f;
        pitch = Mth.clamp(pitch + (float) vertical * 0.15f, -90.0f, 90.0f);
    }

    public Vec3 getPosition(float tickDelta) {
        return previousPosition.lerp(position, tickDelta);
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public HitResult raycastBlock(float tickDelta) {
        Vec3 start = getPosition(tickDelta);
        Vec3 end = start.add(Vec3.directionFromRotation(pitch, yaw).scale(TARGET_DISTANCE));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

        if (hit.getType() == HitResult.Type.BLOCK
                && mc.player.isWithinBlockInteractionRange(hit.getBlockPos(), 0.0)) {
            return hit;
        }

        return BlockHitResult.miss(hit.getLocation(), hit.getDirection(), hit.getBlockPos());
    }

    public boolean isReady() {
        return initialized;
    }

    private void initialize() {
        if (mc.player == null || mc.level == null) return;

        owner = mc.player;
        position = mc.gameRenderer.getMainCamera().position();
        previousPosition = position;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        initialized = true;
    }
}
