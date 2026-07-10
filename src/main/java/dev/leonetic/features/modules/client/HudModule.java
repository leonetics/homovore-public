package dev.leonetic.features.modules.client;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.util.traits.Util;
import net.minecraft.client.gui.screens.ChatScreen;

public abstract class HudModule implements Util {
    private static final int CHAT_INPUT_HEIGHT = 14;

    private final String name;

    public HudModule(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public abstract void render(Render2DEvent event);

    protected int screenWidth() {
        return mc.getWindow().getGuiScaledWidth();
    }

    protected int screenHeight() {
        return mc.getWindow().getGuiScaledHeight();
    }

    protected int chatOffset() {
        return mc.screen instanceof ChatScreen ? CHAT_INPUT_HEIGHT : 0;
    }

    protected int bottomAnchor() {
        return screenHeight() - chatOffset();
    }
}
