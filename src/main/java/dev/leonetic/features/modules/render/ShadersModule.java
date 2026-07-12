package dev.leonetic.features.modules.render;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;

import java.awt.Color;

public class ShadersModule extends Module {
    public enum Mode { Default, Off }

    public Setting<Mode> mode = mode("Mode", Mode.Default).setPage("General");

    public Setting<Boolean> glow          = bool("Glow", true).setPage("General");
    public Setting<Float>   glowRadius    = num("GlowRadius",    3f,    0f, 16f).setPage("General");
    public Setting<Float>   glowIntensity = num("GlowIntensity", 1.55f, 0f, 3f).setPage("General");

    public Setting<Boolean> innerGlow          = bool("InnerGlow", true).setPage("General");
    public Setting<Float>   innerGlowRadius    = num("InnerGlowRadius",    4f,    0f, 16f).setPage("General");
    public Setting<Float>   innerGlowIntensity = num("InnerGlowIntensity", 1.55f, 0f, 3f).setPage("General");

    public Setting<Float>   fillTint      = num("FillTint",  1.0f, 0f, 2f).setPage("General");
    public Setting<Float>   fillAlpha     = num("FillAlpha", 0.4f, 0f, 1f).setPage("General");

    public Setting<Boolean> players     = bool("Players",     true).setPage("Entities");
    public Setting<Float>   playerRange  = num("PlayerRange",     64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> friends     = bool("Friends",     true).setPage("Entities");
    public Setting<Float>   friendRange  = num("FriendRange",     64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> monsters    = bool("Monsters",    true).setPage("Entities");
    public Setting<Float>   monsterRange = num("MonsterRange",    64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> animals     = bool("Animals",     false).setPage("Entities");
    public Setting<Float>   animalRange  = num("AnimalRange",     64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> items       = bool("Items",       false).setPage("Entities");
    public Setting<Float>   itemRange    = num("ItemRange",       64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> crystals    = bool("Crystals",    true).setPage("Entities");
    public Setting<Float>   crystalRange = num("CrystalRange",    64f, 4f, 256f).setPage("Entities");
    public Setting<Boolean> projectiles = bool("Projectiles", false).setPage("Entities");
    public Setting<Float>   projectileRange = num("ProjectileRange", 64f, 4f, 256f).setPage("Entities");

    public Setting<Color> playerColor     = color("PlayerOutline",     255, 0,   0,   255).setPage("Colors");
    public Setting<Color> friendColor     = color("FriendOutline",     0,   255, 100, 255).setPage("Colors");
    public Setting<Color> enemyColor      = color("EnemyOutline",      255, 60,  60,  255).setPage("Colors");
    public Setting<Color> monsterColor    = color("MonsterOutline",    200, 60,  60,  255).setPage("Colors");
    public Setting<Color> animalColor     = color("AnimalOutline",     255, 200, 60,  255).setPage("Colors");
    public Setting<Color> itemColor       = color("ItemOutline",       255, 255, 255, 255).setPage("Colors");
    public Setting<Color> crystalColor    = color("CrystalOutline",    0,   200, 255, 255).setPage("Colors");
    public Setting<Color> projectileColor = color("ProjectileOutline", 200, 200, 0,   255).setPage("Colors");

    public Setting<Boolean> hand          = bool("HandShaders", false).setPage("Hand");
    public Setting<Boolean> handOutline   = bool("HandOutline", true).setPage("Hand");
    public Setting<Float>   handThickness = num("HandThickness", 2f, 1f, 10f).setPage("Hand");
    public Setting<Boolean> handFill      = bool("HandFill", false).setPage("Hand");

    public Setting<Boolean> handGlow          = bool("HandGlow", false).setPage("Hand");
    public Setting<Float>   handGlowRadius    = num("HandGlowRadius",    4f, 1f, 16f).setPage("Hand");
    public Setting<Float>   handGlowIntensity = num("HandGlowIntensity", 1f, 0f, 3f).setPage("Hand");

    public Setting<Boolean> handInnerGlow          = bool("HandInnerGlow", false).setPage("Hand");
    public Setting<Float>   handInnerGlowRadius    = num("HandInnerGlowRadius",    4f, 1f, 16f).setPage("Hand");
    public Setting<Float>   handInnerGlowIntensity = num("HandInnerGlowIntensity", 1f, 0f, 3f).setPage("Hand");

    public Setting<Color> handColor = color("HandColor", 255, 0, 0, 255).setPage("Hand");

    public ShadersModule() {
        super("Shaders", "Stylised post-effect shader on selected entities", Category.RENDER);
        glow.setVisibility(v -> mode.getValue() == Mode.Default);
        glowRadius.setVisibility(v -> mode.getValue() == Mode.Default && glow.getValue());
        glowIntensity.setVisibility(v -> mode.getValue() == Mode.Default && glow.getValue());

        innerGlow.setVisibility(v -> mode.getValue() == Mode.Default);
        innerGlowRadius.setVisibility(v -> mode.getValue() == Mode.Default && innerGlow.getValue());
        innerGlowIntensity.setVisibility(v -> mode.getValue() == Mode.Default && innerGlow.getValue());

        playerRange.setVisibility(v -> players.getValue());
        friendRange.setVisibility(v -> friends.getValue());
        monsterRange.setVisibility(v -> monsters.getValue());
        animalRange.setVisibility(v -> animals.getValue());
        itemRange.setVisibility(v -> items.getValue());
        crystalRange.setVisibility(v -> crystals.getValue());
        projectileRange.setVisibility(v -> projectiles.getValue());

        handThickness.setVisibility(v -> hand.getValue() && handOutline.getValue());
        handOutline.setVisibility(v -> hand.getValue());
        handFill.setVisibility(v -> hand.getValue());
        handGlow.setVisibility(v -> hand.getValue());
        handGlowRadius.setVisibility(v -> hand.getValue() && handGlow.getValue());
        handGlowIntensity.setVisibility(v -> hand.getValue() && handGlow.getValue());
        handInnerGlow.setVisibility(v -> hand.getValue());
        handInnerGlowRadius.setVisibility(v -> hand.getValue() && handInnerGlow.getValue());
        handInnerGlowIntensity.setVisibility(v -> hand.getValue() && handInnerGlow.getValue());
        handColor.setVisibility(v -> hand.getValue());
    }

    public boolean wantsHandShader() {
        return isEnabled() && hand.getValue();
    }

    public int getHandThickness() {
        return Math.round(handThickness.getValue());
    }

    public int getHandGlowRadius() {
        return handGlow.getValue() ? Math.round(handGlowRadius.getValue()) : 0;
    }

    public float getHandGlowIntensity() {
        return handGlowIntensity.getValue();
    }

    public int getHandInnerGlowRadius() {
        return handInnerGlow.getValue() ? Math.round(handInnerGlowRadius.getValue()) : 0;
    }

    public float getHandInnerGlowIntensity() {
        return handInnerGlowIntensity.getValue();
    }

    public int getHandRgb() {
        Color c = handColor.getValue();
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    public int getGlowRadius() {
        return glow.getValue() ? Math.round(glowRadius.getValue()) : 0;
    }

    public float getGlowIntensity() {
        return glowIntensity.getValue();
    }

    public int getInnerGlowRadius() {
        return innerGlow.getValue() ? Math.round(innerGlowRadius.getValue()) : 0;
    }

    public float getInnerGlowIntensity() {
        return innerGlowIntensity.getValue();
    }

    public float getFillTint() {
        return fillTint.getValue();
    }

    public float getFillAlpha() {
        return fillAlpha.getValue();
    }

    public boolean wantsOutlines() {
        Mode m = mode.getValue();
        return m != null && m != Mode.Off;
    }

    public boolean shouldShader(Entity entity) {
        Mode m = mode.getValue();
        if (m == null || m == Mode.Off) return false;
        if (entity == null) return false;
        if (mc.player != null && entity == mc.player) return false;

        if (entity instanceof AbstractClientPlayer p && !(entity instanceof LocalPlayer)) {
            boolean friend = Homovore.friendManager.isFriend(p);
            return friend
                    ? friends.getValue() && inRange(entity, friendRange.getValue())
                    : players.getValue() && inRange(entity, playerRange.getValue());
        }
        if (entity instanceof Monster)     return monsters.getValue()    && inRange(entity, monsterRange.getValue());
        if (entity instanceof Animal)      return animals.getValue()     && inRange(entity, animalRange.getValue());
        if (entity instanceof ItemEntity)  return items.getValue()       && inRange(entity, itemRange.getValue());
        if (entity instanceof EndCrystal)  return crystals.getValue()    && inRange(entity, crystalRange.getValue());
        if (entity instanceof Projectile)  return projectiles.getValue() && inRange(entity, projectileRange.getValue());
        return false;
    }

    private boolean inRange(Entity entity, float range) {
        return mc.player == null || entity.distanceTo(mc.player) <= range;
    }

    public int getRgbFor(Entity entity) {
        Color c = colorFor(entity);
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    private Color colorFor(Entity entity) {
        if (entity instanceof AbstractClientPlayer p && !(entity instanceof LocalPlayer)) {
            if (Homovore.enemyManager.isEnemy(p)) return enemyColor.getValue();
            return Homovore.friendManager.isFriend(p) ? friendColor.getValue() : playerColor.getValue();
        }
        if (entity instanceof Monster) return monsterColor.getValue();
        if (entity instanceof Animal) return animalColor.getValue();
        if (entity instanceof ItemEntity) return itemColor.getValue();
        if (entity instanceof EndCrystal) return crystalColor.getValue();
        if (entity instanceof Projectile) return projectileColor.getValue();
        return playerColor.getValue();
    }
}
