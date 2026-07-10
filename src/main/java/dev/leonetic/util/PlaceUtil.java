package dev.leonetic.util;

import dev.leonetic.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.phys.AABB;

public final class PlaceUtil implements Util {

    private PlaceUtil() {
        throw new AssertionError();
    }

    public static int minCell(double boxMin) {
        return net.minecraft.util.Mth.floor(boxMin);
    }

    public static int maxCell(double boxMax) {
        return net.minecraft.util.Mth.ceil(boxMax) - 1;
    }

    public static boolean canPlace(BlockPos pos) {
        if (mc.level.isOutsideBuildHeight(pos)) return false;
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        if (net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceTo(mc.player.getEyePosition()) > 5.154) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;
        return noEntityCollision(pos);
    }

    public static boolean noEntityCollision(BlockPos pos) {
        for (Entity e : mc.level.getEntitiesOfClass(
                Entity.class,
                new AABB(pos),
                e -> !(e instanceof ItemEntity
                    || e instanceof ExperienceOrb
                    || e instanceof ThrownExperienceBottle
                    || e instanceof FireworkRocketEntity
                    || e instanceof EndCrystal
                    || e == mc.player))) {
            return false;
        }
        return true;
    }
}
