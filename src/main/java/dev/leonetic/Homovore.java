package dev.leonetic;

import dev.leonetic.features.GuiMove;
import dev.leonetic.features.modules.client.ClickGuiModule;
import dev.leonetic.manager.*;
import dev.leonetic.util.BuildConfig;
import dev.leonetic.util.TextUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Homovore implements ModInitializer, ClientModInitializer {
    public static float TIMER = 1f;
    public static final Identifier AUTOHORSE_SOUND_ID = Identifier.fromNamespaceAndPath("homovore", "autohorse");
    public static final SoundEvent AUTOHORSE_SOUND = SoundEvent.createVariableRangeEvent(AUTOHORSE_SOUND_ID);

    public static final Logger LOGGER = LogManager.getLogger("Homovore");
    public static ColorManager colorManager;
    public static PositionManager positionManager;
    public static EventManager eventManager;
    public static CommandManager commandManager;
    public static FriendManager friendManager;
    public static EnemyManager enemyManager;
    public static PlayerInfoManager playerInfoManager;
    public static TurtleMasterManager turtleMasterManager;
    public static ModuleManager moduleManager;
    public static ConfigManager configManager;
    public static PlacementManager placementManager;
    public static RotationManager rotationManager;
    public static SwapManager swapManager;
    public static TPSCounterService tpsCounterService;
    public static GuiMove guiMove;

    @Override
    public void onInitialize() {
        LOGGER.info("Pre-initializing {} v{}",
                BuildConfig.NAME, BuildConfig.VERSION);
        Registry.register(BuiltInRegistries.SOUND_EVENT, AUTOHORSE_SOUND_ID, AUTOHORSE_SOUND);
        configManager = new ConfigManager();
        eventManager = new EventManager();
        positionManager = new PositionManager();
        friendManager = new FriendManager();
        enemyManager = new EnemyManager();
        playerInfoManager = new PlayerInfoManager();
        turtleMasterManager = new TurtleMasterManager();
        colorManager = new ColorManager();
        commandManager = new CommandManager();
        moduleManager = new ModuleManager();
        placementManager = new PlacementManager();
        rotationManager = new RotationManager();
        swapManager = new SwapManager();
        tpsCounterService = new TPSCounterService();
        guiMove = new GuiMove();

        TextUtil.init();
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing {}", BuildConfig.NAME);

        long startTime = System.nanoTime();

        ClickGuiModule.registerKeyMapping();
        eventManager.init();
        rotationManager.init();
        swapManager.init();
        commandManager.init();
        moduleManager.init();
        friendManager.init();
        enemyManager.init();
        playerInfoManager.init();
        turtleMasterManager.init();
        tpsCounterService.init();
        guiMove.init();

        configManager.load();
        if (!commandManager.isFunnyVisible()) {
            moduleManager.stream()
                    .filter(m -> m.getCategory() == dev.leonetic.features.modules.Module.Category.FUNNY)
                    .filter(m -> m.isEnabled())
                    .forEach(dev.leonetic.features.modules.Module::disable);
        }
        moduleManager.onLoad();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> configManager.save()));

        long endTime = System.nanoTime();

        LOGGER.info("Initialized {} in {}ms",
                BuildConfig.NAME, (endTime - startTime) / 1000000.0);
    }
}
