package dev.leonetic.features.debug;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.hud.SpeedHudModule;
import dev.leonetic.util.traits.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

public class MoveLimitDebug implements Util {
    private static final MoveLimitDebug INSTANCE = new MoveLimitDebug();

    private static final int LEFT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;
    private static final int CHAT_INPUT_HEIGHT = 14;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private static final long WINDOW_MS = 1000;
    private static final int EXPECTED_MOVES_PER_SECOND = 20;

    private boolean enabled = false;
    private long windowStart = System.currentTimeMillis();
    private int windowCount = 0;
    private int lastCount = 0;

    private MoveLimitDebug() {
        EVENT_BUS.register(this);
    }

    public static MoveLimitDebug get() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) return;
        rollWindow();
        windowCount++;
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!enabled) return;
        if (mc.options.hideGui) return;
        rollWindow();

        GuiGraphics ctx = event.getContext();
        int ry = bottomAnchor() - BOTTOM_MARGIN - mc.font.lineHeight;

        HudClientModule hud = Homovore.moduleManager.getModuleByClass(HudClientModule.class);
        if (hud != null && hud.isElementEnabled(SpeedHudModule.class)) {
            ry -= mc.font.lineHeight;
        }

        String value = String.valueOf(lastCount);
        String label = "/" + EXPECTED_MOVES_PER_SECOND + " move";
        ctx.drawString(mc.font, value, LEFT_MARGIN, ry, GRAY);
        ctx.drawString(mc.font, label, LEFT_MARGIN + mc.font.width(value), ry, WHITE);
    }

    private void rollWindow() {
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MS) return;

        lastCount = windowCount;
        windowCount = 0;
        windowStart = now;
    }

    private int bottomAnchor() {
        int height = mc.getWindow().getGuiScaledHeight();
        return height - (mc.screen instanceof ChatScreen ? CHAT_INPUT_HEIGHT : 0);
    }
}
