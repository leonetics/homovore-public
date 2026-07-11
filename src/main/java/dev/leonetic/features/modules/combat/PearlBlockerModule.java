package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PearlBlockerModule extends Module {

    private final Setting<Boolean> render        = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime      = num("FadeTime", 1.0f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor     = color("FillColor", 255, 0, 0, 44).setPage("Render");
    private final Setting<Color>   outlineColor  = color("OutlineColor", 255, 0, 0, 44).setPage("Render");
    private final Setting<Boolean> debug         = bool("Debug", false).setPage("Extra");

    private final Set<Integer> handled = new HashSet<>();
    private final Map<BlockPos, Long> renderMap = new HashMap<>();

    public PearlBlockerModule() {
        super("PearlBlocker", "Catches enemy pearls by placing obsidian in their flight path.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        handled.clear();
        renderMap.clear();
    }

    @Override
    public void onDisable() {
        handled.clear();
        renderMap.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;

        var obs = InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.PLACE_SCOPE);
        if (!obs.found() || obs.type() == ResultType.OFFHAND) return;
        int obsSlot = obs.slot();

        long now = System.currentTimeMillis();
        List<Interception> interceptions = new ArrayList<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl) || pearl.isRemoved()) continue;

            if (!(pearl.getOwner() instanceof Player owner)) continue;
            if (owner == mc.player) continue;
            if (Homovore.friendManager.isFriend(owner)) continue;

            if (pearl.isInWater() && pearl.getDeltaMovement().length() < 0.015) continue;

            int id = pearl.getId();
            if (handled.contains(id)) continue;

            PredictionTrace trace = debug.getValue() ? new PredictionTrace() : null;
            Interception candidate = findInterception(pearl, trace);
            if (trace != null) logPrediction(pearl, candidate, trace);
            if (candidate == null) continue;
            interceptions.add(candidate);
        }

        interceptions.sort((a, b) -> {
            int remaining = Integer.compare(b.remainingTicks, a.remainingTicks);
            if (remaining != 0) return remaining;
            int arrival = Integer.compare(a.arrivalTicks, b.arrivalTicks);
            if (arrival != 0) return arrival;
            return Integer.compare(a.pearl.tickCount, b.pearl.tickCount);
        });
        for (Interception interception : interceptions) {
            boolean sent = Homovore.placementManager.placeDirect(interception.pos, interception.face, obsSlot);
            if (debug.getValue()) {
                Homovore.LOGGER.info(
                        "[PearlBlocker] place id={} pos={} face={} arrival={} remaining={} sent={}",
                        interception.pearl.getId(), interception.pos, interception.face,
                        interception.arrivalTicks, interception.remainingTicks, sent);
            }
            if (!sent) continue;
            handled.add(interception.pearl.getId());
            renderMap.put(interception.pos, now);
            break;
        }

        handled.removeIf(id -> {
            Entity found = mc.level.getEntity(id);
            return found == null || found.isRemoved();
        });

        long fadeMs = (long) (fadeTime.getValue() * 1000);
        renderMap.entrySet().removeIf(e -> now - e.getValue() > fadeMs);
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

    private static final int    MAX_FORWARD_TICKS = 60;
    private static final int    MIN_PLACEMENT_LEAD_TICKS = 3;
    private static final int    MIN_REMAINING_FLIGHT_TICKS = 2;
    private static final double GRAVITY           = 0.03;
    private static final double AIR_DRAG          = 0.99;
    private static final double WATER_DRAG        = 0.8;

    private Interception findInterception(ThrownEnderpearl pearl, PredictionTrace trace) {
        Vec3 pos = pearl.position();
        Vec3 vel = pearl.getDeltaMovement();
        boolean inWater = pearl.isInWater();
        Interception candidate = null;

        for (int t = 0; t < MAX_FORWARD_TICKS; t++) {
            vel = stepVelocity(vel, inWater);
            Vec3 next = pos.add(vel);
            HitResult collision = findCollision(pearl, pos, next, t == 0);
            Vec3 pathEnd = collision.getType() == HitResult.Type.MISS ? next : collision.getLocation();

            int arrivalTicks = t + 1;
            if (candidate == null && arrivalTicks >= MIN_PLACEMENT_LEAD_TICKS) {
                PlacementCell cell = findPlacementCell(pos, pathEnd, trace);
                if (cell != null) {
                    candidate = new Interception(pearl, cell.pos, cell.face, arrivalTicks, 0);
                    if (trace != null) {
                        trace.candidatePos = cell.pos;
                        trace.candidateFace = cell.face;
                        trace.candidateTick = arrivalTicks;
                    }
                }
            }

            if (collision.getType() != HitResult.Type.MISS) {
                if (trace != null) {
                    trace.impactType = collision.getType();
                    trace.impactPos = collision.getLocation();
                    trace.impactTick = arrivalTicks;
                }
                if (candidate == null) {
                    if (trace != null) trace.result = "no_candidate_before_impact";
                    return null;
                }
                int remainingTicks = arrivalTicks - candidate.arrivalTicks;
                if (remainingTicks < MIN_REMAINING_FLIGHT_TICKS) {
                    if (trace != null) trace.result = "candidate_too_close_to_impact";
                    return null;
                }
                if (trace != null) trace.result = "ready";
                return new Interception(pearl, candidate.pos, candidate.face,
                        candidate.arrivalTicks, remainingTicks);
            }

            pos = next;
            inWater = mc.level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
            if (pos.y < mc.level.getMinY() - 5) {
                if (trace != null) trace.result = "below_world";
                return null;
            }
        }
        if (trace != null) trace.result = "no_impact_within_horizon";
        return null;
    }

    private HitResult findCollision(ThrownEnderpearl pearl, Vec3 from, Vec3 to, boolean includeEntities) {
        HitResult blockHit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pearl));
        if (!includeEntities) return blockHit;

        AABB predictedBox = pearl.getBoundingBox().move(from.subtract(pearl.position()));
        HitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.level, pearl, from, to, predictedBox.expandTowards(to.subtract(from)).inflate(1.0),
                entity -> entity != pearl.getOwner()
                        && entity.isAlive()
                        && !entity.isSpectator()
                        && entity.isPickable());
        if (entityHit == null) return blockHit;
        if (blockHit.getType() == HitResult.Type.MISS
                || from.distanceToSqr(entityHit.getLocation()) < from.distanceToSqr(blockHit.getLocation())) {
            return entityHit;
        }
        return blockHit;
    }

    private PlacementCell findPlacementCell(Vec3 from, Vec3 to, PredictionTrace trace) {
        return BlockGetter.traverseBlocks(from, to, this, (module, pos) -> {
            if (trace != null) trace.visitedCells++;
            if (mc.level.isOutsideBuildHeight(pos)) {
                if (trace != null) trace.outsideBuildHeight++;
                return null;
            }
            if (!mc.level.getBlockState(pos).canBeReplaced()) {
                if (trace != null) trace.notReplaceable++;
                return null;
            }
            if (Vec3.atCenterOf(pos).distanceTo(mc.player.getEyePosition()) > 5.154) {
                if (trace != null) trace.outOfReach++;
                return null;
            }
            if (mc.player.getBoundingBox().intersects(new AABB(pos))) {
                if (trace != null) trace.playerCollision++;
                return null;
            }
            if (!PlaceUtil.canPlace(pos)) {
                if (trace != null) trace.entityCollision++;
                return null;
            }
            if (!mc.level.getBlockState(pos.below()).getCollisionShape(mc.level, pos.below()).isEmpty()) {
                if (trace != null) trace.grounded++;
                return null;
            }
            Direction face = Homovore.placementManager.getPlaceSide(pos);
            if (face == Direction.DOWN) {
                if (trace != null) trace.floorSupport++;
                return null;
            }
            if (trace != null) {
                trace.validCells++;
                if (face == null) trace.airPlaceCells++;
            }
            return new PlacementCell(pos.immutable(), face);
        }, module -> null);
    }

    private void logPrediction(ThrownEnderpearl pearl, Interception interception, PredictionTrace trace) {
        Vec3 pos = pearl.position();
        Vec3 vel = pearl.getDeltaMovement();
        Homovore.LOGGER.info(
                "[PearlBlocker] id={} owner={} age={} pos=({},{},{}) vel=({},{},{}) speed={} water={} result={} "
                        + "candidate={} face={} arrival={} impact={} impactPos={} impactTick={} remaining={} "
                        + "cells={} valid={} air={} reject[outside={},solid={},reach={},player={},entity={},ground={},floor={}]",
                pearl.getId(), pearl.getOwner() == null ? "unknown" : pearl.getOwner().getName().getString(),
                pearl.tickCount, format(pos.x), format(pos.y), format(pos.z),
                format(vel.x), format(vel.y), format(vel.z), format(vel.length()), pearl.isInWater(),
                trace.result, trace.candidatePos, trace.candidateFace, trace.candidateTick,
                trace.impactType, trace.impactPos == null ? null : formatVec(trace.impactPos), trace.impactTick,
                interception == null ? -1 : interception.remainingTicks,
                trace.visitedCells, trace.validCells, trace.airPlaceCells,
                trace.outsideBuildHeight, trace.notReplaceable,
                trace.outOfReach, trace.playerCollision, trace.entityCollision, trace.grounded,
                trace.floorSupport);
    }

    private static String formatVec(Vec3 vec) {
        return "(" + format(vec.x) + "," + format(vec.y) + "," + format(vec.z) + ")";
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static Vec3 stepVelocity(Vec3 vel, boolean inWater) {
        double drag = inWater ? WATER_DRAG : AIR_DRAG;
        return new Vec3(vel.x * drag, (vel.y - GRAVITY) * drag, vel.z * drag);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    private record PlacementCell(BlockPos pos, Direction face) {}

    private record Interception(ThrownEnderpearl pearl, BlockPos pos, Direction face,
                                int arrivalTicks, int remainingTicks) {}

    private static final class PredictionTrace {
        private String result = "searching";
        private BlockPos candidatePos;
        private Direction candidateFace;
        private int candidateTick = -1;
        private HitResult.Type impactType = HitResult.Type.MISS;
        private Vec3 impactPos;
        private int impactTick = -1;
        private int visitedCells;
        private int validCells;
        private int airPlaceCells;
        private int outsideBuildHeight;
        private int notReplaceable;
        private int outOfReach;
        private int playerCollision;
        private int entityCollision;
        private int grounded;
        private int floorSupport;
    }
}
