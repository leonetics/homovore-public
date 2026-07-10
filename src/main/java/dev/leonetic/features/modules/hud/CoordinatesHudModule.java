package dev.leonetic.features.modules.hud;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.modules.client.HudPosition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;

public class CoordinatesHudModule extends HudModule {
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
        HudPosition pos = hudClient != null ? hudClient.positionOf(this) : HudPosition.BOTTOM_RIGHT;
        int linesBelow = hudClient != null ? hudClient.linesBelow(this) : 0;

        int rx = lineX(pos, totalWidth);
        int ry = blockTop(pos, 1, linesBelow, 0);

        ctx.drawString(mc.font, main, rx, ry, WHITE);
        if (!other.isEmpty()) {
            ctx.drawString(mc.font, other, rx + mc.font.width(main), ry, GRAY);
        }
    }
}
