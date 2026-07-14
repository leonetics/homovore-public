package dev.leonetic.manager;

import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.DisconnectEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.Feature;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TurtleMasterManager extends Feature {

    private static final long EFFECT_PAIR_WINDOW_MS = 1500L;

    private final Map<UUID, TurtleData> active = new ConcurrentHashMap<>();
    private final Map<UUID, PendingEffects> pending = new HashMap<>();
    private final Map<Integer, TrackedPot> trackedPots = new HashMap<>();

    public void init() {
        EVENT_BUS.register(this);
    }

    @Subscribe
    public void onPacketReceive(PacketEvent.Receive event) {
        if (mc.level == null) return;

        if (event.getPacket() instanceof ClientboundUpdateMobEffectPacket packet) {
            handleUpdate(packet);
        } else if (event.getPacket() instanceof ClientboundRemoveMobEffectPacket packet) {
            handleRemove(packet);
        }
    }

    @Subscribe
    public void onPreTick(PreTickEvent event) {
        if (mc.level == null || mc.player == null) {
            resetAll();
            return;
        }

        long now = System.currentTimeMillis();
        trackPots(now);

        for (Player player : mc.level.players()) {
            observeLiveEffects(player, now);
        }

        prune(now);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resetAll();
    }

    private void handleUpdate(ClientboundUpdateMobEffectPacket packet) {
        Entity entity = mc.level.getEntity(packet.getEntityId());
        if (!(entity instanceof Player player)) return;

        Holder<MobEffect> effect = packet.getEffect();
        boolean resistance = effect.is(MobEffects.RESISTANCE);
        boolean slowness = effect.is(MobEffects.SLOWNESS);
        if (!resistance && !slowness) return;

        long now = System.currentTimeMillis();
        PendingEffects effects = pending.computeIfAbsent(player.getUUID(), ignored -> new PendingEffects());
        if (resistance) {
            effects.resistanceSeenMs = now;
            effects.resistanceDuration = packet.getEffectDurationTicks();
            effects.resistanceAmplifier = packet.getEffectAmplifier();
        } else {
            effects.slownessSeenMs = now;
            effects.slownessDuration = packet.getEffectDurationTicks();
            effects.slownessAmplifier = packet.getEffectAmplifier();
        }

        if (effects.hasPair(now)) {
            mark(player, effects.resistanceDuration, effects.resistanceAmplifier,
                    effects.slownessDuration, effects.slownessAmplifier, now);
        }
    }

    private void handleRemove(ClientboundRemoveMobEffectPacket packet) {
        Holder<MobEffect> effect = packet.effect();
        if (!effect.is(MobEffects.RESISTANCE) && !effect.is(MobEffects.SLOWNESS)) return;
        if (!(packet.getEntity(mc.level) instanceof Player player)) return;

        pending.remove(player.getUUID());
        active.remove(player.getUUID());
    }

    private void observeLiveEffects(Player player, long now) {
        if (player == null || player.isRemoved()) return;

        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        MobEffectInstance slowness = player.getEffect(MobEffects.SLOWNESS);
        if (resistance == null || slowness == null) return;

        mark(player, resistance.getDuration(), resistance.getAmplifier(),
                slowness.getDuration(), slowness.getAmplifier(), now);
    }

    private void trackPots(long now) {
        Set<Integer> alive = new HashSet<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractThrownPotion pot)) continue;

            PotionContents contents = pot.getItem().get(DataComponents.POTION_CONTENTS);
            if (contents == null) continue;

            MobEffectInstance resistance = null;
            MobEffectInstance slowness = null;
            for (MobEffectInstance effect : contents.getAllEffects()) {
                if (effect.getEffect().is(MobEffects.RESISTANCE)) {
                    resistance = effect;
                } else if (effect.getEffect().is(MobEffects.SLOWNESS)) {
                    slowness = effect;
                }
            }
            if (resistance == null || slowness == null) continue;

            alive.add(pot.getId());
            trackedPots.put(pot.getId(), new TrackedPot(
                    pot.position().add(pot.getDeltaMovement().scale(0.5)),
                    resistance.getDuration(), resistance.getAmplifier(),
                    slowness.getDuration(), slowness.getAmplifier()));
        }

        Iterator<Map.Entry<Integer, TrackedPot>> iterator = trackedPots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TrackedPot> entry = iterator.next();
            if (alive.contains(entry.getKey())) continue;

            iterator.remove();
            applySplash(entry.getValue(), now);
        }
    }

    private void applySplash(TrackedPot pot, long now) {
        AABB splashBox = AABB.ofSize(pot.pos(), 8.0, 4.0, 8.0);

        for (Player player : mc.level.players()) {
            if (player == null || player.isRemoved() || !player.isAlive()) continue;
            if (!player.getBoundingBox().intersects(splashBox)) continue;

            double distanceSq = player.distanceToSqr(pot.pos());
            if (distanceSq > 16.0) continue;

            double proximity = 1.0 - Math.sqrt(distanceSq) / 4.0;
            if (proximity <= 0.0) continue;

            int resistanceDuration = (int) (proximity * pot.resistanceDuration() + 0.5);
            int slownessDuration = (int) (proximity * pot.slownessDuration() + 0.5);
            if (resistanceDuration <= 20 && slownessDuration <= 20) continue;

            mark(player, resistanceDuration, pot.resistanceAmplifier(),
                    slownessDuration, pot.slownessAmplifier(), now);
        }
    }

    private void mark(Player player, int resistanceDuration, int resistanceAmplifier,
                      int slownessDuration, int slownessAmplifier, long now) {
        if (player == null) return;

        int duration = Math.min(normalize(resistanceDuration), normalize(slownessDuration));
        long expiresAtMs = duration == Integer.MAX_VALUE ? Long.MAX_VALUE : now + duration * 50L;

        active.put(player.getUUID(), new TurtleData(
                resistanceAmplifier, slownessAmplifier, expiresAtMs));
    }

    private int normalize(int durationTicks) {
        return durationTicks == MobEffectInstance.INFINITE_DURATION
                ? Integer.MAX_VALUE
                : Math.max(0, durationTicks);
    }

    private void prune(long now) {
        Set<UUID> present = new HashSet<>();
        for (Player player : mc.level.players()) {
            present.add(player.getUUID());
        }

        active.entrySet().removeIf(entry ->
                !present.contains(entry.getKey()) || now >= entry.getValue().expiresAtMs());

        pending.entrySet().removeIf(entry -> {
            PendingEffects effects = entry.getValue();
            long lastSeen = Math.max(effects.resistanceSeenMs, effects.slownessSeenMs);
            return now - lastSeen > EFFECT_PAIR_WINDOW_MS;
        });
    }

    public void resetAll() {
        active.clear();
        pending.clear();
        trackedPots.clear();
    }

    public boolean hasTurtleMaster(Player player) {
        return player != null && hasTurtleMaster(player.getUUID());
    }

    public boolean hasTurtleMaster(UUID uuid) {
        TurtleData data = uuid == null ? null : active.get(uuid);
        return data != null && System.currentTimeMillis() < data.expiresAtMs();
    }

    public int getRemainingSeconds(UUID uuid) {
        TurtleData data = uuid == null ? null : active.get(uuid);
        if (data == null) return 0;
        if (data.expiresAtMs() == Long.MAX_VALUE) return Integer.MAX_VALUE;

        long remainingMs = data.expiresAtMs() - System.currentTimeMillis();
        return Math.max(0, (int) Math.ceil(remainingMs / 1000.0));
    }

    public long getExpiryMs(UUID uuid) {
        TurtleData data = uuid == null ? null : active.get(uuid);
        return data == null ? 0L : data.expiresAtMs();
    }

    public int getResistanceAmplifier(UUID uuid) {
        TurtleData data = uuid == null ? null : active.get(uuid);
        return data == null ? -1 : data.resistanceAmplifier();
    }

    public int getSlownessAmplifier(UUID uuid) {
        TurtleData data = uuid == null ? null : active.get(uuid);
        return data == null ? -1 : data.slownessAmplifier();
    }

    private record TurtleData(int resistanceAmplifier, int slownessAmplifier, long expiresAtMs) {}

    private record TrackedPot(Vec3 pos, int resistanceDuration, int resistanceAmplifier,
                              int slownessDuration, int slownessAmplifier) {}

    private static class PendingEffects {
        private long resistanceSeenMs;
        private long slownessSeenMs;
        private int resistanceDuration;
        private int slownessDuration;
        private int resistanceAmplifier;
        private int slownessAmplifier;

        private boolean hasPair(long now) {
            return resistanceSeenMs > 0L
                    && slownessSeenMs > 0L
                    && now - resistanceSeenMs <= EFFECT_PAIR_WINDOW_MS
                    && now - slownessSeenMs <= EFFECT_PAIR_WINDOW_MS;
        }
    }
}
