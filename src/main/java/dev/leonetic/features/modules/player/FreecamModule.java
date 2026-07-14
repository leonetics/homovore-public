package dev.leonetic.features.modules.player;

import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Detaches the client camera without moving or replacing the real player. */
public class FreecamModule extends Module {
    public final Setting<Double> speed = num("Speed", 0.5, 0.1, 3.0);
    public final Setting<Boolean> smooth = bool("Smooth", true);
    public final Setting<Double> acceleration = num("Acceleration", 0.35, 0.05, 1.0);
    public final Setting<Double> friction = num("Friction", 0.2, 0.02, 1.0);

    private static final double STOP_EPSILON = 1.0E-4;

    private CameraType previousCameraType;
    private Vec3 position;
    private Vec3 previousPosition;
    private Vec3 velocity = Vec3.ZERO;
    private float yaw;
    private float previousYaw;
    private float pitch;
    private float previousPitch;

    public FreecamModule() {
        super("Freecam", "Detaches the camera from the player while leaving the player in place.", Category.PLAYER);
        acceleration.setVisibility(v -> smooth.getValue());
        friction.setVisibility(v -> smooth.getValue());
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            disable();
            return;
        }

        previousCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);

        position = mc.gameRenderer.getMainCamera().position();
        previousPosition = position;
        velocity = Vec3.ZERO;
        yaw = previousYaw = mc.player.getYRot();
        pitch = previousPitch = mc.player.getXRot();
    }

    @Override
    public void onTick() {
        if (position == null || mc.player == null || mc.level == null) {
            disable();
            return;
        }

        double forward = axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
        double strafe = axis(mc.options.keyLeft.isDown(), mc.options.keyRight.isDown());
        double vertical = axis(mc.options.keyJump.isDown(), mc.options.keyShift.isDown());

        double yawRadians = Math.toRadians(yaw);
        Vec3 wish = new Vec3(
                strafe * Math.cos(yawRadians) - forward * Math.sin(yawRadians),
                vertical,
                forward * Math.cos(yawRadians) + strafe * Math.sin(yawRadians));
        if (wish.lengthSqr() > 0.0) {
            wish = wish.normalize();
        }

        double maxSpeed = speed.getValue();
        if (smooth.getValue()) {
            velocity = applyFriction(velocity);
            velocity = accelerate(velocity, wish, maxSpeed);
        } else {
            velocity = wish.scale(maxSpeed);
        }

        previousPosition = position;
        previousYaw = yaw;
        previousPitch = pitch;
        position = position.add(velocity);
    }

    private Vec3 applyFriction(Vec3 current) {
        double length = current.length();
        if (length < STOP_EPSILON) {
            return Vec3.ZERO;
        }

        double drop = Math.max(length, speed.getValue() * 0.25) * friction.getValue();
        double scaled = Math.max(0.0, length - drop) / length;
        return current.scale(scaled);
    }

    private Vec3 accelerate(Vec3 current, Vec3 wish, double maxSpeed) {
        if (wish.lengthSqr() == 0.0) {
            return current;
        }

        double addSpeed = maxSpeed - current.dot(wish);
        if (addSpeed <= 0.0) {
            return current;
        }

        double accel = Math.min(acceleration.getValue() * maxSpeed, addSpeed);
        return current.add(wish.scale(accel));
    }

    @Override
    public void onDisable() {
        if (previousCameraType != null) {
            mc.options.setCameraType(previousCameraType);
        }
        previousCameraType = null;
        position = null;
        previousPosition = null;
        velocity = Vec3.ZERO;
    }

    public void turnCamera(double yaw, double pitch) {
        previousYaw = this.yaw;
        previousPitch = this.pitch;
        this.yaw += (float) yaw * 0.15f;
        this.pitch = Mth.clamp(this.pitch + (float) pitch * 0.15f, -90.0f, 90.0f);
    }

    public Vec3 getCameraPosition(float tickDelta) {
        return new Vec3(
                Mth.lerp(tickDelta, previousPosition.x, position.x),
                Mth.lerp(tickDelta, previousPosition.y, position.y),
                Mth.lerp(tickDelta, previousPosition.z, position.z));
    }

    public float getCameraYaw(float tickDelta) {
        return Mth.lerp(tickDelta, previousYaw, yaw);
    }

    public float getCameraPitch(float tickDelta) {
        return Mth.lerp(tickDelta, previousPitch, pitch);
    }

    private static int axis(boolean positive, boolean negative) {
        return positive == negative ? 0 : (positive ? 1 : -1);
    }
}
