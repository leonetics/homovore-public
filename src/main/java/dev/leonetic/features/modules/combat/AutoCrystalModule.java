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
import dev.leonetic.manager.PlacementManager;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.render.RenderUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.models.Timer;
import dev.leonetic.util.player.ChatUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import dev.leonetic.util.EnchantmentUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class AutoCrystalModule extends Module {

    private final Setting<Boolean> place         = bool("Place", true).setPage("General");
    private final Setting<Boolean> offhandPlace  = bool("OffhandPlace", true).setPage("General");
    private final Setting<Integer> placeDelay    = num("PlaceDelay", 0, 0, 2000).setPage("General");
    private final Setting<Boolean> doBreak       = bool("Break", true).setPage("General");
    private final Setting<Integer> breakDelay    = num("BreakDelay", 0, 0, 2000).setPage("General");
    private final Setting<Double>  minDamage     = num("MinDamage", 4.0, 0.0, 36.0).setPage("General");
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage", 4.0, 0.0, 36.0).setPage("General");
    private final Setting<Boolean> antiSurround  = bool("AntiSurround", true).setPage("General");
    private final Setting<Integer> antiSurroundCompletion = num("AntiSurroundCompletion", 70, 0, 100).setPage("General");

    private final Setting<Boolean> antiChinese   = bool("AntiChinese", false).setPage("AntiChinese");

    private final Setting<Double>  antiChineseRange = num("Range", 5.0, 1.0, 5.14).setPage("AntiChinese");

    private final Setting<Double>  antiChineseEnemyRange = num("EnemyRange", 8.0, 1.0, 30.0).setPage("AntiChinese");
    private final Setting<Boolean> basePlace          = bool("BasePlace", true).setPage("BasePlace");
    private final Setting<Double>  basePlaceTargetRange = num("BasePlaceEnemyRange", 7.0, 0.1, 15.0).setPage("BasePlace");
    private final Setting<Double>  basePlaceMinDamage = num("BasePlaceMinDamage", 5.0, 0.0, 36.0).setPage("BasePlace");
    private final Setting<Boolean> debug         = bool("Debug", false).setPage("Extra");
    private final Setting<Boolean> packetLog     = bool("PacketLog", false).setPage("Extra");

    private final Setting<RenderMode> renderMode = mode("Render", RenderMode.GRADIENT).setPage("Render");
    private final Setting<Color>   sideColor     = color("SideColor", 130, 80, 255, 45).setPage("Render");
    private final Setting<Color>   lineColor     = color("LineColor", 130, 80, 255, 255).setPage("Render");
    private final Setting<Float>   lineWidth     = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Double>  gradientHeight = num("GradientHeight", 0.7, 0.0, 1.0).setPage("Render");
    private final Setting<Integer> renderTime    = num("RenderTime", 10, 1, 40).setPage("Render");
    private final Setting<Integer> smoothness    = num("Smoothness", 10, 1, 50).setPage("Render");
    private final Setting<Color>   pulseColor    = color("PulseColor", 130, 80, 255, 150).setPage("Render");
    private final Setting<Double>  pulseSpeed    = num("PulseSpeed", 2.0, 0.2, 6.0).setPage("Render");
    private final Setting<Double>  pulseExpand   = num("PulseExpand", 0.35, 0.0, 1.0).setPage("Render");

    private BlockPos renderPos = null;
    private long     renderStartMs = 0L;
    private AABB     smoothBox = null;

    private enum RenderMode { NONE, GRADIENT, GRADIENT_SMOOTH, BOX_SMOOTH, PULSE }

    private static final double  PLACE_RANGE    = 6.0;
    private static final double  BASE_PLACE_RANGE = 6.0;
    private static final double  BREAK_RANGE    = 3.0;
    private static final int     BALANCE        = 4;
    private static final double  HEALTH_BALANCE = 0.20;
    private static final double  ARMOR_BALANCE  = 0.20;
    private static final boolean NO_SELF_POP    = true;

    private static final long PLACE_PENDING_MS = 40;

    private static final long CRYSTAL_TRACK_MS = 1750;

    private final Timer placeTimer  = new Timer();
    private final Timer breakTimer  = new Timer();

    private final Long2LongOpenHashMap crystalPlaces = new Long2LongOpenHashMap();

    private float lastBestDamage    = 0;
    private SwapManager.SwapHandle pendingSwapHandle = null;

    private final java.util.HashMap<String, int[]> diagSendCounts = new java.util.HashMap<>();
    private long diagWindowStart  = 0L;
    private int  diagWindowSends  = 0;
    private int  diagPeakWindow   = 0;
    private long diagLifetimeSends = 0L;
    private long diagLastTickSends = 0L;
    private int  diagPeakTickSends = 0;
    private int  diagPlaceAttempt, diagPlaceSent, diagBreakSent, diagInstaBreak;

    private double lastCalcMs = 0;

    private int lastReactorPlaceTick = -1;
    private BlockPos lastPlaceAir = null;

    private final ExposureContext exposureCtx = new ExposureContext();

    private final ArrayList<PlaceCandidate> candidates = new ArrayList<>();

    private final Set<Integer> deadIds = ConcurrentHashMap.newKeySet();

    private final Set<BlockPos> seeThrough = new HashSet<>();

    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;
    private ChunkAccess cachedChunk;

    private final PlacementManager.PlacementListener placementListener = this::onBlockUpdate;

    public AutoCrystalModule() {
        super("AutoCrystal", "Automatically places and breaks end crystals.", Category.COMBAT);
        placeDelay.setVisibility(v -> place.getValue());
        breakDelay.setVisibility(v -> doBreak.getValue());
        basePlaceTargetRange.setVisibility(v -> basePlace.getValue());
        basePlaceMinDamage.setVisibility(v -> basePlace.getValue());
        antiChineseRange.setVisibility(v -> antiChinese.getValue());
        antiChineseEnemyRange.setVisibility(v -> antiChinese.getValue());
    }

    @Override
    public void onEnable() {
        crystalPlaces.clear();
        deadIds.clear();
        placeTimer.reset();
        breakTimer.reset();
        lastBestDamage = 0;
        lastCalcMs = 0;
        lastReactorPlaceTick = -1;
        pendingSwapHandle = null;
        resetDiag();
        Homovore.placementManager.addListener(placementListener);
    }

    @Override
    public void onDisable() {
        Homovore.placementManager.removeListener(placementListener);
        if (pendingSwapHandle != null) {
            Homovore.swapManager.release(pendingSwapHandle);
            pendingSwapHandle = null;
        }
        crystalPlaces.clear();
        deadIds.clear();
        lastBestDamage = 0;
        lastPlaceAir = null;
        resetDiag();
        renderPos = null;
        smoothBox = null;
    }

    private void markRender(BlockPos crystalPos) {
        if (renderMode.getValue() == RenderMode.NONE) return;
        renderPos = crystalPos;
        renderStartMs = System.currentTimeMillis();
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || renderMode.getValue() == RenderMode.NONE || renderPos == null) return;

        long elapsed = System.currentTimeMillis() - renderStartMs;
        long life = renderTime.getValue() * 50L;
        if (elapsed > life) {
            renderPos = null;
            smoothBox = null;
            return;
        }

        switch (renderMode.getValue()) {
            case GRADIENT        -> drawGradient(event, false);
            case GRADIENT_SMOOTH -> drawGradient(event, true);
            case BOX_SMOOTH      -> drawBoxSmooth(event);
            case PULSE           -> drawPulse(event, elapsed, life);
            default -> { }
        }
    }

    private void updateSmoothBox(AABB target) {
        if (smoothBox == null) {
            smoothBox = target;
        } else {
            double f = 1.0 / smoothness.getValue();
            smoothBox = new AABB(
                    lerp(smoothBox.minX, target.minX, f), lerp(smoothBox.minY, target.minY, f), lerp(smoothBox.minZ, target.minZ, f),
                    lerp(smoothBox.maxX, target.maxX, f), lerp(smoothBox.maxY, target.maxY, f), lerp(smoothBox.maxZ, target.maxZ, f));
        }
    }

    private void drawBoxSmooth(Render3DEvent event) {
        updateSmoothBox(new AABB(renderPos.below()));
        RenderUtil.drawBoxFilled(event.getMatrix(), smoothBox, sideColor.getValue());
        RenderUtil.drawBox(event.getMatrix(), smoothBox, lineColor.getValue(), lineWidth.getValue());
    }

    private void drawGradient(Render3DEvent event, boolean smooth) {
        Color side = sideColor.getValue();
        Color line = lineColor.getValue();
        Color clear = new Color(side.getRed(), side.getGreen(), side.getBlue(), 0);
        double height = gradientHeight.getValue();
        float lw = lineWidth.getValue();

        BlockPos basePos = renderPos.below();

        double x1, z1, x2, z2, yTop;
        if (smooth) {
            updateSmoothBox(new AABB(basePos));
            x1 = smoothBox.minX; z1 = smoothBox.minZ;
            x2 = smoothBox.maxX; z2 = smoothBox.maxZ;
            yTop = smoothBox.maxY;
        } else {
            x1 = basePos.getX(); z1 = basePos.getZ();
            x2 = x1 + 1;         z2 = z1 + 1;
            yTop = basePos.getY() + 1;
        }
        double yBottom = yTop - height;
        var m = event.getMatrix();

        RenderUtil.quadHorizontal(m, x1, z1, x2, z2, yTop, side);
        RenderUtil.gradientQuadVertical(m, x1, z1, x2, z1, yTop, yBottom, side, clear);
        RenderUtil.gradientQuadVertical(m, x2, z1, x2, z2, yTop, yBottom, side, clear);
        RenderUtil.gradientQuadVertical(m, x2, z2, x1, z2, yTop, yBottom, side, clear);
        RenderUtil.gradientQuadVertical(m, x1, z2, x1, z1, yTop, yBottom, side, clear);

        RenderUtil.drawLine(new Vec3(x1, yTop, z1), new Vec3(x2, yTop, z1), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z1), new Vec3(x2, yTop, z2), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z2), new Vec3(x1, yTop, z2), line, lw);
        RenderUtil.drawLine(new Vec3(x1, yTop, z2), new Vec3(x1, yTop, z1), line, lw);

        RenderUtil.drawLine(new Vec3(x1, yTop, z1), new Vec3(x1, yBottom, z1), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z1), new Vec3(x2, yBottom, z1), line, lw);
        RenderUtil.drawLine(new Vec3(x2, yTop, z2), new Vec3(x2, yBottom, z2), line, lw);
        RenderUtil.drawLine(new Vec3(x1, yTop, z2), new Vec3(x1, yBottom, z2), line, lw);
    }

    private void drawPulse(Render3DEvent event, long elapsed, long life) {
        double fade = 1.0 - (double) elapsed / life;
        double phase = elapsed / 1000.0 * pulseSpeed.getValue();
        double pulse = (Math.sin(phase * Math.PI * 2.0) + 1.0) * 0.5;
        double grow = pulse * pulseExpand.getValue();

        Color base = pulseColor.getValue();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.clamp((int) (base.getAlpha() * fade), 0, 255));
        Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.clamp((int) (255 * fade), 0, 255));

        AABB box = new AABB(renderPos).inflate(grow);
        RenderUtil.drawBoxFilled(event.getMatrix(), box, fill);
        RenderUtil.drawBox(event.getMatrix(), box, line, lineWidth.getValue());
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.level == null) return;

        if (event.getPacket() instanceof ClientboundEntityEventPacket pkt) {
            if (pkt.getEventId() != 3) return;

            var e = pkt.getEntity(mc.level);
            if (e != null) deadIds.add(e.getId());
            return;
        }

        if (event.getPacket() instanceof ClientboundAddEntityPacket pkt) {
            onCrystalSpawn(pkt);
        }
    }

    @Subscribe
    private void onPacketSendDiag(PacketEvent.Send event) {
        if (!packetLog.getValue()) return;
        diagSendCounts.computeIfAbsent(event.getPacket().getClass().getSimpleName(), k -> new int[1])[0]++;
        diagWindowSends++;
        diagLifetimeSends++;
        long now = System.currentTimeMillis();
        if (diagWindowStart == 0L) diagWindowStart = now;
        if (now - diagWindowStart >= 1000L) flushDiag(now);
    }

    @Subscribe(priority = 100)
    private void onDiagTickBoundary(PreTickEvent event) {
        if (!packetLog.getValue()) return;
        int tickDelta = (int) (diagLifetimeSends - diagLastTickSends);
        diagLastTickSends = diagLifetimeSends;
        if (tickDelta > diagPeakTickSends) diagPeakTickSends = tickDelta;
    }

    private void flushDiag(long now) {
        if (diagWindowSends > diagPeakWindow) diagPeakWindow = diagWindowSends;
        StringBuilder breakdown = new StringBuilder();
        diagSendCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> breakdown.append(e.getKey()).append('=').append(e.getValue()[0]).append(' '));
        Homovore.LOGGER.info(
                "[ACDiag] {}ms sends={} peakTick={} peakSec={} | place att/sent={}/{} break={} insta={} | {}",
                now - diagWindowStart, diagWindowSends, diagPeakTickSends, diagPeakWindow,
                diagPlaceAttempt, diagPlaceSent, diagBreakSent, diagInstaBreak, breakdown.toString().trim());
        diagSendCounts.clear();
        diagWindowSends = 0;
        diagPeakTickSends = 0;
        diagPlaceAttempt = diagPlaceSent = diagBreakSent = diagInstaBreak = 0;
        diagWindowStart = now;
    }

    private void resetDiag() {
        diagSendCounts.clear();
        diagWindowStart = 0L;
        diagWindowSends = 0;
        diagPeakWindow = 0;
        diagLifetimeSends = 0L;
        diagLastTickSends = 0L;
        diagPeakTickSends = 0;
        diagPlaceAttempt = diagPlaceSent = diagBreakSent = diagInstaBreak = 0;
    }

    private void onCrystalSpawn(ClientboundAddEntityPacket pkt) {

        if (!doBreak.getValue() || breakDelay.getValue() != 0) return;
        if (pkt.getType() != EntityType.END_CRYSTAL) return;
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;
        if (mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;
        AutoSwordModule sword = Homovore.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled() && sword.isMaceAttackReady()) return;

        EndCrystal crystal = new EndCrystal(EntityType.END_CRYSTAL, mc.level);
        crystal.setPos(pkt.getX(), pkt.getY(), pkt.getZ());
        crystal.setId(pkt.getId());

        AABB bb = crystal.getBoundingBox();
        Vec3 reachEye = bestReachEye(bb);
        if (sqDistToBox(reachEye, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ)
                > BREAK_RANGE * BREAK_RANGE) return;

        float optimisticSelf = maxDamageSelf(crystal.position());
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (optimisticSelf > maxSelfDamage.getValue()
                || (NO_SELF_POP && optimisticSelf + 1.5f >= playerHealth)) return;

        float serverYaw = Homovore.rotationManager.getServerYaw();
        float serverPitch = Homovore.rotationManager.getServerPitch();
        if (!canBreakCrystal(crystal, serverYaw, serverPitch)) return;

        diagInstaBreak++;
        mc.player.connection.send(
                ServerboundInteractPacket.createAttackPacket(crystal, mc.player.isShiftKeyDown()));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    private void onBlockUpdate(BlockPos pos, boolean nowAir) {
        if (!nowAir) return;
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;
        if (mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;

        AutoSwordModule sword = Homovore.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled() && sword.isMaceAttackReady()) return;

        double cutoff = (PLACE_RANGE + 2.0) * (PLACE_RANGE + 2.0);
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > cutoff) return;

        updateSeeThrough();

        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        double scanRadius = PLACE_RANGE + 12.0;
        List<TargetCache> potentialTargets = collectTargets(targets, scanRadius);

        if (doBreak.getValue() && breakTimer.passedMs(breakDelay.getValue())) {
            EndCrystal target = findBestCrystal(potentialTargets);
            if (target != null) {
                breakCrystal(target);
                breakTimer.reset();
                return;
            }
        }

        if (!place.getValue() || !placeTimer.passedMs(placeDelay.getValue())) return;

        if (mc.player.tickCount == lastReactorPlaceTick) return;
        lastReactorPlaceTick = mc.player.tickCount;

        PlaceTarget placeTarget = findBestPlace(potentialTargets);
        if (placeTarget != null) {
            doPlace(placeTarget);
            placeTimer.reset();
        }
    }

    @Subscribe(priority = 1000)
    private void onPreTickReleaseStale(PreTickEvent event) {
        releasePendingSwap();
    }

    @Subscribe(priority = -100)
    private void onPreTickRestore(PreTickEvent event) {
        releasePendingSwap();
    }

    private void releasePendingSwap() {
        if (pendingSwapHandle != null) {
            Homovore.swapManager.release(pendingSwapHandle);
            pendingSwapHandle = null;
        }
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        if (mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;

        AutoSwordModule sword = Homovore.moduleManager.getModuleByClass(AutoSwordModule.class);
        if (sword != null && sword.isEnabled() && sword.isMaceAttackReady()) {
            lastBestDamage = 0;
            return;
        }

        ageCrystalPlaces();
        updateSeeThrough();

        boolean antiChineseReady = antiChinese.getValue()
                && enemyNearby(antiChineseEnemyRange.getValue());

        boolean broke = false;
        if (doBreak.getValue() && breakTimer.passedMs(breakDelay.getValue())) {
            TargetsModule breakTargets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
            List<TargetCache> breakTargetCache = collectTargets(breakTargets, BREAK_RANGE + 12.0);
            EndCrystal target = findBestCrystal(breakTargetCache);
            if (target != null) {
                breakCrystal(target);
                breakTimer.reset();
                broke = true;
            }
        }

        if (!broke && antiChineseReady && breakTimer.passedMs(breakDelay.getValue())) {
            EndCrystal acTarget = findAntiChineseCrystal();
            if (acTarget != null) {
                breakCrystal(acTarget);
                breakTimer.reset();
            }
        }

        boolean canPlace = place.getValue() && placeTimer.passedMs(placeDelay.getValue());

        long calcStart = System.nanoTime();

        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);

        double scanRadius = PLACE_RANGE + 12.0
                + (basePlace.getValue() ? BASE_PLACE_RANGE : 0.0);
        List<TargetCache> potentialTargets = collectTargets(targets, scanRadius);

        PlaceTarget     crystalTarget = canPlace            ? findBestPlace(potentialTargets)     : null;
        BasePlaceTarget baseTarget    = basePlace.getValue() ? findBestBasePlace(targets, potentialTargets) : null;

        lastCalcMs = (System.nanoTime() - calcStart) / 1_000_000.0;
        if (debug.getValue()) {
            ChatUtil.sendPersistent("autocrystal:calc",
                    Component.literal(String.format("AutoCrystal calc: %.3f ms", lastCalcMs)));
        }

        boolean baseWins = baseTarget != null
                && (crystalTarget == null || baseTarget.damage > crystalTarget.damage);

        boolean placed = false;
        if (baseWins) {

            boolean baseQueued = doBasePlace(baseTarget);
            if (baseQueued) {
                Homovore.placementManager.flushQueue();
                if (canPlace) {
                    doPlace(new PlaceTarget(baseTarget.base, baseTarget.damage), true);
                    placeTimer.reset();
                    placed = true;
                }
            }
        } else if (crystalTarget != null) {
            doPlace(crystalTarget);
            placeTimer.reset();
            placed = true;
        }

        if (!placed && antiChineseReady && canPlace) {
            PlaceTarget acPlace = findAntiChinesePlace();
            if (acPlace != null) {
                doPlace(acPlace);
                placeTimer.reset();
            }
        }
    }

    private BasePlaceTarget findBestBasePlace(TargetsModule targets, List<TargetCache> potentialTargets) {
        LivingEntity primary = findClosestBaseTarget(targets);
        if (primary == null) return null;

        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 playerCenter = mc.player.position();
        double maxSelf = maxSelfDamage.getValue();
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double baseMin = basePlaceMinDamage.getValue();
        int targetFeetY = primary.blockPosition().getY();
        double range = BASE_PLACE_RANGE;
        double rangeSq = range * range;
        int r = (int) Math.ceil(range);

        BlockPos playerPos = mc.player.blockPosition();

        int topLayerY    = targetFeetY - 1;
        int bottomLayerY = playerPos.getY() - r;

        int rr = r * r;

        for (int by = topLayerY; by >= bottomLayerY; by--) {
            BlockPos layerBest = null;
            float layerDmg = 0;
            int dy = by - playerPos.getY();
            int dyy = dy * dy;
            if (dyy > rr) continue;

            for (int dx = -r; dx <= r; dx++) {
                int dxxdyy = dx * dx + dyy;
                for (int dz = -r; dz <= r; dz++) {
                    if (dxxdyy + dz * dz > rr) continue;
                    BlockPos basePos    = new BlockPos(playerPos.getX() + dx, by, playerPos.getZ() + dz);
                    BlockPos crystalPos = basePos.above();

                    var crystalState = mc.level.getBlockState(crystalPos);
                    if (!crystalState.isAir() && !crystalState.canBeReplaced()) continue;
                    if (isBlocked(crystalPos)) continue;

                    if (playerCenter.distanceToSqr(basePos.getX() + 0.5, basePos.getY() + 0.5, basePos.getZ() + 0.5) > rangeSq) continue;
                    if (!PlaceUtil.canPlace(basePos)) continue;

                    if (sqDistToBox(eye, crystalPos.getX(), crystalPos.getY(), crystalPos.getZ(),
                            crystalPos.getX() + 1, crystalPos.getY() + 1, crystalPos.getZ() + 1) > PLACE_RANGE * PLACE_RANGE) continue;

                    Vec3 crystalCenter = new Vec3(crystalPos.getX() + 0.5, crystalPos.getY(), crystalPos.getZ() + 0.5);
                    double cMinX = crystalCenter.x - 1, cMinZ = crystalCenter.z - 1;
                    double cMaxX = crystalCenter.x + 1, cMaxY = crystalCenter.y + 2, cMaxZ = crystalCenter.z + 1;
                    Vec3 breakEye = bestReachEye(new AABB(cMinX, crystalCenter.y, cMinZ, cMaxX, cMaxY, cMaxZ));
                    if (sqDistToBox(breakEye, cMinX, crystalCenter.y, cMinZ, cMaxX, cMaxY, cMaxZ) > BREAK_RANGE * BREAK_RANGE) continue;

                    if (!anyTargetWithin(potentialTargets, crystalCenter, 144.0)) continue;

                    float bound = 0;
                    boolean anyBound = false;
                    for (int i = 0, n = potentialTargets.size(); i < n; i++) {
                        TargetCache tc = potentialTargets.get(i);
                        if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
                        float dmg = maxDamage(tc, crystalCenter);
                        if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                        bound += dmg;
                        anyBound = true;
                    }
                    if (!anyBound || bound < baseMin || bound <= layerDmg) continue;

                    if (!selfDamageOk(crystalCenter, basePos, maxSelf, playerHealth)) continue;

                    float totalDmg = 0;
                    boolean anyTarget = false;
                    for (int i = 0, n = potentialTargets.size(); i < n; i++) {
                        TargetCache tc = potentialTargets.get(i);
                        if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
                        float dmg = calcDamage(tc, crystalCenter, basePos);
                        if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                        totalDmg += dmg;
                        anyTarget = true;
                    }
                    if (!anyTarget) continue;
                    if (totalDmg < baseMin) continue;

                    if (totalDmg > layerDmg) {
                        layerDmg  = totalDmg;
                        layerBest = basePos;
                    }
                }
            }

            if (layerBest != null) {
                return new BasePlaceTarget(layerBest, layerDmg);
            }
        }
        return null;
    }

    private boolean doBasePlace(BasePlaceTarget target) {
        Result obs = InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.HOTBAR_SCOPE);
        if (!obs.found() || obs.type() == ResultType.OFFHAND) return false;
        int slot = obs.slot();

        return Homovore.placementManager.enqueue(target.base, slot);
    }

    private LivingEntity findClosestBaseTarget(TargetsModule targets) {
        double range = basePlaceTargetRange.getValue();
        double rangeSq = range * range;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        AABB area = mc.player.getBoundingBox().inflate(range);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof Player p)) continue;
            if (targets != null && !targets.isValidPlayerTarget(p)) continue;
            double d = mc.player.distanceToSqr(p);
            if (d > rangeSq) continue;
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    private EndCrystal findBestCrystal(List<TargetCache> potentialTargets) {
        EndCrystal best = null;
        float bestDmg = 0;
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double maxSelf = maxSelfDamage.getValue();
        double breakRangeSq = BREAK_RANGE * BREAK_RANGE;

        AABB searchArea = mc.player.getBoundingBox().inflate(BREAK_RANGE + 2);
        for (Entity e : mc.level.getEntities(null, searchArea)) {
            if (!(e instanceof EndCrystal crystal)) continue;

            AABB bb = crystal.getBoundingBox();
            Vec3 reachEye = bestReachEye(bb);
            if (sqDistToBox(reachEye, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ) > breakRangeSq) continue;

            Vec3 cp = crystal.position();
            if (!anyTargetWithin(potentialTargets, cp, 144.0)) continue;

            if (!selfDamageOk(cp, null, maxSelf, playerHealth)) continue;

            float totalDmg = 0;
            boolean anyTarget = false;
            for (TargetCache tc : potentialTargets) {
                if (tc.pos.distanceToSqr(cp) > 144.0) continue;
                float dmg = calcDamage(tc, cp);
                if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                totalDmg += dmg;
                anyTarget = true;
            }
            if (!anyTarget) continue;
            if (totalDmg > bestDmg) { bestDmg = totalDmg; best = crystal; }
        }
        lastBestDamage = bestDmg;
        return best;
    }

    private void breakCrystal(EndCrystal crystal) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 hit = getClosestPointToEye(eyePos, crystal.getBoundingBox());
        float[] angles = MathUtil.calcAngle(eyePos, hit);
        if (!canBreakCrystal(crystal, angles[0], angles[1])) return;
        Homovore.rotationManager.submit(new RotationRequest(
            "AutoCrystal_break", 60, angles[0], angles[1], RotationRequest.Mode.SILENT
        ));
        diagBreakSent++;
        mc.gameMode.attack(mc.player, crystal);
    }

    private boolean canBreakCrystal(EndCrystal crystal, float yaw, float pitch) {
        AABB bb = crystal.getBoundingBox();
        Vec3 eyePos = bestReachEye(bb);
        if (bb.contains(eyePos)) return true;
        Vec3 look = getLookVector(yaw, pitch);
        Vec3 reachEnd = eyePos.add(look.scale(BREAK_RANGE));
        return bb.clip(eyePos, reachEnd).isPresent();
    }

    private PlaceTarget findBestPlace(List<TargetCache> potentialTargets) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(PLACE_RANGE);
        long now = System.currentTimeMillis();
        boolean isEnd = mc.level.dimension().equals(Level.END);
        double placeRangeSq = PLACE_RANGE * PLACE_RANGE;
        double breakRangeSq = BREAK_RANGE * BREAK_RANGE;
        double maxSelf = maxSelfDamage.getValue();
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int rr = r * r;

        candidates.clear();
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {

                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    var baseState = mc.level.getBlockState(cursor);
                    if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) continue;

                    int bx = cursor.getX(), by = cursor.getY(), bz = cursor.getZ();
                    int ay = by + 1;
                    cursor.set(bx, ay, bz);
                    var airState = mc.level.getBlockState(cursor);
                    if (!airState.isAir() && !(airState.is(Blocks.FIRE) && isEnd)) continue;

                    if (sqDistToBox(eyePos, bx, ay, bz, bx + 1, ay + 1, bz + 1) > placeRangeSq) continue;

                    double cx = bx + 0.5, cy = by + 1.0, cz = bz + 0.5;
                    Vec3 breakEye = bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1));
                    if (sqDistToBox(breakEye, cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) > breakRangeSq) continue;

                    Vec3 crystalCenter = new Vec3(cx, cy, cz);

                    float boundTotal = 0;
                    boolean anyTarget = false;
                    for (int i = 0, n = potentialTargets.size(); i < n; i++) {
                        TargetCache tc = potentialTargets.get(i);
                        if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
                        float dmg = maxDamage(tc, crystalCenter);
                        if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                        boundTotal += dmg;
                        anyTarget = true;
                    }
                    if (!anyTarget) continue;

                    candidates.add(new PlaceCandidate(
                            new BlockPos(bx, by, bz), new BlockPos(bx, ay, bz), crystalCenter, boundTotal));
                }
            }
        }
        if (candidates.isEmpty()) return null;

        candidates.sort((a, b) -> Float.compare(b.bound, a.bound));

        BlockPos locked = null;
        if (lastPlaceAir != null) {
            long lockTs = crystalPlaces.get(lastPlaceAir.asLong());
            boolean pending = lockTs != 0L && now - lockTs < PLACE_PENDING_MS;
            if (pending || hasLiveCrystalAt(lastPlaceAir)) {
                double cx = lastPlaceAir.getX() + 0.5, cy = lastPlaceAir.getY(), cz = lastPlaceAir.getZ() + 0.5;
                Vec3 lockEye = bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1));
                if (sqDistToBox(lockEye, cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) <= breakRangeSq) {
                    locked = lastPlaceAir;
                } else {
                    lastPlaceAir = null;
                }
            } else {
                lastPlaceAir = null;
            }
        }

        PlaceTarget best = null;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            PlaceCandidate c = candidates.get(i);
            if (best != null && c.bound <= best.damage) break;

            if (locked != null && !c.airPos.equals(locked)) continue;
            if (isBlocked(c.airPos)) continue;
            long pendingTs = crystalPlaces.get(c.airPos.asLong());
            if (pendingTs != 0L && now - pendingTs < PLACE_PENDING_MS) continue;

            if (!selfDamageOk(c.crystalCenter, null, maxSelf, playerHealth)) continue;

            float totalDmg = 0;
            boolean anyTarget = false;
            for (int j = 0, m = potentialTargets.size(); j < m; j++) {
                TargetCache tc = potentialTargets.get(j);
                if (tc.pos.distanceToSqr(c.crystalCenter) > 144.0) continue;
                float dmg = calcDamage(tc, c.crystalCenter);
                if (dmg < getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) continue;
                totalDmg += dmg;
                anyTarget = true;
            }
            if (!anyTarget) continue;
            if (best == null || totalDmg > best.damage) best = new PlaceTarget(c.base, totalDmg);
        }
        return best;
    }

    private boolean isBurrowBlock(BlockState state) {
        return state.is(Blocks.REDSTONE_BLOCK)
                || state.is(Blocks.PISTON)
                || state.is(Blocks.STICKY_PISTON)
                || state.is(Blocks.PISTON_HEAD)
                || state.is(Blocks.MOVING_PISTON);
    }

    private boolean nearBurrowBlock(Vec3 center) {
        int r = (int) Math.ceil(ANTI_CHINESE_RADIUS);
        double radSq = ANTI_CHINESE_RADIUS * ANTI_CHINESE_RADIUS;
        double playerRangeSq = antiChineseRange.getValue() * antiChineseRange.getValue();
        Vec3 playerPos = mc.player.position();
        int cx = Mth.floor(center.x), cy = Mth.floor(center.y), cz = Mth.floor(center.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    double ox = (bx + 0.5) - center.x;
                    double oy = (by + 0.5) - center.y;
                    double oz = (bz + 0.5) - center.z;
                    if (ox * ox + oy * oy + oz * oz > radSq) continue;

                    if (playerPos.distanceToSqr(bx + 0.5, by + 0.5, bz + 0.5) > playerRangeSq) continue;
                    cursor.set(bx, by, bz);
                    if (isBurrowBlock(getStateFast(cursor))) return true;
                }
            }
        }
        return false;
    }

    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private boolean adjacentToBurrowBlock(BlockPos airPos) {
        double playerRangeSq = antiChineseRange.getValue() * antiChineseRange.getValue();
        Vec3 playerPos = mc.player.position();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] o : FACE_OFFSETS) {
            int bx = airPos.getX() + o[0], by = airPos.getY() + o[1], bz = airPos.getZ() + o[2];
            if (playerPos.distanceToSqr(bx + 0.5, by + 0.5, bz + 0.5) > playerRangeSq) continue;
            cursor.set(bx, by, bz);
            if (isBurrowBlock(getStateFast(cursor))) return true;
        }
        return false;
    }

    private boolean enemyNearby(double range) {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeSq = range * range;
        AABB area = mc.player.getBoundingBox().inflate(range);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof Player p)) continue;
            if (targets != null && !targets.isValidPlayerTarget(p)) continue;
            if (mc.player.distanceToSqr(p) <= rangeSq) return true;
        }
        return false;
    }

    private PlaceTarget findAntiChinesePlace() {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(PLACE_RANGE);
        long now = System.currentTimeMillis();
        boolean isEnd = mc.level.dimension().equals(Level.END);
        double placeRangeSq = PLACE_RANGE * PLACE_RANGE;
        double breakRangeSq = BREAK_RANGE * BREAK_RANGE;
        double maxSelf = maxSelfDamage.getValue();
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int rr = r * r;

        PlaceTarget best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {
                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    var baseState = mc.level.getBlockState(cursor);
                    if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) continue;

                    int bx = cursor.getX(), by = cursor.getY(), bz = cursor.getZ();
                    int ay = by + 1;
                    cursor.set(bx, ay, bz);
                    var airState = mc.level.getBlockState(cursor);
                    if (!airState.isAir() && !(airState.is(Blocks.FIRE) && isEnd)) continue;

                    if (sqDistToBox(eyePos, bx, ay, bz, bx + 1, ay + 1, bz + 1) > placeRangeSq) continue;

                    double cx = bx + 0.5, cy = by + 1.0, cz = bz + 0.5;
                    Vec3 breakEye = bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1));
                    if (sqDistToBox(breakEye, cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) > breakRangeSq) continue;

                    Vec3 crystalCenter = new Vec3(cx, cy, cz);

                    double dSq = eyePos.distanceToSqr(crystalCenter);
                    if (dSq >= bestDistSq) continue;

                    BlockPos airPos = new BlockPos(bx, ay, bz);
                    if (!adjacentToBurrowBlock(airPos)) continue;

                    if (isBlocked(airPos)) continue;
                    long pendingTs = crystalPlaces.get(airPos.asLong());
                    if (pendingTs != 0L && now - pendingTs < PLACE_PENDING_MS) continue;

                    if (!selfDamageOk(crystalCenter, null, maxSelf, playerHealth)) continue;

                    bestDistSq = dSq;
                    best = new PlaceTarget(new BlockPos(bx, by, bz), 0f);
                }
            }
        }
        return best;
    }

    private EndCrystal findAntiChineseCrystal() {
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double maxSelf = maxSelfDamage.getValue();
        double breakRangeSq = BREAK_RANGE * BREAK_RANGE;

        EndCrystal best = null;
        double bestDist = Double.MAX_VALUE;
        AABB searchArea = mc.player.getBoundingBox().inflate(BREAK_RANGE + 2);
        for (Entity e : mc.level.getEntities(null, searchArea)) {
            if (!(e instanceof EndCrystal crystal)) continue;

            AABB bb = crystal.getBoundingBox();
            Vec3 reachEye = bestReachEye(bb);
            if (sqDistToBox(reachEye, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ) > breakRangeSq) continue;

            Vec3 cp = crystal.position();
            if (!nearBurrowBlock(cp)) continue;
            if (!selfDamageOk(cp, null, maxSelf, playerHealth)) continue;

            double d = mc.player.distanceToSqr(crystal);
            if (d < bestDist) { bestDist = d; best = crystal; }
        }
        return best;
    }

    private List<TargetCache> collectTargets(TargetsModule targets, double scanRadius) {
        List<TargetCache> out = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(scanRadius);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying()) continue;
            if (deadIds.contains(e.getId())) continue;

            if (targets != null && !targets.isValidPlayerTarget(e)) continue;
            boolean armorBroken = e instanceof Player p && isAnyArmorBroken(p);

            float armor     = (float) living.getAttributeValue(Attributes.ARMOR);
            float toughness = (float) living.getAttributeValue(Attributes.ARMOR_TOUGHNESS);

            int protPoints = 0;
            if (!living.getItemBySlot(EquipmentSlot.HEAD).isEmpty())  protPoints += 4;
            if (!living.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) protPoints += 4;
            if (!living.getItemBySlot(EquipmentSlot.LEGS).isEmpty())  protPoints += 8;
            if (!living.getItemBySlot(EquipmentSlot.FEET).isEmpty())  protPoints += 4;

            out.add(new TargetCache(living.position(), living.getBoundingBox(),
                    living.getHealth(), living.getAbsorptionAmount(), armorBroken,
                    armor, toughness, protPoints));
        }
        return out;
    }

    private void updateSeeThrough() {
        seeThrough.clear();
        if (!antiSurround.getValue()) return;
        SpeedMineModule mine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (mine == null || !mine.isEnabled()) return;
        mine.collectMiningPositions(seeThrough, antiSurroundCompletion.getValue() / 100.0);
    }

    private static boolean anyTargetWithin(List<TargetCache> cache, Vec3 center, double maxDistSq) {
        for (int i = 0, n = cache.size(); i < n; i++) {
            if (cache.get(i).pos.distanceToSqr(center) <= maxDistSq) return true;
        }
        return false;
    }

    private static double sqDistToBox(Vec3 p, double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        double dx = p.x < minX ? minX - p.x : (p.x > maxX ? p.x - maxX : 0);
        double dy = p.y < minY ? minY - p.y : (p.y > maxY ? p.y - maxY : 0);
        double dz = p.z < minZ ? minZ - p.z : (p.z > maxZ ? p.z - maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private record TargetCache(Vec3 pos, AABB box, float hp, float abs,
                               boolean armorBroken, float armor, float toughness,
                               int protPoints) {}

    private boolean isBlocked(BlockPos airPos) {
        for (Entity e : mc.level.getEntities(null, new AABB(airPos))) {
            if (e instanceof ItemEntity) continue;
            if (e instanceof EndCrystal crystal && crystal.tickCount < 5) continue;
            if (e instanceof EndCrystal crystal && crystal.blockPosition().equals(airPos)) continue;
            return true;
        }
        return false;
    }

    private void doPlace(PlaceTarget target) {
        doPlace(target, false);
    }

    private void doPlace(PlaceTarget target, boolean trustBase) {
        Result result = InventoryUtil.find(Items.END_CRYSTAL, EnumSet.of(ResultType.HOTBAR));
        if (!result.found()) {
            lastBestDamage = 0;
            return;
        }
        lastBestDamage = target.damage;

        BlockPos base        = target.base;
        int slot             = result.slot();

        if (offhandPlace.getValue()) {
            diagPlaceAttempt++;
            boolean sentOffhand = Homovore.placementManager.placeCrystalOffhand(base, slot, trustBase);
            if (sentOffhand) {
                diagPlaceSent++;
                recordPlace(base.above());
            }
            return;
        }

        int originalSlot     = Homovore.swapManager.serverSlot();

        if (pendingSwapHandle != null && pendingSwapHandle.isReleased()) {
            pendingSwapHandle = null;
        }

        SwapManager.SwapHandle handle = pendingSwapHandle;
        boolean acquiredNow = false;
        if (slot != originalSlot && handle == null) {
            handle = Homovore.swapManager.acquire("AutoCrystal", 68);
            if (handle == null) {
                return;
            }
            acquiredNow = true;
        }

        diagPlaceAttempt++;
        boolean sent = Homovore.placementManager.placeCrystal(base, slot, trustBase);

        if (sent) {
            diagPlaceSent++;
            recordPlace(base.above());
            if (handle != null) pendingSwapHandle = handle;
        } else if (acquiredNow) {

            Homovore.swapManager.release(handle);
        }
    }

    private void recordPlace(BlockPos airPos) {
        crystalPlaces.put(airPos.asLong(), System.currentTimeMillis());
        markRender(airPos);
        lastPlaceAir = airPos;
    }

    private boolean hasLiveCrystalAt(BlockPos airPos) {
        for (Entity e : mc.level.getEntities(null, new AABB(airPos))) {
            if (e instanceof EndCrystal && e.blockPosition().equals(airPos)) return true;
        }
        return false;
    }

    public boolean preplaceCrystal(BlockPos airPos, boolean snap) {
        if (nullCheck() || !place.getValue()) return false;

        BlockPos base = airPos.below();
        var baseState = mc.level.getBlockState(base);
        if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return false;
        if (isBlocked(airPos)) return false;

        long now = System.currentTimeMillis();
        long pendingTs = crystalPlaces.get(airPos.asLong());
        if (pendingTs != 0L && now - pendingTs < PLACE_PENDING_MS) return false;

        doPlace(new PlaceTarget(base, 0f), false);
        return crystalPlaces.get(airPos.asLong()) != pendingTs;
    }

    public boolean isDesirablePlacement(BlockPos airPos) {
        if (nullCheck()) return false;

        BlockPos base = airPos.below();
        BlockState baseState = mc.level.getBlockState(base);
        if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return false;

        int bx = airPos.getX(), by = airPos.getY(), bz = airPos.getZ();
        Vec3 eye = mc.player.getEyePosition(1.0f);
        if (sqDistToBox(eye, bx, by, bz, bx + 1, by + 1, bz + 1) > PLACE_RANGE * PLACE_RANGE) return false;

        double cx = bx + 0.5, cy = by, cz = bz + 0.5;
        if (sqDistToBox(bestReachEye(new AABB(cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1)),
                cx - 1, cy, cz - 1, cx + 1, cy + 2, cz + 1) > BREAK_RANGE * BREAK_RANGE) return false;

        Vec3 crystalCenter = new Vec3(cx, cy, cz);
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (!selfDamageOk(crystalCenter, null, maxSelfDamage.getValue(), playerHealth)) return false;

        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        List<TargetCache> potentialTargets = collectTargets(targets, PLACE_RANGE + 12.0);
        for (int i = 0, n = potentialTargets.size(); i < n; i++) {
            TargetCache tc = potentialTargets.get(i);
            if (tc.pos.distanceToSqr(crystalCenter) > 144.0) continue;
            if (calcDamage(tc, crystalCenter) >= getDynamicMin(tc.hp, tc.abs, tc.armorBroken)) return true;
            if (antiSurround.getValue() && isFeetAdjacent(tc.pos, airPos)) return true;
        }
        return false;
    }

    private boolean isFeetAdjacent(Vec3 targetPos, BlockPos airPos) {
        BlockPos feet = BlockPos.containing(targetPos.x, targetPos.y, targetPos.z);
        if (feet.getY() != airPos.getY()) return false;
        int dx = airPos.getX() - feet.getX();
        int dz = airPos.getZ() - feet.getZ();
        return (Math.abs(dx) == 1 && dz == 0) || (Math.abs(dz) == 1 && dx == 0);
    }

    private void ageCrystalPlaces() {
        long now = System.currentTimeMillis();
        LongIterator it = crystalPlaces.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (now - crystalPlaces.get(key) > CRYSTAL_TRACK_MS) it.remove();
        }

        if (mc.player != null && mc.player.tickCount % 100 == 0) {
            deadIds.clear();
        }
    }

    private float calcDamage(TargetCache tc, Vec3 explosionPos) {
        return calcDamage(tc, explosionPos, null);
    }

    private float calcDamage(TargetCache tc, Vec3 explosionPos, BlockPos phantomObsidianPos) {
        double dist = tc.pos.distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        double exposure = calcExposure(explosionPos, tc.box, phantomObsidianPos);
        return targetDamage(tc, dist, exposure);
    }

    private float maxDamage(TargetCache tc, Vec3 explosionPos) {
        double dist = tc.pos.distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        return targetDamage(tc, dist, 1.0);
    }

    private float targetDamage(TargetCache tc, double dist, double exposure) {
        if (exposure <= 0) return 0;
        double impact = (1.0 - dist / 12.0) * exposure;
        if (impact <= 0) return 0;

        float dmg = baseExplosionDamage(impact);
        dmg = armorAbsorb(dmg, tc.armor, tc.toughness);
        dmg = CombatRules.getDamageAfterMagicAbsorb(dmg, tc.protPoints);
        return Math.max(dmg, 0f);
    }

    private float maxDamageSelf(Vec3 explosionPos) {
        double dist = mc.player.position().distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        return selfDamage(dist, 1.0);
    }

    private float calcDamageSelf(Vec3 explosionPos, BlockPos phantomObsidianPos) {
        double dist = mc.player.position().distanceTo(explosionPos);
        if (dist > 12.0) return 0;
        double exposure = calcExposure(explosionPos, mc.player.getBoundingBox(), phantomObsidianPos);
        return selfDamage(dist, exposure);
    }

    private float selfDamage(double dist, double exposure) {
        if (exposure <= 0) return 0;
        double impact = (1.0 - dist / 12.0) * exposure;
        if (impact <= 0) return 0;

        float dmg = baseExplosionDamage(impact);
        float armor     = (float) mc.player.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) mc.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        dmg = armorAbsorb(dmg, armor, toughness);
        dmg = selfProtectionAbsorb(dmg);
        return Math.max(dmg, 0f);
    }

    private float baseExplosionDamage(double impact) {
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (mc.level.getDifficulty()) {
            case EASY -> dmg = Math.min(dmg / 2f + 1f, dmg);
            case HARD -> dmg *= 1.5f;
        }
        return dmg;
    }

    private boolean selfDamageOk(Vec3 crystalCenter, BlockPos phantomObsidianPos,
                                 double maxSelf, float playerHealth) {
        float optimistic = maxDamageSelf(crystalCenter);
        if (optimistic <= maxSelf && optimistic + 1.5f < playerHealth) return true;

        float real = calcDamageSelf(crystalCenter, phantomObsidianPos);
        if (real > maxSelf) return false;
        if (NO_SELF_POP && real + 1.5f >= playerHealth) return false;
        return true;
    }

    private float armorAbsorb(float dmg, float armor, float toughness) {
        float i = 2.0f + toughness / 4.0f;
        float j = Mth.clamp(armor - dmg / i, armor * 0.2f, 20.0f);
        return dmg * (1.0f - j / 25.0f);
    }

    private float selfProtectionAbsorb(float dmg) {
        int epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            epf += EnchantmentUtil.getLevel(Enchantments.PROTECTION, stack);
            epf += 2 * EnchantmentUtil.getLevel(Enchantments.BLAST_PROTECTION, stack);
        }
        if (epf > 20) epf = 20;
        return CombatRules.getDamageAfterMagicAbsorb(dmg, epf);
    }

    private double calcExposure(Vec3 source, AABB box, BlockPos phantomObsidianPos) {
        double dx = box.getXsize();
        double dy = box.getYsize();
        double dz = box.getZsize();
        int steps = 2;
        int total = 0;
        int unblocked = 0;

        ExposureContext ctx = exposureCtx;
        BiFunction<ExposureContext, BlockPos, BlockHitResult> tester = (c, pos) -> {
            Vec3 from = c.start, to = c.end;
            if (phantomObsidianPos != null && pos.equals(phantomObsidianPos)) {
                return Shapes.block().clip(from, to, pos);
            }

            if (!seeThrough.isEmpty() && seeThrough.contains(pos)) return null;
            BlockState state = getStateFast(pos);
            if (state.isAir()) return null;

            if (state.getBlock().getExplosionResistance() < EXPLODABLE_RESISTANCE) return null;

            VoxelShape shape = state.getCollisionShape(mc.level, pos);
            if (shape.isEmpty()) return null;
            return shape.clip(from, to, pos);
        };

        for (int xi = 0; xi <= steps; xi++) {
            for (int yi = 0; yi <= steps; yi++) {
                for (int zi = 0; zi <= steps; zi++) {
                    Vec3 point = new Vec3(
                        box.minX + dx * xi / steps,
                        box.minY + dy * yi / steps,
                        box.minZ + dz * zi / steps
                    );
                    ctx.set(point, source);
                    if (BlockGetter.traverseBlocks(point, source, ctx, tester, c -> null) == null) unblocked++;
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (double) unblocked / total;
    }

    private static final class ExposureContext {
        private Vec3 start = Vec3.ZERO;
        private Vec3 end = Vec3.ZERO;

        void set(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
        }
    }

    private double getDynamicMin(float health, float absorption, boolean armorBroken) {
        double min = minDamage.getValue();
        float hp = health + absorption;
        if ((hp / 36.0f) <= HEALTH_BALANCE) min /= BALANCE;
        if (armorBroken) min /= BALANCE;
        return min;
    }

    private boolean isAnyArmorBroken(Player player) {
        int threshold = (int) (ARMOR_BALANCE * 100);
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || stack.getMaxDamage() <= 0) continue;
            int durabilityPct = 100 - (stack.getDamageValue() * 100 / stack.getMaxDamage());
            if (durabilityPct < threshold) return true;
        }
        return false;
    }

    private Vec3 getClosestPointToEye(Vec3 eye, AABB box) {
        double x = eye.x, y = eye.y, z = eye.z;
        final double VEC = 1.0 / 16.0;
        final double EPS = 1e-9;

        if (eye.x < box.minX) x = box.minX;
        else if (eye.x > box.maxX) x = box.maxX;
        if (eye.y < box.minY) y = box.minY;
        else if (eye.y > box.maxY) y = box.maxY;
        if (eye.z < box.minZ) z = box.minZ;
        else if (eye.z > box.maxZ) z = box.maxZ;

        if (Math.abs(x - box.minX) < EPS) x = Math.min(box.minX + VEC, box.maxX - EPS);
        else if (Math.abs(x - box.maxX) < EPS) x = Math.max(box.maxX - VEC, box.minX + EPS);
        if (Math.abs(z - box.minZ) < EPS) z = Math.min(box.minZ + VEC, box.maxZ - EPS);
        else if (Math.abs(z - box.maxZ) < EPS) z = Math.max(box.maxZ - VEC, box.minZ + EPS);

        return new Vec3(x, y, z);
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    public float getLastBestDamage() {
        return lastBestDamage;
    }

    public double getMinDamage() {
        return minDamage.getValue();
    }

    @Override
    public String getDisplayInfo() {
        return lastBestDamage > 0 ? String.format("%.1f", lastBestDamage) : null;
    }

    private final java.util.ArrayDeque<Long> explodeTimes = new java.util.ArrayDeque<>();

    @Subscribe
    private void onExplodePacket(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundExplodePacket)) return;
        long now = System.currentTimeMillis();
        explodeTimes.addLast(now);
        while (!explodeTimes.isEmpty() && now - explodeTimes.peekFirst() > 1000) explodeTimes.removeFirst();
    }

    @Override
    public String getMeta() {
        long now = System.currentTimeMillis();
        while (!explodeTimes.isEmpty() && now - explodeTimes.peekFirst() > 1000) explodeTimes.removeFirst();
        return String.valueOf(explodeTimes.size());
    }

    private record PlaceTarget(BlockPos base, float damage) {}
    private record BasePlaceTarget(BlockPos base, float damage) {}
    private record PlaceCandidate(BlockPos base, BlockPos airPos, Vec3 crystalCenter, float bound) {}

    private static final float EXPLODABLE_RESISTANCE = 600.0f;

    private static final double ANTI_CHINESE_RADIUS = 3.0;

    private BlockState getStateFast(BlockPos pos) {
        if (mc.level.isOutsideBuildHeight(pos.getY())) return Blocks.VOID_AIR.defaultBlockState();
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (cachedChunkX != cx || cachedChunkZ != cz || cachedChunk == null) {
            cachedChunk = mc.level.getChunk(cx, cz);
            cachedChunkX = cx;
            cachedChunkZ = cz;
        }
        ChunkAccess chunk = cachedChunk;
        if (chunk == null) return Blocks.AIR.defaultBlockState();
        int sectionIdx = mc.level.getSectionIndex(pos.getY());
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIdx < 0 || sectionIdx >= sections.length) return Blocks.AIR.defaultBlockState();
        LevelChunkSection section = sections[sectionIdx];
        if (section == null || section.hasOnlyAir()) return Blocks.AIR.defaultBlockState();
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private Vec3 bestReachEye(AABB box) {
        Vec3 base = mc.player.getEyePosition(1.0f);
        float scale = (float) mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        double standing = 1.62 * scale;
        double sneaking = 1.27 * scale;
        double swimming = 0.4  * scale;

        Vec3 pos = mc.player.position();
        double[] heights;
        Pose pose = mc.player.getPose();
        switch (pose) {
            case FALL_FLYING:
            case SPIN_ATTACK:
            case SWIMMING:    heights = new double[]{swimming, standing, sneaking}; break;
            case CROUCHING:   heights = new double[]{sneaking, standing, swimming}; break;
            default:          heights = new double[]{standing, sneaking, swimming};
        }

        Vec3 bestEye = base;
        double bestSq = sqDistToBox(base, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        for (double h : heights) {
            Vec3 e = new Vec3(pos.x, pos.y + h, pos.z);
            double sq = sqDistToBox(e, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            if (sq < bestSq) { bestSq = sq; bestEye = e; }
        }
        return bestEye;
    }
}
