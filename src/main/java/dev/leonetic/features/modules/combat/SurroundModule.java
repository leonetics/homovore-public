package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.manager.PlacementManager;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.mixin.entity.FireworkRocketEntityAccessor;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.world.SpeedMineModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SurroundModule extends Module {

    private final Setting<Boolean> attack        = bool("Attack", true).setPage("General");

    private final Setting<Boolean> fireworks     = bool("Fireworks", true).setPage("General");

    private final Setting<Boolean> avoidHelping  = bool("AvoidHelpingOpponents", true).setPage("General");

    private final Setting<Boolean> selfTrap      = bool("SelfTrap", true).setPage("SelfTrap");
    private final Setting<SelfTrapMode> selfTrapMode = mode("SelfTrapMode", SelfTrapMode.None).setPage("SelfTrap");
    private final Setting<Boolean> selfTrapHead  = bool("SelfTrapHead", true).setPage("SelfTrap");
    private final Setting<Boolean> crawlTrap     = bool("CrawlTrap", true).setPage("SelfTrap");

    private final Setting<Boolean> extend        = bool("Extend", true).setPage("Extend");
    private final Setting<ExtendMode> extendMode = mode("ExtendMode", ExtendMode.Smart).setPage("Extend");

    private final Setting<Boolean> render        = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime      = num("FadeTime", 0.2f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor     = color("FillColor", 0, 62, 122, 148).setPage("Render");
    private final Setting<Color>   outlineColor  = color("OutlineColor", 0, 62, 122, 148).setPage("Render");

    private final Map<BlockPos, Long> renderMap = new HashMap<>();

    private final Set<BlockPos> wantedPoses = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> ownedQueued = new HashSet<>();

    public boolean isSurroundPos(BlockPos pos) {
        return wantedPoses.contains(pos);
    }
    private int cachedObsSlot = -1;

    private int cachedFireworkSlot = -1;

    private final Set<BlockPos> fireworkPoses = ConcurrentHashMap.newKeySet();

    private final Map<BlockPos, Long> fireworkDeployedAt = new HashMap<>();
    private static final long FIREWORK_REDEPLOY_COOLDOWN_MS = 500;

    private static final int FIREWORK_REDEPLOY_MARGIN_TICKS = 5;

    private final Set<BlockPos> extendPoses = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> opponentSurroundPoses = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> helpBlockedPoses = ConcurrentHashMap.newKeySet();

    private final Map<BlockPos, Deque<Long>> breakTimes = new ConcurrentHashMap<>();
    private static final long REBREAK_WINDOW_MS = 20 * 50;
    private static final int REBREAK_THRESHOLD = 2;

    private final PlacementManager.PlacementListener airRefillListener = (pos, nowAir) -> {

        ownedQueued.remove(pos);
        if (!nowAir) return;

        boolean wanted = wantedPoses.contains(pos);
        if (!wanted && !extendPoses.contains(pos)) return;

        recordBreak(pos, System.currentTimeMillis());

        if (fireworkPoses.contains(pos)) return;

        if (speedMineClaims(pos)) return;

        if (cachedFireworkSlot >= 0 && isHot(pos, System.currentTimeMillis())
                && isFullBlock(pos.above()) && isFullBlock(pos.below())) {
            fireworkPoses.add(pos.immutable());
            return;
        }

        if (!wanted) return;
        if (helpBlockedPoses.contains(pos)) return;
        int slot = cachedObsSlot;
        if (slot < 0) return;
        if (!PlaceUtil.canPlace(pos)) return;
        if (Homovore.placementManager.enqueue(pos, slot)) {
            ownedQueued.add(pos);
        }

    };

    private long lastCrystalNearHead  = 0;
    private long lastCrystalForExtend = 0;
    private long lastAttackTime       = 0;

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public SurroundModule() {
        super("Surround", "Surrounds you in obsidian to prevent crystal damage.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        Homovore.placementManager.addListener(airRefillListener);
    }

    @Override
    public void onDisable() {
        Homovore.placementManager.removeListener(airRefillListener);
        Homovore.placementManager.removeQueuedFor(ownedQueued::contains);
        ownedQueued.clear();
        wantedPoses.clear();
        fireworkPoses.clear();
        extendPoses.clear();
        opponentSurroundPoses.clear();
        helpBlockedPoses.clear();
        fireworkDeployedAt.clear();
        breakTimes.clear();
        cachedObsSlot = -1;
        cachedFireworkSlot = -1;
        renderMap.clear();
        lastCrystalNearHead  = 0;
        lastCrystalForExtend = 0;
        lastAttackTime       = 0;
    }

    @Subscribe(priority = 1)
    private void onTick(TickEvent event) {
        if (nullCheck() || mc.screen != null) return;

        var obs = InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.HOTBAR_SCOPE);
        if (!obs.found() || obs.type() == ResultType.OFFHAND) {
            cachedObsSlot = -1;
            wantedPoses.clear();
            helpBlockedPoses.clear();
            return;
        }
        int obsSlot = obs.slot();
        cachedObsSlot = obsSlot;

        cachedFireworkSlot = -1;
        if (fireworks.getValue()) {
            var fw = InventoryUtil.find(Items.FIREWORK_ROCKET, InventoryUtil.HOTBAR_SCOPE);
            if (fw.found() && fw.type() != ResultType.OFFHAND) cachedFireworkSlot = fw.slot();
        }

        List<BlockPos> placePoses = new ArrayList<>();
        List<BlockPos> cornerPlacePoses = new ArrayList<>();
        List<BlockPos> fireworkPlacePoses = new ArrayList<>();
        wantedPoses.clear();
        fireworkPoses.clear();
        extendPoses.clear();
        long now = System.currentTimeMillis();

        AABB bounds = mc.player.getBoundingBox();
        int feetY = mc.player.blockPosition().getY();

        int minX = PlaceUtil.minCell(bounds.minX);
        int maxX = PlaceUtil.maxCell(bounds.maxX);
        int minZ = PlaceUtil.minCell(bounds.minZ);
        int maxZ = PlaceUtil.maxCell(bounds.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos feetPos = new BlockPos(x, feetY, z);

                for (Direction dir : HORIZONTALS) {
                    BlockPos adjacent = feetPos.relative(dir);

                    if (adjacent.getX() >= minX && adjacent.getX() <= maxX
                            && adjacent.getZ() >= minZ && adjacent.getZ() <= maxZ) continue;

                    BlockState state = mc.level.getBlockState(adjacent);

                    wantedPoses.add(adjacent.immutable());

                    if (tryFirework(adjacent, now, fireworkPlacePoses)) {

                    } else if (state.isAir() || state.canBeReplaced()) {
                        placePoses.add(adjacent);
                    }

                    if (selfTrap.getValue() && selfTrapMode.getValue() != SelfTrapMode.None) {
                        checkSelfTrap(adjacent, now, placePoses);
                    }

                    if (cachedFireworkSlot >= 0) {
                        BlockPos extendPos = feetPos.relative(dir, 2);
                        extendPoses.add(extendPos.immutable());
                        tryFirework(extendPos, now, fireworkPlacePoses);
                    }

                    if (extend.getValue() && extendMode.getValue() != ExtendMode.None) {
                        checkExtend(feetPos, dir, now, placePoses);
                    }
                }

                BlockPos below = feetPos.below();
                BlockState belowState = mc.level.getBlockState(below);

                wantedPoses.add(below.immutable());
                if (belowState.isAir() || belowState.canBeReplaced()) {
                    placePoses.add(below);
                }
            }
        }

        // Diagonal corners of the footprint. These are placed after the edges
        // (appended to the queue below), so the edge ring is always secured
        // first, then the corners close the diagonal gaps.
        BlockPos[] corners = {
                new BlockPos(minX - 1, feetY, minZ - 1),
                new BlockPos(minX - 1, feetY, maxZ + 1),
                new BlockPos(maxX + 1, feetY, minZ - 1),
                new BlockPos(maxX + 1, feetY, maxZ + 1)
        };
        for (BlockPos corner : corners) {
            BlockState state = mc.level.getBlockState(corner);

            wantedPoses.add(corner.immutable());

            if (fireworks.getValue() && tryFirework(corner, now, fireworkPlacePoses)) continue;
            if (state.isAir() || state.canBeReplaced()) {
                cornerPlacePoses.add(corner);
            }
        }

        if (selfTrap.getValue() && selfTrapHead.getValue()) {
            boolean prone = crawlTrap.getValue()
                    && (mc.player.isVisuallyCrawling() || mc.player.isFallFlying());
            BlockPos head = mc.player.blockPosition().above(prone ? 1 : 2);
            wantedPoses.add(head);
            placePoses.add(head);
        }

        if (!ownedQueued.isEmpty()) {
            Homovore.placementManager.removeQueuedFor(p -> ownedQueued.contains(p) && !wantedPoses.contains(p));
            ownedQueued.removeIf(p -> !wantedPoses.contains(p));
        }

        List<BlockPos> fireworkUsePoses = new ArrayList<>();
        for (BlockPos pos : fireworkPlacePoses) {
            if (!PlaceUtil.canPlace(pos)) continue;
            fireworkUsePoses.add(pos);
        }
        if (Homovore.placementManager.placeFireworksAlt(fireworkUsePoses, Direction.DOWN, cachedFireworkSlot)) {
            for (BlockPos pos : fireworkUsePoses) {
                fireworkDeployedAt.put(pos.immutable(), now);
            }
        }

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        Vec3 predicted = mc.player.position().add(mc.player.getDeltaMovement().scale(0.5));
        placePoses.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(predicted)));

        // Corners are queued strictly after every edge/floor/head block so the
        // main surround is always completed first; sort them among themselves.
        if (fireworks.getValue()) {
            cornerPlacePoses.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(predicted)));
            placePoses.addAll(cornerPlacePoses);
        }

        if (attack.getValue() && now - lastAttackTime >= 50) {

            List<BlockPos> attackPoses = new ArrayList<>(wantedPoses);
            attackPoses.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(predicted)));
            for (BlockPos pos : attackPoses) {
                EndCrystal crystal = getCrystalNear(pos);
                if (crystal == null) continue;

                Vec3 eyePos = mc.player.getEyePosition(1.0f);
                Vec3 crystalCenter = crystal.position().add(0, crystal.getBbHeight() / 2.0, 0);
                float[] angles = MathUtil.calcAngle(eyePos, crystalCenter);

                Homovore.rotationManager.submit(new RotationRequest(
                    "Surround", 100, angles[0], angles[1], RotationRequest.Mode.SILENT
                ));

                float sYaw = Homovore.rotationManager.getServerYaw();
                float sPitch = Homovore.rotationManager.getServerPitch();
                Vec3 lookVec = getLookVector(sYaw, sPitch);
                Vec3 reachEnd = eyePos.add(lookVec.scale(6.0));

                if (crystal.getBoundingBox().clip(eyePos, reachEnd).isPresent()) {
                    mc.gameMode.attack(mc.player, crystal);
                    crystal.discard();
                    lastAttackTime = now;

                    Homovore.placementManager.forceResetPlaceCooldown(pos);
                }
            }
        }

        computeHelpBlocked(minX, maxX, minZ, maxZ, feetY);

        for (BlockPos pos : placePoses) {

            if (helpBlockedPoses.contains(pos)) continue;
            if (speedMineClaims(pos)) continue;
            if (!PlaceUtil.canPlace(pos)) continue;

            if (Homovore.placementManager.enqueue(pos, obsSlot)) {
                ownedQueued.add(pos);
                renderMap.put(pos, now);
            }
        }

        long fadeMs = (long) (fadeTime.getValue() * 1000);
        renderMap.entrySet().removeIf(e -> now - e.getValue() > fadeMs);

        fireworkDeployedAt.entrySet().removeIf(e -> now - e.getValue() > FIREWORK_REDEPLOY_COOLDOWN_MS);

        breakTimes.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long newest = e.getValue().peekLast();
                return newest == null || now - newest > REBREAK_WINDOW_MS;
            }
        });
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue()) return;

        long now = System.currentTimeMillis();
        double fadeMs = fadeTime.getValue() * 1000.0;

        for (Map.Entry<BlockPos, Long> entry : renderMap.entrySet()) {
            long age = now - entry.getValue();
            if (age > fadeMs) continue;

            double t = age / fadeMs;

            Color fc = fillColor.getValue();
            Color oc = outlineColor.getValue();

            RenderUtil.drawBoxFilled(event.getMatrix(), entry.getKey(),
                    withAlpha(fc, (int) (fc.getAlpha() * (1 - t))));
            RenderUtil.drawBox(event.getMatrix(), entry.getKey(),
                    withAlpha(oc, (int) (oc.getAlpha() * (1 - t))), 1.0f);
        }
    }

    private boolean speedMineClaims(BlockPos pos) {
        SpeedMineModule mine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        return mine != null && mine.isEnabled() && mine.alreadyBreaking(pos);
    }

    private boolean intersectsCrystal(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);

        AABB box = new AABB(
                center.x - 0.05, center.y - 0.05, center.z - 0.05,
                center.x + 0.05, center.y + 0.05, center.z + 0.05
        );

        return !mc.level.getEntitiesOfClass(EndCrystal.class, box).isEmpty();
    }

    private EndCrystal getCrystalNear(BlockPos pos) {
        AABB box = new AABB(
                pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        );

        return mc.level.getEntitiesOfClass(EndCrystal.class, box)
                .stream().findFirst().orElse(null);
    }

    private void checkSelfTrap(BlockPos adjacent, long now, List<BlockPos> placePoses) {
        BlockPos face = adjacent.above();
        boolean should = selfTrapMode.getValue() == SelfTrapMode.Always;

        if (selfTrapMode.getValue() == SelfTrapMode.Smart) {
            if (intersectsCrystal(face)) {
                lastCrystalNearHead = now;
            }

            if (now - lastCrystalNearHead < 1000) {
                should = true;
            }
        }

        if (should) {
            wantedPoses.add(face.immutable());
            BlockState state = mc.level.getBlockState(face);
            if (state.isAir() || state.canBeReplaced()) {
                placePoses.add(face);
            }
        }
    }

    private void checkExtend(BlockPos feet, Direction dir, long now, List<BlockPos> placePoses) {
        BlockPos extendPos = feet.relative(dir, 2);
        boolean should = extendMode.getValue() == ExtendMode.Always;

        if (extendMode.getValue() == ExtendMode.Smart) {
            if (intersectsCrystal(extendPos)) {
                lastCrystalForExtend = now;
            }

            if (now - lastCrystalForExtend < 1000) {
                should = true;
            }
        }

        if (should) {
            BlockState state = mc.level.getBlockState(extendPos);
            BlockState below = mc.level.getBlockState(extendPos.below());

            if ((below.is(Blocks.OBSIDIAN) || below.is(Blocks.BEDROCK))) {
                wantedPoses.add(extendPos.immutable());

                if (fireworkPoses.contains(extendPos)) {
                    return;
                }
                if (state.isAir() || state.canBeReplaced()) {
                    placePoses.add(extendPos);
                }
            }
        }
    }

    private void computeHelpBlocked(int minX, int maxX, int minZ, int maxZ, int feetY) {
        opponentSurroundPoses.clear();
        helpBlockedPoses.clear();

        if (!avoidHelping.getValue()) return;

        buildOpponentSurroundPoses();
        if (opponentSurroundPoses.isEmpty()) return;

        boolean singleFootCell = minX == maxX && minZ == maxZ;
        boolean lowHealth = mc.player.getHealth() < 10.0f;

        if (singleFootCell || lowHealth) return;

        Set<BlockPos> myFootCells = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                myFootCells.add(new BlockPos(x, feetY, z));
            }
        }

        for (BlockPos pos : opponentSurroundPoses) {
            if (!isNearMyPerimeter(pos, myFootCells)) {
                helpBlockedPoses.add(pos.immutable());
            }
        }
    }

    private void buildOpponentSurroundPoses() {
        BlockPos self = mc.player.blockPosition();

        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            if (Homovore.friendManager.isFriend(p)) continue;

            BlockPos oFeet = p.blockPosition();
            double dx = oFeet.getX() - self.getX();
            double dz = oFeet.getZ() - self.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > 5.0) continue;
            if (isEntityPhased(p)) continue;

            AABB pb = p.getBoundingBox();
            int oy = oFeet.getY();
            int oMinX = PlaceUtil.minCell(pb.minX);
            int oMaxX = PlaceUtil.maxCell(pb.maxX);
            int oMinZ = PlaceUtil.minCell(pb.minZ);
            int oMaxZ = PlaceUtil.maxCell(pb.maxZ);

            for (int x = oMinX; x <= oMaxX; x++) {
                for (int z = oMinZ; z <= oMaxZ; z++) {
                    BlockPos cell = new BlockPos(x, oy, z);

                    for (Direction dir : HORIZONTALS) {
                        BlockPos adj = cell.relative(dir);
                        if (adj.getX() >= oMinX && adj.getX() <= oMaxX
                                && adj.getZ() >= oMinZ && adj.getZ() <= oMaxZ) continue;
                        opponentSurroundPoses.add(adj.immutable());
                    }

                    opponentSurroundPoses.add(cell.below().immutable());
                    opponentSurroundPoses.add(cell.immutable());
                }
            }
        }
    }

    private boolean isNearMyPerimeter(BlockPos pos, Set<BlockPos> myFootCells) {
        for (BlockPos cell : myFootCells) {
            if (pos.equals(cell)
                    || pos.equals(cell.above())
                    || pos.equals(cell.below())
                    || pos.equals(cell.north())
                    || pos.equals(cell.south())
                    || pos.equals(cell.east())
                    || pos.equals(cell.west())) {
                return true;
            }
        }
        return false;
    }

    private boolean isEntityPhased(Player p) {
        BlockState state = mc.level.getBlockState(p.blockPosition());
        return !state.isAir() && !state.canBeReplaced();
    }

    private boolean tryFirework(BlockPos pos, long now, List<BlockPos> out) {
        if (cachedFireworkSlot < 0) return false;

        if (!isFullBlock(pos.above())) return false;

        if (!isFullBlock(pos.below())) return false;

        if (!isHot(pos, now)) return false;
        fireworkPoses.add(pos.immutable());
        if (speedMineClaims(pos)) return true;
        if (hasLiveFireworkAt(pos)) return true;
        Long last = fireworkDeployedAt.get(pos);
        if (last != null && now - last < FIREWORK_REDEPLOY_COOLDOWN_MS) return true;
        out.add(pos);
        return true;
    }

    private void recordBreak(BlockPos pos, long now) {
        Deque<Long> times = breakTimes.computeIfAbsent(pos.immutable(), p -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > REBREAK_WINDOW_MS) {
                times.pollFirst();
            }
        }
    }

    private boolean isHot(BlockPos pos, long now) {
        Deque<Long> times = breakTimes.get(pos);
        if (times == null) return false;
        int count = 0;
        synchronized (times) {
            for (long t : times) {
                if (now - t <= REBREAK_WINDOW_MS) count++;
            }
        }
        return count > REBREAK_THRESHOLD;
    }

    private boolean isFullBlock(BlockPos pos) {
        return Block.isShapeFullBlock(mc.level.getBlockState(pos).getCollisionShape(mc.level, pos));
    }

    private boolean hasLiveFireworkAt(BlockPos pos) {
        for (FireworkRocketEntity fw : mc.level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(pos))) {
            FireworkRocketEntityAccessor acc = (FireworkRocketEntityAccessor) fw;
            if (acc.homovore$getLifetime() - acc.homovore$getLife() > FIREWORK_REDEPLOY_MARGIN_TICKS) return true;
        }
        return false;
    }

    private static Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    public enum SelfTrapMode { None, Smart, Always }
    public enum ExtendMode { None, Smart, Always }
}
