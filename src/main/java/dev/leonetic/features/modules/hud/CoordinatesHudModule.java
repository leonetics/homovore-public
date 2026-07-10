package dev.leonetic.features.modules.hud;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;

public class CoordinatesHudModule extends HudModule {
    private static final int RIGHT_MARGIN = 2;
    private static final int LEFT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    public CoordinatesHudModule() {
        super("Coordinates");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics ctx = event.getContext();

        int x = (int) Math.floor(mc.player.getX());
        int y = (int) Math.floor(mc.player.getY());
        int z = (int) Math.floor(mc.player.getZ());

        boolean nether = mc.level.dimension().equals(Level.NETHER);
        boolean end = mc.level.dimension().equals(Level.END);

        String main = x + ", " + y + ", " + z + (end ? "" : " ");
        String other;
        if (end) {
            other = "";
        } else if (nether) {
            other = "[" + (x * 8) + ", " + (z * 8) + "]";
        } else {
            other = "[" + Math.floorDiv(x, 8) + ", " + Math.floorDiv(z, 8) + "]";
        }

        int totalWidth = mc.font.width(main) + mc.font.width(other);
        HudClientModule hudClient = Homovore.moduleManager.getModuleByClass(HudClientModule.class);
        boolean left = hudClient != null && hudClient.coordinatesLeft.getValue();
        int rx = left ? LEFT_MARGIN : screenWidth() - RIGHT_MARGIN - totalWidth;
        int ry = bottomAnchor() - BOTTOM_MARGIN - mc.font.lineHeight;

        ctx.drawString(mc.font, main, rx, ry, WHITE);
        if (!other.isEmpty()) {
            ctx.drawString(mc.font, other, rx + mc.font.width(main), ry, GRAY);
        }
    }
}
