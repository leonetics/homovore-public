package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.render.BreakIndicatorsModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapRequest;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class PhaseModule extends Module {

    private static final double CORNER_THRESHOLD = 0.5;
    private static final double CORNER_OFFSET = 0.5;

    private static final int PRIORITY = 150;

    private static final double ARRIVE_EPSILON = 0.02;

    private final Setting<Boolean> autoPhase = bool("AutoPhase", false);
    private final Setting<Double> autoPhaseThreshold = num("AutoPhaseThreshold", 0.5, 0.05, 1.0);
    private final Setting<Double> autoPhaseSpeed = num("AutoPhaseSpeed", 0.08, 0.01, 0.3);
    private final Setting<Boolean> autoPhaseAdvance = bool("AutoPhaseAdvance", true);

    public PhaseModule() {
        super("Phase", "Phases into walls", Category.COMBAT);
        autoPhaseThreshold.setVisibility(v -> autoPhase.getValue());
        autoPhaseSpeed.setVisibility(v -> autoPhase.getValue());
        autoPhaseAdvance.setVisibility(v -> autoPhase.getValue());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) {
            disable();
            return;
        }

        if (autoPhase.getValue() && isClipped()) {
            autoPhaseTick();
            return;
        }

        if (Homovore.rotationManager.isSilentSyncRequiredAtLeast(PRIORITY)) return;

        Result pearl = InventoryUtil.find(Items.ENDER_PEARL, InventoryUtil.FULL_SCOPE);
        if (!pearl.found()) {
            if (!autoPhase.getValue()) disable();
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            if (!autoPhase.getValue()) disable();
            return;
        }

        if (mc.player.isCrouching()) {
            if (!autoPhase.getValue()) disable();
            return;
        }

        Vec3 target = calculateTargetPos();
        float yaw = calcYaw(target);
        float pitch = mc.player.getBlockY() > 4 ? 85f : 75f;

        Homovore.rotationManager.submit(new RotationRequest("phase", PRIORITY, yaw, pitch, RotationRequest.Mode.SILENT));

        mc.gameMode.ensureHasSentCarriedItem();
        boolean thrown = Homovore.swapManager.submit(new SwapRequest("Phase", 80, pearl, r -> {
            try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                mc.getConnection().send(new ServerboundUseItemPacket(r.hand(), handler.currentSequence(), yaw, pitch));
            }
        }, true));

        if (thrown && !autoPhase.getValue()) disable();
    }

    private void autoPhaseTick() {
        List<BlockPos> overlapped = overlappedColumns();
        if (overlapped.isEmpty()) return;

        List<BlockPos> safe = new ArrayList<>();
        for (BlockPos column : overlapped) {
            if (isColumnSolid(column) && columnProgress(column) < autoPhaseThreshold.getValue()) {
                safe.add(column);
            }
        }

        Vec3 target = safe.isEmpty()
                ? (autoPhaseAdvance.getValue() ? nextQuadTarget() : null)
                : clipTargetFor(safe);
        if (target == null) return;

        moveToward(target);
    }

    private List<BlockPos> overlappedColumns() {
        AABB box = mc.player.getBoundingBox().deflate(0.001, 0.0, 0.001);
        int feetY = Mth.floor(mc.player.getY() + 0.01);
        int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);

        List<BlockPos> columns = new ArrayList<>(4);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                columns.add(new BlockPos(x, feetY, z));
            }
        }
        return columns;
    }

    private boolean isColumnSolid(BlockPos column) {
        return isSolid(column) || isSolid(column.above());
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private double columnProgress(BlockPos column) {
        BreakIndicatorsModule indicators = Homovore.moduleManager.getModuleByClass(BreakIndicatorsModule.class);
        if (indicators == null || indicators.isDisabled()) return 0.0;

        double feet = isSolid(column) ? indicators.getBlockBreakProgress(column) : 1.0;
        BlockPos head = column.above();
        double top = isSolid(head) ? indicators.getBlockBreakProgress(head) : 1.0;
        return Math.max(feet, top);
    }

    private Vec3 clipTargetFor(List<BlockPos> safe) {
        double y = mc.player.getY();

        if (safe.size() == 1) {
            BlockPos column = safe.getFirst();
            return new Vec3(column.getX() + 0.5, y, column.getZ() + 0.5);
        }

        double sumX = 0.0;
        double sumZ = 0.0;
        for (BlockPos column : safe) {
            sumX += column.getX() + 0.5;
            sumZ += column.getZ() + 0.5;
        }
        return new Vec3(sumX / safe.size(), y, sumZ / safe.size());
    }

    private Vec3 nextQuadTarget() {
        Vec3 input = inputDirection();
        if (input.lengthSqr() == 0.0) return null;

        double cornerX = Math.round(mc.player.getX());
        double cornerZ = Math.round(mc.player.getZ());
        return new Vec3(cornerX + input.x, mc.player.getY(), cornerZ + input.z);
    }

    private Vec3 inputDirection() {
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        if (forward == 0.0 && strafe == 0.0) return Vec3.ZERO;

        double yaw = Math.toRadians(mc.player.getYRot());
        double x = strafe * Math.cos(yaw) - forward * Math.sin(yaw);
        double z = forward * Math.cos(yaw) + strafe * Math.sin(yaw);

        return Math.abs(x) >= Math.abs(z)
                ? new Vec3(Math.signum(x), 0.0, 0.0)
                : new Vec3(0.0, 0.0, Math.signum(z));
    }

    private void moveToward(Vec3 target) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq <= ARRIVE_EPSILON * ARRIVE_EPSILON) return;

        double dist = Math.sqrt(distSq);
        double step = Math.min(autoPhaseSpeed.getValue(), dist);
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(dx / dist * step, motion.y, dz / dist * step);
    }

    private boolean isClipped() {
        AABB box = mc.player.getBoundingBox().deflate(0.001);
        return !mc.level.noCollision(mc.player, box);
    }

    private Vec3 calculateTargetPos() {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        double nearestIntX = Math.round(playerX);
        double nearestIntZ = Math.round(playerZ);
        double dxCorner = nearestIntX - playerX;
        double dzCorner = nearestIntZ - playerZ;

        double threshold = CORNER_THRESHOLD;
        double offset = CORNER_OFFSET;
        if (Math.abs(dxCorner) <= threshold && Math.abs(dzCorner) <= threshold) {
            return new Vec3(
                playerX + Mth.clamp(dxCorner, -offset, offset),
                mc.player.getY() - 0.5,
                playerZ + Mth.clamp(dzCorner, -offset, offset)
            );
        }

        final double A = Math.PI / 13;
        final double B = Math.PI / 4;

        double x = playerX + Mth.clamp(
            toClosest(playerX, Math.floor(playerX) + A, Math.floor(playerX) + B) - playerX,
            -0.2, 0.2);
        double z = playerZ + Mth.clamp(
            toClosest(playerZ, Math.floor(playerZ) + A, Math.floor(playerZ) + B) - playerZ,
            -0.2, 0.2);

        return new Vec3(x, mc.player.getY() - 0.5, z);
    }

    private double toClosest(double num, double min, double max) {
        return (num - min) > (max - num) ? max : min;
    }

    private float calcYaw(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 diff = target.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
    }
}
