package dev.leonetic.features.modules.hud;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.util.EnchantmentUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

public class ArmorHudModule extends HudModule {
    private static final int ICON = 16;
    private static final int STRIDE = 18;
    private static final int HOTBAR_HALF = 91;
    private static final int HUNGER_TOP_OFFSET = 39;
    private static final int BUBBLE_HEIGHT = 10;
    private static final float TAG_SCALE = 0.8f;

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private record ProtTag(ResourceKey<Enchantment> key, String label) {}

    private static final ProtTag[] PROT_TAGS = {
            new ProtTag(Enchantments.PROTECTION, "P"),
            new ProtTag(Enchantments.BLAST_PROTECTION, "BP"),
            new ProtTag(Enchantments.FIRE_PROTECTION, "FP"),
            new ProtTag(Enchantments.PROJECTILE_PROTECTION, "PP"),
    };

    public ArmorHudModule() {
        super("Armor");
    }

    @Override
    public void render(Render2DEvent event) {
        int totalWidth = SLOTS.length * STRIDE - (STRIDE - ICON);
        int rightX = screenWidth() / 2 + HOTBAR_HALF;
        int x = rightX - totalWidth;
        int y = screenHeight() - HUNGER_TOP_OFFSET - 1 - ICON;
        if (showingBubbles()) y -= BUBBLE_HEIGHT;

        GuiGraphics ctx = event.getContext();
        for (int i = 0; i < SLOTS.length; i++) {
            int sx = x + i * STRIDE;
            ItemStack stack = mc.player.getItemBySlot(SLOTS[i]);
            if (stack.isEmpty()) continue;

            ctx.renderItem(stack, sx, y);
            ctx.renderItemDecorations(mc.font, stack, sx, y);

            String tag = protectionTag(stack);
            if (tag != null) {

                ctx.pose().pushMatrix();
                ctx.pose().scale(TAG_SCALE, TAG_SCALE);
                ctx.drawString(mc.font, tag,
                        Math.round(sx / TAG_SCALE), Math.round(y / TAG_SCALE),
                        0xFFFFFFFF, true);
                ctx.pose().popMatrix();
            }
        }
    }

    private String protectionTag(ItemStack stack) {
        for (ProtTag t : PROT_TAGS) {
            if (EnchantmentUtil.has(t.key(), stack)) return t.label();
        }
        return null;
    }

    private boolean showingBubbles() {
        return mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply();
    }
}
