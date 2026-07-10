package dev.leonetic.features.modules.hud;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CountsHudModule extends HudModule {
    private static final int ICON = 16;
    private static final float ICON_SCALE = 0.85f;
    private static final float RENDER_ICON = ICON * ICON_SCALE;
    private static final int HOTBAR_HALF = 91;
    private static final int HUNGER_TOP_OFFSET = 39;
    private static final int BUBBLE_HEIGHT = 10;
    private static final int GAP = 2;
    private static final int TEXT_PAD = 2;

    private static final Item[] ITEMS = {
            Items.END_CRYSTAL,
            Items.OBSIDIAN,
            Items.ENDER_PEARL,
            Items.EXPERIENCE_BOTTLE,
    };

    public CountsHudModule() {
        super("Counts");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics ctx = event.getContext();

        int x = screenWidth() / 2 + HOTBAR_HALF + GAP;
        int yTop = screenHeight() - HUNGER_TOP_OFFSET - 1 - ICON;
        if (showingBubbles()) yTop -= BUBBLE_HEIGHT;

        for (int i = 0; i < ITEMS.length; i++) {
            int y = yTop + Math.round(i * RENDER_ICON);
            int count = countItem(ITEMS[i]);

            ItemStack stack = new ItemStack(ITEMS[i], Math.max(count, 1));
            drawIcon(ctx, stack, x, y);
            drawCount(ctx, count, x, y);
        }
    }

    private void drawIcon(GuiGraphics ctx, ItemStack stack, int x, int y) {

        ctx.pose().pushMatrix();
        ctx.pose().scale(ICON_SCALE, ICON_SCALE);
        ctx.renderItem(stack, Math.round(x / ICON_SCALE), Math.round(y / ICON_SCALE));
        ctx.pose().popMatrix();
    }

    private static final int GLYPH_HEIGHT = 8;

    private void drawCount(GuiGraphics ctx, int count, int x, int y) {

        String label = String.valueOf(count);
        int drawX = Math.round(x + RENDER_ICON + TEXT_PAD);
        int drawY = Math.round(y + (RENDER_ICON - GLYPH_HEIGHT) / 2f);
        ctx.drawString(mc.font, label, drawX, drawY,
                count > 0 ? 0xFFFFFFFF : 0xFFFF5555, true);
    }

    private int countItem(Item item) {
        int total = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    private boolean showingBubbles() {
        return mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply();
    }
}
