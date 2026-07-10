package dev.leonetic.features.modules.hud;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.modules.client.HudPosition;
import dev.leonetic.features.modules.render.NametagsModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class RadarHudModule extends HudModule {
    private static final int DEFAULT_POP_COLOR = 0xFFFF5050;

    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("homovore", "textures/font/logo_white.png");
    private static final int LOGO_TEX_W = 128;
    private static final int LOGO_TEX_H = 128;
    private static final int LOGO_ALPHA_WHITE = 0xFFFFFFFF;
    private static final int LOGO_GAP = 3;

    public RadarHudModule() {
        super("Radar");
    }

    public int renderedLineCount() {
        if (mc.player == null || mc.level == null) return 0;
        int count = 1;
        UUID selfId = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(selfId)) continue;
            if (!player.isAlive()) continue;
            count++;
        }
        return count;
    }

    @Override
    public void render(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;

        List<Entry> entries = new ArrayList<>();
        UUID selfId = mc.player.getUUID();
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(selfId)) continue;
            if (!player.isAlive()) continue;
            double dist = mc.player.position().distanceTo(player.position());
            entries.add(new Entry(player, dist));
        }

        entries.sort(Comparator.comparingDouble((Entry e) -> e.dist).reversed());

        entries.add(new Entry(mc.player, 0.0));

        if (entries.isEmpty()) return;

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = Homovore.moduleManager.getModuleByClass(HudClientModule.class);
        int popColor = resolvePopColor();
        int lineHeight = mc.font.lineHeight;

        int linesBelow = hudClient != null ? hudClient.linesBelow(this) : 0;
        int baseY = blockTop(HudPosition.BOTTOM_RIGHT, 1, linesBelow, 0);

        for (int i = entries.size() - 1, row = 0; i >= 0; i--, row++) {
            Entry entry = entries.get(i);
            String name = entry.player.getGameProfile().name();
            int pops = Homovore.playerInfoManager.getTotemPops(entry.player.getUUID());
            String popsStr = pops > 0 ? " -" + pops : "";

            int nameW = mc.font.width(name);
            int popsW = mc.font.width(popsStr);
            int totalW = nameW + popsW;

            int y = baseY - row * lineHeight;
            int x = lineX(HudPosition.BOTTOM_RIGHT, totalW);

            int nameColor = resolveNameColor(hudClient, entry.player);

            if (entry.player == mc.player) {
                int logoSize = lineHeight;
                int logoX = x - logoSize - LOGO_GAP;
                int logoY = y + (lineHeight - logoSize) / 2;
                ctx.blit(RenderPipelines.GUI_TEXTURED, LOGO,
                        logoX, logoY, 0.0f, 0.0f,
                        logoSize, logoSize,
                        LOGO_TEX_W, LOGO_TEX_H,
                        LOGO_TEX_W, LOGO_TEX_H,
                        LOGO_ALPHA_WHITE);
            }

            ctx.drawString(mc.font, name, x, y, nameColor);
            if (!popsStr.isEmpty()) {
                ctx.drawString(mc.font, popsStr, x + nameW, y, popColor);
            }
        }
    }

    private int resolveNameColor(HudClientModule hudClient, Player player) {
        if (hudClient == null) return 0xFFFFFFFF;
        if (player == mc.player) return hudClient.radarSelfColor.getValue().getRGB();
        if (Homovore.friendManager.isFriend(player)) return hudClient.radarFriendColor.getValue().getRGB();
        return hudClient.radarEnemyColor.getValue().getRGB();
    }

    private int resolvePopColor() {
        NametagsModule nametags = Homovore.moduleManager.getModuleByClass(NametagsModule.class);
        if (nametags == null || nametags.popColor.getValue() == null) return DEFAULT_POP_COLOR;
        return nametags.popColor.getValue().getRGB();
    }

    private record Entry(Player player, double dist) {}
}
