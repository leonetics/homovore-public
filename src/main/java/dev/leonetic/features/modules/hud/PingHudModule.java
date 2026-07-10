package dev.leonetic.features.modules.hud;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.modules.client.HudPosition;
import dev.leonetic.mixin.client.PlayerTabOverlayAccessor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PingHudModule extends HudModule {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;

    private static final Pattern PING_SUFFIX = Pattern.compile("(?i)(\\d+)\\s*(?:ms)?\\s*ping");
    private static final Pattern PING_PREFIX = Pattern.compile("(?i)ping\\s*(?:[:=-])?\\s*(\\d+)");

    public PingHudModule() {
        super("Ping");
    }

    @Override
    public void render(Render2DEvent event) {
        String label = "Ping ";
        String value = String.valueOf(getPing());

        int totalWidth = mc.font.width(label) + mc.font.width(value);

        HudClientModule hud = Homovore.moduleManager.getModuleByClass(HudClientModule.class);
        int linesBelow = hud != null ? hud.linesBelow(this) : 0;

        int rx = lineX(HudPosition.BOTTOM_RIGHT, totalWidth);
        int ry = blockTop(HudPosition.BOTTOM_RIGHT, 1, linesBelow, 0);

        event.getContext().drawString(mc.font, label, rx, ry, WHITE);
        event.getContext().drawString(mc.font, value, rx + mc.font.width(label), ry, GRAY);
    }

    private int getPing() {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null && mc.player != null) {
            PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
            int latency = info != null ? info.getLatency() : 0;
            if (latency > 0) return latency;
        }

        Integer parsed = parseFromTablist();
        return parsed != null ? Math.max(parsed, 0) : 0;
    }

    private Integer parseFromTablist() {
        if (mc.gui == null) return null;
        PlayerTabOverlay tab = mc.gui.getTabList();
        if (!(tab instanceof PlayerTabOverlayAccessor accessor)) return null;

        Integer fromHeader = parse(accessor.homovore$getHeader());
        return fromHeader != null ? fromHeader : parse(accessor.homovore$getFooter());
    }

    private Integer parse(Component component) {
        if (component == null) return null;
        String text = component.getString();
        if (text.isEmpty()) return null;

        Matcher m = PING_SUFFIX.matcher(text);
        if (m.find()) return tryInt(m.group(1));
        m = PING_PREFIX.matcher(text);
        if (m.find()) return tryInt(m.group(1));
        return null;
    }

    private Integer tryInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
