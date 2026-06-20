package dev.leonetic.util;

import dev.leonetic.util.traits.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

public final class DamageUtil implements Util {

    private DamageUtil() {
        throw new AssertionError();
    }

    public static float crystalMaxSelfDamage(Vec3 explosionPos) {
        if (mc.player == null || mc.level == null) return 0f;
        double dist = mc.player.position().distanceTo(explosionPos);
        if (dist > 12.0) return 0f;
        double impact = 1.0 - dist / 12.0;
        if (impact <= 0) return 0f;

        float dmg = baseExplosionDamage(impact);

        float armor = (float) mc.player.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) mc.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        dmg = armorAbsorb(dmg, armor, toughness);

        MobEffectInstance resistance = mc.player.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) dmg *= 1.0f - 0.2f * (resistance.getAmplifier() + 1);

        dmg = protectionAbsorb(dmg);
        return Math.max(dmg, 0f);
    }

    private static float baseExplosionDamage(double impact) {
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (mc.level.getDifficulty()) {
            case EASY -> dmg = Math.min(dmg / 2f + 1f, dmg);
            case HARD -> dmg *= 1.5f;
            default -> { }
        }
        return dmg;
    }

    private static float armorAbsorb(float dmg, float armor, float toughness) {
        float i = 2.0f + toughness / 4.0f;
        float j = Mth.clamp(armor - dmg / i, armor * 0.2f, 20.0f);
        return dmg * (1.0f - j / 25.0f);
    }

    private static float protectionAbsorb(float dmg) {
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
}
