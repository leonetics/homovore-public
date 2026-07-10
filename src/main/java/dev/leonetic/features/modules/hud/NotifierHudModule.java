package dev.leonetic.features.modules.hud;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class NotifierHudModule extends HudModule {

    public enum Icon {
        DANGER("⚠", 0xFFFFAA00),
        SKULL("☠", 0xFFFF5555),
        INFO("ℹ", 0xFFFFFF55);

        private final String glyph;
        private final int color;

        Icon(String glyph, int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    private static final long DEFAULT_DURATION_MS = 3000;
    private static final long FADE_MS = 400;
    private static final int MAX_VISIBLE = 5;
    private static final int LINE_GAP = 3;
    private static final int STATS_GAP = 4;
    private static final int STATS_TOP_OFFSET = 56;
    private static final int NAME_CLEARANCE = 11;
    private static final int BUBBLE_HEIGHT = 10;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final String OPEN = "<< ";
    private static final String SEP = " ";
    private static final String CLOSE = " >>";

    private static final class Entry {
        final String key;
        Icon icon;
        Component message;
        long expiry;

        Entry(String key, Icon icon, Component message, long expiry) {
            this.key = key;
            this.icon = icon;
            this.message = message;
            this.expiry = expiry;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    public NotifierHudModule() {
        super("Notifier");
    }

    public static void push(Icon icon, String message) {
        push(null, icon, Component.literal(message), DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, String message) {
        push(key, icon, Component.literal(message), DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, Component message) {
        push(key, icon, message, DEFAULT_DURATION_MS);
    }

    public static void push(String key, Icon icon, Component message, long durationMs) {
        long expiry = System.currentTimeMillis() + durationMs;
        if (key != null) {
            for (Entry e : ENTRIES) {
                if (key.equals(e.key)) {
                    e.icon = icon;
                    e.message = message;
                    e.expiry = expiry;
                    return;
                }
            }
        }
        ENTRIES.add(new Entry(key, icon, message, expiry));
    }

    @Override
    public void render(Render2DEvent event) {
        long now = System.currentTimeMillis();
        ENTRIES.removeIf(e -> e.expiry <= now);
        if (ENTRIES.isEmpty()) return;

        GuiGraphics ctx = event.getContext();
        int lineHeight = mc.font.lineHeight;
        int lineStride = lineHeight + LINE_GAP;

        int statsTop = screenHeight() - STATS_TOP_OFFSET;
        if (showingBubbles()) statsTop -= BUBBLE_HEIGHT;
        int anchorY = statsTop - STATS_GAP - NAME_CLEARANCE;

        int total = ENTRIES.size();
        int shown = Math.min(total, MAX_VISIBLE);
        for (int k = 0; k < shown; k++) {
            Entry e = ENTRIES.get(total - 1 - k);
            int topY = anchorY - lineHeight - k * lineStride;
            float alpha = fade(e, now);
            drawLine(ctx, e, topY, alpha);
        }
    }

    private void drawLine(GuiGraphics ctx, Entry e, int y, float alpha) {
        int width = mc.font.width(OPEN) + mc.font.width(e.icon.glyph)
                + mc.font.width(SEP) + mc.font.width(e.message) + mc.font.width(CLOSE);
        int x = (screenWidth() - width) / 2;

        int text = applyAlpha(TEXT_COLOR, alpha);
        int iconColor = applyAlpha(e.icon.color, alpha);

        ctx.drawString(mc.font, OPEN, x, y, text, true);
        x += mc.font.width(OPEN);
        ctx.drawString(mc.font, e.icon.glyph, x, y, iconColor, true);
        x += mc.font.width(e.icon.glyph);
        ctx.drawString(mc.font, SEP, x, y, text, true);
        x += mc.font.width(SEP);
        ctx.drawString(mc.font, e.message, x, y, text, true);
        x += mc.font.width(e.message);
        ctx.drawString(mc.font, CLOSE, x, y, text, true);
    }

    private float fade(Entry e, long now) {
        long remaining = e.expiry - now;
        if (remaining >= FADE_MS) return 1f;
        return Math.max(0f, remaining / (float) FADE_MS);
    }

    private int applyAlpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private boolean showingBubbles() {
        return mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply();
    }
}
