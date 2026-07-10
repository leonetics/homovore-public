package dev.leonetic.features.modules.movement;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ElytraAssistModule extends Module {

    public static final String ID = "ElytraAssist";
    public static final int PRIORITY = 90;

    private final Setting<Double> lookahead = num("Lookahead", 10.0, 10.0, 60.0);

    private final Setting<Double> clearance = num("Clearance", 0.15, 0.0, 1.5);

    private final Setting<Double> maxYaw = num("MaxYaw", 90.0, 0.0, 90.0);

    private final Setting<Double> react = num("React", 20.0, 4.0, 40.0);

    private final Setting<Double> climbBias = num("ClimbBias", 3.0, 0.0, 20.0);

    private static final int RELEASE_HYST = 6;

    private static final int COMMIT_TICKS = 3;

    private static final double DESCEND_EPS = 0.5;

    private static final double DRAG_H = 0.99;
    private static final double DRAG_V = 0.98;
    private static final double DEFAULT_GRAVITY = 0.08;

    private static final double[] PITCH_OFFSETS = {
            0, -2, -4, -6, -9, -13, -18, -25, -35, -50, -70, -90,
            2, 4, 7, 11, 16, 24
    };

    private static final double[] YAW_OFFSETS = {
            0, 2, -2, 4, -4, 7, -7, 11, -11, 16, -16, 23, -23, 32, -32, 45, -45, 65, -65, 90, -90
    };

    private static final double YAW_COST_WEIGHT = 1.5;

    private static final double W_SURVIVAL = 1000.0;

    private static final double W_CONTINUITY = 2.0;

    private static final double SUBSTEP_SIZE = 0.45;

    private static final int MAX_SUBSTEPS = 12;

    private boolean steering;
    private int clearTicks;
    private float lastYaw, lastPitch;

    private List<double[]> offsets;

    public ElytraAssistModule() {
        super("ElytraAssist", "Aim-assist for elytra: nudges your aim the least amount needed to clear obstacles, always preferring to climb over dive.", Category.MOVEMENT);
    }

    public boolean isSteering() {
        return steering;
    }

    @Override
    public void onDisable() {
        stop();
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || !mc.player.isFallFlying()) {
            stop();
            return;
        }

        AABB detect = mc.player.getBoundingBox().inflate(clearance.getValue());
        Vec3 startVel = mc.player.getDeltaMovement();
        double gravity = effectiveGravity(startVel);
        int ticks = lookahead.getValue().intValue();

        float lookYaw = mc.player.getYRot();
        float lookPitch = mc.player.getXRot();

        int aimSurvival = simulateSurvival(lookYaw, lookPitch, detect, startVel, gravity, ticks);
        int engageWithin = (int) Math.min(react.getValue(), ticks);
        int releaseWithin = Math.min(ticks, engageWithin + RELEASE_HYST);
        boolean clear = steering ? aimSurvival >= releaseWithin : aimSurvival >= engageWithin;
        if (clear) {
            if (steering && ++clearTicks < COMMIT_TICKS) {
                resubmit();
                return;
            }
            stop();
            return;
        }
        clearTicks = 0;

        if (steering && simulateSurvival(lastYaw, lastPitch, detect, startVel, gravity, ticks) >= releaseWithin) {
            resubmit();
            return;
        }

        float[] heading = chooseHeading(lookYaw, lookPitch, detect, startVel, gravity, ticks);
        steerTo(heading[0], heading[1]);
    }

    private float[] chooseHeading(float lookYaw, float lookPitch, AABB detect, Vec3 startVel, double gravity, int ticks) {
        double yawCap = maxYaw.getValue();
        boolean committed = steering;

        int bestTier = Integer.MAX_VALUE;
        double bestScore = Double.NEGATIVE_INFINITY;
        float bestYaw = lookYaw, bestPitch = lookPitch;

        for (double[] off : offsets()) {
            if (Math.abs(off[0]) > yawCap) continue;
            float yaw = lookYaw + (float) off[0];
            float pitch = Mth.clamp(lookPitch + (float) off[1], -90f, 90f);

            double[] sim = simulate(yaw, pitch, detect, startVel, gravity, ticks);
            int surv = (int) sim[0];
            boolean clears = surv >= ticks;
            boolean descends = sim[1] < -DESCEND_EPS;

            int tier = clears ? 0 : (descends ? 2 : 1);

            double dev = deviation(off);
            double cont = committed ? headingDelta(yaw, pitch, lastYaw, lastPitch) : 0.0;

            double descentPenalty = clears ? -sim[1] * climbBias.getValue() : 0.0;
            double score = (clears ? 0.0 : surv * W_SURVIVAL) - dev - descentPenalty - cont * W_CONTINUITY;

            if (tier < bestTier || (tier == bestTier && score > bestScore)) {
                bestTier = tier;
                bestScore = score;
                bestYaw = yaw;
                bestPitch = pitch;
            }
        }

        return new float[]{bestYaw, bestPitch};
    }

    private double deviation(double[] off) {
        double yw = off[0] * YAW_COST_WEIGHT;
        return Math.sqrt(yw * yw + off[1] * off[1]);
    }

    private double headingDelta(float yaw, float pitch, float refYaw, float refPitch) {
        double dy = Mth.wrapDegrees(yaw - refYaw) * YAW_COST_WEIGHT;
        double dp = pitch - refPitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    private void resubmit() {
        Homovore.rotationManager.submit(new RotationRequest(ID, PRIORITY, lastYaw, lastPitch, RotationRequest.Mode.MOTION, true, true));
    }

    private void steerTo(float yaw, float pitch) {
        lastYaw = yaw;
        lastPitch = Mth.clamp(pitch, -90f, 90f);
        resubmit();
        steering = true;
    }

    private void stop() {
        clearTicks = 0;
        if (steering) {
            Homovore.rotationManager.cancel(ID);
            steering = false;
        }
    }

    private double[] simulate(float yaw, float pitch, AABB box, Vec3 startVel, double gravity, int horizon) {
        Vec3 vel = startVel;
        double ox = 0, oy = 0, oz = 0;
        double lowestY = 0;

        for (int t = 0; t < horizon; t++) {
            vel = stepFlight(vel, yaw, pitch, gravity);

            double startX = ox, startY = oy, startZ = oz;
            ox += vel.x;
            oy += vel.y;
            oz += vel.z;
            int sub = Mth.clamp((int) Math.ceil(vel.length() / SUBSTEP_SIZE), 1, MAX_SUBSTEPS);
            for (int s = 1; s <= sub; s++) {
                double frac = (double) s / sub;
                if (!mc.level.noCollision(box.move(startX + vel.x * frac, startY + vel.y * frac, startZ + vel.z * frac))) {
                    return new double[]{t, lowestY};
                }
            }
            if (oy < lowestY) lowestY = oy;
        }
        return new double[]{horizon, lowestY};
    }

    private int simulateSurvival(float yaw, float pitch, AABB box, Vec3 startVel, double gravity, int horizon) {
        return (int) simulate(yaw, pitch, box, startVel, gravity, horizon)[0];
    }

    private Vec3 stepFlight(Vec3 vel, float yaw, float pitch, double gravity) {
        Vec3 look = dirFromAngles(yaw, pitch);
        double f = Math.toRadians(pitch);
        double horizLook = Math.sqrt(look.x * look.x + look.z * look.z);
        double cosSq = Math.cos(f);
        cosSq *= cosSq;
        double sinF = Math.sin(f);

        double vx = vel.x, vy = vel.y, vz = vel.z;
        double horizSpeed = Math.sqrt(vx * vx + vz * vz);

        vy += gravity * (-1.0 + cosSq * 0.75);

        if (vy < 0 && horizLook > 0) {
            double k = vy * -0.1 * cosSq;
            vx += look.x * k / horizLook;
            vy += k;
            vz += look.z * k / horizLook;
        }

        if (f < 0 && horizLook > 0) {
            double k = horizSpeed * (-sinF) * 0.04;
            vx += -look.x * k / horizLook;
            vy += k * 3.2;
            vz += -look.z * k / horizLook;
        }

        if (horizLook > 0) {
            vx += (look.x / horizLook * horizSpeed - vx) * 0.1;
            vz += (look.z / horizLook * horizSpeed - vz) * 0.1;
        }

        return new Vec3(vx * DRAG_H, vy * DRAG_V, vz * DRAG_H);
    }

    private double effectiveGravity(Vec3 velocity) {
        double g = mc.player.getAttributes().hasAttribute(Attributes.GRAVITY)
                ? mc.player.getAttributeValue(Attributes.GRAVITY)
                : DEFAULT_GRAVITY;
        if (velocity.y <= 0.0 && mc.player.hasEffect(MobEffects.SLOW_FALLING)) {
            g = Math.min(g, 0.01);
        }
        return g;
    }

    private List<double[]> offsets() {
        if (offsets != null) return offsets;

        List<double[]> list = new ArrayList<>();
        for (double y : YAW_OFFSETS) {
            for (double p : PITCH_OFFSETS) {
                if (y == 0 && p == 0) continue;
                list.add(new double[]{y, p});
            }
        }
        list.sort(Comparator.comparingDouble(this::deviation));
        offsets = list;
        return list;
    }

    private Vec3 dirFromAngles(float yaw, float pitch) {
        double ry = Math.toRadians(yaw);
        double rp = Math.toRadians(pitch);
        double cosP = Math.cos(rp);
        return new Vec3(-cosP * Math.sin(ry), -Math.sin(rp), cosP * Math.cos(ry));
    }

    @Override
    public String getDisplayInfo() {
        return steering ? "Steering" : null;
    }
}
