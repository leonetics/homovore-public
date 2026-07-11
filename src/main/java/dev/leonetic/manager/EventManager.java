package dev.leonetic.manager;

import dev.leonetic.Homovore;
import dev.leonetic.event.Stage;
import dev.leonetic.event.impl.entity.DeathEvent;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.entity.player.UpdateWalkingPlayerEvent;
import dev.leonetic.event.impl.input.KeyInputEvent;
import dev.leonetic.event.impl.input.MouseInputEvent;
import dev.leonetic.event.impl.network.ChatEvent;
import dev.leonetic.event.impl.render.Render2DEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.Feature;
import dev.leonetic.features.modules.client.ClickGuiModule;
import net.minecraft.world.entity.player.Player;

public class EventManager extends Feature {
    public void init() {
        EVENT_BUS.register(this);
    }

    public void onUnload() {
        EVENT_BUS.unregister(this);
    }

    @Subscribe
    public void onTick(TickEvent event) {
        if (nullCheck())
            return;
        Homovore.moduleManager.onTick();
        for (Player player : mc.level.players()) {
            if (player == null || player.getHealth() > 0.0F)
                continue;
            EVENT_BUS.post(new DeathEvent(player));
        }
    }

    @Subscribe(priority = 100)
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        if (nullCheck())
            return;
        if (event.getStage() == Stage.PRE) {
            Homovore.positionManager.updatePosition();
        }
        if (event.getStage() == Stage.POST) {
            Homovore.positionManager.restorePosition();
        }
    }

    @Subscribe
    public void onWorldRender(Render3DEvent event) {
        Homovore.moduleManager.onRender3D(event);
    }

    @Subscribe
    public void onRenderGameOverlayEvent(Render2DEvent event) {
        Homovore.moduleManager.onRender2D(event);
    }

    @Subscribe
    public void onKeyInput(KeyInputEvent event) {
        if (event.getAction() == 1) {
            if (mc.screen == null && ClickGuiModule.matchesKey(event.getKey())) {
                ClickGuiModule.syncModuleBind();
                ClickGuiModule.getInstance().toggle();
            } else {
                Homovore.moduleManager.onKeyPressed(event.getKey());
            }
        } else if (event.getAction() == 0) {
            Homovore.moduleManager.onKeyReleased(event.getKey());
        }
    }

    @Subscribe
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() == 1) {
            Homovore.moduleManager.onMousePressed(event.getButton());
        } else if (event.getAction() == 0) {
            Homovore.moduleManager.onMouseReleased(event.getButton());
        }
    }

    @Subscribe
    public void onChatSent(ChatEvent event) {
        String message = event.getMessage();
        if (!message.startsWith(Homovore.commandManager.getCommandPrefix())) {
            return;
        }
        event.cancel();
        Homovore.commandManager.onChatSent(message);
    }
}
