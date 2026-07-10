package dev.leonetic.features.modules.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.HudModule;
import dev.leonetic.features.modules.client.HudPosition;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.util.traits.Jsonable;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ActiveModulesHudModule extends HudModule implements Jsonable {

    private static final int STACK_GAP = 2;
    private static final int GRAY = 0xFFAAAAAA;

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

        HudPosition pos = hudClient != null ? hudClient.positionOf(this) : HudPosition.CENTER_RIGHT;
        int linesBelow = hudClient != null ? hudClient.linesBelow(this) : 0;
        int gap = pos.isBottom() && linesBelow > 0 ? STACK_GAP : 0;
        int y = blockTop(pos, entries.size(), linesBelow, gap);

        for (String name : entries) {
            Module module = Homovore.moduleManager.getModuleByName(name);
            if (module == null) continue;

            String display = module.getDisplayName();
            String metaRaw = module.getMeta();
            String meta = metaRaw != null ? " (" + metaRaw + ")" : "";
            Bind bind = module.getBind().getKey() > 0 ? module.getBind() : null;
            String suffix = bind != null ? " [" + bind.toString() + "]" : "";

            int width = mc.font.width(display) + mc.font.width(meta) + mc.font.width(suffix);
            int x = lineX(pos, width);

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
