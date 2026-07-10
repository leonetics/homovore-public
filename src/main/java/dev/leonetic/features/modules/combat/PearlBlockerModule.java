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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public class PearlBlockerModule extends Module {

    private final Setting<Boolean> render        = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime      = num("FadeTime", 1.0f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor     = color("FillColor", 255, 0, 0, 44).setPage("Render");
    private final Setting<Color>   outlineColor  = color("OutlineColor", 255, 0, 0, 44).setPage("Render");

    private final Map<Integer, BlockPos> tracked = new HashMap<>();
    private final Map<BlockPos, Long> renderMap = new HashMap<>();

    public PearlBlockerModule() {
        super("PearlBlocker", "Catches enemy pearls by placing obsidian in their flight path.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        tracked.clear();
        renderMap.clear();
    }

    @Override
    public void onDisable() {
        tracked.clear();
        renderMap.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;

        var obs = InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.PLACE_SCOPE);
        if (!obs.found() || obs.type() == ResultType.OFFHAND) return;
        int obsSlot = obs.slot();

        long now = System.currentTimeMillis();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl) || pearl.isRemoved()) continue;

            if (!(pearl.getOwner() instanceof Player owner)) continue;
            if (owner == mc.player) continue;
            if (Homovore.friendManager.isFriend(owner)) continue;

            if (pearl.isInWater() && pearl.getDeltaMovement().length() < 0.015) continue;

            int id = pearl.getId();

            BlockPos prev = tracked.get(id);
            if (prev != null && !mc.level.getBlockState(prev).canBeReplaced()) continue;

            BlockPos target = findPlaceablePosInPath(pearl);
            if (target == null) continue;

            if (!PlaceUtil.canPlace(target)) continue;
            if (Homovore.placementManager.enqueue(target, obsSlot)) {
                renderMap.put(target.immutable(), now);
            }

            tracked.put(id, target);
        }

        tracked.entrySet().removeIf(entry -> {
            Entity found = mc.level.getEntity(entry.getKey());
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
    private static final int    SUB_SAMPLES       = 8;
    private static final double GRAVITY           = 0.03;
    private static final double AIR_DRAG          = 0.99;
    private static final double WATER_DRAG        = 0.8;

    private BlockPos findPlaceablePosInPath(ThrownEnderpearl pearl) {
        Vec3 pos = pearl.position();
        Vec3 vel = pearl.getDeltaMovement();
        boolean inWater = pearl.isInWater();

        Vec3 eye = mc.player.getEyePosition();
        for (int t = 0; t < MAX_FORWARD_TICKS; t++) {
            Vec3 next = pos.add(vel);
            for (int s = 1; s <= SUB_SAMPLES; s++) {
                Vec3 sample = pos.add(next.subtract(pos).scale(s / (double) SUB_SAMPLES));
                BlockPos blockPos = BlockPos.containing(sample);
                if (eye.distanceTo(sample) <= 5.1
                        && mc.level.getBlockState(blockPos).canBeReplaced()
                        && PlaceUtil.canPlace(blockPos)) {
                    return blockPos;
                }
            }
            pos = next;
            vel = stepVelocity(vel, inWater);
            if (pos.y < mc.level.getMinY() - 5) break;
        }
        return null;
    }

    private static Vec3 stepVelocity(Vec3 vel, boolean inWater) {
        double drag = inWater ? WATER_DRAG : AIR_DRAG;
        return new Vec3(vel.x * drag, vel.y * drag - GRAVITY, vel.z * drag);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }
}
