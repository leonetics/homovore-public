package dev.leonetic.features.modules.movement;

import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.player.ChatUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

public class SpeedModule extends Module {

    public final Setting<Float> collisionDistance = num("CollisionDistance", 1.5f, 0.5f, 2.0f);
    public final Setting<Float> boostPerCollision = num("BoostPerCollision", 0.08f, 0.01f, 0.2f);

    private int debugTicks = 0;

    public SpeedModule() {
        super("Speed", "Boosts your movement off entity collisions", Category.MOVEMENT);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;

        boolean moving = isInputtingMovement();
        int collisions = countCollidingEntities();
        int nearest = nearestLivingDistance();

        if (++debugTicks % 20 == 0) {
            ChatUtil.sendMessage(Component.literal("[speed] moving=" + moving
                    + " collisions=" + collisions
                    + " nearest=" + (nearest < 0 ? "none" : nearest / 100.0)
                    + " vel=" + mc.player.getDeltaMovement().horizontalDistance()));
        }

        if (!moving || collisions <= 0) return;

        double[] motion = MathUtil.directionSpeed(boostPerCollision.getValue() * collisions);
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x + motion[0], velocity.y, velocity.z + motion[1]);
    }

    private int nearestLivingDistance() {
        double best = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isCollidable(entity)) continue;
            best = Math.min(best, Math.sqrt(mc.player.distanceToSqr(entity)));
        }
        return best == Double.MAX_VALUE ? -1 : (int) (best * 100);
    }

    private int countCollidingEntities() {
        double range = collisionDistance.getValue();
        int collisions = 0;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isCollidable(entity)) continue;
            if (mc.player.distanceToSqr(entity) <= range * range) collisions++;
        }

        return collisions;
    }

    private boolean isCollidable(Entity entity) {
        if (entity == null || entity == mc.player) return false;
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand);
    }

    private boolean isInputtingMovement() {
        return mc.player.input.getMoveVector().x != 0.0f || mc.player.input.getMoveVector().y != 0.0f;
    }
}
