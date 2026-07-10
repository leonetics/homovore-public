package dev.leonetic.features.modules.client;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.hud.ActiveModulesHudModule;
import dev.leonetic.features.modules.hud.ArmorHudModule;
import dev.leonetic.features.modules.hud.CoordinatesHudModule;
import dev.leonetic.features.modules.hud.CountsHudModule;
import dev.leonetic.features.modules.hud.NotifierHudModule;
import dev.leonetic.features.modules.hud.PingHudModule;
import dev.leonetic.features.modules.hud.RadarHudModule;
import dev.leonetic.features.modules.hud.SpeedHudModule;
import dev.leonetic.features.modules.hud.TestLimitHudModule;
import dev.leonetic.features.modules.hud.TotemsHudModule;
import dev.leonetic.features.settings.Setting;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

public class HudClientModule extends Module {
    private final Map<HudModule, Setting<Boolean>> elements = new LinkedHashMap<>();

    public final Setting<Color> radarEnemyColor  = color("Radar Enemy Color",  255, 255, 255, 255).setPage("Colors");
    public final Setting<Color> radarFriendColor = color("Radar Friend Color",   0, 255, 100, 255).setPage("Colors");
    public final Setting<Color> radarSelfColor   = color("Radar Self Color",   255, 255, 255, 255).setPage("Colors");
    public final Setting<Color> activeModuleColor = color("Active Module Color", 175,   0,   0, 255).setPage("Colors");

    public final Setting<ActiveModulesHudModule.SnapTo> activeModulesSnap =
            mode("SnapTo", ActiveModulesHudModule.SnapTo.DEFAULT).setPage("Elements");

    public final Setting<Boolean> coordinatesLeft = bool("Coordinates Left", false).setPage("Elements");

    public HudClientModule() {
        super("Hud", "Static-position HUD elements", Category.CLIENT);
        register(new TotemsHudModule(), true);
        register(new ArmorHudModule(), true);
        register(new CountsHudModule(), true);
        register(new CoordinatesHudModule(), true);
        register(new PingHudModule(), true);
        register(new RadarHudModule(), true);
        register(new ActiveModulesHudModule(), true);
        register(new NotifierHudModule(), true);
        register(new SpeedHudModule(), false);
        register(new TestLimitHudModule(), false);
    }

    private void register(HudModule element, boolean defaultOn) {
        elements.put(element, bool(element.getName(), defaultOn).setPage("Elements"));
    }

    @SuppressWarnings("unchecked")
    public <T extends HudModule> T getElement(Class<T> type) {
        for (HudModule element : elements.keySet()) {
            if (type.isInstance(element)) return (T) element;
        }
        return null;
    }

    public boolean isElementEnabled(Class<? extends HudModule> type) {
        for (Map.Entry<HudModule, Setting<Boolean>> entry : elements.entrySet()) {
            if (type.isInstance(entry.getKey())) return entry.getValue().getValue();
        }
        return false;
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        if (mc.options.hideGui) return;
        dev.leonetic.util.render.font.Fonts.beginHudPass();
        try {
            for (Map.Entry<HudModule, Setting<Boolean>> entry : elements.entrySet()) {
                if (!entry.getValue().getValue()) continue;
                entry.getKey().render(event);
            }
        } finally {
            dev.leonetic.util.render.font.Fonts.endHudPass();
        }
    }
}
