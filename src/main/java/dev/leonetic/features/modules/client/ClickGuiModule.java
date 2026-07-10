package dev.leonetic.features.modules.client;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.ClientEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.gui.HomovoreGui;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.features.settings.Setting;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public class ClickGuiModule extends Module {
    private static ClickGuiModule INSTANCE;

    public Setting<String>  prefix           = str("Prefix", ".");
    public Setting<Theme>   theme            = mode("Theme", Theme.HOMOVORE);
    public Setting<Color>   customColor      = color("Custom Color", 120, 170, 210, 220);
    public Setting<Boolean> smooth           = bool("Smooth", true);
    public Setting<Integer> rainbowHue       = num("Delay", 240, 0, 600);
    public Setting<Float>   rainbowBrightness = num("Brightness", 200.0f, 1.0f, 255.0f);
    public Setting<Float>   rainbowSaturation = num("Saturation", 140.0f, 1.0f, 255.0f);

    public Setting<Boolean> clickGuiFont = bool("ClickGUI Font", false);
    public Setting<Boolean> hudFont      = bool("HUD Font", false);
    public Setting<String>  fontName     = str("Font Name", "");

    private static final Color CAT_COMBAT   = new Color(196,  88,  90);
    private static final Color CAT_WORLD    = new Color(118, 168, 118);
    private static final Color CAT_RENDER   = new Color( 98, 166, 200);
    private static final Color CAT_MOVEMENT = new Color(212, 142,  78);
    private static final Color CAT_PLAYER   = new Color(202, 180,  92);
    private static final Color CAT_FUNNY    = new Color(198, 124, 178);
    private static final Color CAT_CLIENT   = new Color( 70,  75,  82);
    private static final Color CAT_HUD      = new Color( 96, 142, 200);

    private static final Color HOMOVORE_ACCENT = new Color(140, 26, 38);
    private static final Color HOMOVORE_MODULE = new Color(190, 72, 82);

    public Color categoryAccent(Module.Category cat) {
        return categoryAccent(cat, 0f);
    }

    public Color categoryAccent(Module.Category cat, float yOffset) {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW)  return rainbowAt(yOffset);
        if (t == Theme.CUSTOM)   return customColor.getValue();
        if (t == Theme.HOMOVORE) return HOMOVORE_ACCENT;
        if (cat == null) return CAT_CLIENT;
        switch (cat) {
            case COMBAT:   return CAT_COMBAT;
            case WORLD:    return CAT_WORLD;
            case RENDER:   return CAT_RENDER;
            case MOVEMENT: return CAT_MOVEMENT;
            case PLAYER:   return CAT_PLAYER;
            case FUNNY:    return CAT_FUNNY;
            case CLIENT:   return CAT_CLIENT;
            case HUD:      return CAT_HUD;
            default:       return CAT_CLIENT;
        }
    }

    public Color moduleAccent(Module.Category cat, float yOffset) {
        if (theme.getValue() == Theme.HOMOVORE) return HOMOVORE_MODULE;
        return categoryAccent(cat, yOffset);
    }

    public Color chatAccent() {
        Theme t = theme.getValue();
        if (t == Theme.RAINBOW)  return rainbowAt(0f);
        if (t == Theme.CUSTOM)   return customColor.getValue();
        if (t == Theme.HOMOVORE) return HOMOVORE_ACCENT;
        return Color.WHITE;
    }

    public Color rainbowAt(float yOffset) {
        return dev.leonetic.util.ColorUtil.rainbow((int)(yOffset / 10f * rainbowHue.getValue()));
    }

    public float getExpandSpeed() {
        return smooth.getValue() ? 0.5f : 1f;
    }

    public ClickGuiModule() {
        super("ClickGui", "Opens the ClickGui", Module.Category.CLIENT);

        this.bind.setValue(new Bind(GLFW.GLFW_KEY_RIGHT_SHIFT));
        this.bindMode.setVisibility(v -> false);

        rainbowHue.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        rainbowBrightness.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        rainbowSaturation.setVisibility(v -> theme.getValue() == Theme.RAINBOW);
        customColor.setVisibility(v -> theme.getValue() == Theme.CUSTOM);
        fontName.setVisibility(v -> clickGuiFont.getValue() || hudFont.getValue());

        INSTANCE = this;
    }

    @Subscribe
    public void onSettingChange(ClientEvent event) {
        if (event.getType() == ClientEvent.Type.SETTING_UPDATE
                && event.getSetting().getFeature().equals(this)) {
            if (event.getSetting().equals(this.prefix)) {
                Homovore.commandManager.setCommandPrefix(this.prefix.getPlannedValue());
                Command.sendMessage("Prefix set to {global} %s",
                        Homovore.commandManager.getCommandPrefix());
            }
            if (event.getSetting().equals(this.clickGuiFont)
                    || event.getSetting().equals(this.hudFont)
                    || event.getSetting().equals(this.fontName)) {
                dev.leonetic.util.render.font.Fonts.markDirty();
            }
        }
    }

    @Override
    public void onEnable() {
        if (nullCheck()) return;
        mc.setScreen(HomovoreGui.getClickGui());
    }

    @Override
    public void onLoad() {
        Color fixedAccent = new Color(120, 175, 220, 220);
        Homovore.colorManager.register("ui",          () -> fixedAccent);
        Homovore.colorManager.register("chat",        this::chatAccent);
        Homovore.colorManager.register("chatBracket", this::chatAccent);
        Homovore.commandManager.setCommandPrefix(this.prefix.getValue());
    }

    @Override
    public void onTick() {
        if (!(mc.screen instanceof HomovoreGui)) {
            this.disable();
        }
    }

    public static ClickGuiModule getInstance() {
        return INSTANCE;
    }

    public enum Theme {
        HOMOVORE, COLORS, CUSTOM, RAINBOW
    }
}
