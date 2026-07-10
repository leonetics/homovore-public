package dev.leonetic.features;

import dev.leonetic.event.impl.input.KeyInputEvent;
import dev.leonetic.event.impl.input.MouseInputEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.gui.HomovoreGui;
import dev.leonetic.util.traits.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

public class GuiMove implements Util {

    private boolean up, down, left, right, jump, shift, sprint;

    public void init() {
        EVENT_BUS.register(this);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (shouldRun()) {
                mc.options.keyUp.setDown(up);
                mc.options.keyDown.setDown(down);
                mc.options.keyLeft.setDown(left);
                mc.options.keyRight.setDown(right);
                mc.options.keyJump.setDown(jump);
                mc.options.keyShift.setDown(shift);
                mc.options.keySprint.setDown(sprint);
            }
        });
    }

    @Subscribe
    private void onKey(KeyInputEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_RELEASE) return;
        if (mc.screen instanceof HomovoreGui && isArrowKey(event.getKey())) return;
        
        boolean pressed = event.getAction() == GLFW.GLFW_PRESS;
        KeyEvent ke = new KeyEvent(event.getKey(), 0, 0);
        
        if (mc.options.keyUp.matches(ke)) up = pressed;
        else if (mc.options.keyDown.matches(ke)) down = pressed;
        else if (mc.options.keyLeft.matches(ke)) left = pressed;
        else if (mc.options.keyRight.matches(ke)) right = pressed;
        else if (mc.options.keyJump.matches(ke)) jump = pressed;
        else if (mc.options.keyShift.matches(ke)) shift = pressed;
        else if (mc.options.keySprint.matches(ke)) sprint = pressed;
    }

    @Subscribe
    private void onMouse(MouseInputEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_RELEASE) return;
        
        boolean pressed = event.getAction() == GLFW.GLFW_PRESS;
        MouseButtonEvent me = new MouseButtonEvent(0, 0, new MouseButtonInfo(event.getButton(), 0));
        
        if (mc.options.keyUp.matchesMouse(me)) up = pressed;
        else if (mc.options.keyDown.matchesMouse(me)) down = pressed;
        else if (mc.options.keyLeft.matchesMouse(me)) left = pressed;
        else if (mc.options.keyRight.matchesMouse(me)) right = pressed;
        else if (mc.options.keyJump.matchesMouse(me)) jump = pressed;
        else if (mc.options.keyShift.matchesMouse(me)) shift = pressed;
        else if (mc.options.keySprint.matchesMouse(me)) sprint = pressed;
    }

    private static boolean isArrowKey(int key) {
        return key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN;
    }

    private boolean shouldRun() {
        return mc.screen != null 
                && !(mc.screen instanceof ChatScreen)
                && !(mc.screen instanceof AbstractSignEditScreen)
                && !(mc.screen instanceof AnvilScreen)
                && !(mc.screen instanceof AbstractCommandBlockEditScreen)
                && !(mc.screen instanceof StructureBlockEditScreen);
    }

}
