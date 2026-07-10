package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.manager.PlacementManager;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.render.BreakIndicatorsModule;
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

    private final Setting<Boolean> mineProtect   = bool("MineProtect", true).setPage("General");

    private final Setting<Boolean> fireworks     = bool("Fireworks", true).setPage("General");
    private final Setting<Boolean> crystalProtect = bool("CrystalProtect", true).setPage("General");

    private final Setting<Boolean> avoidHelping  = bool("AvoidHelpingOpponents", true).setPage("General");

    private final Setting<Boolean> selfTrap      = bool("SelfTrap", true).setPage("SelfTrap");
    private final Setting<SelfTrapMode> selfTrapMode = mode("SelfTrapMode", SelfTrapMode.Smart).setPage("SelfTrap");
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

    private final Set<BlockPos> extendPoses = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> opponentSurroundPoses = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> helpBlockedPoses = ConcurrentHashMap.newKeySet();

    private final Map<BlockPos, Integer> breakCounts = new ConcurrentHashMap<>();
    private static final int SAFE_REBREAK_THRESHOLD = 3;

    private final PlacementManager.PlacementListener airRefillListener = (pos, nowAir) -> {

        boolean wanted = wantedPoses.contains(pos);
        if (!wanted && !extendPoses.contains(pos)) return;

        boolean wasOwned = ownedQueued.remove(pos);
        if (!nowAir) {
            if (wasOwned) breakCounts.merge(pos.immutable(), 1, Integer::sum);
            return;
        }

        if (fireworkPoses.contains(pos)) return;

        if (speedMineClaims(pos)) return;

        if (cachedFireworkSlot >= 0 && canRocket(pos, System.currentTimeMillis())) {
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
    private long lastAttackTime       = 0;

    private BlockPos lastExtendOffset = null;
    private long     lastExtendThreat = 0;
    private static final long EXTEND_HOLD_MS = 1000;

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
        breakCounts.clear();
        cachedObsSlot = -1;
        cachedFireworkSlot = -1;
        renderMap.clear();
        lastExtendOffset = null;
        lastExtendThreat = 0;
        lastCrystalNearHead  = 0;
        lastAttackTime       = 0;
    }

    @Subscribe(priority = 1)
    private void onTick(TickEvent event) {
        if (nullCheck() || mc.screen != null) return;

        var obs = InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.PLACE_SCOPE);
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
            var fw = InventoryUtil.find(Items.FIREWORK_ROCKET, InventoryUtil.PLACE_SCOPE);
            if (fw.found() && fw.type() != ResultType.OFFHAND) cachedFireworkSlot = fw.slot();
        }

        List<BlockPos> placePoses = new ArrayList<>();
        List<BlockPos> rebreakPlacePoses = new ArrayList<>();
        List<BlockPos> rebreakSides = new ArrayList<>();
        List<BlockPos> fireworkPlacePoses = new ArrayList<>();
        List<BlockPos> feetPositions = new ArrayList<>();
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
                feetPositions.add(feetPos);

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

                    if (isHot(adjacent, now)) {
                        rebreakSides.add(adjacent.immutable());
                    }

                    if (selfTrap.getValue() && selfTrapMode.getValue() != SelfTrapMode.None) {
                        checkSelfTrap(adjacent, now, placePoses);
                    }

                    if (cachedFireworkSlot >= 0) {
                        BlockPos extendPos = feetPos.relative(dir, 2);
                        extendPoses.add(extendPos.immutable());
                        tryFirework(extendPos, now, fireworkPlacePoses);
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

        if (extend.getValue() && extendMode.getValue() != ExtendMode.None) {
            computeExtend(feetPositions, placePoses, now);
        }

        if (mineProtect.getValue()) {
            applyMineProtect(feetPositions, placePoses);
        }

        if (fireworks.getValue()) {
            for (BlockPos rebreak : rebreakSides) {
                for (Direction dir : HORIZONTALS) {
                    BlockPos neighbor = rebreak.relative(dir);

                    if (neighbor.getY() == feetY
                            && neighbor.getX() >= minX && neighbor.getX() <= maxX
                            && neighbor.getZ() >= minZ && neighbor.getZ() <= maxZ) continue;
                    if (wantedPoses.contains(neighbor)) continue;

                    wantedPoses.add(neighbor.immutable());

                    BlockState state = mc.level.getBlockState(neighbor);
                    if (state.isAir() || state.canBeReplaced()) {
                        rebreakPlacePoses.add(neighbor);
                    }
                }
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
                breakCounts.remove(pos);
            }
        }

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        Vec3 predicted = mc.player.position().add(mc.player.getDeltaMovement().scale(0.5));
        placePoses.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(predicted)));

        if (fireworks.getValue()) {
            rebreakPlacePoses.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(predicted)));
            placePoses.addAll(rebreakPlacePoses);
        }

        if (attack.getValue() && now - lastAttackTime >= 50) {
            EndCrystal crystal = findThreateningCrystal();
            if (crystal != null) {
                BlockPos crystalCell = crystal.blockPosition();
                breakCrystal(crystal);
                lastAttackTime = now;
                for (BlockPos p : wantedPoses) {
                    if (Math.abs(p.getX() - crystalCell.getX()) <= 1
                            && Math.abs(p.getZ() - crystalCell.getZ()) <= 1
                            && Math.abs(p.getY() - crystalCell.getY()) <= 2) {
                        Homovore.placementManager.forceResetPlaceCooldown(p);
                    }
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

    private EndCrystal findThreateningCrystal() {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        AABB search = mc.player.getBoundingBox().inflate(6.0);
        EndCrystal best = null;
        double bestSq = Double.MAX_VALUE;
        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, search)) {
            if (!threatensSurround(c)) continue;
            AABB box = c.getBoundingBox();
            double d = distSqToBox(eye, box);
            if (d >= bestSq) continue;
            if (d > 36.0) continue;
            bestSq = d;
            best = c;
        }
        return best;
    }

    private boolean threatensSurround(EndCrystal crystal) {
        BlockPos cell = crystal.blockPosition();
        return nearSurroundCell(cell) || nearSurroundCell(cell.below()) || nearFootprintPerimeter(cell) || nearFootprintPerimeter(cell.below());
    }

    private boolean nearSurroundCell(BlockPos p) {
        if (wantedPoses.contains(p) || extendPoses.contains(p)) return true;
        for (Direction dir : HORIZONTALS) {
            BlockPos n = p.relative(dir);
            if (wantedPoses.contains(n) || extendPoses.contains(n)) return true;
        }
        return false;
    }

    private boolean nearFootprintPerimeter(BlockPos p) {
        AABB bounds = mc.player.getBoundingBox();
        int feetY = mc.player.blockPosition().getY();
        if (Math.abs(p.getY() - feetY) > 2) return false;

        int minX = PlaceUtil.minCell(bounds.minX);
        int maxX = PlaceUtil.maxCell(bounds.maxX);
        int minZ = PlaceUtil.minCell(bounds.minZ);
        int maxZ = PlaceUtil.maxCell(bounds.maxZ);

        return p.getX() >= minX - 2 && p.getX() <= maxX + 2
                && p.getZ() >= minZ - 2 && p.getZ() <= maxZ + 2
                && (p.getX() < minX || p.getX() > maxX || p.getZ() < minZ || p.getZ() > maxZ);
    }

    private void breakCrystal(EndCrystal crystal) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 hit = closestPointToEye(eye, crystal.getBoundingBox());
        float[] angles = MathUtil.calcAngle(eye, hit);
        Homovore.rotationManager.submit(new RotationRequest(
                "Surround", 100, angles[0], angles[1], RotationRequest.Mode.SILENT));
        mc.gameMode.attack(mc.player, crystal);
    }

    private Vec3 closestPointToEye(Vec3 eye, AABB box) {
        double x = eye.x;
        double y = eye.y;
        double z = eye.z;
        double vec = 1.0 / 16.0;
        double eps = 1e-9;

        if (eye.x < box.minX) x = box.minX;
        else if (eye.x > box.maxX) x = box.maxX;
        if (eye.y < box.minY) y = box.minY;
        else if (eye.y > box.maxY) y = box.maxY;
        if (eye.z < box.minZ) z = box.minZ;
        else if (eye.z > box.maxZ) z = box.maxZ;

        if (Math.abs(x - box.minX) < eps) x = Math.min(box.minX + vec, box.maxX - eps);
        else if (Math.abs(x - box.maxX) < eps) x = Math.max(box.maxX - vec, box.minX + eps);
        if (Math.abs(z - box.minZ) < eps) z = Math.min(box.minZ + vec, box.maxZ - eps);
        else if (Math.abs(z - box.maxZ) < eps) z = Math.max(box.maxZ - vec, box.minZ + eps);

        return new Vec3(x, y, z);
    }

    private static double distSqToBox(Vec3 p, AABB box) {
        double dx = p.x < box.minX ? box.minX - p.x : (p.x > box.maxX ? p.x - box.maxX : 0);
        double dy = p.y < box.minY ? box.minY - p.y : (p.y > box.maxY ? p.y - box.maxY : 0);
        double dz = p.z < box.minZ ? box.minZ - p.z : (p.z > box.maxZ ? p.z - box.maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
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

    private void computeExtend(List<BlockPos> feetPositions, List<BlockPos> placePoses, long now) {
        if (extendMode.getValue() == ExtendMode.Always) {
            for (BlockPos feet : feetPositions) {
                for (Direction dir : HORIZONTALS) {
                    placeExtendBlocks(placePoses, feet, new BlockPos(dir.getStepX(), 0, dir.getStepZ()));
                }
            }
            return;
        }

        EndCrystal crystal = findExtendCrystal();
        if (crystal != null) {
            lastExtendOffset = crystal.blockPosition().subtract(mc.player.blockPosition());
            lastExtendThreat = now;
        }

        if (lastExtendOffset == null || now - lastExtendThreat >= EXTEND_HOLD_MS) return;

        for (BlockPos feet : feetPositions) {
            placeExtendBlocks(placePoses, feet, lastExtendOffset);
        }
    }

    private EndCrystal findExtendCrystal() {
        AABB box = mc.player.getBoundingBox().inflate(1.5, 0.5, 1.5).move(0, 1, 0);
        Vec3 eye = mc.player.getEyePosition();
        EndCrystal closest = null;
        double bestSq = Double.MAX_VALUE;
        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, box)) {
            double d = c.distanceToSqr(eye);
            if (d < bestSq) {
                bestSq = d;
                closest = c;
            }
        }
        return closest;
    }

    private void placeExtendBlocks(List<BlockPos> out, BlockPos feetPos, BlockPos crystalOffset) {
        if (crystalOffset == null) return;

        int normDx = Integer.signum(crystalOffset.getX());
        int normDz = Integer.signum(crystalOffset.getZ());

        boolean isDiagonal = normDx != 0 && normDz != 0;
        boolean isCardinal = (normDx != 0) ^ (normDz != 0);

        if (isDiagonal) {
            addExtendIfReplaceable(out, feetPos.offset(normDx, 0, normDz));
            addExtendIfReplaceable(out, feetPos.offset(normDx * 2, 0, 0));
            addExtendIfReplaceable(out, feetPos.offset(0, 0, normDz * 2));
        } else if (isCardinal) {
            BlockPos diagonal1, diagonal2, straightBlock;
            if (normDx != 0) {
                diagonal1 = feetPos.offset(normDx, 0, 1);
                diagonal2 = feetPos.offset(normDx, 0, -1);
                straightBlock = feetPos.offset(normDx * 2, 0, 0);
            } else {
                diagonal1 = feetPos.offset(1, 0, normDz);
                diagonal2 = feetPos.offset(-1, 0, normDz);
                straightBlock = feetPos.offset(0, 0, normDz * 2);
            }
            addExtendIfReplaceable(out, diagonal1);
            addExtendIfReplaceable(out, diagonal2);
            addExtendIfReplaceable(out, straightBlock);
        }
    }

    private void applyMineProtect(List<BlockPos> feetPositions, List<BlockPos> placePoses) {
        BreakIndicatorsModule indicators = Homovore.moduleManager.getModuleByClass(BreakIndicatorsModule.class);
        if (indicators == null || !indicators.isEnabled()) return;

        for (BreakIndicatorsModule.BreakInfo info : indicators.getActiveBreaksSnapshot().values()) {
            Player player = info.player();
            if (player == null || player == mc.player || Homovore.friendManager.isFriend(player)) continue;

            applyMineProtectBlock(feetPositions, placePoses, info.pos());
        }
    }

    private void applyMineProtectBlock(List<BlockPos> feetPositions, List<BlockPos> placePoses, BlockPos mined) {
        if (mined == null) return;

        for (BlockPos feet : feetPositions) {
            if (mined.getY() != feet.getY()) continue;
            int dx = mined.getX() - feet.getX();
            int dz = mined.getZ() - feet.getZ();
            if (Math.abs(dx) + Math.abs(dz) != 1) continue;

            addMineProtectIfReplaceable(placePoses, mined.above());
            addMineProtectIfReplaceable(placePoses, mined.below());
            if (dx != 0) {
                addMineProtectIfReplaceable(placePoses, feet.offset(dx, 0, 1));
                addMineProtectIfReplaceable(placePoses, feet.offset(dx, 0, -1));
                addMineProtectIfReplaceable(placePoses, feet.offset(dx * 2, 0, 0));
            } else {
                addMineProtectIfReplaceable(placePoses, feet.offset(1, 0, dz));
                addMineProtectIfReplaceable(placePoses, feet.offset(-1, 0, dz));
                addMineProtectIfReplaceable(placePoses, feet.offset(0, 0, dz * 2));
            }
        }
    }

    private void addMineProtectIfReplaceable(List<BlockPos> out, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) {
            wantedPoses.add(pos.immutable());
            extendPoses.add(pos.immutable());
            out.add(pos);
        }
    }

    private void addExtendIfReplaceable(List<BlockPos> out, BlockPos pos) {
        BlockState below = mc.level.getBlockState(pos.below());
        if (!below.is(Blocks.OBSIDIAN) && !below.is(Blocks.BEDROCK)) return;

        wantedPoses.add(pos.immutable());
        extendPoses.add(pos.immutable());
        if (fireworkPoses.contains(pos)) return;

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) {
            out.add(pos);
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

        if (hasLiveFireworkAt(pos)) {
            fireworkPoses.add(pos.immutable());
            return true;
        }
        Long last = fireworkDeployedAt.get(pos);
        if (last != null && now - last < FIREWORK_REDEPLOY_COOLDOWN_MS) {
            fireworkPoses.add(pos.immutable());
            return true;
        }

        if (!canRocket(pos, now)) return false;
        if (speedMineClaims(pos)) return false;

        fireworkPoses.add(pos.immutable());
        out.add(pos);
        return true;
    }

    private boolean canRocket(BlockPos pos, long now) {
        return (isHot(pos, now) || crystalProtect.getValue() && hasCrystalAt(pos))
                && isFullBlock(pos.above()) && isCrystalBase(pos.below()) && hasFireworkCornerSupport(pos);
    }

    private boolean isCrystalBase(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private boolean hasCrystalAt(BlockPos pos) {
        AABB box = new AABB(pos).inflate(0.5, 1.0, 0.5);
        return !mc.level.getEntitiesOfClass(EndCrystal.class, box).isEmpty();
    }

    private boolean hasFireworkCornerSupport(BlockPos pos) {
        AABB bounds = mc.player.getBoundingBox();
        int minX = PlaceUtil.minCell(bounds.minX);
        int maxX = PlaceUtil.maxCell(bounds.maxX);
        int minZ = PlaceUtil.minCell(bounds.minZ);
        int maxZ = PlaceUtil.maxCell(bounds.maxZ);
        int x = pos.getX();
        int z = pos.getZ();

        if (z < minZ && x >= minX && x <= maxX) return isFullBlock(pos.west()) && isFullBlock(pos.east());
        if (z > maxZ && x >= minX && x <= maxX) return isFullBlock(pos.west()) && isFullBlock(pos.east());
        if (x < minX && z >= minZ && z <= maxZ) return isFullBlock(pos.north()) && isFullBlock(pos.south());
        if (x > maxX && z >= minZ && z <= maxZ) return isFullBlock(pos.north()) && isFullBlock(pos.south());
        return false;
    }

    private boolean isHot(BlockPos pos, long now) {
        return breakCounts.getOrDefault(pos, 0) >= SAFE_REBREAK_THRESHOLD;
    }

    private boolean isFullBlock(BlockPos pos) {
        return Block.isShapeFullBlock(mc.level.getBlockState(pos).getCollisionShape(mc.level, pos));
    }

    private boolean hasLiveFireworkAt(BlockPos pos) {
        AABB box = new AABB(pos.getX() + 0.2, pos.getY(), pos.getZ() + 0.2,
                pos.getX() + 0.8, pos.getY() + 4.0, pos.getZ() + 0.8);
        for (FireworkRocketEntity fw : mc.level.getEntitiesOfClass(FireworkRocketEntity.class, box)) {
            if (fw.isAlive()) return true;
        }
        return false;
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    public enum SelfTrapMode { None, Smart, Always }
    public enum ExtendMode { None, Smart, Always }
}
