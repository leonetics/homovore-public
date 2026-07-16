package dev.leonetic.features.modules.client;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

public class SoundBlockerModule extends Module {
    public final Setting<Boolean> all = bool("All Sounds", false);
    public final Setting<Boolean> crystals = bool("End Crystals", true);
    public final Setting<Boolean> anchors = bool("Respawn Anchors", true);
    public final Setting<Boolean> totems = bool("Totems", true);
    public final Setting<Boolean> pearls = bool("Ender Pearls", false);
    public final Setting<Boolean> fireworks = bool("Fireworks", false);
    public final Setting<Boolean> xp = bool("XP Bottles", false);
    public final Setting<Boolean> armor = bool("Armor", false);
    public final Setting<Boolean> elytra = bool("Elytra", false);
    public final Setting<Boolean> food = bool("Food", false);
    public final Setting<Boolean> bows = bool("Bows", false);
    public final Setting<Boolean> chests = bool("Chests", false);
    public final Setting<Boolean> portals = bool("Portals", false);
    public final Setting<Boolean> weather = bool("Weather", false);
    public final Setting<Boolean> withers = bool("Withers", false);
    public final Setting<String> custom = str("Custom Match", "");

    public SoundBlockerModule() {
        super("SoundBlocker", "Blocks selected client-side sounds.", Category.CLIENT);
    }

    public boolean shouldBlock(SoundInstance sound) {
        if (!isEnabled() || sound == null) return false;
        if (all.getValue()) return true;

        Identifier location = sound.getIdentifier();
        if (location == null) return false;

        String id = location.toString();
        return matchesCustom(id)
                || crystals.getValue() && containsAny(id,
                        "entity.generic.explode",
                        "entity.dragon_fireball.explode",
                        "block.glass.place",
                        "block.amethyst_block.place")
                || anchors.getValue() && containsAny(id, "block.respawn_anchor")
                || totems.getValue() && containsAny(id, "item.totem.use")
                || pearls.getValue() && containsAny(id, "entity.ender_pearl.throw")
                || fireworks.getValue() && containsAny(id, "entity.firework_rocket")
                || xp.getValue() && containsAny(id, "entity.experience_orb.pickup", "entity.experience_bottle.throw")
                || armor.getValue() && containsAny(id, "item.armor.equip")
                || elytra.getValue() && containsAny(id, "item.elytra.flying")
                || food.getValue() && containsAny(id, "entity.generic.eat", "entity.generic.drink")
                || bows.getValue() && containsAny(id, "entity.arrow.shoot", "item.crossbow")
                || chests.getValue() && containsAny(id, "block.chest", "block.ender_chest", "block.shulker_box", "block.barrel")
                || portals.getValue() && containsAny(id, "block.portal", "block.end_portal", "block.end_gateway")
                || weather.getValue() && containsAny(id, "entity.lightning_bolt.thunder", "entity.lightning_bolt.impact", "weather.rain")
                || withers.getValue() && containsAny(id, "entity.wither", "entity.wither_skeleton");
    }

    private boolean matchesCustom(String id) {
        String value = custom.getValue();
        if (value == null || value.isBlank()) return false;

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .anyMatch(id::contains);
    }

    private boolean containsAny(String id, String... needles) {
        for (String needle : needles) {
            if (id.contains(needle)) return true;
        }
        return false;
    }

    public static SoundBlockerModule getInstance() {
        if (Homovore.moduleManager == null) return null;
        return Homovore.moduleManager.getModuleByClass(SoundBlockerModule.class);
    }

    public static boolean shouldBlockSound(SoundInstance sound) {
        SoundBlockerModule module = getInstance();
        return module != null && module.shouldBlock(sound);
    }
}
