package dev.leonetic.features.modules.player;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.Module;

public class NoRotateModule extends Module {

    public NoRotateModule() {
        super("NoRotate", "Ignores server-forced rotations.", Category.PLAYER);
    }

    public static boolean isActive() {
        if (Homovore.moduleManager == null) return false;
        NoRotateModule module = Homovore.moduleManager.getModuleByClass(NoRotateModule.class);
        return module != null && module.isEnabled();
    }
}
