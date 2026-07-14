package dev.leonetic.features.modules.world;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.AttackBlockEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.combat.AutoMineModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.manager.SwapRequest;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.mixin.entity.EntityRotationAccessor;
import dev.leonetic.util.EnchantmentUtil;
import dev.leonetic.util.InteractionUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class SpeedMineModule extends Module {
    private static final int SWAP_PRIORITY = 75;
    private static final int PROACTIVE_REBREAK_SWAP_PRIORITY = 67;
    private static final boolean STRICT_ANTI_RUBBERBAND = true;
    private static final boolean PRE_SWITCH_SINGLE_BREAK = true;
    private static final boolean SET_REBREAK_BLOCK_BROKEN = true;
    private static final boolean RENDER = true;
    private static final boolean RENDER_BLOCK = true;

    private final Setting<Double> range = num("Range", 5.14, 0.0, 7.0);
    private final Setting<Integer> singleBreakFailTicks = num("SingleBreakFailTicks", 20, 5, 50);

    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color> sideColor = color("SideColor", 255, 180, 255, 15).setPage("Render");
    private final Setting<Color> lineColor = color("LineColor", 255, 255, 255, 60).setPage("Render");
    private final Setting<Boolean> debugRenderPrimary = bool("DebugRenderPrimary", false).setPage("Render");

    private SilentMineBlock rebreakBlock;
    private SilentMineBlock delayedDestroyBlock;
    private BlockPos lastDelayedDestroyBlockPos;
    private double currentGameTickCalculated;

    private boolean needDelayedDestroySwapBack;
    private Result delayedDestroySwapResult;
    private SwapManager.SwapHandle delayedDestroySwapHandle;
    private BlockPos rebreakHoldPos;
    private int rebreakHoldTicks;

    public interface MineFinishListener {
        void onMineFinish(BlockPos pos);
    }

    private final CopyOnWriteArrayList<MineFinishListener> finishListeners = new CopyOnWriteArrayList<>();

    public SpeedMineModule() {
        super("SpeedMine", "Allows you to mine blocks without holding a pickaxe.", Category.WORLD);
    }

    @Override
    public void onEnable() {
        currentGameTickCalculated = gameTick();
    }

    @Override
    public void onDisable() {
        if (rebreakBlock != null) rebreakBlock.cancelBreaking();
        if (delayedDestroyBlock != null) delayedDestroyBlock.cancelBreaking();
        endDelayedDestroySwap();
        rebreakBlock = null;
        delayedDestroyBlock = null;
        lastDelayedDestroyBlockPos = null;
    }

    @Subscribe
    private void onTick(PreTickEvent event) {
        if (nullCheck()) return;
        currentGameTickCalculated = gameTick();

        lastDelayedDestroyBlockPos = hasDelayedDestroy() ? delayedDestroyBlock.blockPos : null;
        if (rebreakHoldTicks > 0) rebreakHoldTicks--;

        if (hasDelayedDestroy() && (mc.level.getBlockState(delayedDestroyBlock.blockPos).isAir()
                || !canBreak(delayedDestroyBlock.blockPos))) {
            fireFinish(delayedDestroyBlock.blockPos);
            delayedDestroyBlock = null;
        }

        if (rebreakBlock != null) {
            BlockState rebreakState = mc.level.getBlockState(rebreakBlock.blockPos);
            if (!rebreakState.isAir()) rebreakBlock.lastMiningState = rebreakState;
            if (rebreakState.isAir() || !canBreak(rebreakBlock.blockPos)) {
                rebreakBlock.beenAir = true;
            }
        }

        if (hasRebreakBlock() && rebreakBlock.timesSendBreakPacket > 10
                && !canRebreakRebreakBlock()) {
            rebreakBlock.cancelBreaking();
            rebreakBlock = null;
        }

        boolean holdRebreak = rebreakHoldTicks > 0 && rebreakBlock != null
                && rebreakBlock.blockPos.equals(rebreakHoldPos);

        if (hasDelayedDestroy()
                && delayedDestroyBlock.ticksHeldPickaxe <= singleBreakFailTicks.getValue()) {
            BlockState state = mc.level.getBlockState(delayedDestroyBlock.blockPos);
            if (delayedDestroyBlock.isReady()) {
                Result result = findFastestTool(state, false);
                if (result.found() && !result.holding() && !needDelayedDestroySwapBack) {
                    beginDelayedDestroySwap(result);
                }
                if (!result.found() || result.holding() || needDelayedDestroySwapBack) {
                    delayedDestroyBlock.ticksHeldPickaxe++;
                }
            }
        }

        boolean proactiveRebreak = rebreakBlock != null && rebreakBlock.beenAir
                && autoMineTargetSelected();
        if (rebreakBlock != null
                && (proactiveRebreak || rebreakBlock.isReady()) && !holdRebreak) {
            if (inBreakRange(rebreakBlock.blockPos)) {
                BlockState state = mc.level.getBlockState(rebreakBlock.blockPos);
                Result result = findFastestTool(state.isAir() ? rebreakBlock.lastMiningState : state, false);
                fireFinish(rebreakBlock.blockPos);
                withInstantTool(result, rebreakBlock::tryBreak,
                        proactiveRebreak ? PROACTIVE_REBREAK_SWAP_PRIORITY : SWAP_PRIORITY + 1);

                if (SET_REBREAK_BLOCK_BROKEN && canRebreakRebreakBlock()) {
                    mc.level.setBlockAndUpdate(rebreakBlock.blockPos, Blocks.AIR.defaultBlockState());
                }
            } else {
                rebreakBlock = null;
            }
        }

        if (hasDelayedDestroy()
                && delayedDestroyBlock.ticksHeldPickaxe > singleBreakFailTicks.getValue()) {
            if (inBreakRange(delayedDestroyBlock.blockPos)) {
                delayedDestroyBlock.startBreaking(true);
            } else {
                delayedDestroyBlock.cancelBreaking();
                delayedDestroyBlock = null;
            }
        }

        boolean delayedDestroyFinished = !(hasDelayedDestroy() && delayedDestroyBlock.isReady());
        if (needDelayedDestroySwapBack && delayedDestroyFinished) {
            endDelayedDestroySwap();
        }
    }

    public boolean silentBreakBlock(BlockPos pos, double priority) {
        return silentBreakBlock(pos, Direction.UP, priority);
    }

    public boolean silentBreakBlock(BlockPos blockPos, Direction direction, double priority) {
        if (!isEnabled() || nullCheck() || blockPos == null) return false;
        if (alreadyBreaking(blockPos)) return true;
        if (!canBreak(blockPos) || !inBreakRange(blockPos)) return false;

        if (!hasDelayedDestroy()) {
            boolean willResetPrimary = rebreakBlock != null && !canRebreakRebreakBlock();
            if (willResetPrimary && rebreakBlock.priority < priority) return false;

            currentGameTickCalculated -= 0.1;
            delayedDestroyBlock = new SilentMineBlock(blockPos, direction, priority, false);
            delayedDestroyBlock.startBreaking(true);

            if (willResetPrimary) rebreakBlock.startBreaking(false);
        }

        if (alreadyBreaking(blockPos)) return true;

        if (rebreakBlock != null && delayedDestroyBlock != null
                && (priority >= rebreakBlock.priority || canRebreakRebreakBlock())) {
            if (delayedDestroyBlock.getBreakProgress() <= 0.8) rebreakBlock = null;
        }

        if (rebreakBlock == null) {
            rebreakBlock = new SilentMineBlock(blockPos, direction, priority, true);
            rebreakBlock.startBreaking(false);
        }
        return alreadyBreaking(blockPos);
    }

    @Subscribe
    private void onAttackBlock(AttackBlockEvent event) {
        event.cancel();
        silentBreakBlock(event.getPos(), event.getDirection(), 100.0);
    }

    @Subscribe
    private void onPacket(PacketEvent.Send event) {
        if (!(event.getPacket() instanceof ServerboundPlayerActionPacket packet)) return;
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) return;
        if (!STRICT_ANTI_RUBBERBAND) return;
        if (!packet.getPos().equals(getRebreakBlockPos())
                && !packet.getPos().equals(getDelayedDestroyBlockPos())) return;

        sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                packet.getPos(), packet.getDirection(), false);
    }

    @Subscribe
    private void onRender(Render3DEvent event) {
        if (nullCheck() || !RENDER || !RENDER_BLOCK) return;
        double renderTick = gameTick() + event.getDelta();
        if (rebreakBlock != null) rebreakBlock.render(event, renderTick, true);
        if (delayedDestroyBlock != null) delayedDestroyBlock.render(event, renderTick, false);
    }

    public boolean canSwapBack() {
        return needDelayedDestroySwapBack
                && !(hasDelayedDestroy() && delayedDestroyBlock.isReady());
    }

    public boolean hasDelayedDestroy() {
        return delayedDestroyBlock != null;
    }

    public boolean hasRebreakBlock() {
        return rebreakBlock != null && !rebreakBlock.beenAir;
    }

    public BlockPos getDelayedDestroyBlockPos() {
        return delayedDestroyBlock == null ? null : delayedDestroyBlock.blockPos;
    }

    public BlockPos getLastDelayedDestroyBlockPos() {
        return lastDelayedDestroyBlockPos;
    }

    public double getDelayedDestroyProgress() {
        return delayedDestroyBlock == null ? 0.0 : delayedDestroyBlock.getBreakProgress();
    }

    public BlockPos getRebreakBlockPos() {
        return rebreakBlock == null ? null : rebreakBlock.blockPos;
    }

    public double getRebreakBlockProgress() {
        return rebreakBlock == null ? 0.0 : rebreakBlock.getBreakProgress();
    }

    public boolean canRebreakRebreakBlock() {
        return rebreakBlock != null && rebreakBlock.beenAir;
    }

    public boolean inBreakRange(BlockPos blockPos) {
        return blockPos != null && new AABB(blockPos).distanceToSqr(mc.player.getEyePosition())
                <= range.getValue() * range.getValue();
    }

    public boolean alreadyBreaking(BlockPos blockPos) {
        return rebreakBlock != null && blockPos.equals(rebreakBlock.blockPos)
                || delayedDestroyBlock != null && blockPos.equals(delayedDestroyBlock.blockPos);
    }

    public void addFinishListener(MineFinishListener listener) {
        finishListeners.addIfAbsent(listener);
    }

    public void removeFinishListener(MineFinishListener listener) {
        finishListeners.remove(listener);
    }

    public boolean hasFailingBlock() {
        return rebreakBlock != null && !rebreakBlock.beenAir && rebreakBlock.timesSendBreakPacket > 10
                || delayedDestroyBlock != null
                && delayedDestroyBlock.ticksHeldPickaxe > singleBreakFailTicks.getValue();
    }

    public void holdRebreak(BlockPos pos, int ticks) {
        rebreakHoldPos = pos == null ? null : pos.immutable();
        rebreakHoldTicks = pos == null ? 0 : ticks;
    }

    public void clearRebreak() {
        if (rebreakBlock != null) rebreakBlock.cancelBreaking();
        rebreakBlock = null;
        rebreakHoldPos = null;
        rebreakHoldTicks = 0;
    }

    public void collectMiningPositions(Set<BlockPos> positions, double minProgress) {
        if (rebreakBlock != null && rebreakBlock.getBreakProgress() >= minProgress) {
            positions.add(rebreakBlock.blockPos);
        }
        if (delayedDestroyBlock != null && delayedDestroyBlock.getBreakProgress() >= minProgress) {
            positions.add(delayedDestroyBlock.blockPos);
        }
    }

    public String getDebugState() {
        return "rebreak=" + describe(rebreakBlock) + ",delayed=" + describe(delayedDestroyBlock)
                + ",hold=" + rebreakHoldTicks + ",swap=" + needDelayedDestroySwapBack;
    }

    private String describe(SilentMineBlock block) {
        if (block == null) return "none";
        return block.blockPos + "{air=" + block.beenAir
                + ",progress=" + String.format(java.util.Locale.ROOT, "%.3f", block.getBreakProgress())
                + ",sends=" + block.timesSendBreakPacket + ",held=" + block.ticksHeldPickaxe + "}";
    }

    private void fireFinish(BlockPos pos) {
        for (MineFinishListener listener : finishListeners) listener.onMineFinish(pos);
    }

    private boolean beginDelayedDestroySwap(Result result) {
        delayedDestroySwapHandle = Homovore.swapManager.acquireLease("SpeedMine", SWAP_PRIORITY);
        if (delayedDestroySwapHandle == null) return false;
        if (!InventoryUtil.swapSilent(result)) {
            Homovore.swapManager.release(delayedDestroySwapHandle);
            delayedDestroySwapHandle = null;
            return false;
        }
        delayedDestroySwapResult = result;
        needDelayedDestroySwapBack = true;
        return true;
    }

    private boolean autoMineTargetSelected() {
        AutoMineModule autoMine = Homovore.moduleManager.getModuleByClass(AutoMineModule.class);
        return autoMine != null && autoMine.isEnabled() && autoMine.isTargetingAnything();
    }

    private void endDelayedDestroySwap() {
        if (needDelayedDestroySwapBack && delayedDestroySwapResult != null && mc.player != null) {
            InventoryUtil.swapBackSilent(delayedDestroySwapResult);
        }
        if (delayedDestroySwapHandle != null) {
            Homovore.swapManager.release(delayedDestroySwapHandle);
        }
        needDelayedDestroySwapBack = false;
        delayedDestroySwapResult = null;
        delayedDestroySwapHandle = null;
    }

    private void withInstantTool(Result result, Runnable action, int priority) {
        if (!result.found() || result.holding()) {
            action.run();
            return;
        }
        if (needDelayedDestroySwapBack) {
            if (priority == PROACTIVE_REBREAK_SWAP_PRIORITY) return;
            if (!InventoryUtil.swapSilent(result)) return;
            try {
                action.run();
            } finally {
                InventoryUtil.swapBackSilent(result);
            }
            return;
        }
        Homovore.swapManager.submit(new SwapRequest(
                "SpeedMine", priority, result, action, true));
    }

    private Result findFastestTool(BlockState state, boolean hotbarOnly) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        int limit = hotbarOnly ? 9 : 36;
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > 1.0f) {
                int efficiency = EnchantmentUtil.getLevel(Enchantments.EFFICIENCY, stack);
                if (efficiency > 0) speed += efficiency * efficiency + 1;
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) return new Result(-1, ItemStack.EMPTY, ResultType.NONE);
        ResultType type = bestSlot < 9 ? ResultType.HOTBAR : ResultType.INVENTORY;
        return new Result(bestSlot, mc.player.getInventory().getItem(bestSlot), type);
    }

    private boolean canBreak(BlockPos pos) {
        return InteractionUtil.canBreak(pos, mc.level.getBlockState(pos));
    }

    private double gameTick() {
        return mc.level == null ? currentGameTickCalculated : mc.level.getGameTime();
    }

    private void sendAction(ServerboundPlayerActionPacket.Action action, BlockPos pos,
                            Direction direction, boolean sequenced) {
        if (mc.getConnection() == null) return;
        if (!sequenced) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(action, pos, direction));
            return;
        }
        try (var handler = ((ClientLevelAccessor) mc.level)
                .homovore$getBlockStatePredictionHandler().startPredicting()) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    action, pos, direction, handler.currentSequence()));
        }
    }

    private boolean willBeOnGround() {
        AABB bounds = mc.player.getBoundingBox();
        double feetY = bounds.minY;
        AABB ground = new AABB(bounds.minX, feetY - 0.2, bounds.minZ,
                bounds.maxX, feetY, bounds.maxZ);
        double velocityReach = Math.abs(mc.player.getDeltaMovement().y * 2.0);

        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(ground.minX), Mth.floor(ground.minY), Mth.floor(ground.minZ),
                Mth.floor(ground.maxX), Mth.floor(ground.maxY), Mth.floor(ground.maxZ))) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.getCollisionShape(mc.level, pos).isEmpty()) continue;
            double distance = feetY - (pos.getY() + 1.0);
            if (distance >= 0.0 && distance < velocityReach) return true;
        }
        return false;
    }

    private float breakDelta(ItemStack stack, BlockPos pos, BlockState state, boolean onGround) {
        float speed = stack.getDestroySpeed(state);
        if (speed > 1.0f) {
            int efficiency = EnchantmentUtil.getLevel(Enchantments.EFFICIENCY, stack);
            if (efficiency > 0) speed += efficiency * efficiency + 1;
        }
        if (MobEffectUtil.hasDigSpeed(mc.player)) {
            speed *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2f;
        }
        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            int amplifier = mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier();
            speed *= switch (amplifier) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
        }
        if (mc.player.isEyeInFluid(FluidTags.WATER)
                && !EnchantmentUtil.has(Enchantments.AQUA_AFFINITY, EquipmentSlot.HEAD)) {
            speed /= 5.0f;
        }
        if (!onGround) speed /= 5.0f;

        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness < 0.0f) return 0.0f;
        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return speed / hardness / (correctTool ? 30.0f : 100.0f);
    }

    private final class SilentMineBlock {
        private final BlockPos blockPos;
        private final Direction direction;
        private final double priority;
        private final boolean rebreak;

        private int timesSendBreakPacket;
        private int ticksHeldPickaxe;
        private boolean beenAir;
        private double destroyProgressStart;
        private BlockState lastMiningState;

        private SilentMineBlock(BlockPos blockPos, Direction direction, double priority, boolean rebreak) {
            this.blockPos = blockPos.immutable();
            this.direction = direction;
            this.priority = priority;
            this.rebreak = rebreak;
            this.lastMiningState = mc.level.getBlockState(blockPos);
        }

        private boolean isReady() {
            if (!canBreak(blockPos)) return false;
            double singleTick = getBreakProgress(destroyProgressStart + 1.0);
            double threshold = rebreak ? 0.7
                    : 1.0 - (PRE_SWITCH_SINGLE_BREAK ? singleTick / 2.0 : 0.0);
            return getBreakProgress() >= threshold || timesSendBreakPacket > 0;
        }

        private void startBreaking(boolean delayedDestroy) {
            ticksHeldPickaxe = 0;
            timesSendBreakPacket = 0;
            destroyProgressStart = currentGameTickCalculated;

            if (delayedDestroy && canRebreakRebreakBlock()) rebreakBlock = null;

            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, true);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, true);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, true);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, true);
            sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, direction, true);
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, true);

            if (!STRICT_ANTI_RUBBERBAND) {
                sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction, false);
                sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction, false);
            }
        }

        private void tryBreak() {
            sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, true);
            if (!STRICT_ANTI_RUBBERBAND) {
                sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction, false);
            }
            timesSendBreakPacket++;
        }

        private void cancelBreaking() {
            sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, direction, false);
        }

        private double getBreakProgress() {
            return getBreakProgress(currentGameTickCalculated);
        }

        private double getBreakProgress(double gameTick) {
            BlockState state = mc.level.getBlockState(blockPos);
            Result result = findFastestTool(state, false);
            int slot = result.found() ? result.slot() : InventoryUtil.selected();
            ItemStack stack = mc.player.getInventory().getItem(slot);
            boolean onGround = ((EntityRotationAccessor) mc.player).homovore$getLastOnGround()
                    || willBeOnGround() && !rebreak;
            return Math.min(breakDelta(stack, blockPos, state, onGround)
                    * (gameTick - destroyProgressStart), 1.0);
        }

        private void render(Render3DEvent event, double renderTick, boolean primary) {
            BlockState state = mc.level.getBlockState(blockPos);
            VoxelShape shape = state.getShape(mc.level, blockPos);
            AABB bounds = shape.isEmpty() ? new AABB(blockPos) : shape.bounds().move(blockPos);
            double progress = Mth.clamp(primary ? getBreakProgress(renderTick) / 0.7
                    : getBreakProgress(renderTick), 0.0, 1.0);

            double centerX = (bounds.minX + bounds.maxX) * 0.5;
            double centerY = (bounds.minY + bounds.maxY) * 0.5;
            double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;
            double halfX = bounds.getXsize() * progress * 0.5;
            double halfY = bounds.getYsize() * progress * 0.5;
            double halfZ = bounds.getZsize() * progress * 0.5;
            AABB renderBox = new AABB(centerX - halfX, centerY - halfY, centerZ - halfZ,
                    centerX + halfX, centerY + halfY, centerZ + halfZ);

            Color fill = primary && debugRenderPrimary.getValue()
                    ? new Color(255, 165, 0, 40) : sideColor.getValue();
            RenderUtil.drawBoxFilled(event.getMatrix(), renderBox, fill);
            RenderUtil.drawBox(event.getMatrix(), renderBox, lineColor.getValue(), lineWidth.getValue());
        }
    }
}
