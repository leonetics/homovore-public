package dev.leonetic.features.modules.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.MatrixCapture;
import dev.leonetic.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NametagsModule extends Module {

    public Setting<Boolean> showPearls  = bool("ShowPearls",  true).setPage("Info");
    public Setting<Boolean> showItems   = bool("ShowItems",   true).setPage("Info");
    public Setting<Boolean> showArmor   = bool("ShowArmor",   true).setPage("Info");
    public Setting<Boolean> showDist    = bool("ShowDist",    true).setPage("Info");
    public Setting<Boolean> showPops    = bool("ShowPops",    true).setPage("Info");

    public Setting<Color>   nameColor   = color("NameColor",   255, 255, 255, 255).setPage("Colors");
    public Setting<Color>   friendColor = color("FriendColor",   0, 255, 100, 255).setPage("Colors");
    public Setting<Color>   enemyColor  = color("EnemyColor",  255,  60,  60, 255).setPage("Colors");
    public Setting<Color>   bgColor     = color("BgColor",       0,   0,   0, 128).setPage("Colors");
    public Setting<Color>   distColor   = color("DistColor",   170, 170, 170, 255).setPage("Colors");
    public Setting<Color>   popColor    = color("PopColor",   255,  80,  80, 255).setPage("Colors");

    public Setting<Float>   gap         = num("Gap",       1.0f, 0.1f, 15.0f).setPage("Render");
    public Setting<Float>   armorGap    = num("ArmorGap",  2.0f, 0.0f, 20.0f).setPage("Render");
    public Setting<Float>   scale       = num("Scale",     1.0f, 0.1f,  3.0f).setPage("Render");
    public Setting<Float>   minScale    = num("MinScale",  0.3f, 0.1f,  1.0f).setPage("Render");
    public Setting<Boolean> itemsOnArmor = bool("ItemsOnArmor", false).setPage("Render");

    private record PearlEntry(String ownerName, long lastSeenMs) {}

    private static final long FIVE_DAYS_MS = 5L * 24 * 60 * 60 * 1000;

    private final Map<UUID, PearlEntry> pearlOwnerCache = new HashMap<>();

    private final Jsonable pearlCacheJson = new Jsonable() {
        @Override
        public JsonElement toJson() {
            JsonObject root = new JsonObject();
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, PearlEntry> e : pearlOwnerCache.entrySet()) {
                if (now - e.getValue().lastSeenMs() <= FIVE_DAYS_MS) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("owner", e.getValue().ownerName());
                    obj.addProperty("lastSeen", e.getValue().lastSeenMs());
                    root.add(e.getKey().toString(), obj);
                }
            }
            return root;
        }

        @Override
        public void fromJson(JsonElement element) {
            if (element == null || element.isJsonNull()) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                try {
                    JsonObject obj = e.getValue().getAsJsonObject();
                    String owner = obj.get("owner").getAsString();
                    long lastSeen = obj.get("lastSeen").getAsLong();
                    if (now - lastSeen <= FIVE_DAYS_MS) {
                        pearlOwnerCache.put(UUID.fromString(e.getKey()), new PearlEntry(owner, lastSeen));
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override
        public String getFileName() {
            return "pearl_owners.json";
        }
    };

    static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    public NametagsModule() {
        super("Nametags", "Renders custom nametags above players", Category.RENDER);
        Homovore.configManager.addConfig(pearlCacheJson);
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        if (MatrixCapture.projection == null) return;

        GuiGraphics graphics = event.getContext();
        float delta = event.getDelta();

        record RenderJob(double distSq, Runnable draw) {}
        List<RenderJob> jobs = new ArrayList<>();

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;

            double px = player.xo + (player.getX() - player.xo) * delta;
            double py = player.yo + (player.getY() - player.yo) * delta + player.getBbHeight()
                    + gap.getValue() * 0.5;
            double pz = player.zo + (player.getZ() - player.zo) * delta;

            double dist = mc.player.position().distanceTo(player.position());
            boolean isEnemy = Homovore.enemyManager.isEnemy(player);
            boolean isFriend = !isEnemy && Homovore.friendManager.isFriend(player);
            int nameArgb = (isEnemy ? enemyColor.getValue()
                    : isFriend ? friendColor.getValue()
                    : nameColor.getValue()).getRGB();
            String secondaryStr = showDist.getValue() ? " " + (int) dist + "m" : "";
            int pops = Homovore.playerInfoManager.getTotemPops(player.getUUID());

            Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                armor.put(slot, Homovore.playerInfoManager.getEquipment(player, slot));
            }
            ItemStack mainHand = Homovore.playerInfoManager.getMainHandItem(player);
            ItemStack offHand  = Homovore.playerInfoManager.getOffHandItem(player);
            String name = player.getGameProfile().name();

            jobs.add(new RenderJob(dist * dist, () ->
                    renderNametag(graphics, px, py, pz, dist,
                            name, nameArgb, secondaryStr, pops,
                            armor, mainHand, offHand)));
        }

        if (showPearls.getValue()) {
            long now = System.currentTimeMillis();
            pearlOwnerCache.entrySet().removeIf(e -> now - e.getValue().lastSeenMs() > FIVE_DAYS_MS);

            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof ThrownEnderpearl pearl)) continue;
                if (pearl.tickCount <= 2) continue;

                UUID uuid = pearl.getUUID();

                if (pearl.getOwner() instanceof Player thrower) {
                    pearlOwnerCache.put(uuid, new PearlEntry(thrower.getGameProfile().name(), now));
                } else {
                    PearlEntry existing = pearlOwnerCache.get(uuid);
                    if (existing != null) {
                        pearlOwnerCache.put(uuid, new PearlEntry(existing.ownerName(), now));
                    }
                }

                PearlEntry entry = pearlOwnerCache.get(uuid);
                if (entry == null) continue;

                double px = pearl.xo + (pearl.getX() - pearl.xo) * delta;
                double py = pearl.yo + (pearl.getY() - pearl.yo) * delta + pearl.getBbHeight()
                        + gap.getValue() * 0.5;
                double pz = pearl.zo + (pearl.getZ() - pearl.zo) * delta;

                double dist = mc.player.position().distanceTo(pearl.position());

                boolean isEnemy = Homovore.enemyManager.isEnemy(entry.ownerName());
                boolean isFriend = !isEnemy && Homovore.friendManager.isFriend(entry.ownerName());
                int nameArgb = (isEnemy ? enemyColor.getValue()
                        : isFriend ? friendColor.getValue()
                        : nameColor.getValue()).getRGB();
                String ownerName = entry.ownerName();

                jobs.add(new RenderJob(dist * dist, () ->
                        renderPearlTag(graphics, px, py, pz, dist, ownerName, nameArgb)));
            }
        }

        jobs.sort(Comparator.comparingDouble(RenderJob::distSq).reversed());
        for (RenderJob job : jobs) job.draw.run();
    }

    private void renderPearlTag(GuiGraphics graphics,
                                double wx, double wy, double wz,
                                double dist,
                                String name, int nameArgb) {
        float[] screen = MatrixCapture.worldToScreen(wx, wy, wz);
        if (screen == null) return;

        float anchorX = screen[0];
        float anchorY = screen[1];
        float s = (float) Math.max(minScale.getValue(), scale.getValue() * 8.0 / (dist + 8.0));

        int nameW = mc.font.width(name);
        int halfW = nameW / 2;
        int textH = mc.font.lineHeight;
        int textTopY = -textH;

        graphics.pose().pushMatrix();
        graphics.pose().translate(anchorX, anchorY);
        graphics.pose().scale(s, s);

        graphics.fill(-halfW - 2, textTopY - 1, halfW + 2, 1, bgColor.getValue().getRGB());
        graphics.drawString(mc.font, name, -halfW, textTopY, nameArgb);

        graphics.pose().popMatrix();
    }

    public void renderNametag(GuiGraphics graphics,
                               double wx, double wy, double wz,
                               double dist,
                               String name, int nameArgb,
                               String secondaryStr,
                               int totemPops,
                               Map<EquipmentSlot, ItemStack> armor,
                               ItemStack mainHand, ItemStack offHand) {
        float[] screen = MatrixCapture.worldToScreen(wx, wy, wz);
        if (screen == null) return;

        float anchorX = screen[0];
        float anchorY = screen[1];

        float s = (float) Math.max(minScale.getValue(), scale.getValue() * 8.0 / (dist + 8.0));

        int nameW      = mc.font.width(name);
        int secondaryW = mc.font.width(secondaryStr);

        String popsStr = (showPops.getValue() && totemPops > 0) ? " -" + totemPops : "";
        int popsW = mc.font.width(popsStr);

        int totalW   = nameW + secondaryW + popsW;
        int halfW    = totalW / 2;
        int textH    = mc.font.lineHeight;
        int textTopY = -textH;

        graphics.pose().pushMatrix();
        graphics.pose().translate(anchorX, anchorY);
        graphics.pose().scale(s, s);

        graphics.fill(-halfW - 2, textTopY - 1, halfW + 2, 1, bgColor.getValue().getRGB());

        graphics.drawString(mc.font, name, -halfW, textTopY, nameArgb);
        if (!secondaryStr.isEmpty()) {
            graphics.drawString(mc.font, secondaryStr, -halfW + nameW, textTopY, distColor.getValue().getRGB());
        }
        if (!popsStr.isEmpty()) {
            graphics.drawString(mc.font, popsStr, -halfW + nameW + secondaryW, textTopY, popColor.getValue().getRGB());
        }

        if (showArmor.getValue()) {
            boolean hasArmor = false;
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                if (!armor.get(slot).isEmpty()) { hasArmor = true; break; }
            }
            if (hasArmor) {
                int slotSize  = 16;
                int armorTopY = textTopY - 1 - armorGap.getValue().intValue() - slotSize;
                for (int i = 0; i < 4; i++) {
                    ItemStack armorStack = armor.get(ARMOR_SLOTS[i]);
                    int slotX = -(slotSize * 4) / 2 + i * slotSize;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(slotX, armorTopY);
                    graphics.renderItem(armorStack, 0, 0);
                    graphics.renderItemDecorations(mc.font, armorStack, 0, 0);
                    graphics.pose().popMatrix();
                }
            }
        }

        if (showItems.getValue()) {
            int slotSize = 16;
            int itemY, offX, mainX;
            if (itemsOnArmor.getValue()) {
                itemY = textTopY - 1 - armorGap.getValue().intValue() - slotSize;
                offX  = -(slotSize * 4) / 2 - slotSize;
                mainX = (slotSize * 4) / 2;
            } else {
                itemY = textTopY + textH / 2 - slotSize / 2;
                offX  = -halfW - 2 - slotSize;
                mainX = halfW + 2;
            }
            if (!offHand.isEmpty()) {
                graphics.renderItem(offHand, offX, itemY);
                graphics.renderItemDecorations(mc.font, offHand, offX, itemY);
            }
            if (!mainHand.isEmpty()) {
                graphics.renderItem(mainHand, mainX, itemY);
                graphics.renderItemDecorations(mc.font, mainHand, mainX, itemY);
            }
        }

        graphics.pose().popMatrix();
    }
}
