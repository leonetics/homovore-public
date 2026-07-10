package dev.leonetic.features.modules.hud;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.modules.client.TestLimitModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

public class TestLimitHudModule extends HudModule {
    private static final int LEFT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private static final long WINDOW_MS = 1000;
    private static final int EXPECTED_MOVES_PER_SECOND = 20;

    private long windowStart = System.currentTimeMillis();
    private int windowCount = 0;
    private int lastCount = 0;

    public TestLimitHudModule() {
        super("TestLimit");
        EVENT_BUS.register(this);
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) return;
        rollWindow();
        windowCount++;
    }

    private void rollWindow() {
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MS) return;

        lastCount = windowCount;
        windowCount = 0;
        windowStart = now;
    }

    @Override
    public void render(Render2DEvent event) {
        rollWindow();

        GuiGraphics ctx = event.getContext();
        int ry = bottomAnchor() - BOTTOM_MARGIN - mc.font.lineHeight;

        HudClientModule hud = Homovore.moduleManager.getModuleByClass(HudClientModule.class);
        if (hud != null && hud.isElementEnabled(SpeedHudModule.class)) {
            ry -= mc.font.lineHeight;
        }

        drawLine(ctx, String.valueOf(lastCount), "/" + EXPECTED_MOVES_PER_SECOND + " move", ry);

        TestLimitModule limit = TestLimitModule.get();
        if (limit != null && limit.isEnabled()) {
            drawLine(ctx, String.valueOf(limit.count()), "/" + limit.getLimit() + " click", ry - mc.font.lineHeight);
        }
    }

    private void drawLine(GuiGraphics ctx, String value, String label, int y) {
        ctx.drawString(mc.font, value, LEFT_MARGIN, y, GRAY);
        ctx.drawString(mc.font, label, LEFT_MARGIN + mc.font.width(value), y, WHITE);
    }
}
