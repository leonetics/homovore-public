package dev.leonetic.features.modules.render;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;

import java.util.function.Predicate;

public class ViewModelModule extends Module {
    public final Setting<Double> scale = num("Scale", 1.0, 0.0, 3.0);
    public final Setting<Double> posX = num("PosX", 0.0, -1.0, 1.0);
    public final Setting<Double> posY = num("PosY", 0.0, -1.0, 1.0);
    public final Setting<Double> posZ = num("PosZ", 0.0, -1.0, 1.0);
    public final Setting<Boolean> noSway = bool("NoSway", false);
    public final Setting<Boolean> noSwapAnimation = bool("NoSwapAnimation", false);

    public ViewModelModule() {
        super("ViewModel", "change scale position and stuff", Category.RENDER);
    }

    public static ViewModelModule getInstance() {
        if (Homovore.moduleManager == null) return null;
        return Homovore.moduleManager.getModuleByClass(ViewModelModule.class);
    }

    public static boolean isActive(Predicate<ViewModelModule> predicate) {
        ViewModelModule module = getInstance();
        return module != null && module.isEnabled() && predicate.test(module);
    }
}
