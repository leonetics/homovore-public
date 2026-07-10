package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.modules.world.SpeedMineModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.InteractionUtil;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
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
import net.minecraft.world.phys.shapes.Shapes;

import java.awt.Color;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class AutoMineModule extends Module {

    private static final double INVALID_SCORE = -1000;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private final Setting<Double>  range = num("Range", 6.5, 0.0, 7.0);
    private final Setting<SortPriority> targetPriority = mode("Priority", SortPriority.Angle);
    private final Setting<Boolean> ignoreNakeds = bool("IgnoreNakeds", true);
    private final Setting<ExtendBreakMode> extendBreakMode = mode("Extend", ExtendBreakMode.Long);
    private final Setting<Boolean> underMine = bool("UnderMine", true);
    private final Setting<Boolean> crawlMine = bool("CrawlMine", false);
    private final Setting<AntiSwimMode> antiSwim = mode("AntiSwim", AntiSwimMode.None);
    private final Setting<AntiSurroundMode> antiSurroundMode = mode("AntiSurround", AntiSurroundMode.Auto);
    private final Setting<Boolean> antiSurroundInnerSnap = bool("InnerSnap", true);
    private final Setting<Boolean> antiSurroundOuterSnap = bool("OuterSnap", true);
    private final Setting<Double>  antiSurroundOuterCooldown = num("OuterCooldown", 0.1, 0.0, 1.0);

    private final Setting<Boolean> glassPush     = bool("GlassPush", false);
    private final Setting<Integer> glassAttempts = num("GlassAttempts", 2, 1, 5);

    private final Setting<Boolean> glassRender = bool("GlassRender", true).setPage("Render");
    private final Setting<Float> glassFadeTime = num("GlassFadeTime", 0.5f, 0.05f, 2.0f).setPage("Render")
            .setVisibility(v -> glassRender.getValue());
    private final Setting<Color> glassFillColor = color("GlassFillColor", 255, 255, 255, 50).setPage("Render")
            .setVisibility(v -> glassRender.getValue());
    private final Setting<Color> glassOutlineColor = color("GlassOutlineColor", 255, 255, 255, 255).setPage("Render")
            .setVisibility(v -> glassRender.getValue());
    private final Setting<Boolean> renderDebugScores = bool("DebugScores", false).setPage("Render");

    private Player targetPlayer = null;
    private CityBlock target1 = null;
    private CityBlock target2 = null;
    private BlockPos ignorePos = null;
    private long lastOuterPlaceTime = 0;

    private boolean isTerrainFight = false;

    private BlockPos glassTargetPos = null;
    private int glassUsedAttempts = 0;
    private BlockPos glassRenderPos = null;
    private long glassRenderStart = 0L;

    private final Long2LongOpenHashMap enemyBreaking = new Long2LongOpenHashMap();
    private static final long ENEMY_BREAK_TTL_MS = 1000;

    private final SpeedMineModule.MineFinishListener finishListener = this::onMineFinish;

    public AutoMineModule() {
        super("AutoMine", "Automatically mines blocks around a target. Requires SpeedMine.", Category.COMBAT);
        antiSurroundInnerSnap.setVisibility(v -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Inner);
        antiSurroundOuterSnap.setVisibility(v -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Outer);
        antiSurroundOuterCooldown.setVisibility(v -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Outer);
        glassAttempts.setVisibility(v -> glassPush.getValue());
    }

    @Override
    public void onEnable() {
        SpeedMineModule mine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (mine != null) mine.addFinishListener(finishListener);
        enemyBreaking.clear();
        lastOuterPlaceTime = 0;
    }

    @Override
    public void onDisable() {
        SpeedMineModule mine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (mine != null) mine.removeFinishListener(finishListener);
        targetPlayer = null;
        target1 = target2 = null;
        ignorePos = null;
        enemyBreaking.clear();
        resetGlass();
        glassRenderPos = null;
    }

    @Subscribe
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundBlockDestructionPacket pkt)) return;

        if (mc.player != null && pkt.getId() == mc.player.getId()) return;
        int progress = pkt.getProgress();
        if (progress >= 0 && progress <= 9) {
            enemyBreaking.put(pkt.getPos().asLong(), System.currentTimeMillis());
        } else {
            enemyBreaking.remove(pkt.getPos().asLong());
        }
    }

    private boolean isBlockBeingBroken(BlockPos pos) {
        long ts = enemyBreaking.get(pos.asLong());
        return ts != 0L && System.currentTimeMillis() - ts < ENEMY_BREAK_TTL_MS;
    }

    private void onMineFinish(BlockPos pos) {

        if (nullCheck() || targetPlayer == null) return;

        AntiSurroundMode swMode = antiSurroundMode.getValue();
        if (swMode == AntiSurroundMode.None) return;

        AutoCrystalModule crystal = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
        if (crystal == null || !crystal.isEnabled()) return;

        if (swMode == AntiSurroundMode.Auto || swMode == AntiSurroundMode.Outer) {
            for (Direction dir : HORIZONTAL) {
                BlockPos playerSurroundBlock = targetPlayer.blockPosition().relative(dir);
                if (!pos.equals(playerSurroundBlock)) continue;

                boolean outerReady = (System.currentTimeMillis() - lastOuterPlaceTime)
                        > (antiSurroundOuterCooldown.getValue() * 1000.0);
                if (!outerReady) return;

                AABB checkBox = AABB.ofSize(Vec3.atCenterOf(playerSurroundBlock), 2.5, 3.0, 2.5);
                AABB blockHitbox = new AABB(playerSurroundBlock);

                for (BlockPos bp : iterate(checkBox)) {
                    if (!mc.level.getBlockState(bp).isAir()) continue;
                    BlockState downState = mc.level.getBlockState(bp.below());
                    if (!downState.is(Blocks.OBSIDIAN) && !downState.is(Blocks.BEDROCK)) continue;

                    AABB crystalPlaceHitbox = new AABB(bp.getX(), bp.getY(), bp.getZ(),
                            bp.getX() + 1, bp.getY() + 2, bp.getZ() + 1);
                    if (intersectsEntity(crystalPlaceHitbox, e -> !e.isSpectator())) continue;

                    Vec3 crystalPos = new Vec3(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
                    AABB crystalHitbox = new AABB(crystalPos.x - 1, crystalPos.y, crystalPos.z - 1,
                            crystalPos.x + 1, crystalPos.y + 2, crystalPos.z + 1);

                    if (crystalHitbox.intersects(blockHitbox)) {

                        if (crystal.preplaceCrystal(bp.immutable(), antiSurroundOuterSnap.getValue())) {
                            lastOuterPlaceTime = System.currentTimeMillis();
                        }
                        return;
                    }
                }
            }
        }

        if (swMode == AntiSurroundMode.Auto || swMode == AntiSurroundMode.Inner) {
            for (Direction dir : HORIZONTAL) {
                BlockPos playerSurroundBlock = targetPlayer.blockPosition().relative(dir);
                if (playerSurroundBlock.equals(pos)) {
                    crystal.preplaceCrystal(playerSurroundBlock, antiSurroundInnerSnap.getValue());
                }
            }
        }
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        update();
    }

    private void update() {
        SpeedMineModule mine = speedMine();
        if (mine == null) return;

        if (tryCrawlMine(mine)) return;

        BlockState selfFeetBlock = mc.level.getBlockState(mc.player.blockPosition());
        BlockState selfHeadBlock = mc.level.getBlockState(mc.player.blockPosition().above());
        BlockPos selfHeadPos = mc.player.blockPosition().above();
        boolean shouldBreakSelfHeadBlock = canBreak(selfHeadPos, selfHeadBlock)
                && (selfHeadBlock.is(Blocks.OBSIDIAN) || selfHeadBlock.is(Blocks.CRYING_OBSIDIAN));

        boolean prioHead = false;

        if (antiSwim.getValue() == AntiSwimMode.Always && shouldBreakSelfHeadBlock) {
            mine.silentBreakBlock(selfHeadPos, 10);
            prioHead = true;
        }
        if (antiSwim.getValue() == AntiSwimMode.MineOrSwim && mc.player.isVisuallyCrawling()
                && shouldBreakSelfHeadBlock) {
            mine.silentBreakBlock(selfHeadPos, 30);
            prioHead = true;
        }
        if ((antiSwim.getValue() == AntiSwimMode.Mine || antiSwim.getValue() == AntiSwimMode.MineOrSwim)
                && isBlockBeingBroken(mc.player.blockPosition()) && shouldBreakSelfHeadBlock) {
            mine.silentBreakBlock(selfHeadPos, 20);
            prioHead = true;
        }

        targetPlayer = selectTarget();
        if (targetPlayer == null) return;

        handleGlassPush(mine);

        if (mine.hasDelayedDestroy() && selfHeadBlock.is(Blocks.OBSIDIAN) && selfFeetBlock.isAir()
                && selfHeadPos.equals(mine.getRebreakBlockPos())) {
            return;
        }

        if (prioHead) return;

        findTargetBlocks();

        boolean isTargetingFeetBlock = (target1 != null && target1.isFeetBlock)
                || (target2 != null && target2.isFeetBlock);

        if (!isTargetingFeetBlock && mine.canRebreakRebreakBlock()
                && ((target1 != null && target1.blockPos.equals(mine.getRebreakBlockPos()))
                || (target2 != null && target2.blockPos.equals(mine.getRebreakBlockPos())))) {
            return;
        }

        boolean hasBothInProgress = mine.hasDelayedDestroy() && mine.hasRebreakBlock()
                && !mine.canRebreakRebreakBlock();
        if (hasBothInProgress && !mine.hasFailingBlock()) return;

        Queue<BlockPos> targetBlocks = new LinkedList<>();
        if (target1 != null) targetBlocks.add(target1.blockPos);
        if (target2 != null) targetBlocks.add(target2.blockPos);

        while (!targetBlocks.isEmpty() && mine.alreadyBreaking(targetBlocks.peek())) {
            targetBlocks.remove();
        }
        if (!targetBlocks.isEmpty()) {
            mine.silentBreakBlock(targetBlocks.remove(), 10);
        }
    }

    private boolean tryCrawlMine(SpeedMineModule mine) {
        if (!crawlMine.getValue()) return false;
        if (mc.player.isCrouching()) return false;

        BlockPos feetPos = mc.player.blockPosition();
        BlockPos headPos = feetPos.above();
        BlockState feetBlock = mc.level.getBlockState(feetPos);
        BlockState headBlock = mc.level.getBlockState(headPos);

        if (!isBlocked(feetPos, feetBlock) || !isBlocked(headPos, headBlock)) return false;
        if (!InteractionUtil.canBreak(feetPos, feetBlock)) return false;

        return mine.silentBreakBlock(feetPos, 200);
    }

    private boolean isBlocked(BlockPos pos, BlockState state) {
        return !state.canBeReplaced() && state.getShape(mc.level, pos) != Shapes.empty();
    }

    private Player selectTarget() {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeV = range.getValue();
        Player best = null;
        double bestMetric = Double.MAX_VALUE;

        AABB area = mc.player.getBoundingBox().inflate(rangeV);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof Player player)) continue;
            if (player == mc.player) continue;
            if (!player.isAlive() || player.isDeadOrDying() || player.isSpectator()) continue;

            if (targets != null && !targets.isValidTarget(player)) continue;
            if (player.position().distanceTo(mc.player.getEyePosition()) > rangeV) continue;
            if (ignoreNakeds.getValue() && isNaked(player)) continue;

            double metric = switch (targetPriority.getValue()) {
                case Distance -> mc.player.distanceToSqr(player);
                case Angle    -> angleTo(player);
            };
            if (metric < bestMetric) { bestMetric = metric; best = player; }
        }
        return best;
    }

    private double angleTo(Player player) {
        float[] need = MathUtil.calcAngle(mc.player.getEyePosition(), player.getEyePosition());
        double dYaw = MathUtil.wrapDegrees(need[0] - mc.player.getYRot());
        double dPitch = need[1] - mc.player.getXRot();
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    private boolean isNaked(Player p) {
        return p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && p.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && p.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private void handleGlassPush(SpeedMineModule mine) {
        if (!glassPush.getValue()) { resetGlass(); return; }

        AutoCrystalModule crystal = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
        if (crystal == null || !crystal.isEnabled()) { resetGlass(); return; }

        if (!mine.canRebreakRebreakBlock()) { resetGlass(); return; }
        BlockPos pos = mine.getRebreakBlockPos();
        if (pos == null) { resetGlass(); return; }

        if (!isGoodRebreak(pos, inBedrockCase()) || !crystal.isDesirablePlacement(pos)) {
            resetGlass();
            return;
        }

        if (!pos.equals(glassTargetPos)) {
            glassTargetPos = pos.immutable();
            glassUsedAttempts = 0;
        }

        if (!itemInCrystalSpot(pos)) {

            if (glassUsedAttempts > 0 && mc.level.getBlockState(pos).isAir()) {
                crystal.preplaceCrystal(pos, true);
            }
            glassUsedAttempts = 0;
            return;
        }

        if (glassUsedAttempts >= glassAttempts.getValue()) return;

        if (!mc.level.getBlockState(pos).isAir()) return;

        if (placeGlass(pos)) {
            glassUsedAttempts++;
            mine.holdRebreak(pos, 5);
            glassRenderPos = pos.immutable();
            glassRenderStart = System.currentTimeMillis();
        }
    }

    private boolean itemInCrystalSpot(BlockPos airPos) {
        AABB box = new AABB(airPos).expandTowards(0, 1, 0);
        for (Entity e : mc.level.getEntities((Entity) null, box)) {
            if (e instanceof ItemEntity) return true;
        }
        return false;
    }

    private boolean placeGlass(BlockPos pos) {
        Result glass = InventoryUtil.find(Items.GLASS, InventoryUtil.PLACE_SCOPE);
        if (!glass.found()) return false;
        if (!PlaceUtil.canPlace(pos)) return false;
        if (!Homovore.placementManager.enqueue(pos, glass.slot())) return false;
        Homovore.placementManager.flushQueue();
        return true;
    }

    private void resetGlass() {
        glassTargetPos = null;
        glassUsedAttempts = 0;
    }

    private void findTargetBlocks() {
        target1 = findCityBlock(null);
        ignorePos = target1 != null ? target1.blockPos : null;
        target2 = findCityBlock(target1 != null ? target1.blockPos : null);
    }

    private CityBlock findCityBlock(BlockPos exclude) {
        if (targetPlayer == null) return null;

        boolean inBedrock = inBedrockCase();
        isTerrainFight = !inBedrock && computeTerrainFight();
        Set<CheckPos> checkPositions = new HashSet<>();
        if (inBedrock) addBedrockCaseCheckPositions(checkPositions);
        else addNormalCaseCheckPositions(checkPositions);

        CityBlock bestBlock = new CityBlock();
        boolean set = false;
        for (CheckPos pos : checkPositions) {
            if (pos.blockPos.equals(exclude)) continue;
            double score = evalCheckPos(pos, inBedrock);
            if (score == INVALID_SCORE) continue;
            if (score > bestBlock.score) {
                bestBlock.score = score;
                bestBlock.blockPos = pos.blockPos;
                bestBlock.isFeetBlock = isBlockInFeet(pos.blockPos);
                set = true;
            }
        }
        return set ? bestBlock : null;
    }

    private double evalCheckPos(CheckPos pos, boolean inBedrock) {
        SpeedMineModule mine = speedMine();
        if (mine == null) return INVALID_SCORE;

        BlockPos blockPos = pos.blockPos;
        BlockState block = mc.level.getBlockState(blockPos);
        boolean goodRebreak = isGoodRebreak(blockPos, inBedrock);

        if (block.isAir() && !goodRebreak) return INVALID_SCORE;
        if (!canBreak(blockPos, block) && !goodRebreak) return INVALID_SCORE;
        if (!mine.inBreakRange(blockPos)) return INVALID_SCORE;

        double score = inBedrock ? scoreBedrock(pos) : scoreNormal(pos);
        if (score == INVALID_SCORE) return INVALID_SCORE;
        if (goodRebreak) score += 40;
        return score;
    }

    private boolean isGoodRebreak(BlockPos blockPos, boolean inBedrock) {
        SpeedMineModule mine = speedMine();
        if (mine == null || !mine.canRebreakRebreakBlock()) return false;
        if (!blockPos.equals(mine.getRebreakBlockPos())) return false;

        if (inBedrock) {
            boolean isSelfTrapBlock = false;
            for (Direction dir : HORIZONTAL) {
                if (targetPlayer.blockPosition().above().relative(dir).equals(blockPos)) {
                    isSelfTrapBlock = true;
                    break;
                }
            }
            boolean canFacePlace = mc.level.getBlockState(targetPlayer.blockPosition().above()).isAir();
            return BlockPos.betweenClosedStream(targetFeetBox()).count() == 1
                    && (blockPos.equals(targetPlayer.blockPosition().above(2))
                            || (isSelfTrapBlock && canFacePlace));
        } else {
            if (blockPos.equals(targetPlayer.blockPosition()) || isBlockInFeet(blockPos)) return false;
            for (Direction dir : HORIZONTAL) {
                if (targetPlayer.blockPosition().relative(dir).equals(blockPos)
                        && isCrystalBlock(targetPlayer.blockPosition().relative(dir).below())) {
                    return true;
                }
            }
            return false;
        }
    }

    private void addNormalCaseCheckPositions(Set<CheckPos> checkPos) {
        AABB feetBox = targetFeetBox();
        for (BlockPos raw : iterate(feetBox)) {
            checkPos.add(new CheckPos(raw.immutable(), CheckPosType.Feet));
        }
        for (BlockPos raw : iterate(feetBox)) {
            BlockPos feet = raw.immutable();
            for (Direction dir : HORIZONTAL) {
                BlockPos surround = feet.relative(dir);

                if (isBlockInFeet(surround)) continue;
                checkPos.add(new CheckPos(surround, CheckPosType.Surround));

                if (underMine.getValue()) {
                    BlockPos base = surround.below();
                    if (canBreak(base, mc.level.getBlockState(base))) {
                        checkPos.add(new CheckPos(base, CheckPosType.TerrainBase));
                    }
                }

                switch (extendBreakMode.getValue()) {
                    case None -> { }
                    case Long -> checkPos.add(new CheckPos(surround.relative(dir), CheckPosType.Extend));
                    case Corner -> {
                        Direction perpDir = getCornerPerpDir(dir);
                        if (perpDir != null) {
                            checkPos.add(new CheckPos(surround.relative(perpDir), CheckPosType.Extend));
                        }
                    }
                }
            }
        }
    }

    private boolean computeTerrainFight() {
        AABB feetBox = targetFeetBox();
        for (BlockPos feet : iterate(feetBox)) {
            for (Direction dir : HORIZONTAL) {
                BlockPos neighbor = feet.relative(dir);
                if (isBlockInFeet(neighbor)) continue;
                if (isCrystalBlock(neighbor.below())) return false;
            }
        }
        return true;
    }

    private void addBedrockCaseCheckPositions(Set<CheckPos> checkPos) {
        AABB feetBox = targetFeetBox();

        boolean canFallDown = BlockPos.betweenClosedStream(feetBox)
                .allMatch(bp -> !mc.level.getBlockState(bp.below()).is(Blocks.BEDROCK));
        boolean canBeHitUp = BlockPos.betweenClosedStream(feetBox)
                .allMatch(bp -> !mc.level.getBlockState(bp.above(2)).is(Blocks.BEDROCK));

        for (BlockPos pos : iterate(feetBox)) {
            BlockPos p = pos.immutable();
            if (canFallDown) checkPos.add(new CheckPos(p.below(), CheckPosType.Below));
            if (canBeHitUp)  checkPos.add(new CheckPos(p.above(2), CheckPosType.Head));

            checkPos.add(new CheckPos(p.above(), CheckPosType.FacePlace));
            for (Direction dir : HORIZONTAL) {
                checkPos.add(new CheckPos(p.above().relative(dir), CheckPosType.FacePlace));
            }

            checkPos.add(new CheckPos(p, CheckPosType.Surround));
            for (Direction dir : HORIZONTAL) {
                checkPos.add(new CheckPos(p.relative(dir), CheckPosType.Surround));
            }
        }
    }

    private double scoreNormal(CheckPos pos) {
        BlockPos blockPos = pos.blockPos;
        double score = 0;
        BlockState block = mc.level.getBlockState(blockPos);

        if (pos.type == CheckPosType.Feet) {
            BlockState headBlock = mc.level.getBlockState(blockPos.above());
            if (headBlock.is(Blocks.OBSIDIAN)) {
                score += 100;
            } else {
                if (block.is(Blocks.COBWEB)) return INVALID_SCORE;
                score += 50;
            }
        } else {
            BlockState selfHeadState = mc.level.getBlockState(mc.player.blockPosition().above());

            if (blockPos.equals(mc.player.blockPosition())
                    && (selfHeadState.is(Blocks.OBSIDIAN) || selfHeadState.is(Blocks.BEDROCK))) {
                return INVALID_SCORE;
            }

            if (pos.type == CheckPosType.Surround) {

                BlockState down = mc.level.getBlockState(blockPos.below());
                if (down.isAir()) {
                    score += 35;
                } else if (!down.is(Blocks.OBSIDIAN) && !down.is(Blocks.BEDROCK)) {
                    score += 10;
                } else {
                    score += 55;

                    boolean isPosAntiSurround = false;
                    for (Direction dir : HORIZONTAL) {
                        BlockPos antiSurroundBlockPos = blockPos.relative(dir);
                        if (targetPlayer.distanceToSqr(Vec3.atCenterOf(blockPos)) < 4.0
                                && getBlockStateIgnore(antiSurroundBlockPos).isAir()
                                && isCrystalBlock(antiSurroundBlockPos.below())) {
                            isPosAntiSurround = true;
                            break;
                        }
                    }
                    if (isPosAntiSurround) score += 25;
                }
            }

            if (pos.type == CheckPosType.Extend) score += 20;

            if (pos.type == CheckPosType.TerrainBase) {
                score += isTerrainFight ? 30 : 1;
            }
        }

        double d = targetPlayer.position().distanceTo(Vec3.atCenterOf(blockPos));
        score += 10 / d;

        Vec3 velocity = targetPlayer.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() > 0.001) {
            Vec3 moveDir = new Vec3(velocity.x, 0, velocity.z).normalize();
            Vec3 toBlock = Vec3.atCenterOf(blockPos).subtract(targetPlayer.position()).normalize();
            double dot = moveDir.dot(toBlock);
            if (dot > 0) score += dot * 5;
        }

        return score;
    }

    private double scoreBedrock(CheckPos pos) {
        BlockPos blockPos = pos.blockPos;
        double score = 0;

        if (blockPos.getY() == targetPlayer.getBlockY() + 2
                || blockPos.getY() == targetPlayer.getBlockY() - 1) {
            score += 10;
        }

        if (BlockPos.betweenClosedStream(targetFeetBox()).count() == 1) {
            boolean canMineFaceBlock = !mc.level.getBlockState(targetPlayer.blockPosition().above()).is(Blocks.BEDROCK);
            if (canMineFaceBlock) {
                if (blockPos.equals(targetPlayer.blockPosition().above())) {
                    score += 20;
                } else {
                    boolean isSelfTrapBlock = false;
                    for (Direction dir : HORIZONTAL) {
                        if (targetPlayer.blockPosition().above().relative(dir).equals(blockPos)) {
                            isSelfTrapBlock = true;
                            break;
                        }
                    }
                    if (isSelfTrapBlock) score += 7.5;
                }
            }
        }

        double d = targetPlayer.position().distanceTo(Vec3.atCenterOf(blockPos));
        score += 10 / d;
        return score;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || !glassRender.getValue() || glassRenderPos == null) return;

        long age = System.currentTimeMillis() - glassRenderStart;
        double fadeMs = glassFadeTime.getValue() * 1000.0;
        if (age > fadeMs) {
            glassRenderPos = null;
            return;
        }
        double t = age / fadeMs;
        Color fc = glassFillColor.getValue();
        Color oc = glassOutlineColor.getValue();
        RenderUtil.drawBoxFilled(event.getMatrix(), glassRenderPos,
                new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), (int) (fc.getAlpha() * (1 - t))));
        RenderUtil.drawBox(event.getMatrix(), glassRenderPos,
                new Color(oc.getRed(), oc.getGreen(), oc.getBlue(), (int) (oc.getAlpha() * (1 - t))), 1.5f);
    }

    @Subscribe
    private void onRenderDebug(Render3DEvent event) {
        if (nullCheck() || !renderDebugScores.getValue() || targetPlayer == null) return;
        if (speedMine() == null) return;

        boolean inBedrock = inBedrockCase();
        isTerrainFight = !inBedrock && computeTerrainFight();
        Set<CheckPos> checkPos = new HashSet<>();
        if (inBedrock) addBedrockCaseCheckPositions(checkPos);
        else addNormalCaseCheckPositions(checkPos);

        double bestScore = 0;
        for (CheckPos cp : checkPos) {
            double s = evalCheckPos(cp, inBedrock);
            if (s != INVALID_SCORE && s > bestScore) bestScore = s;
        }
        if (bestScore <= 0) return;

        for (CheckPos cp : checkPos) {
            double s = evalCheckPos(cp, inBedrock);
            if (s == INVALID_SCORE) continue;
            int alpha = (int) Math.min(200, Math.max(20, 200 * (s / bestScore)));
            RenderUtil.drawBox(event.getMatrix(), cp.blockPos, new Color(255, 40, 40, alpha), 1.0f);
        }
    }

    private SpeedMineModule speedMine() {
        SpeedMineModule m = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private AABB targetFeetBox() {
        AABB bb = targetPlayer.getBoundingBox().deflate(0.01, 0.1, 0.01);
        double feetY = targetPlayer.getY();
        return new AABB(bb.minX, feetY, bb.minZ, bb.maxX, feetY + 0.1, bb.maxZ);
    }

    private boolean inBedrockCase() {
        return BlockPos.betweenClosedStream(targetFeetBox())
                .anyMatch(bp -> mc.level.getBlockState(bp).is(Blocks.BEDROCK));
    }

    private boolean isBlockInFeet(BlockPos blockPos) {
        AABB feetBox = targetFeetBox();
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(feetBox.minX), Mth.floor(feetBox.minY), Mth.floor(feetBox.minZ),
                Mth.floor(feetBox.maxX), Mth.floor(feetBox.maxY), Mth.floor(feetBox.maxZ))) {
            if (blockPos.equals(pos)) return true;
        }
        return false;
    }

    private boolean isCrystalBlock(BlockPos blockPos) {
        BlockState s = mc.level.getBlockState(blockPos);
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK);
    }

    private BlockState getBlockStateIgnore(BlockPos blockPos) {
        if (blockPos == null) return Blocks.AIR.defaultBlockState();
        if (blockPos.equals(ignorePos)) return Blocks.AIR.defaultBlockState();
        return mc.level.getBlockState(blockPos);
    }

    private boolean canBreak(BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return false;
        return state.getDestroySpeed(mc.level, pos) >= 0;
    }

    private boolean intersectsEntity(AABB box, java.util.function.Predicate<Entity> pred) {
        for (Entity e : mc.level.getEntities((Entity) null, box)) {
            if (pred.test(e)) return true;
        }
        return false;
    }

    private static Iterable<BlockPos> iterate(AABB box) {
        return BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ));
    }

    private Direction getCornerPerpDir(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST  -> Direction.NORTH;
            case WEST  -> Direction.SOUTH;
            default    -> null;
        };
    }

    @Override
    public String getDisplayInfo() {
        return targetPlayer != null ? targetPlayer.getName().getString() : null;
    }

    private static class CityBlock {
        BlockPos blockPos;
        double score;
        boolean isFeetBlock = false;
    }

    private static final class CheckPos {
        final BlockPos blockPos;
        final CheckPosType type;

        CheckPos(BlockPos blockPos, CheckPosType type) {
            this.blockPos = blockPos;
            this.type = type;
        }

        @Override
        public int hashCode() {
            return blockPos.hashCode() * 31 + type.ordinal();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CheckPos other
                    && other.type == type && other.blockPos.equals(blockPos);
        }
    }

    private enum CheckPosType { Feet, Surround, Extend, FacePlace, Head, Below, TerrainBase }

    public enum SortPriority { Angle, Distance }

    private enum AntiSwimMode { None, Always, Mine, MineOrSwim }

    private enum AntiSurroundMode { None, Inner, Outer, Auto }

    private enum ExtendBreakMode { None, Long, Corner }
}
