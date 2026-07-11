package dev.leonetic.features.modules.render;

import dev.leonetic.Homovore;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;

import java.util.function.Predicate;

public class NoRenderModule extends Module {
    public final Setting<Boolean> noTilt = bool("NoTilt", true).setPage("Camera");
    public final Setting<Boolean> noBob = bool("NoBob", true).setPage("Camera");
    public final Setting<Boolean> noTotem = bool("NoTotem", true).setPage("Camera");

    public final Setting<Boolean> noFire = bool("NoFire", true).setPage("Overlays");
    public final Setting<Boolean> noPumpkin = bool("NoPumpkin", false).setPage("Overlays");
    public final Setting<Boolean> noPowderedSnow = bool("NoPowdered", false).setPage("Overlays");
    public final Setting<Boolean> portalGui = bool("PortalGui", true).setPage("Overlays");
    public final Setting<Boolean> noPortal = bool("NoPortalGui", true).setPage("Overlays");
    public final Setting<Boolean> noBackground = bool("NoBackground", true).setPage("Overlays");
    public final Setting<Boolean> noVignette = bool("NoVignette", true).setPage("Overlays");
    public final Setting<Boolean> noBossBar = bool("NoBoss", true).setPage("Overlays");
    public final Setting<Boolean> noPotIcon = bool("NoPotIcon", true).setPage("Overlays");
    public final Setting<Boolean> noCrosshair = bool("NoCrosshair", false).setPage("Overlays");
    public final Setting<Boolean> noCooldown = bool("NoCooldown", false).setPage("Overlays");
    public final Setting<Boolean> noItemName = bool("NoItemName", false).setPage("Overlays");
    public final Setting<Boolean> noArmor = bool("NoArmor", true).setPage("Overlays");
    public final Setting<Boolean> noNausea = bool("NoNausea", true).setPage("Overlays");

    public final Setting<Boolean> noTotemParticle = bool("NoPopParticle", false).setPage("Particles");
    public final Setting<Boolean> noWaterParticle = bool("NoWaterParticle", true).setPage("Particles");
    public final Setting<Boolean> noExplosion = bool("NoExplosion", true).setPage("Particles");
    public final Setting<Boolean> noBlockBreak = bool("NoBreakParticle", false).setPage("Particles");
    public final Setting<Boolean> noPotionParticle = bool("NoPotionParticle", false).setPage("Particles");

    public final Setting<Integer> tileEntity = num("TileEntity", 0, 0, 75).setPage("World");
    public final Setting<Boolean> noLiquid = bool("NoLiquid", false).setPage("World");
    public final Setting<Boolean> noWall = bool("NoWall", false).setPage("World");
    public final Setting<Boolean> noFog = bool("NoFog", true).setPage("World");
    public final Setting<Boolean> noDarkness = bool("NoDarkness", true).setPage("World");
    public final Setting<Boolean> noCrystalFire = bool("NoCrystalFire", false).setPage("World");

    public NoRenderModule() {
        super("NoRender", "Prevent rendering certain overlays and visual effects.", Category.RENDER);
    }

    @Override
    public void onEnable() {
        reloadRenderer();
    }

    @Override
    public void onDisable() {
        reloadRenderer();
    }

    private void reloadRenderer() {
        if (mc.level != null) {
            mc.levelRenderer.allChanged();
        }
    }

    public static NoRenderModule getInstance() {
        if (Homovore.moduleManager == null) return null;
        return Homovore.moduleManager.getModuleByClass(NoRenderModule.class);
    }

    public static boolean isActive(Predicate<NoRenderModule> predicate) {
        NoRenderModule module = getInstance();
        return module != null && module.isEnabled() && predicate.test(module);
    }
}
