package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapRequest;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.mixin.item.CooldownInstanceAccessor;
import dev.leonetic.mixin.item.ItemCooldownsAccessor;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.player.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class PhaseModule extends Module {

    private static final double CORNER_THRESHOLD = 0.5;
    private static final double CORNER_OFFSET = 0.5;

    private static final int PRIORITY = 150;

    public PhaseModule() {
        super("Phase", "Phases into walls", Category.COMBAT);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) {
            disable();
            return;
        }

        if (Homovore.rotationManager.isSilentSyncRequiredAtLeast(PRIORITY)) return;

        Result pearl = InventoryUtil.find(Items.ENDER_PEARL, InventoryUtil.FULL_SCOPE);
        if (!pearl.found()) {
            disable();
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            announceCooldown(pearlCooldownTicks());
            disable();
            return;
        }

        if (mc.player.isCrouching()) {
            disable();
            return;
        }

        Vec3 target = calculateTargetPos();
        float yaw = calcYaw(target);
        float pitch = mc.player.getBlockY() > 4 ? 85f : 75f;

        Homovore.rotationManager.submit(new RotationRequest("phase", PRIORITY, yaw, pitch, RotationRequest.Mode.SILENT));

        mc.gameMode.ensureHasSentCarriedItem();
        boolean thrown = Homovore.swapManager.submit(new SwapRequest("Phase", 80, pearl, r -> {
            try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                mc.getConnection().send(new ServerboundUseItemPacket(r.hand(), handler.currentSequence(), yaw, pitch));
            }
        }, true));

        if (thrown) disable();
    }

    private int pearlCooldownTicks() {
        ItemCooldowns cooldowns = mc.player.getCooldowns();
        Identifier group = cooldowns.getCooldownGroup(new ItemStack(Items.ENDER_PEARL));
        if (group == null) return 0;

        Object instance = ((ItemCooldownsAccessor) cooldowns).homovore$getCooldowns().get(group);
        if (!(instance instanceof CooldownInstanceAccessor cooldown)) return 0;

        int remaining = cooldown.homovore$getEndTime()
                - ((ItemCooldownsAccessor) cooldowns).homovore$getTickCount();
        return Math.max(0, remaining);
    }

    private void announceCooldown(int ticks) {
        ChatUtil.sendPersistent("phase:cooldown",
                Component.literal("Pearl cooldown is not finished: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(ticks + (ticks == 1 ? " tick" : " ticks"))
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                        .append(Component.literal(" remain.").withStyle(ChatFormatting.GRAY)));
    }

    private Vec3 calculateTargetPos() {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        double nearestIntX = Math.round(playerX);
        double nearestIntZ = Math.round(playerZ);
        double dxCorner = nearestIntX - playerX;
        double dzCorner = nearestIntZ - playerZ;

        double threshold = CORNER_THRESHOLD;
        double offset = CORNER_OFFSET;
        if (Math.abs(dxCorner) <= threshold && Math.abs(dzCorner) <= threshold) {
            return new Vec3(
                playerX + Mth.clamp(dxCorner, -offset, offset),
                mc.player.getY() - 0.5,
                playerZ + Mth.clamp(dzCorner, -offset, offset)
            );
        }

        final double A = Math.PI / 13;
        final double B = Math.PI / 4;

        double x = playerX + Mth.clamp(
            toClosest(playerX, Math.floor(playerX) + A, Math.floor(playerX) + B) - playerX,
            -0.2, 0.2);
        double z = playerZ + Mth.clamp(
            toClosest(playerZ, Math.floor(playerZ) + A, Math.floor(playerZ) + B) - playerZ,
            -0.2, 0.2);

        return new Vec3(x, mc.player.getY() - 0.5, z);
    }

    private double toClosest(double num, double min, double max) {
        return (num - min) > (max - num) ? max : min;
    }

    private float calcYaw(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 diff = target.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
    }
}
