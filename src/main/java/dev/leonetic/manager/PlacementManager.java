package dev.leonetic.manager;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.Feature;
import dev.leonetic.features.modules.combat.OffhandModule;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlacementManager extends Feature {

    private static final int  WINDOW_LIMIT = 9;
    private static final long WINDOW_MS    = 300;

    private static final long PER_BLOCK_COOLDOWN_MS = 30;

    private final ArrayDeque<Long> recentPlacements = new ArrayDeque<>();

    private final Map<BlockPos, Long> sentAt = new ConcurrentHashMap<>();

    private final Queue<PlacementTask> queue    = new ArrayDeque<>();
    private final Set<BlockPos>        queued   = new HashSet<>();

    private boolean placing = false;

    private boolean wasUsingItem = false;

    private int lastSwapSequenceTick = -1;
    private SwapSequenceOwner lastSwapSequenceOwner = SwapSequenceOwner.OTHER;

    private enum SwapSequenceOwner {
        OTHER,
        AUTO_CRYSTAL
    }

    private boolean swapSequenceAvailable() {
        return mc.player == null || mc.player.tickCount != lastSwapSequenceTick;
    }

    private static int containerSlotOf(int slot) {
        return slot < 9 ? slot + 36 : slot;
    }

    private static boolean isInventorySlot(int slot) {
        return slot > 8;
    }

    private boolean canAltSwap() {
        return mc.player != null && mc.player.containerMenu.containerId == 0;
    }

    private int altSwapIn(int slot) {
        if (!isInventorySlot(slot)) return slot;
        if (!canAltSwap()) return -1;
        int selected = InventoryUtil.selected();
        InventoryUtil.click(slot, selected, ClickType.SWAP);
        return selected;
    }

    private void altSwapOut(int slot, int hotbarSlot) {
        if (!isInventorySlot(slot)) return;
        InventoryUtil.click(slot, hotbarSlot, ClickType.SWAP);
    }

    private boolean reactiveCrystalSequenceAvailable() {
        return swapSequenceAvailable() || lastSwapSequenceOwner == SwapSequenceOwner.AUTO_CRYSTAL;
    }

    private void markSwapSequenceUsed(SwapSequenceOwner owner) {
        if (mc.player != null) {
            lastSwapSequenceTick = mc.player.tickCount;
            lastSwapSequenceOwner = owner;
        }
    }

    public interface PlacementListener {
        void onBlockUpdate(BlockPos pos, boolean nowAir);
    }

    private final CopyOnWriteArrayList<PlacementListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(PlacementListener listener)    { listeners.addIfAbsent(listener); }
    public void removeListener(PlacementListener listener) { listeners.remove(listener); }

    public PlacementManager() {
        EVENT_BUS.register(this);
    }

    public boolean enqueue(BlockPos pos, int hotbarSlot) {
        if (isOnCooldown(pos)) return false;
        if (!queued.add(pos)) return false;
        return queue.offer(new PlacementTask(pos, null, hotbarSlot));
    }

    public boolean enqueue(BlockPos pos, Direction face, int hotbarSlot) {
        if (isOnCooldown(pos)) return false;
        if (!queued.add(pos)) return false;
        return queue.offer(new PlacementTask(pos, face, hotbarSlot));
    }

    private boolean isOnCooldown(BlockPos pos) {
        Long last = sentAt.get(pos);
        if (last == null) return false;
        if (System.currentTimeMillis() - last >= PER_BLOCK_COOLDOWN_MS) {
            sentAt.remove(pos, last);
            return false;
        }
        return true;
    }

    public void clearQueue() {
        queue.clear();
        queued.clear();
    }

    public void removeQueuedFor(java.util.function.Predicate<BlockPos> filter) {
        queue.removeIf(task -> {
            if (filter.test(task.pos())) {
                queued.remove(task.pos());
                return true;
            }
            return false;
        });
    }

    public void forceResetPlaceCooldown(BlockPos pos) {
        sentAt.remove(pos);
    }

    public boolean hasPending() {
        return !queue.isEmpty();
    }

    public boolean isPlacing() {
        return placing;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) { wasUsingItem = false; return; }
        flushQueue();

        wasUsingItem = mc.player.isUsingItem();
    }

    private boolean usingItemAnyTick() {
        return mc.player.isUsingItem() || wasUsingItem;
    }

    public void flushQueue() {
        if (nullCheck()) return;

        long now = System.currentTimeMillis();
        while (!recentPlacements.isEmpty() && now - recentPlacements.peekFirst() >= WINDOW_MS) {
            recentPlacements.pollFirst();
        }

        if (usingItemAnyTick()) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        if (!swapSequenceAvailable()) return;

        int budget = WINDOW_LIMIT - recentPlacements.size();
        if (budget <= 0) return;

        List<PreparedClick> ready = new ArrayList<>();
        List<PlacementTask> deferred = new ArrayList<>();
        int burstSlot = -1;
        while (ready.size() < budget && !queue.isEmpty()) {
            PlacementTask task = queue.poll();
            if (burstSlot != -1 && task.hotbarSlot() != burstSlot) {
                deferred.add(task);
                continue;
            }
            queued.remove(task.pos());
            PreparedClick prepared = prepareClick(task);
            if (prepared != null) {
                ready.add(prepared);
                burstSlot = task.hotbarSlot();
            }
        }
        for (PlacementTask task : deferred) queue.offer(task);
        if (ready.isEmpty()) return;

        boolean sent = false;
        placing = true;
        try {
            int originalSlot = InventoryUtil.selected();
            int useSlot = altSwapIn(burstSlot);
            if (useSlot >= 0) {
                try {
                    sendBurst(ready, useSlot, originalSlot);
                    if (useSlot != originalSlot) {
                        mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
                    }
                    sent = true;
                } finally {
                    altSwapOut(burstSlot, useSlot);
                }
            }
        } finally {
            placing = false;
        }
        if (!sent) return;
        markSwapSequenceUsed(SwapSequenceOwner.OTHER);

        long stamp = System.currentTimeMillis();
        for (PreparedClick p : ready) {
            sentAt.put(p.pos(), stamp);
            recentPlacements.addLast(stamp);
        }
    }

    public List<BlockPos> placeBatchOffhand(List<BlockPos> positions, int hotbarSlot) {
        if (nullCheck() || positions.isEmpty()) return List.of();

        long now = System.currentTimeMillis();
        while (!recentPlacements.isEmpty() && now - recentPlacements.peekFirst() >= WINDOW_MS) {
            recentPlacements.pollFirst();
        }

        if (usingItemAnyTick()) return List.of();
        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return List.of();

        if (!swapSequenceAvailable()) return List.of();

        int budget = WINDOW_LIMIT - recentPlacements.size();
        if (budget <= 0) return List.of();

        List<PreparedClick> ready = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (ready.size() >= budget) break;
            if (isOnCooldown(pos)) continue;
            PreparedClick prepared = prepareClick(new PlacementTask(pos, null, hotbarSlot));
            if (prepared != null) ready.add(prepared);
        }
        if (ready.isEmpty()) return List.of();

        boolean sent = false;
        placing = true;
        try {
            int originalSlot = InventoryUtil.selected();
            int useSlot = altSwapIn(hotbarSlot);
            if (useSlot >= 0) {
                try {
                    sendBurst(ready, useSlot, originalSlot);
                    if (useSlot != originalSlot) {
                        mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
                    }
                    sent = true;
                } finally {
                    altSwapOut(hotbarSlot, useSlot);
                }
            }
        } finally {
            placing = false;
        }
        if (!sent) return List.of();
        markSwapSequenceUsed(SwapSequenceOwner.OTHER);

        long stamp = System.currentTimeMillis();
        List<BlockPos> placed = new ArrayList<>(ready.size());
        for (PreparedClick p : ready) {
            sentAt.put(p.pos(), stamp);
            recentPlacements.addLast(stamp);
            placed.add(p.pos());
        }
        return placed;
    }

    @Nullable
    public BlockHitResult prepareAirPlaceHit(BlockPos pos) {
        if (isOnCooldown(pos)) return null;
        if (!PlaceUtil.canPlace(pos)) return null;

        Vec3 eye = mc.player.getEyePosition();
        Direction bestSide = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            var state = mc.level.getBlockState(neighbour);
            if (state.isAir()) continue;
            if (!state.getFluidState().isEmpty()) continue;
            if (isInteractable(state.getBlock())) continue;

            Vec3 hit = Vec3.atCenterOf(pos).relative(side, 0.5);
            Vec3 normal = new Vec3(-side.getStepX(), -side.getStepY(), -side.getStepZ());
            if (eye.subtract(hit).dot(normal) <= 0.01) continue;

            double distSq = eye.distanceToSqr(hit);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestSide = side;
            }
        }

        if (bestSide != null) {
            return new BlockHitResult(
                    Vec3.atCenterOf(pos).relative(bestSide, 0.5),
                    bestSide.getOpposite(),
                    pos.relative(bestSide),
                    false);
        }

        Direction dir = getAirPlaceDirection(pos);
        return new BlockHitResult(getAirPlaceHitPos(pos, dir), dir, pos, false);
    }

    public void notePlacement(BlockPos pos) {
        long stamp = System.currentTimeMillis();
        sentAt.put(pos, stamp);
        recentPlacements.addLast(stamp);
    }

    @Nullable
    public Direction getPlaceSide(BlockPos pos) {

        Vec3 eye = mc.player.getEyePosition();
        double bestDistSq = Double.MAX_VALUE;
        Direction bestSide = null;

        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            var neighborState = mc.level.getBlockState(neighbour);

            if (neighborState.isAir()) continue;
            if (!neighborState.getFluidState().isEmpty()) continue;
            if (isInteractable(neighborState.getBlock())) continue;

            double dx = (neighbour.getX() + 0.5) - eye.x;
            double dy = (neighbour.getY() + 0.5) - eye.y;
            double dz = (neighbour.getZ() + 0.5) - eye.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestSide = side;
            }
        }

        return bestSide;
    }

    private boolean isInteractable(net.minecraft.world.level.block.Block block) {
        return block instanceof BaseEntityBlock
            || block instanceof DoorBlock
            || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof BedBlock
            || block instanceof NoteBlock
            || block instanceof AnvilBlock;
    }

    @Nullable
    private PreparedClick prepareClick(PlacementTask task) {
        BlockPos pos = task.pos();
        if (!PlaceUtil.canPlace(pos)) return null;

        Direction dir = task.face() != null ? task.face() : getPlaceSide(pos);

        BlockPos neighbour;
        Vec3 hitPos;
        Direction hitSide;

        if (dir == null) {

            Direction airDir = getAirPlaceDirection(pos);
            neighbour = pos;
            hitPos    = getAirPlaceHitPos(pos, airDir);
            hitSide   = airDir;
        } else {
            neighbour = pos.relative(dir);
            hitPos    = Vec3.atCenterOf(pos).relative(dir, 0.5);
            hitSide   = dir.getOpposite();
        }

        ItemStack stack = mc.player.getInventory().getItem(task.hotbarSlot());
        if (stack.getItem() instanceof BlockItem blockItem) {
            BlockState desired = blockItem.getBlock().defaultBlockState();
            AdjustedHit adjusted = adjustHitForAxisBlock(pos, desired, hitPos, hitSide);
            if (adjusted != null) {
                hitSide = adjusted.hitSide();
                hitPos = adjusted.hitPos();
            }
        }

        return new PreparedClick(pos, neighbour, hitPos, hitSide, task.hotbarSlot());
    }

    /**
     * Sends the offhand-swap placement burst. Returns true if it changed the
     * server carried slot (the caller must then restore it). When another swap
     * already owns the hotbar this tick, the block item is clicked into the held
     * slot instead of issuing a competing carried-item change, and the carried
     * slot is left untouched (returns false).
     */
    private boolean sendBurst(List<PreparedClick> group, int slot, int currentSelected) {
        var conn = mc.getConnection();

        int heldSlot = Homovore.swapManager.serverSlot();
        boolean altSwap = Homovore.swapManager.isHotbarBusy() && heldSlot >= 0 && heldSlot <= 8;

        boolean moved = false;
        if (altSwap) {
            moved = heldSlot != slot;
            if (moved) InventoryUtil.swapToHotbarSlot(slot, heldSlot);
        } else if (slot != currentSelected) {
            conn.send(new ServerboundSetCarriedItemPacket(slot));
        }

        conn.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO,
                Direction.DOWN
        ));

        for (PreparedClick click : group) {
            int sequence = ((ClientLevelAccessor) mc.level)
                    .homovore$getBlockStatePredictionHandler()
                    .startPredicting()
                    .currentSequence();
            conn.send(new ServerboundUseItemOnPacket(
                    InteractionHand.OFF_HAND,
                    new BlockHitResult(click.hitPos(), click.hitSide(), click.neighbour(), false),
                    sequence
            ));
        }

        conn.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO,
                Direction.DOWN
        ));

        if (altSwap) {
            if (moved) InventoryUtil.swapToHotbarSlot(slot, heldSlot);
            return false;
        }
        return slot != currentSelected;
    }

    private record PreparedClick(BlockPos pos, BlockPos neighbour, Vec3 hitPos, Direction hitSide, int hotbarSlot) {}

    @Nullable
    private AdjustedHit adjustHitForAxisBlock(BlockPos pos, BlockState desiredState, Vec3 hitPos, Direction hitSide) {
        if (desiredState == null || pos == null || hitPos == null || hitSide == null) return null;
        if (!desiredState.hasProperty(BlockStateProperties.AXIS)) return null;

        Direction.Axis axis = desiredState.getValue(BlockStateProperties.AXIS);
        Direction adjustedSide = switch (axis) {
            case X -> Direction.EAST;
            case Z -> Direction.SOUTH;
            case Y -> Direction.UP;
        };

        Vec3 adjustedPos = getAirPlaceHitPos(pos, adjustedSide);
        return new AdjustedHit(adjustedPos, adjustedSide);
    }

    private Direction getAirPlaceDirection(BlockPos pos) {
        if (mc.player == null) return Direction.UP;

        Vec3 eyePos = mc.player.getEyePosition();
        Direction bestDir = Direction.UP;
        double bestDist = Double.MAX_VALUE;

        for (Direction d : Direction.values()) {
            Vec3 face = getAirPlaceHitPos(pos, d);
            double dist = eyePos.distanceToSqr(face);
            if (dist < bestDist) {
                bestDist = dist;
                bestDir = d;
            }
        }

        return bestDir;
    }

    private Vec3 getAirPlaceHitPos(BlockPos pos, @Nullable Direction dir) {
        Vec3 hit = Vec3.atCenterOf(pos);
        if (dir != null) {
            hit = hit.add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
        }
        return hit;
    }

    private record AdjustedHit(Vec3 hitPos, Direction hitSide) {}

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundBlockUpdatePacket bup) {
            handleBlockUpdate(bup);
        } else if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                if (sub instanceof ClientboundBlockUpdatePacket bup) handleBlockUpdate(bup);
            }
        }
    }

    private void handleBlockUpdate(ClientboundBlockUpdatePacket packet) {

        BlockPos pos = packet.getPos().immutable();
        sentAt.remove(pos);

        if (listeners.isEmpty()) return;

        boolean nowAir = packet.getBlockState().isAir();
        mc.execute(() -> {
            for (PlacementListener listener : listeners) {
                listener.onBlockUpdate(pos, nowAir);
            }
            flushQueue();
        });
    }

    public boolean placeDirect(BlockPos pos, @Nullable Direction face, int hotbarSlot) {
        if (nullCheck()) return false;

        long now = System.currentTimeMillis();
        while (!recentPlacements.isEmpty() && now - recentPlacements.peekFirst() >= WINDOW_MS) {
            recentPlacements.pollFirst();
        }

        if (usingItemAnyTick()) return false;
        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return false;

        if (recentPlacements.size() >= WINDOW_LIMIT) return false;
        if (isOnCooldown(pos)) return false;

        PreparedClick click = prepareClick(new PlacementTask(pos, face, hotbarSlot));
        if (click == null) return false;

        boolean sent = false;
        placing = true;
        try {
            int originalSlot = InventoryUtil.selected();
            int useSlot = altSwapIn(hotbarSlot);
            if (useSlot >= 0) {
                try {
                    sendBurst(List.of(click), useSlot, originalSlot);
                    if (useSlot != originalSlot) {
                        mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
                    }
                    sent = true;
                } finally {
                    altSwapOut(hotbarSlot, useSlot);
                }
            }
        } finally {
            placing = false;
        }
        if (!sent) return false;

        long stamp = System.currentTimeMillis();
        sentAt.put(click.pos(), stamp);
        recentPlacements.addLast(stamp);
        return true;
    }

    public boolean placeCrystal(BlockPos base, int hotbarSlot) {
        return placeCrystal(base, hotbarSlot, false);
    }

    public boolean placeCrystal(BlockPos base, int hotbarSlot, boolean trustBase) {
        boolean altSwap = isInventorySlot(hotbarSlot);
        if (altSwap) {
            if (usingItemAnyTick()) return false;
            OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
            if (offhand != null && offhand.shouldDeferForEat()) return false;
            if (hasPending()) flushQueue();
            if (!swapSequenceAvailable()) return false;
        }

        BlockHitResult hit = computeCrystalHit(base, trustBase);
        if (hit == null) return false;

        int useSlot = altSwapIn(hotbarSlot);
        if (useSlot < 0) return false;

        try {
            var conn = mc.getConnection();
            int originalSlot = Homovore.swapManager.serverSlot();
            boolean needSlotSwap = useSlot != originalSlot;

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(useSlot));
            }

            try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
            }

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        } finally {
            altSwapOut(hotbarSlot, useSlot);
        }

        if (altSwap) markSwapSequenceUsed(SwapSequenceOwner.OTHER);
        return true;
    }

    public boolean placeCrystalOffhand(BlockPos base, int hotbarSlot, boolean trustBase) {
        return placeCrystalOffhand(base, hotbarSlot, trustBase, false, SwapSequenceOwner.OTHER);
    }

    public boolean placeCrystalOffhandAutoCrystal(BlockPos base, int hotbarSlot, boolean trustBase) {
        return placeCrystalOffhand(base, hotbarSlot, trustBase, false, SwapSequenceOwner.AUTO_CRYSTAL);
    }

    public boolean placeCrystalOffhandReactive(BlockPos base, int hotbarSlot, boolean trustBase) {
        return placeCrystalOffhand(base, hotbarSlot, trustBase, true, SwapSequenceOwner.AUTO_CRYSTAL);
    }

    private boolean placeCrystalOffhand(BlockPos base, int hotbarSlot, boolean trustBase, boolean reactive,
                                        SwapSequenceOwner owner) {
        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return false;

        if (!reactive) {
            if (hasPending()) flushQueue();
            if (!swapSequenceAvailable()) return false;
        } else if (!reactiveCrystalSequenceAvailable()) {
            return false;
        }

        BlockHitResult hit = computeCrystalHit(base, trustBase);
        if (hit == null) return false;

        int useSlot = altSwapIn(hotbarSlot);
        if (useSlot < 0) return false;

        try {
            var conn = mc.getConnection();
            int originalSlot = Homovore.swapManager.serverSlot();
            boolean needSlotSwap = useSlot != originalSlot;

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(useSlot));
            }
            conn.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            try {
                try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                    conn.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, handler.currentSequence()));
                }
            } finally {
                conn.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                if (needSlotSwap) {
                    conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
                }
            }
        } finally {
            altSwapOut(hotbarSlot, useSlot);
        }
        markSwapSequenceUsed(owner);

        return true;
    }

    /**
     * One-tick silent break + crystal place. Holds the pickaxe in the mainhand
     * and the crystal in the offhand simultaneously, runs the block-break burst,
     * places a crystal from the offhand, then restores both hands. Unlike
     * {@link #placeCrystalOffhand}, the crystal reaches the offhand via a
     * container click (not SWAP_ITEM_WITH_OFFHAND), so the mainhand stays free to
     * hold the pickaxe — letting us break a block and place a crystal in the same
     * tick.
     *
     * @param pickaxeSlot hotbar slot (0-8) of the pickaxe to break with
     * @param breakBurst  emits the block-break packets; run while the pickaxe is held
     * @param crystalSlot hotbar slot (0-8) holding the end crystal
     * @param crystalBase obsidian/bedrock the crystal is placed on
     * @param trustBase   skip the obsidian/bedrock check on the base
     */
    public boolean breakThenPlaceCrystalOffhand(int pickaxeSlot, Runnable breakBurst,
                                                int crystalSlot, BlockPos crystalBase, boolean trustBase) {
        if (nullCheck()) return false;
        if (mc.player.containerMenu.containerId != 0) return false;
        if (!InventoryUtil.cursor().isEmpty()) return false;
        if (pickaxeSlot < 0 || pickaxeSlot > 8 || crystalSlot < 0 || crystalSlot > 8) return false;
        if (pickaxeSlot == crystalSlot) return false;
        if (!mc.player.getInventory().getItem(crystalSlot).is(Items.END_CRYSTAL)) return false;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return false;

        // This combo owns the hotbar for the tick; don't fight another active swap.
        if (Homovore.swapManager.isHotbarBusy()) return false;

        if (hasPending()) flushQueue();
        if (!swapSequenceAvailable()) return false;

        BlockHitResult hit = computeCrystalHit(crystalBase, trustBase);
        if (hit == null) return false;

        var conn = mc.getConnection();
        if (conn == null) return false;

        int originalSlot = Homovore.swapManager.serverSlot();
        boolean needSlotSwap = pickaxeSlot != originalSlot;

        // 1. Crystal -> offhand via container click (mainhand stays free).
        InventoryUtil.swapToOffhand(crystalSlot);
        // 2. Pickaxe -> mainhand.
        if (needSlotSwap) conn.send(new ServerboundSetCarriedItemPacket(pickaxeSlot));

        try {
            // 3. Break the block with the pickaxe now held in the mainhand.
            if (breakBurst != null) breakBurst.run();

            // 4. Place the crystal from the offhand.
            try (var handler = ((ClientLevelAccessor) mc.level)
                    .homovore$getBlockStatePredictionHandler().startPredicting()) {
                conn.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, handler.currentSequence()));
            }
        } finally {
            // 5. Restore mainhand, then 6. restore offhand (swapToOffhand is its own inverse).
            if (needSlotSwap) conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
            InventoryUtil.swapToOffhand(crystalSlot);
        }

        markSwapSequenceUsed(SwapSequenceOwner.OTHER);
        return true;
    }

    @Nullable
    private BlockHitResult computeCrystalHit(BlockPos base, boolean trustBase) {
        if (!trustBase) {
            var baseState = mc.level.getBlockState(base);
            if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return null;
        }

        BlockPos airPos = base.above();
        var airState = mc.level.getBlockState(airPos);
        if (!airState.isAir() && !(airState.is(Blocks.FIRE) && mc.level.dimension().equals(Level.END))) return null;

        Vec3 eye = mc.player.getEyePosition(1.0f);

        AABB checkBox = new AABB(airPos);
        for (Entity e : mc.level.getEntities(null, checkBox)) {
            if (e instanceof ItemEntity) continue;
            if (e instanceof EndCrystal crystal && crystal.tickCount < 5) continue;
            if (e instanceof EndCrystal crystal && crystal.blockPosition().equals(airPos)) continue;
            return null;
        }

        Vec3 baseCenter = Vec3.atCenterOf(base);
        double dx = eye.x - baseCenter.x;
        double dy = eye.y - baseCenter.y;
        double dz = eye.z - baseCenter.z;
        double absX = Math.abs(dx), absY = Math.abs(dy), absZ = Math.abs(dz);
        Direction clickFace;
        if (absY >= absX && absY >= absZ)      clickFace = dy > 0 ? Direction.UP    : Direction.DOWN;
        else if (absX >= absZ)                 clickFace = dx > 0 ? Direction.EAST  : Direction.WEST;
        else                                   clickFace = dz > 0 ? Direction.SOUTH : Direction.NORTH;

        Vec3 playerPos = mc.player.position();
        double offX = Math.max(0.2, Math.min(0.8, playerPos.x - Math.floor(playerPos.x)));
        double offY = Math.max(0.2, Math.min(0.8, playerPos.y - Math.floor(playerPos.y)));
        double offZ = Math.max(0.2, Math.min(0.8, playerPos.z - Math.floor(playerPos.z)));
        Vec3 hitVec = baseCenter;
        switch (clickFace) {
            case UP, DOWN -> hitVec = hitVec.add(
                    offX - 0.5,
                    clickFace == Direction.UP ? 0.5 : -0.5,
                    offZ - 0.5
            );
            case NORTH, SOUTH -> hitVec = hitVec.add(
                    offX - 0.5,
                    offY - 0.5,
                    clickFace == Direction.SOUTH ? 0.5 : -0.5
            );
            case EAST, WEST -> hitVec = hitVec.add(
                    clickFace == Direction.EAST ? 0.5 : -0.5,
                    offY - 0.5,
                    offZ - 0.5
            );
        }

        float[] angles = MathUtil.calcAngle(eye, hitVec);

        AABB baseBox = new AABB(base);
        boolean insideBox = baseBox.contains(eye);
        if (!insideBox) {
            Vec3 look = getLookVector(angles[0], angles[1]);
            Vec3 reachEnd = eye.add(look.scale(6.0));
            if (baseBox.clip(eye, reachEnd).isEmpty()) return null;
        }

        return new BlockHitResult(hitVec, clickFace, base, false);
    }

    public boolean placeFireworksAlt(List<BlockPos> poses, Direction face, int hotbarSlot) {
        if (nullCheck()) return false;
        if (poses.isEmpty()) return false;
        if (mc.player.containerMenu.containerId != 0) return false;
        if (hotbarSlot < 0 || hotbarSlot > 35) return false;

        ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
        if (stack.isEmpty()) return false;

        var conn = mc.getConnection();
        if (conn == null) return false;

        int containerSlot = containerSlotOf(hotbarSlot);
        boolean swapped = hotbarSlot != InventoryUtil.selected();

        if (swapped) InventoryUtil.click(containerSlot, InventoryUtil.selected(), ClickType.SWAP);
        try {
            for (BlockPos pos : poses) {
                BlockPos neighbour = pos.relative(face);
                Vec3 hitPos = getFireworkHitPos(pos, face);
                Direction hitSide = face.getOpposite();
                BlockHitResult hit = new BlockHitResult(hitPos, hitSide, neighbour, false);

                try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                    conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
                }
            }
        } finally {
            if (swapped) InventoryUtil.click(containerSlot, InventoryUtil.selected(), ClickType.SWAP);
        }
        return true;
    }

    private Vec3 getFireworkHitPos(BlockPos pos, Direction face) {
        if (face != Direction.DOWN || mc.player == null) {
            return Vec3.atCenterOf(pos).relative(face, 0.5);
        }

        // Lean toward the corner facing away from the player, but don't jam it fully
        // into the corner: sitting ~0.15 from the edge can end up placing crystals there.
        // 0.7 of the way through keeps the firework in that corner-ish region while
        // staying clear enough of the edge.
        final double lean = 0.7;
        Vec3 player = mc.player.position();
        double x = pos.getX() + (player.x >= pos.getX() + 0.5 ? 1.0 - lean : lean);
        double z = pos.getZ() + (player.z >= pos.getZ() + 0.5 ? 1.0 - lean : lean);
        return new Vec3(x, pos.getY(), z);
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    private record PlacementTask(BlockPos pos, @Nullable Direction face, int hotbarSlot) {}
}
