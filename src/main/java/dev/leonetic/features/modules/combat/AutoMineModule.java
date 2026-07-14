package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.modules.world.SpeedMineModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.DamageUtil;
import dev.leonetic.util.InteractionUtil;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class AutoMineModule extends Module {
    private static final double INVALID_SCORE = -1000.0;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private final Setting<Double> range = num("Range", 6.5, 0.0, 7.0);
    private final Setting<SortPriority> targetPriority = mode("TargetPriority", SortPriority.Angle);
    private final Setting<Boolean> ignoreNakeds = bool("IgnoreNakeds", true);
    private final Setting<ExtendBreakMode> extendBreakMode = mode("ExtendBreakMode", ExtendBreakMode.None);
    private final Setting<AntiSurroundMode> antiSurroundMode = mode("AntiSurroundMode", AntiSurroundMode.Auto);
    private final Setting<Boolean> antiSurroundInnerSnap = bool("AntiSurroundInnerSnap", true);
    private final Setting<Boolean> antiSurroundOuterSnap = bool("AntiSurroundOuterSnap", true);
    private final Setting<Boolean> avoidPistonCrystal = bool("AvoidPistonCrystal", true);
    private final Setting<Boolean> glassPush = bool("GlassPush", false);
    private final Setting<Integer> glassAttempts = num("GlassAttempts", 2, 1, 5);
    private final Setting<Boolean> glassRender = bool("GlassRender", true).setPage("Render");
    private final Setting<Float> glassFadeTime = num("GlassFadeTime", 0.5f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color> glassFillColor = color("GlassFillColor", 255, 255, 255, 50).setPage("Render");
    private final Setting<Color> glassOutlineColor = color("GlassOutlineColor", 255, 255, 255, 255).setPage("Render");
    private final Setting<Boolean> renderDebugScores = bool("RenderDebugScores", false).setPage("Render");
    private final Setting<Boolean> debugLog = bool("DebugLog", false).setPage("Extra");

    private SpeedMineModule speedMine;
    private Player targetPlayer;
    private CityBlock target1;
    private CityBlock target2;
    private BlockPos ignorePos;
    private long lastOuterPlaceTime;
    private boolean crawlTargeting;
    private int crawlPhasedCells;
    private int crawlHitboxCells;
    private float crawlRebreakDamage;
    private String crawlDecision = "idle";
    private BlockPos finishingStandingDelayed;
    private BlockPos finishingStandingRebreak;
    private int lastDebugTick = -1;
    private BlockPos glassTargetPos;
    private int glassUsedAttempts;
    private BlockPos glassRenderPos;
    private long glassRenderStart;

    private final SpeedMineModule.MineFinishListener finishListener = this::onMineFinished;

    public AutoMineModule() {
        super("AutoMine", "Automatically mines blocks. Requires SpeedMine to work.", Category.COMBAT);
        antiSurroundInnerSnap.setVisibility(value -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Inner);
        antiSurroundOuterSnap.setVisibility(value -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Outer);
        glassAttempts.setVisibility(value -> glassPush.getValue());
        glassFadeTime.setVisibility(value -> glassRender.getValue());
        glassFillColor.setVisibility(value -> glassRender.getValue());
        glassOutlineColor.setVisibility(value -> glassRender.getValue());
    }

    @Override
    public void onEnable() {
        speedMine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (speedMine != null) speedMine.addFinishListener(finishListener);
    }

    @Override
    public void onDisable() {
        if (speedMine != null) speedMine.removeFinishListener(finishListener);
        targetPlayer = null;
        target1 = null;
        target2 = null;
        ignorePos = null;
        crawlTargeting = false;
        crawlPhasedCells = 0;
        crawlHitboxCells = 0;
        crawlRebreakDamage = 0.0f;
        crawlDecision = "disabled";
        clearFinishingStandingMines();
        resetGlass();
        glassRenderPos = null;
    }

    private void onMineFinished(BlockPos minedPos) {
        if (nullCheck() || targetPlayer == null) return;
        AntiSurroundMode mode = antiSurroundMode.getValue();

        AutoCrystalModule autoCrystal = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
        if (autoCrystal == null || !autoCrystal.isEnabled()) return;

        if (mode == AntiSurroundMode.None) return;

        if (mode == AntiSurroundMode.Auto || mode == AntiSurroundMode.Outer) {
            boolean delayedDestroy = minedPos.equals(speedMine.getDelayedDestroyBlockPos());
            boolean rebreak = minedPos.equals(speedMine.getRebreakBlockPos());
            if (delayedDestroy && tryPlaceFurthestFace(autoCrystal, minedPos)) return;
            if (rebreak && tryPlaceOpenRebreakFace(autoCrystal, minedPos)) return;
        }

        if (mode == AntiSurroundMode.Auto || mode == AntiSurroundMode.Inner) {
            for (Direction direction : HORIZONTAL) {
                BlockPos surroundPos = targetPlayer.blockPosition().relative(direction);
                if (surroundPos.equals(minedPos)) {
                    autoCrystal.preplaceCrystal(surroundPos, antiSurroundInnerSnap.getValue());
                }
            }
        }
    }

    private void update() {
        speedMine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (speedMine == null || !speedMine.isEnabled()) return;

        Player previousTarget = targetPlayer;
        boolean wasCrawlTargeting = crawlTargeting;
        targetPlayer = selectTarget();
        if (targetPlayer == null) {
            crawlTargeting = false;
            crawlPhasedCells = 0;
            crawlHitboxCells = 0;
            crawlRebreakDamage = 0.0f;
            crawlDecision = "no_target";
            clearFinishingStandingMines();
            return;
        }

        handleGlassPush();

        boolean enteringCrawl = previousTarget == targetPlayer
                && !wasCrawlTargeting
                && targetPlayer.isVisuallyCrawling();
        if (enteringCrawl) captureFinishingStandingMines();

        if (updateCrawlTargets()) {
            if (waitingForStandingMines()) {
                crawlDecision = "finishing_standing_pair";
                return;
            }
            requestCrawlTargets();
            return;
        }
        clearFinishingStandingMines();
        crawlTargeting = false;
        crawlPhasedCells = 0;
        crawlHitboxCells = 0;
        crawlRebreakDamage = 0.0f;
        crawlDecision = "standing_targets";

        if ((antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Outer)
                && speedMine.hasDelayedDestroy()) {
            AutoCrystalModule autoCrystal = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
            if (autoCrystal != null && autoCrystal.isEnabled()) {
                tryPlaceFurthestFace(autoCrystal, speedMine.getDelayedDestroyBlockPos());
            }
        }

        findTargetBlocks();
        requestSelectedTargets();
    }

    private void captureFinishingStandingMines() {
        if (target1 == null || target2 == null
                || !speedMine.hasDelayedDestroy() || !speedMine.hasRebreakBlock()) return;

        BlockPos delayed = speedMine.getDelayedDestroyBlockPos();
        BlockPos rebreak = speedMine.getRebreakBlockPos();
        boolean delayedSelected = target1.blockPos.equals(delayed) || target2.blockPos.equals(delayed);
        boolean rebreakSelected = target1.blockPos.equals(rebreak) || target2.blockPos.equals(rebreak);
        if (!delayedSelected || !rebreakSelected) return;

        finishingStandingDelayed = delayed.immutable();
        finishingStandingRebreak = rebreak.immutable();
    }

    private boolean waitingForStandingMines() {
        if (finishingStandingDelayed == null || finishingStandingRebreak == null) return false;

        boolean delayedFinished = !finishingStandingDelayed.equals(speedMine.getDelayedDestroyBlockPos());
        boolean rebreakFinished = !finishingStandingRebreak.equals(speedMine.getRebreakBlockPos())
                || speedMine.canRebreakRebreakBlock();
        if (!delayedFinished || !rebreakFinished) return true;

        clearFinishingStandingMines();
        return false;
    }

    private void clearFinishingStandingMines() {
        finishingStandingDelayed = null;
        finishingStandingRebreak = null;
    }

    private void requestSelectedTargets() {
        boolean targetingFeet = target1 != null && target1.isFeetBlock
                || target2 != null && target2.isFeetBlock;
        if (!targetingFeet && (target1 != null && target1.blockPos.equals(speedMine.getRebreakBlockPos())
                || target2 != null && target2.blockPos.equals(speedMine.getRebreakBlockPos()))) return;

        boolean bothInProgress = speedMine.hasDelayedDestroy() && speedMine.hasRebreakBlock()
                && !speedMine.canRebreakRebreakBlock();
        if (bothInProgress) return;

        Queue<BlockPos> targets = new LinkedList<>();
        if (target1 != null) targets.add(target1.blockPos);
        if (target2 != null) targets.add(target2.blockPos);
        if (!targets.isEmpty() && speedMine.hasDelayedDestroy()) {
            speedMine.silentBreakBlock(targets.remove(), 10);
        }
        if (!targets.isEmpty() && (!speedMine.hasRebreakBlock() || speedMine.canRebreakRebreakBlock())) {
            speedMine.silentBreakBlock(targets.remove(), 10);
        }
    }

    private boolean updateCrawlTargets() {
        if (!targetPlayer.isVisuallyCrawling()) return false;

        List<BlockPos> phasedCells = phasedCrawlCells();
        crawlTargeting = true;
        Set<BlockPos> hitboxCells = crawlHitboxCells();
        crawlPhasedCells = phasedCells.size();
        crawlHitboxCells = hitboxCells.size();
        crawlRebreakDamage = 0.0f;
        List<BlockPos> validCells = new ArrayList<>();
        for (BlockPos pos : phasedCells) {
            BlockState state = mc.level.getBlockState(pos);
            if (canBreak(pos, state) && speedMine.inBreakRange(pos)) validCells.add(pos);
        }

        CrawlPair pair = selectCrawlPair(phasedCells, validCells, hitboxCells);
        target1 = pair == null ? null : cityBlock(pair.first, true);
        target2 = pair == null || pair.second == null ? null : cityBlock(pair.second, false);
        ignorePos = target1 == null ? null : target1.blockPos;
        crawlDecision = pair == null ? "no_valid_target"
                : phasedCells.isEmpty() ? "unphased_damage_pair"
                : pair.second == null ? "phased_delayed_only" : "phased_yaw_damage_pair";
        return true;
    }

    private void requestCrawlTargets() {
        if (target1 == null) return;

        BlockPos delayedTarget = target1.blockPos;
        BlockPos rebreakTarget = target2 == null ? null : target2.blockPos;
        BlockPos delayedPos = speedMine.getDelayedDestroyBlockPos();
        BlockPos rebreakPos = speedMine.getRebreakBlockPos();
        if (delayedPos != null && !delayedPos.equals(delayedTarget)) return;
        if (rebreakPos != null && (rebreakTarget == null || !rebreakPos.equals(rebreakTarget))) {
            speedMine.clearRebreak();
        }

        speedMine.silentBreakBlock(delayedTarget, 100);
        if (rebreakTarget != null && delayedTarget.equals(speedMine.getDelayedDestroyBlockPos())) {
            speedMine.silentBreakBlock(rebreakTarget, 100);
        }
    }

    private List<BlockPos> phasedCrawlCells() {
        AABB playerBox = targetPlayer.getBoundingBox().deflate(1.0E-4);
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos raw : iterate(playerBox)) {
            BlockPos pos = raw.immutable();
            BlockState state = mc.level.getBlockState(pos);
            if (state.getCollisionShape(mc.level, pos).toAabbs().stream()
                    .map(shape -> shape.move(pos.getX(), pos.getY(), pos.getZ()))
                    .anyMatch(shape -> shape.intersects(playerBox))) {
                cells.add(pos);
            }
        }
        return cells;
    }

    private CrawlPair selectCrawlPair(List<BlockPos> phasedCells, List<BlockPos> validCells,
                                      Set<BlockPos> hitboxCells) {
        double yaw = Math.toRadians(targetPlayer.getYRot());
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        if (phasedCells.isEmpty()) return selectUnphasedPair(hitboxCells, lookX, lookZ);

        Set<BlockPos> validSet = new HashSet<>(validCells);
        CrawlPair best = null;
        double bestYaw = -Double.MAX_VALUE;
        float bestDamage = -1.0f;
        for (BlockPos delayedCandidate : validCells) {
            double yawScore = facingScore(delayedCandidate, lookX, lookZ);
            BlockPos rebreakCandidate = null;
            float rebreakDamage = -1.0f;

            for (Direction direction : HORIZONTAL) {
                BlockPos adjacent = delayedCandidate.relative(direction);
                if (phasedCells.size() > 1) {
                    if (!validSet.contains(adjacent) || !validCrystalMine(adjacent)) continue;
                } else {
                    if (hitboxCells.contains(adjacent) || !validCrystalMine(adjacent)) continue;
                }

                float damage = crawlCrystalDamage(adjacent);
                if (damage <= 1.0f || damage <= rebreakDamage) continue;
                rebreakDamage = damage;
                rebreakCandidate = adjacent;
            }

            float candidateDamage = Math.max(rebreakDamage, 0.0f);
            if (yawScore > bestYaw + 1.0E-6
                    || Math.abs(yawScore - bestYaw) <= 1.0E-6 && candidateDamage > bestDamage) {
                bestYaw = yawScore;
                bestDamage = candidateDamage;
                best = new CrawlPair(delayedCandidate, rebreakCandidate);
            }
        }

        crawlRebreakDamage = Math.max(bestDamage, 0.0f);
        return best;
    }

    private Set<BlockPos> crawlHitboxCells() {
        AABB box = targetPlayer.getBoundingBox().deflate(1.0E-4);
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos pos : iterate(box)) cells.add(pos.immutable());
        return cells;
    }

    private CrawlPair selectUnphasedPair(Set<BlockPos> hitboxCells, double lookX, double lookZ) {
        if (hitboxCells.isEmpty()) return null;

        int minX = hitboxCells.stream().mapToInt(BlockPos::getX).min().orElse(0) - 2;
        int maxX = hitboxCells.stream().mapToInt(BlockPos::getX).max().orElse(0) + 2;
        int minZ = hitboxCells.stream().mapToInt(BlockPos::getZ).min().orElse(0) - 2;
        int maxZ = hitboxCells.stream().mapToInt(BlockPos::getZ).max().orElse(0) + 2;
        int y = Mth.floor(targetPlayer.getBoundingBox().minY);

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos candidate = new BlockPos(x, y, z);
                if (!hitboxCells.contains(candidate) && validCrystalMine(candidate)) candidates.add(candidate);
            }
        }

        CrawlPair best = null;
        float bestCombinedDamage = -1.0f;
        double bestYaw = -Double.MAX_VALUE;
        for (int firstIndex = 0; firstIndex < candidates.size(); firstIndex++) {
            BlockPos first = candidates.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < candidates.size(); secondIndex++) {
                BlockPos second = candidates.get(secondIndex);
                if (!cardinallyAdjacent(first, second)) continue;

                float firstDamage = crawlCrystalDamage(first);
                float secondDamage = crawlCrystalDamage(second);
                if (firstDamage <= 1.0f || secondDamage <= 1.0f) continue;

                BlockPos delayed = facingScore(first, lookX, lookZ) >= facingScore(second, lookX, lookZ)
                        ? first : second;
                BlockPos rebreak = delayed.equals(first) ? second : first;
                float combinedDamage = firstDamage + secondDamage;
                double yawScore = facingScore(delayed, lookX, lookZ);
                if (combinedDamage > bestCombinedDamage + 1.0E-4f
                        || Math.abs(combinedDamage - bestCombinedDamage) <= 1.0E-4f && yawScore > bestYaw) {
                    bestCombinedDamage = combinedDamage;
                    bestYaw = yawScore;
                    crawlRebreakDamage = rebreak.equals(first) ? firstDamage : secondDamage;
                    best = new CrawlPair(delayed, rebreak);
                }
            }
        }
        return best;
    }

    private boolean validCrystalMine(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        BlockState base = mc.level.getBlockState(pos.below());
        return canBreak(pos, state) && speedMine.inBreakRange(pos)
                && (base.is(Blocks.OBSIDIAN) || base.is(Blocks.BEDROCK))
                && crystalIntersectsTarget(pos);
    }

    private static boolean cardinallyAdjacent(BlockPos first, BlockPos second) {
        return first.getY() == second.getY()
                && Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ()) == 1;
    }

    private double facingScore(BlockPos pos, double lookX, double lookZ) {
        Vec3 offset = Vec3.atCenterOf(pos).subtract(targetPlayer.position());
        return offset.x * lookX + offset.z * lookZ;
    }

    private boolean crystalIntersectsTarget(BlockPos airPos) {
        double centerX = airPos.getX() + 0.5;
        double centerZ = airPos.getZ() + 0.5;
        AABB crystalBox = new AABB(centerX - 1.0, airPos.getY(), centerZ - 1.0,
                centerX + 1.0, airPos.getY() + 2.0, centerZ + 1.0);
        return crystalBox.intersects(targetPlayer.getBoundingBox());
    }

    private float crawlCrystalDamage(BlockPos airPos) {
        Vec3 explosionPos = new Vec3(airPos.getX() + 0.5, airPos.getY(), airPos.getZ() + 0.5);
        return DamageUtil.crystalDamage(targetPlayer, explosionPos, airPos);
    }

    private Player selectTarget() {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        Player best = null;
        double bestMetric = Double.MAX_VALUE;
        AABB area = mc.player.getBoundingBox().inflate(range.getValue());
        for (Entity entity : mc.level.getEntities(mc.player, area)) {
            if (!(entity instanceof Player player)) continue;
            if (!player.isAlive() || player.isDeadOrDying() || player.isCreative() || player.isSpectator()) continue;
            if (targets != null && !targets.isValidTarget(player)) continue;
            if (player.position().distanceTo(mc.player.getEyePosition()) > range.getValue()) continue;
            if (ignoreNakeds.getValue() && isNaked(player)) continue;

            double metric = targetPriority.getValue() == SortPriority.Distance
                    ? mc.player.distanceToSqr(player) : angleTo(player);
            if (metric < bestMetric) {
                bestMetric = metric;
                best = player;
            }
        }
        return best;
    }

    private static CityBlock cityBlock(BlockPos pos, boolean feet) {
        CityBlock block = new CityBlock();
        block.blockPos = pos;
        block.isFeetBlock = feet;
        return block;
    }

    private boolean tryPlaceFurthestFace(AutoCrystalModule autoCrystal, BlockPos minedPos) {
        if (minedPos == null || System.currentTimeMillis() - lastOuterPlaceTime < 75L) return false;

        BlockPos best = null;
        double bestDistance = -1.0;
        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = minedPos.relative(direction);
            if (!canPlaceCrystalAt(candidate)) continue;
            double distance = targetPlayer.distanceToSqr(Vec3.atCenterOf(candidate));
            if (distance > bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return placeOuter(autoCrystal, best);
    }

    private boolean tryPlaceOpenRebreakFace(AutoCrystalModule autoCrystal, BlockPos minedPos) {
        if (minedPos == null || System.currentTimeMillis() - lastOuterPlaceTime < 75L) return false;

        Set<BlockPos> targetFeet = new HashSet<>();
        for (BlockPos pos : iterate(targetFeetBox())) targetFeet.add(pos.immutable());

        BlockPos openFace = null;
        double bestDistance = -1.0;
        for (Direction direction : HORIZONTAL) {
            BlockPos face = minedPos.relative(direction);
            if (targetFeet.contains(face)) continue;
            if (mc.level.getBlockState(face).is(Blocks.OBSIDIAN)) return false;
            if (!canPlaceCrystalAt(face)) continue;

            double distance = targetPlayer.distanceToSqr(Vec3.atCenterOf(face));
            if (distance > bestDistance) {
                bestDistance = distance;
                openFace = face;
            }
        }
        return placeOuter(autoCrystal, openFace);
    }

    private boolean canPlaceCrystalAt(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        BlockState base = mc.level.getBlockState(pos.below());
        return (state.isAir() || state.canBeReplaced())
                && (base.is(Blocks.OBSIDIAN) || base.is(Blocks.BEDROCK));
    }

    private boolean placeOuter(AutoCrystalModule autoCrystal, BlockPos pos) {
        if (pos == null || !autoCrystal.preplaceCrystal(pos, antiSurroundOuterSnap.getValue())) return false;
        lastOuterPlaceTime = System.currentTimeMillis();
        return true;
    }

    private void handleGlassPush() {
        if (!glassPush.getValue()) {
            resetGlass();
            return;
        }

        AutoCrystalModule autoCrystal = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
        if (autoCrystal == null || !autoCrystal.isEnabled() || !speedMine.canRebreakRebreakBlock()) {
            resetGlass();
            return;
        }

        BlockPos pos = speedMine.getRebreakBlockPos();
        if (pos == null) {
            resetGlass();
            return;
        }

        AABB feetBox = targetFeetBox();
        boolean bedrock = BlockPos.betweenClosedStream(feetBox)
                .anyMatch(blockPos -> mc.level.getBlockState(blockPos).is(Blocks.BEDROCK));
        if (!isGoodRebreak(pos, bedrock, feetBox) || !autoCrystal.isDesirablePlacement(pos)) {
            resetGlass();
            return;
        }

        if (!pos.equals(glassTargetPos)) {
            glassTargetPos = pos.immutable();
            glassUsedAttempts = 0;
        }
        if (!itemInCrystalSpot(pos)) {
            glassUsedAttempts = 0;
            return;
        }
        if (glassUsedAttempts >= glassAttempts.getValue()) return;
        if (!mc.level.getBlockState(pos).isAir()) return;

        if (placeGlass(pos)) {
            glassUsedAttempts++;
            speedMine.holdRebreak(pos, 5);
            glassRenderPos = pos.immutable();
            glassRenderStart = System.currentTimeMillis();
        }
    }

    private boolean itemInCrystalSpot(BlockPos airPos) {
        AABB box = new AABB(airPos).expandTowards(0, 1, 0);
        for (Entity entity : mc.level.getEntities((Entity) null, box)) {
            if (entity instanceof ItemEntity) return true;
        }
        return false;
    }

    private boolean placeGlass(BlockPos pos) {
        Result glass = InventoryUtil.find(Items.GLASS, InventoryUtil.PLACE_SCOPE);
        if (!glass.found() || !PlaceUtil.canPlace(pos)) return false;
        if (!Homovore.placementManager.enqueue(pos, glass.slot())) return false;
        Homovore.placementManager.flushQueue();
        return true;
    }

    private void resetGlass() {
        glassTargetPos = null;
        glassUsedAttempts = 0;
    }

    private void renderGlass(Render3DEvent event) {
        if (!glassRender.getValue() || glassRenderPos == null) return;
        long age = System.currentTimeMillis() - glassRenderStart;
        double fadeMs = glassFadeTime.getValue() * 1000.0;
        if (age > fadeMs) {
            glassRenderPos = null;
            return;
        }

        double progress = age / fadeMs;
        Color fill = glassFillColor.getValue();
        Color outline = glassOutlineColor.getValue();
        RenderUtil.drawBoxFilled(event.getMatrix(), glassRenderPos,
                fadeColor(fill, progress));
        RenderUtil.drawBox(event.getMatrix(), glassRenderPos,
                fadeColor(outline, progress), 1.5f);
    }

    private static Color fadeColor(Color color, double progress) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * (1.0 - progress)));
    }

    private double angleTo(Player player) {
        float[] angle = MathUtil.calcAngle(mc.player.getEyePosition(), player.getEyePosition());
        double yaw = MathUtil.wrapDegrees(angle[0] - mc.player.getYRot());
        double pitch = angle[1] - mc.player.getXRot();
        return Math.sqrt(yaw * yaw + pitch * pitch);
    }

    private boolean isNaked(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private void findTargetBlocks() {
        target1 = findCityBlock(null);
        ignorePos = target1 == null ? null : target1.blockPos;
        target2 = findCityBlock(target1 == null ? null : target1.blockPos);
    }

    private CityBlock findCityBlock(BlockPos exclude) {
        if (targetPlayer == null) return null;
        AABB feetBox = targetFeetBox();
        boolean bedrock = BlockPos.betweenClosedStream(feetBox)
                .anyMatch(pos -> mc.level.getBlockState(pos).is(Blocks.BEDROCK));
        Set<CheckPos> candidates = new HashSet<>();
        if (bedrock) addBedrockCaseCheckPositions(candidates);
        else addNormalCaseCheckPositions(candidates);

        CityBlock best = new CityBlock();
        boolean found = false;
        for (CheckPos candidate : candidates) {
            BlockPos pos = candidate.blockPos;
            if (pos.equals(exclude)) continue;
            BlockState state = mc.level.getBlockState(pos);
            boolean goodRebreak = isGoodRebreak(pos, bedrock, feetBox);
            if (state.isAir() && !goodRebreak) continue;
            if (!canBreak(pos, state) && !goodRebreak) continue;
            if (!speedMine.inBreakRange(pos)) continue;

            double score = bedrock ? scoreBedrockCityBlock(candidate, feetBox)
                    : scoreNormalCityBlock(candidate);
            if (score == INVALID_SCORE) continue;
            if (goodRebreak) score += 40;
            if (score > best.score) {
                best.score = score;
                best.blockPos = pos;
                best.isFeetBlock = isBlockInFeet(pos);
                found = true;
            }
        }
        return found ? best : null;
    }

    private boolean isGoodRebreak(BlockPos pos, boolean bedrock, AABB feetBox) {
        if (!speedMine.canRebreakRebreakBlock() || !pos.equals(speedMine.getRebreakBlockPos())) return false;
        if (bedrock) {
            boolean selfTrap = false;
            for (Direction direction : HORIZONTAL) {
                if (targetPlayer.blockPosition().above().relative(direction).equals(pos)) {
                    selfTrap = true;
                    break;
                }
            }
            boolean canFacePlace = mc.level.getBlockState(targetPlayer.blockPosition().above()).isAir();
            return BlockPos.betweenClosedStream(feetBox).count() == 1
                    && (pos.equals(targetPlayer.blockPosition().above(2)) || selfTrap && canFacePlace);
        }
        if (pos.equals(targetPlayer.blockPosition()) || isBlockInFeet(pos)) return false;
        for (Direction direction : HORIZONTAL) {
            BlockPos surround = targetPlayer.blockPosition().relative(direction);
            if (surround.equals(pos) && isCrystalBlock(surround.below())) return true;
        }
        return false;
    }

    private void addNormalCaseCheckPositions(Set<CheckPos> candidates) {
        AABB feetBox = targetFeetBox();
        for (BlockPos pos : iterate(feetBox)) candidates.add(new CheckPos(pos.immutable(), CheckPosType.Feet));
        for (BlockPos pos : iterate(feetBox)) {
            for (Direction direction : HORIZONTAL) {
                candidates.add(new CheckPos(pos.relative(direction), CheckPosType.Surround));
            }
        }
        candidates.add(new CheckPos(targetPlayer.blockPosition(), CheckPosType.Feet));

        if (BlockPos.betweenClosedStream(feetBox).count() == 1) {
            for (Direction direction : HORIZONTAL) {
                switch (extendBreakMode.getValue()) {
                    case None -> { }
                    case Long -> candidates.add(new CheckPos(
                            targetPlayer.blockPosition().relative(direction, 2), CheckPosType.Extend));
                    case Corner -> candidates.add(new CheckPos(targetPlayer.blockPosition()
                            .relative(direction).relative(getCornerPerpDir(direction)), CheckPosType.Extend));
                }
            }
        }
    }

    private void addBedrockCaseCheckPositions(Set<CheckPos> candidates) {
        AABB feetBox = targetFeetBox();
        boolean canFall = BlockPos.betweenClosedStream(feetBox)
                .allMatch(pos -> !mc.level.getBlockState(pos.below()).is(Blocks.BEDROCK));
        boolean canRise = BlockPos.betweenClosedStream(feetBox)
                .allMatch(pos -> !mc.level.getBlockState(pos.above(2)).is(Blocks.BEDROCK));

        for (BlockPos raw : iterate(feetBox)) {
            BlockPos pos = raw.immutable();
            if (canFall) candidates.add(new CheckPos(pos.below(), CheckPosType.Below));
            if (canRise) candidates.add(new CheckPos(pos.above(2), CheckPosType.Head));
            candidates.add(new CheckPos(pos.above(), CheckPosType.FacePlace));
            for (Direction direction : HORIZONTAL) {
                candidates.add(new CheckPos(pos.above().relative(direction), CheckPosType.FacePlace));
            }
            candidates.add(new CheckPos(pos, CheckPosType.Surround));
            for (Direction direction : HORIZONTAL) {
                candidates.add(new CheckPos(pos.relative(direction), CheckPosType.Surround));
            }
        }
    }

    private double scoreNormalCityBlock(CheckPos candidate) {
        BlockPos pos = candidate.blockPos;
        BlockState state = mc.level.getBlockState(pos);
        double score = 0;
        if (pos.equals(targetPlayer.blockPosition())) {
            if (mc.level.getBlockState(pos.above()).is(Blocks.OBSIDIAN)) score += 100;
            else {
                if (state.is(Blocks.COBWEB)) return INVALID_SCORE;
                score += 50;
            }
        } else {
            BlockState selfHead = mc.level.getBlockState(mc.player.blockPosition().above());
            if (pos.equals(mc.player.blockPosition())
                    && (selfHead.is(Blocks.OBSIDIAN) || selfHead.is(Blocks.BEDROCK))) return INVALID_SCORE;
            if (candidate.type == CheckPosType.Surround) {
                score += 3;
                for (Direction direction : HORIZONTAL) {
                    if (!targetPlayer.blockPosition().relative(direction).equals(pos)) continue;
                    BlockPos straight = targetPlayer.blockPosition().relative(direction, 2);
                    BlockPos corner = targetPlayer.blockPosition().relative(direction)
                            .relative(getCornerPerpDir(direction));
                    if (getBlockStateIgnore(straight).isAir() && isCrystalBlock(straight.below())
                            || getBlockStateIgnore(corner).isAir() && isCrystalBlock(corner.below())) {
                        score += 25;
                        break;
                    }
                }
            }
            if (candidate.type == CheckPosType.Extend) score += 20;
        }
        return score + 10 / targetPlayer.position().distanceTo(Vec3.atCenterOf(pos));
    }

    private double scoreBedrockCityBlock(CheckPos candidate, AABB feetBox) {
        BlockPos pos = candidate.blockPos;
        double score = 0;
        if (pos.getY() == targetPlayer.getBlockY() + 2 || pos.getY() == targetPlayer.getBlockY() - 1) {
            score += 10;
        }
        if (BlockPos.betweenClosedStream(feetBox).count() == 1
                && !mc.level.getBlockState(targetPlayer.blockPosition().above()).is(Blocks.BEDROCK)) {
            if (pos.equals(targetPlayer.blockPosition().above())) score += 20;
            else {
                for (Direction direction : HORIZONTAL) {
                    if (targetPlayer.blockPosition().above().relative(direction).equals(pos)) {
                        score += 7.5;
                        break;
                    }
                }
            }
        }
        return score + 10 / targetPlayer.position().distanceTo(Vec3.atCenterOf(pos));
    }

    private AABB targetFeetBox() {
        AABB bounds = targetPlayer.getBoundingBox().deflate(0.01, 0.1, 0.01);
        double feetY = targetPlayer.getY();
        return new AABB(bounds.minX, feetY, bounds.minZ, bounds.maxX, feetY + 0.1, bounds.maxZ);
    }

    private boolean isBlockInFeet(BlockPos blockPos) {
        for (BlockPos pos : iterate(targetFeetBox())) if (blockPos.equals(pos)) return true;
        return false;
    }

    private boolean isCrystalBlock(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private BlockState getBlockStateIgnore(BlockPos pos) {
        if (pos == null || pos.equals(ignorePos)) return Blocks.AIR.defaultBlockState();
        return mc.level.getBlockState(pos);
    }

    private Direction getCornerPerpDir(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.NORTH;
        };
    }

    private boolean canBreak(BlockPos pos, BlockState state) {
        if (!InteractionUtil.canBreak(pos, state)) return false;
        return !isOwnPistonCrystalBlock(pos);
    }

    private boolean isOwnPistonCrystalBlock(BlockPos pos) {
        if (!avoidPistonCrystal.getValue()) return false;

        PistonCrystalModule pistonCrystal = Homovore.moduleManager.getModuleByClass(PistonCrystalModule.class);
        return pistonCrystal != null && pistonCrystal.isOwnPistonBlock(pos);
    }

    private static Iterable<BlockPos> iterate(AABB box) {
        return BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ));
    }

    public boolean isTargetedPos(BlockPos pos) {
        return target1 != null && target1.blockPos.equals(pos)
                || target2 != null && target2.blockPos.equals(pos);
    }

    public boolean isTargetingAnything() {
        return target1 != null || target2 != null;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        update();
        logDebugState();
        renderGlass(event);
        if (!renderDebugScores.getValue() || targetPlayer == null || speedMine == null) return;

        if (crawlTargeting) {
            if (target1 != null) {
                RenderUtil.drawBoxFilled(event.getMatrix(), target1.blockPos, new Color(255, 0, 0, 110));
                RenderUtil.drawBox(event.getMatrix(), target1.blockPos, new Color(255, 255, 255, 220), 1.5f);
            }
            if (target2 != null) {
                RenderUtil.drawBoxFilled(event.getMatrix(), target2.blockPos, new Color(255, 0, 0, 70));
                RenderUtil.drawBox(event.getMatrix(), target2.blockPos, new Color(255, 255, 255, 180), 1.0f);
            }
            return;
        }

        AABB feetBox = targetFeetBox();
        boolean bedrock = BlockPos.betweenClosedStream(feetBox)
                .anyMatch(pos -> mc.level.getBlockState(pos).is(Blocks.BEDROCK));
        Set<CheckPos> candidates = new HashSet<>();
        if (bedrock) addBedrockCaseCheckPositions(candidates);
        else addNormalCaseCheckPositions(candidates);

        double bestScore = 0;
        for (CheckPos candidate : candidates) {
            CityBlock scored = scoreDebug(candidate, bedrock, feetBox);
            if (scored != null) bestScore = Math.max(bestScore, scored.score);
        }
        if (bestScore <= 0) return;
        for (CheckPos candidate : candidates) {
            CityBlock scored = scoreDebug(candidate, bedrock, feetBox);
            if (scored == null) continue;
            int alpha = (int) (255.0 * (scored.score / bestScore) / 4.0);
            RenderUtil.drawBoxFilled(event.getMatrix(), candidate.blockPos,
                    new Color(255, 0, 0, Mth.clamp(alpha, 0, 255)));
        }
    }

    private void logDebugState() {
        if (!debugLog.getValue() || mc.player.tickCount == lastDebugTick) return;
        lastDebugTick = mc.player.tickCount;

        if (targetPlayer == null) {
            Homovore.LOGGER.info("[AutoMine] tick={} target=none decision=no_target active={}",
                    mc.player.tickCount, speedMine == null ? "unavailable" : speedMine.getDebugState());
            return;
        }

        Homovore.LOGGER.info(
                "[AutoMine] tick={} target={} pos={} yaw={} pose={} crawl={} crawlMode={} "
                        + "phase={}/{} decision={} selected[delayed={},rebreak={}] rebreakDamage={} active={}",
                mc.player.tickCount, targetPlayer.getName().getString(), targetPlayer.position(),
                String.format(java.util.Locale.ROOT, "%.1f", targetPlayer.getYRot()), targetPlayer.getPose(),
                targetPlayer.isVisuallyCrawling(), crawlTargeting, crawlPhasedCells, crawlHitboxCells,
                crawlDecision, target1 == null ? "none" : target1.blockPos,
                target2 == null ? "none" : target2.blockPos,
                String.format(java.util.Locale.ROOT, "%.2f", crawlRebreakDamage),
                speedMine == null ? "unavailable" : speedMine.getDebugState());
    }

    private CityBlock scoreDebug(CheckPos candidate, boolean bedrock, AABB feetBox) {
        BlockState state = mc.level.getBlockState(candidate.blockPos);
        boolean goodRebreak = isGoodRebreak(candidate.blockPos, bedrock, feetBox);
        if (state.isAir() && !goodRebreak) return null;
        if (!canBreak(candidate.blockPos, state) && !goodRebreak) return null;
        if (!speedMine.inBreakRange(candidate.blockPos)) return null;
        double score = bedrock ? scoreBedrockCityBlock(candidate, feetBox) : scoreNormalCityBlock(candidate);
        if (score == INVALID_SCORE) return null;
        CityBlock result = new CityBlock();
        result.score = score + (goodRebreak ? 40 : 0);
        return result;
    }

    @Override
    public String getDisplayInfo() {
        return targetPlayer == null ? null : targetPlayer.getName().getString();
    }

    private static final class CityBlock {
        private BlockPos blockPos;
        private double score;
        private boolean isFeetBlock;
    }

    private record CheckPos(BlockPos blockPos, CheckPosType type) {
        @Override
        public int hashCode() {
            return blockPos.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof CheckPos other && blockPos.equals(other.blockPos);
        }
    }

    private record CrawlPair(BlockPos first, BlockPos second) {
    }

    private enum CheckPosType { Feet, Surround, Extend, FacePlace, Head, Below }
    private enum AntiSurroundMode { None, Inner, Outer, Auto }
    private enum ExtendBreakMode { None, Long, Corner }
    private enum SortPriority { Distance, Angle }
}
