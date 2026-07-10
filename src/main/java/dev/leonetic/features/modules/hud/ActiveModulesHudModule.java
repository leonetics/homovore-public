package dev.leonetic.features.modules.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ActiveModulesHudModule extends HudModule implements Jsonable {
    private static final int RIGHT_MARGIN = 2;
    private static final int BOTTOM_MARGIN = 2;

    private static final int BOTTOM_RIGHT_GAP = 2;
    private static final int GRAY = 0xFFAAAAAA;

    public enum SnapTo {
        DEFAULT,
        BOTTOMRIGHT
    }

    private static ActiveModulesHudModule INSTANCE;

    private final LinkedHashSet<String> entries = new LinkedHashSet<>();

    public ActiveModulesHudModule() {
        super("ActiveModules");
        INSTANCE = this;
        Homovore.configManager.addConfig(this);
    }

    public static ActiveModulesHudModule getInstance() {
        return INSTANCE;
    }

    public boolean add(String name) {
        Module module = Homovore.moduleManager.getModuleByName(name);
        if (module == null) return false;
        return entries.add(module.getName());
    }

    public boolean remove(String name) {
        Module module = Homovore.moduleManager.getModuleByName(name);
        String key = module != null ? module.getName() : name;
        return entries.removeIf(e -> e.equalsIgnoreCase(key));
    }

    public void clear() {
        entries.clear();
    }

    public List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    @Override
    public void render(Render2DEvent event) {
        if (entries.isEmpty()) return;

        GuiGraphics ctx = event.getContext();
        HudClientModule hudClient = Homovore.moduleManager.getModuleByClass(HudClientModule.class);
        int activeColor = hudClient != null
                ? hudClient.activeModuleColor.getValue().getRGB()
                : Homovore.colorManager.getAsIntFullAlpha("chat");

        SnapTo snap = hudClient != null ? hudClient.activeModulesSnap.getValue() : SnapTo.DEFAULT;
        int y = snap == SnapTo.BOTTOMRIGHT ? bottomRightTop(hudClient) : screenHeight() / 2;

        for (String name : entries) {
            Module module = Homovore.moduleManager.getModuleByName(name);
            if (module == null) continue;

            String display = module.getDisplayName();
            String metaRaw = module.getMeta();
            String meta = metaRaw != null ? " (" + metaRaw + ")" : "";
            Bind bind = module.getBind().getKey() > 0 ? module.getBind() : null;
            String suffix = bind != null ? " [" + bind.toString() + "]" : "";

            int width = mc.font.width(display) + mc.font.width(meta) + mc.font.width(suffix);
            int x = screenWidth() - RIGHT_MARGIN - width;

            int nameColor = module.isEnabled() ? activeColor : GRAY;
            ctx.drawString(mc.font, display, x, y, nameColor);
            int cursor = x + mc.font.width(display);
            if (!meta.isEmpty()) {
                ctx.drawString(mc.font, meta, cursor, y, GRAY);
                cursor += mc.font.width(meta);
            }
            if (!suffix.isEmpty()) {
                ctx.drawString(mc.font, suffix, cursor, y, activeColor);
            }

            y += mc.font.lineHeight;
        }
    }

    private int bottomRightTop(HudClientModule hudClient) {
        int linesBelow = 0;
        if (hudClient != null) {
            if (hudClient.isElementEnabled(CoordinatesHudModule.class)
                    && !hudClient.coordinatesLeft.getValue()) linesBelow++;
            if (hudClient.isElementEnabled(PingHudModule.class)) linesBelow++;

            if (hudClient.isElementEnabled(RadarHudModule.class)) {
                RadarHudModule radar = hudClient.getElement(RadarHudModule.class);
                if (radar != null) linesBelow += radar.renderedLineCount();
            }
        }
        int blockBottom = bottomAnchor() - BOTTOM_MARGIN - linesBelow * mc.font.lineHeight;
        if (linesBelow > 0) blockBottom -= BOTTOM_RIGHT_GAP;
        return blockBottom - entries.size() * mc.font.lineHeight;
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (String entry : entries) array.add(entry);
        object.add("entries", array);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        entries.clear();
        JsonElement arr = element.getAsJsonObject().get("entries");
        if (arr == null || !arr.isJsonArray()) return;
        for (JsonElement e : arr.getAsJsonArray()) entries.add(e.getAsString());
    }

    @Override
    public String getFileName() {
        return "active_modules_hud.json";
    }
}
