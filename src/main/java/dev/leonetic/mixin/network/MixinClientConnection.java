package dev.leonetic.mixin.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import dev.leonetic.event.impl.network.PacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.leonetic.util.traits.Util.EVENT_BUS;

@Mixin(Connection.class)
public class MixinClientConnection {

    @Shadow
    private Channel channel;
    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    public void channelRead0(ChannelHandlerContext chc, Packet<?> packet, CallbackInfo ci) {
        if (this.channel.isOpen() && packet != null) {
            try {
                if (silentSwapFix(packet)) {
                    ci.cancel();
                    return;
                }
                if (EVENT_BUS.post(new PacketEvent.Receive(packet))) {
                    ci.cancel();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean silentSwapFix(Packet<?> packet) {
        if (!(packet instanceof ClientboundContainerSetSlotPacket slot)) return false;
        if (slot.getContainerId() != 0) return false;
        int s = slot.getSlot();
        if (s < 36 || s > 44) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        ItemStack packetStack = slot.getItem();
        ItemStack handStack = mc.player.getMainHandItem();
        if (packetStack.isEmpty() || handStack.isEmpty()) return false;
        return ItemStack.isSameItem(packetStack, handStack) && packetStack.getCount() == handStack.getCount();
    }

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void sendImmediately(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) return;
        try {
            if (EVENT_BUS.post(new PacketEvent.Send(packet))) {
                ci.cancel();
            }
        } catch (Exception ignored) {
        }
    }

}
