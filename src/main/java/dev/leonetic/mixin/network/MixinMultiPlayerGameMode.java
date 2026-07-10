package dev.leonetic.mixin.network;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.AttackBlockEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.leonetic.util.traits.Util.EVENT_BUS;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Unique private float homovore$origUseYaw, homovore$origUsePitch;

    @Inject(method = "useItem", at = @At("HEAD"))
    private void homovore$useItemRotateHead(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (player != Minecraft.getInstance().player) return;
        if (Homovore.rotationManager == null || !Homovore.rotationManager.isRotating()) return;
        if (Homovore.rotationManager.isBypassUseSpoof()) return;

        homovore$origUseYaw = player.getYRot();
        homovore$origUsePitch = player.getXRot();
        player.setYRot(Homovore.rotationManager.getRotationYaw());
        player.setXRot(Homovore.rotationManager.getRotationPitch());
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void homovore$useItemRotateReturn(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (player != Minecraft.getInstance().player) return;
        if (Homovore.rotationManager == null || !Homovore.rotationManager.isRotating()) return;
        if (Homovore.rotationManager.isBypassUseSpoof()) return;

        player.setYRot(homovore$origUseYaw);
        player.setXRot(homovore$origUsePitch);
    }
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void homovore$onStartDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        AttackBlockEvent event = new AttackBlockEvent(pos, mc.level.getBlockState(pos), direction);
        if (EVENT_BUS.post(event)) {
            cir.setReturnValue(false);
        }
    }

}
