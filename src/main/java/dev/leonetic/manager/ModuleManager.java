package dev.leonetic.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leonetic.Homovore;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.features.Feature;
import dev.leonetic.features.commands.ModuleCommand;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.combat.*;
import dev.leonetic.features.modules.player.*;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.features.modules.client.ClickGuiModule;
import dev.leonetic.features.modules.client.HudClientModule;
import dev.leonetic.features.modules.client.NotificationsModule;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.modules.funny.TrickshotModule;
import dev.leonetic.features.modules.funny.MoFuckerModule;
import dev.leonetic.features.modules.movement.SprintModule;
import dev.leonetic.features.modules.movement.VelocityModule;
import dev.leonetic.features.modules.movement.NoSlowModule;
import dev.leonetic.features.modules.movement.ElytraAssistModule;
import dev.leonetic.features.modules.movement.ElytraFlyModule;
import dev.leonetic.features.modules.movement.ElytraDashModule;
import dev.leonetic.features.modules.world.AutoPortalModule;
import dev.leonetic.features.modules.world.FastPortalModule;
import dev.leonetic.features.modules.world.BomberModule;
import dev.leonetic.features.modules.world.SpeedMineModule;
import dev.leonetic.features.modules.world.NukerModule;
import dev.leonetic.features.modules.world.ScaffoldModule;
import dev.leonetic.features.modules.render.BlockHighlightModule;
import dev.leonetic.features.modules.render.BreadcrumbsModule;
import dev.leonetic.features.modules.render.BreakIndicatorsModule;
import dev.leonetic.features.modules.funny.AutoHorseModule;
import dev.leonetic.features.modules.funny.DiscordRPCModule;
import dev.leonetic.features.modules.funny.EndermanLookModule;
import dev.leonetic.features.modules.render.CrystalHandModule;
import dev.leonetic.features.modules.render.ShadersModule;
import dev.leonetic.features.modules.render.SkyboxModule;
import dev.leonetic.features.modules.render.SeeThroughModule;
import dev.leonetic.features.modules.render.FullbrightModule;
import dev.leonetic.features.modules.render.LogoutSpotsModule;
import dev.leonetic.features.modules.render.NametagsModule;
import dev.leonetic.features.modules.render.TablistModule;
import dev.leonetic.features.modules.render.NoRenderModule;
import dev.leonetic.features.modules.render.PopEffectsModule;
import dev.leonetic.features.modules.render.ShulkerPreviewModule;
import dev.leonetic.features.modules.render.QuadSupremeModule;
import dev.leonetic.features.modules.render.ViewModelModule;
import dev.leonetic.util.traits.Jsonable;
import dev.leonetic.util.traits.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

public class ModuleManager implements Jsonable, Util {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModuleManager");

    private final Map<Class<? extends Module>, Module> fastRegistry = new HashMap<>();
    private final List<Module> modules = new ArrayList<>();

    public void init() {
        register(new HudClientModule());
        register(new ClickGuiModule());
        register(new NotificationsModule());
        register(new TargetsModule());
        register(new AutoTrapModule());
        register(new OffhandModule());
        register(new AutoLogModule());
        register(new AutoSwordModule());
        register(new AutoCrystalModule());
        register(new AutoAnchorModule());
        register(new AutoMineModule());
        register(new AutoXPModule());
        register(new VelocityModule());
        register(new SprintModule());
        register(new ElytraAssistModule());
        register(new ElytraFlyModule());
        register(new ElytraDashModule());
        register(new NoSlowModule());

        register(new AutoPortalModule());
        register(new FastPortalModule());
        register(new FastuseModule());
        register(new BlockHighlightModule());
        register(new BreadcrumbsModule());
        register(new BreakIndicatorsModule());
        register(new AutoHorseModule());
        register(new DiscordRPCModule());
        register(new EndermanLookModule());
        register(new CrystalHandModule());
        register(new FullbrightModule());
        register(new NoRenderModule());
        register(new ShadersModule());
        register(new SkyboxModule());
        register(new SeeThroughModule());
        register(new NametagsModule());
        register(new TablistModule());
        register(new LogoutSpotsModule());
        register(new PopEffectsModule());
        register(new ShulkerPreviewModule());
        register(new QuadSupremeModule());
        register(new ViewModelModule());
        register(new MiddleClickExtraModule());
        register(new KeyPotionModule());
        register(new SurroundModule());
        register(new PistonCrystalModule());
        register(new PearlBlockerModule());
        register(new PhaseModule());
        register(new TrickshotModule());
        register(new MoFuckerModule());
        register(new BomberModule());
        register(new SpeedMineModule());
        register(new NukerModule());
        register(new ScaffoldModule());
        register(new ReplenishModule());
        register(new InstantRekitModule());
        register(new NoRotateModule());

        LOGGER.info("Registered {} modules", modules.size());

        for (Module module : modules) {
            Homovore.commandManager.register(new ModuleCommand(module));
        }

        Homovore.configManager.addConfig(this);
    }

    public void register(Module module) {
        getModules().add(module);
        fastRegistry.put(module.getClass(), module);
    }

    public List<Module> getModules() {
        return modules;
    }

    public Stream<Module> stream() {
        return getModules().stream();
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModuleByClass(Class<T> clazz) {
        return (T) fastRegistry.get(clazz);
    }

    public Module getModuleByName(String name) {
        return stream().filter(m -> m.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Module getModuleByDisplayName(String display) {
        return stream().filter(m -> m.getDisplayName().equalsIgnoreCase(display)).findFirst().orElse(null);
    }

    public List<Module> getModulesByCategory(Module.Category category) {
        return stream().filter(m -> m.getCategory() == category).toList();
    }

    public List<Module.Category> getCategories() {
        return Arrays.asList(Module.Category.values());
    }

    public void onLoad() {
        getModules().forEach(Module::onLoad);
    }

    public void onTick() {
        stream().filter(Feature::isEnabled).forEach(Module::onTick);
        stream().filter(m -> m.isEnabled()
                && m.getBindMode() == Module.BindMode.HOLD
                && !m.getBind().isDown())
                .toList()
                .forEach(Module::disable);
    }

    public void onRender2D(Render2DEvent event) {
        stream().filter(Feature::isEnabled).forEach(module -> module.onRender2D(event));
    }

    public void onRender3D(Render3DEvent event) {
        stream().filter(Feature::isEnabled).forEach(module -> module.onRender3D(event));
    }

    public void onUnload() {
        getModules().forEach(EVENT_BUS::unregister);
        getModules().forEach(Module::onUnload);
    }

    public void onKeyPressed(int key) {
        if (key <= 0 || mc.screen != null) return;
        stream().filter(module -> module.getBind().getKey() == key).forEach(module -> {
            if (module.getBindMode() == Module.BindMode.HOLD) {
                if (!module.isEnabled()) module.enable();
            } else {
                module.toggle();
            }
        });
    }

    public void onKeyReleased(int key) {
        if (key <= 0) return;
        stream().filter(module -> module.getBind().getKey() == key
                && module.getBindMode() == Module.BindMode.HOLD
                && module.isEnabled())
                .forEach(Module::disable);
    }

    public void onMousePressed(int button) {
        if (mc.screen != null) return;
        int key = Bind.MOUSE_BUTTON_OFFSET + button;
        stream().filter(module -> module.getBind().getKey() == key).forEach(module -> {
            if (module.getBindMode() == Module.BindMode.HOLD) {
                if (!module.isEnabled()) module.enable();
            } else {
                module.toggle();
            }
        });
    }

    public void onMouseReleased(int button) {
        int key = Bind.MOUSE_BUTTON_OFFSET + button;
        stream().filter(module -> module.getBind().getKey() == key
                && module.getBindMode() == Module.BindMode.HOLD
                && module.isEnabled())
                .forEach(Module::disable);
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        for (Module module : getModules()) {
            object.add(module.getName(), module.toJson());
        }
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        for (Module module : getModules()) {
            try {
                module.fromJson(element.getAsJsonObject().get(module.getName()));
            } catch (Throwable e) {
                LOGGER.error("Failed to load module {}", module.getName(), e);
            }
        }
    }

    @Override
    public String getFileName() {
        return "modules.json";
    }
}
