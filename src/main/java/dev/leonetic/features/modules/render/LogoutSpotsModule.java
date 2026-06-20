package dev.leonetic.features.modules.render;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.DisconnectEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.render.MatrixCapture;
import dev.leonetic.util.render.WireframeEntityRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LogoutSpotsModule extends Module {

    public Setting<Color>   spotColor = color("SpotColor",  255, 100, 100, 255);
    public Setting<Color>   sideColor = color("SideColor",  255, 100, 100,  55);
    public Setting<Float>   lineWidth = num("LineWidth",    1.5f, 0.5f, 5.0f);
    public Setting<Boolean> showTime  = bool("ShowTime",    true);
    public Setting<Color>   nameColor = color("NameColor",  255, 100, 100, 255);

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Map<UUID, SpotData>   playerCache       = new ConcurrentHashMap<>();
    private final Map<UUID, LogoutSpot> loggedPlayers     = new ConcurrentHashMap<>();
    private final Map<UUID, Integer>    ticksOnPlayerList = new ConcurrentHashMap<>();

    private ResourceKey<Level> lastDimension = null;
    private ClientLevel lastLevel = null;

    public LogoutSpotsModule() {
        super("LogoutSpots", "Shows where players logged out with a wireframe avatar", Category.RENDER);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    public void onTick() {
        if (nullCheck() || mc.getConnection() == null) return;

        ResourceKey<Level> dim = mc.level.dimension();
        if (mc.level != lastLevel || (lastDimension != null && !dim.equals(lastDimension))) {
            playerCache.clear();
            ticksOnPlayerList.clear();
        }
        lastLevel = mc.level;
        lastDimension = dim;

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            playerCache.put(player.getUUID(), new SpotData(player, dim));
        }

        loggedPlayers.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            if (mc.getConnection().getPlayerInfo(uuid) != null) {
                int n = ticksOnPlayerList.getOrDefault(uuid, 0);
                ticksOnPlayerList.put(uuid, n + 1);
                if (n > 1) {
                    ticksOnPlayerList.remove(uuid);
                    return true;
                }
            } else {
                ticksOnPlayerList.remove(uuid);
            }
            return false;
        });
    }

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ClientboundPlayerInfoRemovePacket pkt) {
            for (UUID id : pkt.profileIds()) {
                if (loggedPlayers.containsKey(id)) continue;
                SpotData data = playerCache.get(id);
                if (data == null) continue;
                loggedPlayers.put(id, new LogoutSpot(data));
            }
        } else if (event.getPacket() instanceof ClientboundPlayerInfoUpdatePacket pkt
                && pkt.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            for (ClientboundPlayerInfoUpdatePacket.Entry e : pkt.entries()) {
                loggedPlayers.remove(e.profileId());
            }
        }
    }

    @Override
    public String getDisplayInfo() {
        if (nullCheck()) return loggedPlayers.isEmpty() ? null : String.valueOf(loggedPlayers.size());
        ResourceKey<Level> dim = mc.level.dimension();
        long n = loggedPlayers.values().stream().filter(s -> dim.equals(s.dimension)).count();
        return n == 0 ? null : String.valueOf(n);
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        ResourceKey<Level> dim = mc.level.dimension();
        float delta = event.getDelta();
        for (LogoutSpot spot : loggedPlayers.values()) {
            if (!dim.equals(spot.dimension)) continue;
            spot.capturedGeometry = WireframeEntityRenderer.render(
                    event.getMatrix(),
                    spot.entity,
                    spot.pos,
                    spot.capturedGeometry,
                    delta,
                    sideColor.getValue(),
                    spotColor.getValue(),
                    lineWidth.getValue()
            );
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        if (MatrixCapture.projection == null) return;

        NametagsModule nametags = Homovore.moduleManager.getModuleByClass(NametagsModule.class);
        GuiGraphics graphics = event.getContext();
        ResourceKey<Level> dim = mc.level.dimension();

        for (LogoutSpot spot : loggedPlayers.values()) {
            if (!dim.equals(spot.dimension)) continue;
            double tagY = spot.pos.y + 1.8 + nametags.gap.getValue() * 0.5;
            double dist = mc.player.position().distanceTo(spot.pos);
            String timeStr = showTime.getValue() ? " " + formatElapsed(spot.logoutTime) : "";
            nametags.renderNametag(graphics, spot.pos.x, tagY, spot.pos.z, dist,
                    spot.name, nameColor.getValue().getRGB(), timeStr,
                    spot.totemPops, spot.armor, spot.mainHand, spot.offHand);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resetState();
    }

    private void resetState() {
        loggedPlayers.clear();
        playerCache.clear();
        ticksOnPlayerList.clear();
        lastDimension = null;
        lastLevel = null;
    }

    private String formatElapsed(long logoutTime) {
        long secs = (System.currentTimeMillis() - logoutTime) / 1000;
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    private static class SpotData {
        final String name;
        final UUID uuid;
        final Vec3 pos;
        final Player entity;
        final Map<EquipmentSlot, ItemStack> armor;
        final ItemStack mainHand;
        final ItemStack offHand;
        final int totemPops;
        final ResourceKey<Level> dimension;

        SpotData(Player player, ResourceKey<Level> dimension) {
            this.name      = player.getGameProfile().name();
            this.uuid      = player.getUUID();
            this.pos       = player.position();
            this.entity    = player;
            this.dimension = dimension;
            this.totemPops = Homovore.playerInfoManager.getTotemPops(player.getUUID());
            this.mainHand  = player.getMainHandItem().copy();
            this.offHand   = player.getOffhandItem().copy();
            this.armor     = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                armor.put(slot, player.getItemBySlot(slot).copy());
            }
        }
    }

    public static class LogoutSpot {
        public final String name;
        public final UUID uuid;
        public final Vec3 pos;
        public final Player entity;
        public final Map<EquipmentSlot, ItemStack> armor;
        public final ItemStack mainHand;
        public final ItemStack offHand;
        public final int totemPops;
        public final long logoutTime;
        public final ResourceKey<Level> dimension;

        public List<float[][]> capturedGeometry = null;

        LogoutSpot(SpotData data) {
            this.name       = data.name;
            this.uuid       = data.uuid;
            this.pos        = data.pos;
            this.entity     = data.entity;
            this.armor      = data.armor;
            this.mainHand   = data.mainHand;
            this.offHand    = data.offHand;
            this.totemPops  = data.totemPops;
            this.dimension  = data.dimension;
            this.logoutTime = System.currentTimeMillis();
        }
    }
}
