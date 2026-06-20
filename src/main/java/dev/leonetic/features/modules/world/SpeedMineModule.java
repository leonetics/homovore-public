package dev.leonetic.features.modules.world;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.AttackBlockEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.combat.OffhandModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.manager.SwapRequest;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.mixin.entity.EntityRotationAccessor;
import dev.leonetic.util.EnchantmentUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.concurrent.TimeUnit;

public class SpeedMineModule extends Module {

    private static final double USER_PRIORITY = 100.0;

    private static final int MINE_SWAP_PRIORITY = 65;

    private static final long INIT_NANOS = System.nanoTime();

    private SilentMineBlock rebreakBlock;
    private SilentMineBlock delayedDestroyBlock;
    private BlockPos lastDelayedDestroyBlockPos;

    private double currentGameTickCalculated;

    private boolean brokeThisTick;

    private SwapManager.SwapHandle mineSwapHandle;

    private boolean heldPickaxeThisTick;

    private boolean usingMainhandThisTick;

    public interface MineFinishListener { void onMineFinish(BlockPos pos); }
    private final java.util.concurrent.CopyOnWriteArrayList<MineFinishListener> finishListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    public void addFinishListener(MineFinishListener l)    { finishListeners.addIfAbsent(l); }
    public void removeFinishListener(MineFinishListener l) { finishListeners.remove(l); }
    private void fireFinish(BlockPos pos) {
        for (MineFinishListener l : finishListeners) l.onMineFinish(pos);
    }

    private final Setting<Boolean> swing              = bool("Swing", false);
    private final Setting<Integer> singleBreakFailTicks = num("SingleBreakFailTicks", 20, 5, 50);

    private final Setting<Boolean> debugLog           = bool("DebugLog", true);
    private final Setting<Boolean> rebreakSetBroken   = bool("ClientSideBreak", true);

    private static final float BREAK_RANGE = 5.5f;

    private final Setting<Boolean> render             = bool("Render", true).setPage("Render");
    private final Setting<Float>   lineWidth          = num("LineWidth", 2.0f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color>   lineColor          = color("LineColor", 255, 255, 255, 150).setPage("Render");
    private final Setting<Color>   sideColor          = color("SideColor", 255, 255, 255, 40).setPage("Render");
    private final Setting<Color>   primaryColor       = color("PrimaryColor", 255, 180, 255, 60).setPage("Render");

    public SpeedMineModule() {
        super(
                "SpeedMine",
                "Mines two blocks simultaneously using GrimV3 packet mining (gware SilentMine port)",
                Category.WORLD
        );
    }

    @Override
    public void onDisable() {
        if (rebreakBlock != null) rebreakBlock.cancelBreaking();
        if (delayedDestroyBlock != null) delayedDestroyBlock.cancelBreaking();
        rebreakBlock = null;
        delayedDestroyBlock = null;
        lastDelayedDestroyBlockPos = null;

        if (mineSwapHandle != null) {
            Homovore.swapManager.release(mineSwapHandle);
            mineSwapHandle = null;
        }
    }

    private double getCurrentGameTickCalculated() {
        return (double) (System.nanoTime() - INIT_NANOS)
                / (double) TimeUnit.MILLISECONDS.toNanos(50L);
    }

    private boolean withPickaxe(BlockState state, Runnable burst, boolean rebreak) {
        Result pickaxe = bestPickaxeResult(state);

        if (!pickaxe.found() || pickaxe.holding()) {
            if (!rebreak && pickaxe.holding() && mineSwapHandle != null) heldPickaxeThisTick = true;
            burst.run();
            return true;
        }

        if (usingMainhand()) return false;

        if (!rebreak) {
            if (mineSwapHandle == null || mineSwapHandle.isReleased()) {
                mineSwapHandle = Homovore.swapManager.acquire("SpeedMine", MINE_SWAP_PRIORITY);
                if (mineSwapHandle == null) return false;
            }

            if (InventoryUtil.selected() != pickaxe.slot()) InventoryUtil.swap(pickaxe);
            heldPickaxeThisTick = true;
            burst.run();
            return true;
        }

        return Homovore.swapManager.submit(new SwapRequest(
                "SpeedMine", MINE_SWAP_PRIORITY, pickaxe, burst, false));
    }

    private boolean usingMainhand() {
        return usingMainhandThisTick;
    }

    public boolean silentBreakBlock(BlockPos pos, double priority) {
        return silentBreakBlock(pos, Direction.UP, priority);
    }

    public boolean silentBreakBlock(BlockPos blockPos, Direction direction, double priority) {
        if (nullCheck()) return false;
        if (mc.player.isCreative() || mc.player.isSpectator()) return false;
        if (blockPos == null || alreadyBreaking(blockPos)) return false;
        if (!canBreak(blockPos)) return false;
        if (!inBreakRange(blockPos)) return false;

        if (!hasDelayedDestroy() && rebreakBlock != null && !blockPos.equals(rebreakBlock.blockPos)) {
            promoteRebreakToDelayedDestroy();
        }

        if (!hasDelayedDestroy() && rebreakBlock == null) {
            rebreakBlock = new SilentMineBlock(blockPos, direction, priority, true);
            rebreakBlock.startBreaking(false);
            return true;
        }

        if (alreadyBreaking(blockPos)) {
            return true;
        }

        if (rebreakBlock != null && delayedDestroyBlock != null
                && (priority >= rebreakBlock.priority || canRebreakRebreakBlock())) {
            if (delayedDestroyBlock.getBreakProgress() <= 0.8) {
                rebreakBlock = null;
            }
        }

        if (rebreakBlock == null) {
            rebreakBlock = new SilentMineBlock(blockPos, direction, priority, true);
            rebreakBlock.startBreaking(false);
        }
        return true;
    }

    private void promoteRebreakToDelayedDestroy() {
        if (rebreakBlock == null || delayedDestroyBlock != null) return;
        delayedDestroyBlock = rebreakBlock.promoteToDelayedDestroy();
        rebreakBlock = null;
    }

    public boolean alreadyBreaking(BlockPos blockPos) {
        return (rebreakBlock != null && blockPos.equals(rebreakBlock.blockPos))
                || (delayedDestroyBlock != null && blockPos.equals(delayedDestroyBlock.blockPos));
    }

    @Subscribe
    private void onAttackBlock(AttackBlockEvent event) {
        if (nullCheck()) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        event.cancel();

        silentBreakBlock(event.getPos(), event.getDirection(), USER_PRIORITY);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        brokeThisTick = false;
        heldPickaxeThisTick = false;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        usingMainhandThisTick = (offhand != null && offhand.shouldDeferForEat())
                || (mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND);
        currentGameTickCalculated = getCurrentGameTickCalculated();

        lastDelayedDestroyBlockPos = hasDelayedDestroy() ? delayedDestroyBlock.blockPos : null;

        if (delayedDestroyBlock != null && !inBreakRange(delayedDestroyBlock.blockPos)) {
            delayedDestroyBlock.cancelBreaking();
            delayedDestroyBlock = null;
        }
        if (rebreakBlock != null && !inBreakRange(rebreakBlock.blockPos)) {
            rebreakBlock.cancelBreaking();
            rebreakBlock = null;
        }

        if (hasDelayedDestroy() && (mc.level.getBlockState(delayedDestroyBlock.blockPos).isAir()
                || !canBreak(delayedDestroyBlock.blockPos))) {
            delayedDestroyBlock = null;
        }

        if (rebreakBlock != null && (mc.level.getBlockState(rebreakBlock.blockPos).isAir()
                || !canBreak(rebreakBlock.blockPos))) {
            rebreakBlock.beenAir = true;
        }

        if (hasRebreakBlock() && rebreakBlock.timesSendBreakPacket > singleBreakFailTicks.getValue()
                && !canRebreakRebreakBlock()) {
            rebreakBlock.cancelBreaking();
            rebreakBlock = null;
        }

        if (swing.getValue() && (hasDelayedDestroy() || rebreakBlock != null)) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (debugLog.getValue()) {
                Homovore.LOGGER.info("[SpeedMine] swing (live dig: rebreak={} delayed={})",
                        rebreakBlock != null, hasDelayedDestroy());
            }
        }

        tryFinalizeRebreak();
        sustainDelayedDestroy();

        if (hasDelayedDestroy() && delayedDestroyBlock.ticksHeldPickaxe > singleBreakFailTicks.getValue()) {
            if (inBreakRange(delayedDestroyBlock.blockPos)) {
                delayedDestroyBlock.startBreaking(true);
            } else {
                delayedDestroyBlock.cancelBreaking();
                delayedDestroyBlock = null;
            }
        }

        releaseMineSwap();
    }

    private void releaseMineSwap() {
        if (!heldPickaxeThisTick && mineSwapHandle != null) {
            Homovore.swapManager.release(mineSwapHandle);
            mineSwapHandle = null;
        }
    }

    private boolean tryFinalizeRebreak() {
        if (rebreakBlock == null) return false;

        if (!rebreakBlock.isReady()) return false;

        if (!inBreakRange(rebreakBlock.blockPos)) {
            rebreakBlock = null;
            return false;
        }

        if (sendFinishMine(rebreakBlock, true)) {
            if (rebreakSetBroken.getValue() && canRebreakRebreakBlock()) {
                mc.level.setBlockAndUpdate(
                        rebreakBlock.blockPos,
                        Blocks.AIR.defaultBlockState()
                );
            }
            return true;
        }

        return false;
    }

    private void sustainDelayedDestroy() {
        if (!hasDelayedDestroy()) return;
        if (delayedDestroyBlock.ticksHeldPickaxe > singleBreakFailTicks.getValue()) return;
        if (!delayedDestroyBlock.isReady()) return;

        BlockState state = mc.level.getBlockState(delayedDestroyBlock.blockPos);
        if (state.isAir()) return;

        if (holdPickaxe(state)) {
            delayedDestroyBlock.ticksHeldPickaxe++;
        }
    }

    private boolean holdPickaxe(BlockState state) {
        Result pickaxe = bestPickaxeResult(state);

        if (!pickaxe.found() || pickaxe.holding()) {
            if (pickaxe.holding() && mineSwapHandle != null) heldPickaxeThisTick = true;
            return true;
        }

        if (usingMainhand()) return false;

        if (mineSwapHandle == null || mineSwapHandle.isReleased()) {
            mineSwapHandle = Homovore.swapManager.acquire("SpeedMine", MINE_SWAP_PRIORITY);
            if (mineSwapHandle == null) return false;
        }
        if (InventoryUtil.selected() != pickaxe.slot()) InventoryUtil.swap(pickaxe);
        heldPickaxeThisTick = true;
        return true;
    }

    private boolean sendFinishMine(SilentMineBlock data, boolean notifyFinish) {

        if (brokeThisTick) return false;

        BlockState state = mc.level.getBlockState(data.blockPos);

        if (!data.beenAir && state.isAir()) return false;

        Runnable burst = () -> {
            if (notifyFinish) fireFinish(data.blockPos);

            data.tryBreak();
            data.timesSendBreakPacket++;
        };

        boolean shipped;
        String path;
        if (state.isAir()) {

            if (usingMainhand()) return false;

            burst.run();
            shipped = true;
            path = "in-hand(air)";
        } else {

            shipped = withPickaxe(state, burst, data.beenAir);
            path = data.beenAir ? "silent-rebreak" : "visible-fresh";
        }
        if (shipped) brokeThisTick = true;

        if (debugLog.getValue()) {
            Homovore.LOGGER.info(
                    "[SpeedMine] finalize {} pos={} beenAir={} sends={} prog={} swap={} shipped={}",
                    path, data.blockPos, data.beenAir, data.timesSendBreakPacket,
                    String.format("%.2f", data.getBreakProgress()), !state.isAir() && !bestPickaxeResult(state).holding(),
                    shipped);
        }
        return shipped;
    }

    public boolean hasDelayedDestroy() {
        return delayedDestroyBlock != null;
    }

    public boolean hasRebreakBlock() {
        return rebreakBlock != null && !rebreakBlock.beenAir;
    }

    public boolean canRebreakRebreakBlock() {
        return rebreakBlock != null && rebreakBlock.beenAir;
    }

    public BlockPos getRebreakBlockPos() {
        return rebreakBlock != null ? rebreakBlock.blockPos : null;
    }

    public BlockPos getDelayedDestroyBlockPos() {
        return delayedDestroyBlock != null ? delayedDestroyBlock.blockPos : null;
    }

    public BlockPos getLastDelayedDestroyBlockPos() {
        return lastDelayedDestroyBlockPos;
    }

    public boolean inBreakRange(BlockPos pos) {
        if (mc.player == null || pos == null) return false;
        double r = BREAK_RANGE;
        Vec3 eye = mc.player.getEyePosition();
        double cx = Mth.clamp(eye.x, pos.getX(), pos.getX() + 1.0);
        double cy = Mth.clamp(eye.y, pos.getY(), pos.getY() + 1.0);
        double cz = Mth.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0);
        double dx = eye.x - cx, dy = eye.y - cy, dz = eye.z - cz;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    public void collectMiningPositions(java.util.Set<BlockPos> out, double minProgress) {
        double tick = getCurrentGameTickCalculated();
        if (rebreakBlock != null && rebreakBlock.getBreakProgress(tick) >= minProgress) out.add(rebreakBlock.blockPos);
        if (delayedDestroyBlock != null && delayedDestroyBlock.getBreakProgress(tick) >= minProgress) out.add(delayedDestroyBlock.blockPos);
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || !render.getValue()) return;
        if (rebreakBlock != null)        drawBlock(event, rebreakBlock, true);
        if (delayedDestroyBlock != null) drawBlock(event, delayedDestroyBlock, false);
    }

    private void drawBlock(Render3DEvent event, SilentMineBlock data, boolean isPrimary) {
        double prog = data.getBreakProgress(getCurrentGameTickCalculated());

        Color side = isPrimary ? primaryColor.getValue() : sideColor.getValue();
        Color line = lineColor.getValue();
        float lw = lineWidth.getValue();

        boolean isInstantRebreak = isPrimary && data.beenAir && prog >= 0.7;
        if (isInstantRebreak) {
            RenderUtil.drawBoxFilled(event.getMatrix(), data.blockPos, side);
            RenderUtil.drawBox(event.getMatrix(), data.blockPos, line, lw);
            return;
        }

        float t = (float) Math.min(1.0, isPrimary ? prog / 0.7 : prog);
        double cx = data.blockPos.getX() + 0.5;
        double cy = data.blockPos.getY() + 0.5;
        double cz = data.blockPos.getZ() + 0.5;
        double half = 0.5 * t;
        AABB box = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);
        RenderUtil.drawBoxFilled(event.getMatrix(), box, side);
        RenderUtil.drawBox(event.getMatrix(), box, line, lw);
    }

    private boolean canBreak(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        return !s.isAir() && s.getDestroySpeed(mc.level, pos) >= 0;
    }

    private void sendAction(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction dir) {
        if (mc.level == null) return;
        try (var handler = ((ClientLevelAccessor) mc.level)
                .homovore$getBlockStatePredictionHandler()
                .startPredicting()) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    action, pos, dir, handler.currentSequence()
            ));
        }
    }

    private Result bestPickaxeResult(BlockState state) {
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack item = mc.player.getInventory().getItem(i);
            if (item.isEmpty()) continue;
            float speed = item.getDestroySpeed(state);
            if (speed > 1.0f) {
                int eff = EnchantmentUtil.getLevel(Enchantments.EFFICIENCY, item);
                if (eff > 0) speed += eff * eff + 1;
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best == -1) return new Result(-1, ItemStack.EMPTY, ResultType.NONE);
        return new Result(best, mc.player.getInventory().getItem(best), ResultType.HOTBAR);
    }

    private int bestToolSlot(BlockState state) {
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack item = mc.player.getInventory().getItem(i);
            float speed = item.getDestroySpeed(state);
            if (speed > 1.0f) {
                int eff = EnchantmentUtil.getLevel(Enchantments.EFFICIENCY, item);
                if (eff > 0) speed += eff * eff + 1;
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    private ItemStack bestToolStack(BlockState state) {
        int slot = bestToolSlot(state);
        if (slot == -1) slot = mc.player.getInventory().getSelectedSlot();
        return mc.player.getInventory().getItem(slot);
    }

    private float calcDelta(ItemStack item, BlockPos pos, BlockState state, boolean onGround) {
        float speed = item.getDestroySpeed(state);
        if (speed > 1.0f) {
            int eff = EnchantmentUtil.getLevel(Enchantments.EFFICIENCY, item);
            if (eff > 0) speed += eff * eff + 1;
        }
        if (MobEffectUtil.hasDigSpeed(mc.player)) {
            speed *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2f;
        }
        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            int amp = mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier();
            float g = switch (amp) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1e-4f;
            };
            speed *= g;
        }

        if (mc.player.isEyeInFluid(FluidTags.WATER)
                && !EnchantmentUtil.has(Enchantments.AQUA_AFFINITY, EquipmentSlot.HEAD)) {
            speed /= 5.0f;
        }
        if (!onGround) speed /= 5.0f;
        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness < 0) return 0f;
        boolean correct = !state.requiresCorrectToolForDrops() || item.isCorrectToolForDrops(state);
        return speed / hardness / (correct ? 30f : 100f);
    }

    private boolean serverKnownOnGround() {
        return ((EntityRotationAccessor) mc.player).homovore$getLastOnGround();
    }

    private boolean willBeOnGround() {
        AABB bb = mc.player.getBoundingBox();
        double feetY = bb.minY;
        AABB ground = new AABB(bb.minX, feetY - 0.2, bb.minZ, bb.maxX, feetY, bb.maxZ);
        double velReach = Math.abs(mc.player.getDeltaMovement().y * 2);
        for (BlockPos p : BlockPos.betweenClosed(
                Mth.floor(ground.minX), Mth.floor(ground.minY), Mth.floor(ground.minZ),
                Mth.floor(ground.maxX), Mth.floor(ground.maxY), Mth.floor(ground.maxZ))) {
            BlockState s = mc.level.getBlockState(p);
            if (s.isAir()) continue;
            double dist = feetY - (p.getY() + 1.0);
            if (dist >= 0 && dist < velReach) return true;
        }
        return false;
    }

    private class SilentMineBlock {
        final BlockPos blockPos;
        final Direction direction;
        final double priority;

        final boolean isRebreak;

        boolean beenAir;
        int timesSendBreakPacket;
        int ticksHeldPickaxe;
        double destroyProgressStart;

        SilentMineBlock(BlockPos blockPos, Direction direction, double priority, boolean isRebreak) {
            this.blockPos = blockPos;
            this.direction = direction;
            this.priority = priority;
            this.isRebreak = isRebreak;
        }

        SilentMineBlock promoteToDelayedDestroy() {
            SilentMineBlock promoted = new SilentMineBlock(blockPos, direction, priority, false);
            promoted.beenAir = beenAir;
            promoted.ticksHeldPickaxe = ticksHeldPickaxe;
            promoted.timesSendBreakPacket = 0;
            promoted.destroyProgressStart = destroyProgressStart;
            return promoted;
        }

        boolean isReady() {

            if (beenAir) return true;
            if (!canBreak(blockPos)) return false;
            return getBreakProgress() >= 0.7 || timesSendBreakPacket > 0;
        }

        double getBreakProgress() {
            return getBreakProgress(currentGameTickCalculated);
        }

        double getBreakProgress(double gameTick) {
            BlockState state = mc.level.getBlockState(blockPos);
            boolean onGround = serverKnownOnGround() || (willBeOnGround() && !isRebreak);
            double perTick = calcDelta(bestToolStack(state), blockPos, state, onGround);
            return Math.min(perTick * (gameTick - destroyProgressStart), 1.0);
        }

        void startBreaking(boolean isDelayedDestroy) {
            ticksHeldPickaxe = 0;
            timesSendBreakPacket = 0;
            destroyProgressStart = currentGameTickCalculated;

            if (isDelayedDestroy && canRebreakRebreakBlock()) {
                rebreakBlock = null;
            }

            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction);
        }

        void tryBreak() {
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction);
            sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction);
        }

        void cancelBreaking() {
            sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction);
        }
    }
}
