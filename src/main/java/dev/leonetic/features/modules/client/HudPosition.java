package dev.leonetic.features.modules.client;

public enum HudPosition {
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    CENTER_RIGHT,
    CENTER_LEFT;

    public boolean isLeft() {
        return this == BOTTOM_LEFT || this == CENTER_LEFT;
    }

    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }
}
