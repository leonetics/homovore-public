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

        int budget = WINDOW_LIMIT - recentPlacements.size();
        if (budget <= 0) return;

        List<PreparedClick> ready = new ArrayList<>();
        while (ready.size() < budget && !queue.isEmpty()) {
            PlacementTask task = queue.poll();
            queued.remove(task.pos());
            PreparedClick prepared = prepareClick(task);
            if (prepared != null) ready.add(prepared);
        }
        if (ready.isEmpty()) return;

        placing = true;
        try {
            int originalSlot = InventoryUtil.selected();
            int currentSelected = originalSlot;
            int i = 0;
            while (i < ready.size()) {
                int slot = ready.get(i).hotbarSlot();
                int j = i + 1;
                while (j < ready.size() && ready.get(j).hotbarSlot() == slot) j++;
                sendBurst(ready.subList(i, j), slot, currentSelected);
                currentSelected = slot;
                i = j;
            }
            if (currentSelected != originalSlot) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        } finally {
            placing = false;
        }

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

        placing = true;
        try {
            int originalSlot = InventoryUtil.selected();
            sendBurst(ready, hotbarSlot, originalSlot);
            if (hotbarSlot != originalSlot) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        } finally {
            placing = false;
        }

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

    private void sendBurst(List<PreparedClick> group, int slot, int currentSelected) {
        var conn = mc.getConnection();

        if (slot != currentSelected) {
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

    public boolean placeCrystal(BlockPos base, int hotbarSlot) {
        return placeCrystal(base, hotbarSlot, false);
    }

    public boolean placeCrystal(BlockPos base, int hotbarSlot, boolean trustBase) {
        if (!trustBase) {
            var baseState = mc.level.getBlockState(base);
            if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return false;
        }

        BlockPos airPos = base.above();
        var airState = mc.level.getBlockState(airPos);
        if (!airState.isAir() && !(airState.is(Blocks.FIRE) && mc.level.dimension().equals(Level.END))) return false;

        Vec3 eye = mc.player.getEyePosition(1.0f);

        AABB checkBox = new AABB(airPos);
        for (Entity e : mc.level.getEntities(null, checkBox)) {
            if (e instanceof ItemEntity) continue;
            if (e instanceof EndCrystal crystal && crystal.tickCount < 5) continue;
            if (e instanceof EndCrystal crystal && crystal.blockPosition().equals(airPos)) continue;
            return false;
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
        Homovore.rotationManager.submit(new RotationRequest("AutoCrystal_place", 60, angles[0], angles[1], RotationRequest.Mode.SILENT));

        AABB baseBox = new AABB(base);
        boolean insideBox = baseBox.contains(eye);
        if (!insideBox) {
            Vec3 look = getLookVector(Homovore.rotationManager.getServerYaw(), Homovore.rotationManager.getServerPitch());
            Vec3 reachEnd = eye.add(look.scale(6.0));
            if (baseBox.clip(eye, reachEnd).isEmpty()) return false;
        }

        BlockHitResult hit = new BlockHitResult(hitVec, clickFace, base, false);

        var conn = mc.getConnection();
        int originalSlot = InventoryUtil.selected();
        boolean needSlotSwap = hotbarSlot != originalSlot;

        if (needSlotSwap) {
            conn.send(new ServerboundSetCarriedItemPacket(hotbarSlot));
        }

        try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
            conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
        }

        if (needSlotSwap) {
            conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
        }

        return true;
    }

    public boolean placeFireworksAlt(List<BlockPos> poses, Direction face, int hotbarSlot) {
        if (nullCheck()) return false;
        if (poses.isEmpty()) return false;
        if (mc.player.containerMenu.containerId != 0) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;

        ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
        if (stack.isEmpty()) return false;

        var conn = mc.getConnection();
        if (conn == null) return false;

        int containerSlot = hotbarSlot + 36;
        boolean swapped = hotbarSlot != InventoryUtil.selected();

        if (swapped) InventoryUtil.click(containerSlot, InventoryUtil.selected(), ClickType.SWAP);
        try {
            for (BlockPos pos : poses) {
                BlockPos neighbour = pos.relative(face);
                Vec3 hitPos = Vec3.atCenterOf(pos).relative(face, 0.5);
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

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    private record PlacementTask(BlockPos pos, @Nullable Direction face, int hotbarSlot) {}
}
