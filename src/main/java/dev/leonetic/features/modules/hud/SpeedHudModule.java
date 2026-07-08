package dev.leonetic.features.modules.hud;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudModule;
import net.minecraft.client.gui.GuiGraphics;

public class SpeedHudModule extends HudModule {
    private static final int LEFT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    public SpeedHudModule() {
        super("Speed");
    }

    @Override
    public void render(Render2DEvent event) {
        GuiGraphics ctx = event.getContext();

        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        double bps = Math.sqrt(dx * dx + dz * dz) * 20.0;

        String value = String.format("%.1f", bps);
        String unit = " bps";

        int ry = bottomAnchor() - BOTTOM_MARGIN - mc.font.lineHeight;
        ctx.drawString(mc.font, value, LEFT_MARGIN, ry, GRAY);
        ctx.drawString(mc.font, unit, LEFT_MARGIN + mc.font.width(value), ry, WHITE);
    }
}
