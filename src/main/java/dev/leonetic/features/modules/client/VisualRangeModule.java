package dev.leonetic.features.modules.client;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.DisconnectEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.player.ChatUtil;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class VisualRangeModule extends Module {
    private final Setting<Boolean> enters = bool("Enters", true);
    private final Setting<Boolean> leaves = bool("Leaves", true);
    private final Setting<Boolean> friends = bool("Friends", true);
    private final Setting<Sound> sound = mode("Sound", Sound.ORB);
    private final Setting<Float> volume = num("Volume", 1.5f, 0.1f, 4.0f);

    private final Set<UUID> seen = new HashSet<>();

    public VisualRangeModule() {
        super("VisualRange", "Notifies when players enter or leave visual range.", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        seed();
    }

    @Override
    public void onDisable() {
        seen.clear();
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) {
            seen.clear();
            return;
        }

        Set<UUID> current = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            UUID id = player.getUUID();
            current.add(id);

            if (!seen.contains(id) && shouldNotify(player)) {
                notify(player, true);
            }
        }

        if (leaves.getValue()) {
            for (UUID id : seen) {
                if (current.contains(id)) continue;
                String name = nameFor(id);
                if (name != null) notify(name, false, false);
            }
        }

        seen.clear();
        seen.addAll(current);
    }

    @Subscribe
    private void onDisconnect(DisconnectEvent event) {
        seen.clear();
    }

    private void seed() {
        seen.clear();
        if (nullCheck()) return;
        for (Player player : mc.level.players()) {
            if (player != mc.player) seen.add(player.getUUID());
        }
    }

    private boolean shouldNotify(Player player) {
        return friends.getValue() || !Homovore.friendManager.isFriend(player);
    }

    private void notify(Player player, boolean enter) {
        if (enter && !enters.getValue()) return;
        notify(player.getGameProfile().name(), enter, Homovore.friendManager.isFriend(player));
    }

    private void notify(String name, boolean enter, boolean friend) {
        String action = enter ? "entered" : "left";
        Component body = Component.literal(name + " " + action + " visual range" + (friend ? " [friend]" : ""));
        ChatUtil.sendPrefixed(ChatUtil.getInfoComponent(), body);
        playSound();
    }

    private String nameFor(UUID id) {
        if (mc.getConnection() == null) return null;
        var info = mc.getConnection().getPlayerInfo(id);
        if (info == null) return null;
        return info.getProfile().name();
    }

    private void playSound() {
        SoundEvent event = switch (sound.getValue()) {
            case ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case LEVEL -> SoundEvents.PLAYER_LEVELUP;
            case BELL -> SoundEvents.BELL_BLOCK;
            case NONE -> null;
        };
        if (event != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, volume.getValue()));
        }
    }

    public enum Sound {
        NONE,
        ORB,
        LEVEL,
        BELL
    }
}
