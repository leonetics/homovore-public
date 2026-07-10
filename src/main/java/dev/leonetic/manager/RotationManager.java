package dev.leonetic.manager;

import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.system.Subscribe;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.leonetic.util.traits.Util.EVENT_BUS;
import static dev.leonetic.util.traits.Util.mc;

public class RotationManager {

    private static final float SPEED = 360f;

    private static final float EASE = 1.0f;

    private static final float THRESHOLD = 5f;

    private static final int HOLD_TICKS = 0;
    private static final JitterMode JITTER = JitterMode.NORMAL;
    private static final boolean MOVE_FIX = true;

    public enum JitterMode { NONE, GRIM, NORMAL }

    private float realYaw, realPitch;
    private float rotationYaw, rotationPitch;
    private float rotationYaw0, rotationPitch0;
    private float serverYaw, serverPitch;
    private float serverYaw0;
    private float serverDeltaYaw;

    private boolean bypassUseSpoof;

    private boolean silentSyncRequired;
    private boolean silentSentThisTick;
    private int silentSentPriority;
    private long lastSilentMs;

    private final List<RotationRequest> requests = new ArrayList<>();
    private String lastActiveId;
    private float currentYawSpeed, currentPitchSpeed;
    private int ticksHolding;
    private boolean returning;

    public void init() {
        EVENT_BUS.register(this);
    }

    public void submit(RotationRequest request) {
        if (request.mode == RotationRequest.Mode.SILENT) {
            if (silentSentThisTick && request.priority <= silentSentPriority) return;
            performSilent(request);
            silentSentThisTick = true;
            silentSentPriority = request.priority;
            silentSyncRequired = true;
            lastSilentMs = System.currentTimeMillis();
            return;
        }

        requests.removeIf(r -> r.id.equals(request.id));
        requests.add(request);
        requests.sort(Comparator.comparingInt((RotationRequest r) -> r.priority).reversed());
    }

    public void cancel(String id) {
        requests.removeIf(r -> r.id.equals(id));
    }

    public boolean hasRequest(String id) {
        return requests.stream().anyMatch(r -> r.id.equals(id));
    }

    public boolean isCompleted(String id) {
        return requests.stream()
                .filter(r -> r.id.equals(id))
                .findFirst()
                .map(r -> Math.abs(yawDifference(r.targetYaw, rotationYaw)) <= THRESHOLD
                        && Math.abs(r.targetPitch - rotationPitch) <= THRESHOLD)
                .orElse(false);
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPreTick(PreTickEvent event) {
        if (mc.player == null) return;

        updateRealRotation(mc.player.getYRot(), mc.player.getXRot());

        rotationYaw0 = rotationYaw;
        rotationPitch0 = rotationPitch;

        RotationRequest active = getActiveRequest();
        if (active != null) {
            processRequest(active);
        } else if (returning) {
            returnToRealRotation();
        } else {
            idleReset();
        }
    }

    private void processRequest(RotationRequest request) {

        if (!request.id.equals(lastActiveId)) {
            resetRotationToReal();
            lastActiveId = request.id;
        }

        boolean updated = false;
        if (request.shouldUpdate()) {
            float oldYaw = request.targetYaw;
            float oldPitch = request.targetPitch;
            request.updateTarget();
            updated = Math.abs(oldYaw - request.targetYaw) > 0.001f
                    || Math.abs(oldPitch - request.targetPitch) > 0.001f;
            if (updated) ticksHolding = 0;
        }

        float yawDiff = yawDifference(request.targetYaw, rotationYaw);
        float pitchDiff = request.targetPitch - rotationPitch;

        boolean reached = Math.abs(yawDiff) <= THRESHOLD && Math.abs(pitchDiff) <= THRESHOLD;

        if (reached && !updated) {
            if (request.sticky) {

                ticksHolding = 0;
                interpolateRotation(yawDiff, pitchDiff, !request.noJitter);
            } else if (++ticksHolding >= HOLD_TICKS) {
                removeActiveRequest();
                ticksHolding = 0;
                returning = true;
            }
        } else {
            ticksHolding = 0;
            interpolateRotation(yawDiff, pitchDiff, !request.noJitter);
        }
    }

    private void resetRotationToReal() {
        float targetYaw = alignYaw(realYaw, rotationYaw);
        realYaw = targetYaw;
        mc.player.setYRot(targetYaw);
        rotationYaw = realYaw;
        rotationPitch = realPitch;
        currentYawSpeed = 0f;
        currentPitchSpeed = 0f;
        ticksHolding = 0;
        returning = false;
    }

    private void returnToRealRotation() {
        float targetYaw = alignYaw(realYaw, rotationYaw);
        float yawDiff = targetYaw - rotationYaw;
        float pitchDiff = realPitch - rotationPitch;

        interpolateRotation(yawDiff, pitchDiff, true);

        boolean backReached = Math.abs(yawDiff) <= THRESHOLD && Math.abs(pitchDiff) <= THRESHOLD;
        if (backReached) {
            returning = false;
            realYaw = targetYaw;
            mc.player.setYRot(targetYaw);
            rotationYaw = realYaw;
            rotationPitch = realPitch;
            lastActiveId = null;
        }
    }

    private void interpolateRotation(float yawDiff, float pitchDiff, boolean jitter) {
        currentYawSpeed = lerp(currentYawSpeed, yawDiff, EASE);
        currentPitchSpeed = lerp(currentPitchSpeed, pitchDiff, EASE);

        float yawSpeed = Mth.clamp(currentYawSpeed, -SPEED, SPEED);
        float pitchSpeed = Mth.clamp(currentPitchSpeed, -SPEED, SPEED);

        float newYaw = rotationYaw + yawSpeed;
        float newPitch = rotationPitch + pitchSpeed;

        if (jitter && JITTER == JitterMode.NORMAL) {
            float minJitter = THRESHOLD / 4f;
            float maxJitter = THRESHOLD / 2f;
            float jitterYaw = minJitter + (float) (Math.random() * (maxJitter - minJitter));
            float jitterPitch = minJitter + (float) (Math.random() * (maxJitter - minJitter));
            jitterYaw *= Math.random() < 0.5 ? -1 : 1;
            jitterPitch *= Math.random() < 0.5 ? -1 : 1;
            newYaw += jitterYaw;
            newPitch = Mth.clamp(newPitch + jitterPitch, -90f, 90f);
        } else if (jitter && JITTER == JitterMode.GRIM) {
            float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
            newPitch = Mth.clamp(newPitch + f, -90f, 90f);
        }

        rotationYaw = newYaw;
        rotationPitch = Mth.clamp(newPitch, -90f, 90f);
    }

    private void idleReset() {
        lastActiveId = null;
        rotationYaw = realYaw;
        rotationPitch = realPitch;
        currentYawSpeed = 0f;
        currentPitchSpeed = 0f;
    }

    public Input computeMoveFixInput(Input real, float realYawNow) {
        float delta = Mth.wrapDegrees(realYawNow - rotationYaw);

        float inputX = (real.right() ? 1 : 0) - (real.left() ? 1 : 0);
        float inputZ = (real.forward() ? 1 : 0) - (real.backward() ? 1 : 0);
        if (inputX == 0 && inputZ == 0) return real;

        double moveAngle = Math.toDegrees(Math.atan2(inputX, inputZ));
        double finalAngle = moveAngle + delta;
        int sector = (int) Math.round(finalAngle / 45.0) & 7;

        boolean f = false, b = false, l = false, r = false;
        switch (sector) {
            case 0 -> f = true;
            case 1 -> { f = true; r = true; }
            case 2 -> r = true;
            case 3 -> { b = true; r = true; }
            case 4 -> b = true;
            case 5 -> { b = true; l = true; }
            case 6 -> l = true;
            case 7 -> { f = true; l = true; }
        }
        return new Input(f, b, l, r, real.jump(), real.shift(), real.sprint());
    }

    private void performSilent(RotationRequest req) {
        float targetYaw = req.targetYaw;
        float targetPitch = req.targetPitch;

        float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        targetPitch = Mth.clamp(targetPitch + f, -90.0f, 90.0f);

        setServerYaw(targetYaw);
        setServerPitch(targetPitch);

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                targetYaw, targetPitch, mc.player.onGround(), true
        ));
    }

    private RotationRequest getActiveRequest() {
        return requests.isEmpty() ? null : requests.get(0);
    }

    private void removeActiveRequest() {
        if (!requests.isEmpty()) requests.remove(0);
    }

    private void updateRealRotation(float yaw, float pitch) {
        realYaw = yaw;
        realPitch = Mth.clamp(pitch, -90f, 90f);
    }

    public boolean isRotating() {
        return getActiveRequest() != null || returning;
    }

    public boolean isActive(String id) {
        RotationRequest active = getActiveRequest();
        return active != null && active.id.equals(id);
    }

    public boolean isMoveFixEnabled() {
        return MOVE_FIX;
    }

    public float getRealYaw() { return realYaw; }
    public float getRealPitch() { return realPitch; }

    public float getRotationYaw() { return rotationYaw; }
    public float getRotationPitch() { return rotationPitch; }

    public float getRenderYaw(float partialTicks) {
        return Mth.rotLerp(partialTicks, rotationYaw0, rotationYaw);
    }

    public float getRenderPitch(float partialTicks) {
        return rotationPitch0 + (rotationPitch - rotationPitch0) * partialTicks;
    }

    public float getServerYaw() { return serverYaw; }
    public float getServerPitch() { return serverPitch; }
    public float getServerYaw0() { return serverYaw0; }
    public float getServerDeltaYaw() { return serverDeltaYaw; }

    public void setServerYaw(float yaw) {
        this.serverYaw0 = this.serverYaw;
        this.serverYaw = yaw;
    }
    public void setServerPitch(float pitch) {
        this.serverPitch = pitch;
    }
    public void setServerDeltaYaw(float delta) {
        this.serverDeltaYaw = delta;
    }

    public boolean isBypassUseSpoof() { return bypassUseSpoof; }
    public void setBypassUseSpoof(boolean bypass) { bypassUseSpoof = bypass; }

    public boolean isSilentSyncRequired() { return silentSyncRequired; }

    public boolean isSilentSyncRequiredAtLeast(int priority) {
        return silentSyncRequired && silentSentPriority >= priority;
    }

    public void setSilentSyncRequired(boolean required) { silentSyncRequired = required; }

    public void resetSilentTick() { silentSentThisTick = false; }
    public boolean isSilentActive() { return System.currentTimeMillis() - lastSilentMs < 500; }

    private static float yawDifference(float target, float current) {
        return Mth.wrapDegrees(target - current);
    }

    private static float alignYaw(float base, float reference) {
        int wraps = Math.round((reference - base) / 360f);
        return base + wraps * 360f;
    }

    private static float lerp(float from, float to, float factor) {
        return from + (to - from) * factor;
    }
}
