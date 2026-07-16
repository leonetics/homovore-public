package dev.leonetic.mixin.client;

import dev.leonetic.features.modules.client.ChatMentionsModule;
import dev.leonetic.util.player.ChatUtil;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class MixinChatComponent {

    @Unique private static final double homovore$SPEED = 12.0;
    @Unique private static final int homovore$MAX_LINES = 3;

    @Unique private boolean homovore$pushed;
    @Unique private float homovore$offset;
    @Unique private GuiMessage.Line homovore$lastHead;
    @Unique private long homovore$lastTimeMs;
    @Unique private boolean homovore$decoratingMention;

    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void homovore$decorateMention(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (homovore$decoratingMention) return;

        Component decorated = ChatMentionsModule.decorate(message);
        if (decorated == message) return;

        ci.cancel();
        homovore$decoratingMention = true;
        try {
            ((ChatComponent) (Object) this).addMessage(decorated, signature, tag);
        } finally {
            homovore$decoratingMention = false;
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
            at = @At("HEAD")
    )
    private void homovore$slideIn(GuiGraphics graphics, Font font, int tickCount, int mouseX, int mouseY,
                                  boolean focused, boolean bl, CallbackInfo ci) {
        homovore$pushed = false;

        float dy = homovore$update(((ChatComponentAccessor) this).homovore$getTrimmedMessages());
        if (dy != 0f) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(0f, dy);
            homovore$pushed = true;
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
            at = @At("RETURN")
    )
    private void homovore$slideInEnd(GuiGraphics graphics, Font font, int tickCount, int mouseX, int mouseY,
                                     boolean focused, boolean bl, CallbackInfo ci) {
        if (homovore$pushed) {
            graphics.pose().popMatrix();
            homovore$pushed = false;
        }
    }

    @Unique
    private float homovore$update(List<GuiMessage.Line> lines) {
        long now = System.currentTimeMillis();

        float dt = Math.min((now - homovore$lastTimeMs) / 1000f, 0.1f);
        homovore$lastTimeMs = now;

        GuiMessage.Line head = lines.isEmpty() ? null : lines.get(0);
        if (head != homovore$lastHead) {

            if (homovore$lastHead != null && head != ChatUtil.noAnimateHead) {

                int newLines = 0;
                for (GuiMessage.Line line : lines) {
                    if (line == homovore$lastHead) break;
                    newLines++;
                }
                float pitch = homovore$linePitch();
                homovore$offset = Math.min(homovore$offset + newLines * pitch, homovore$MAX_LINES * pitch);
            }
            homovore$lastHead = head;
        }

        homovore$offset *= (float) Math.exp(-homovore$SPEED * dt);
        if (homovore$offset < 0.5f) {
            homovore$offset = 0f;
        }
        return homovore$offset;
    }

    @Unique
    private float homovore$linePitch() {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.options.chatScale().get();
        double spacing = mc.options.chatLineSpacing().get();
        return (float) (9.0 * (spacing + 1.0) * scale);
    }
}
