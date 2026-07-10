package dev.leonetic.features.modules.client;

import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.util.traits.Util;
import net.minecraft.client.gui.screens.ChatScreen;

public abstract class HudModule implements Util {
    private static final int CHAT_INPUT_HEIGHT = 14;

    protected static final int SCREEN_MARGIN = 2;

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

    protected int lineX(HudPosition pos, int lineWidth) {
        return pos.isLeft() ? SCREEN_MARGIN : screenWidth() - SCREEN_MARGIN - lineWidth;
    }

    protected int blockTop(HudPosition pos, int ownLines, int linesBelow, int gap) {
        if (pos.isBottom()) {
            return bottomAnchor() - SCREEN_MARGIN - (linesBelow + ownLines) * mc.font.lineHeight - gap;
        }
        return screenHeight() / 2;
    }
}
