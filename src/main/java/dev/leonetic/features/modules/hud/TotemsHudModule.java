package dev.leonetic.features.modules.hud;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudModule;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class TotemsHudModule extends HudModule {
    private static final int ICON = 16;
    private static final int XP_BAR_TOP_OFFSET = 32;
    private static final int GAP_ABOVE_XP = 6;

    public TotemsHudModule() {
        super("Totems");
    }

    @Override
    public void render(Render2DEvent event) {
        int count = countTotems();

        int x = screenWidth() / 2 - ICON / 2;
        int y = screenHeight() - XP_BAR_TOP_OFFSET - GAP_ABOVE_XP - ICON;

        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, Math.max(count, 1));
        event.getContext().renderItem(stack, x, y);
        if (count > 0) {
            event.getContext().renderItemDecorations(mc.font, stack, x, y, String.valueOf(count));
        } else {
            String label = "0";
            int tx = x + 19 - 2 - mc.font.width(label);
            int ty = y + 6 + 3;
            event.getContext().drawString(mc.font, label, tx, ty, 0xFFFF5555);
        }
    }

    private int countTotems() {
        int total = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.TOTEM_OF_UNDYING)) total += s.getCount();
        }
        return total;
    }
}
