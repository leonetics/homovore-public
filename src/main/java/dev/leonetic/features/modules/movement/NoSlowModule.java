package dev.leonetic.features.modules.movement;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

public class NoSlowModule extends Module {
    public final Setting<Boolean> crawl = bool("Crawl", true);
    public final Setting<Boolean> eat   = bool("Eat", true);

    private static final int SPOOF_DELAY_MOVES  = 1;
    private static final int SPOOF_PACKET_COUNT = 37;
    private static final float MIN_EAT_TPS      = 15.0f;

    private int movesUntilSlotSpoof = 0;
    private int slotSpoofsRemaining = 0;
    private boolean useSessionActive = false;
    private boolean ignoreNextMovementPacket = false;
    private boolean wasConsuming = false;

    public NoSlowModule() {
        super("NoSlow", "remove slowdown", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        resetSpoof();
        wasConsuming = false;
    }

    @Override
    public void onDisable() {
        resetSpoof();
        wasConsuming = false;
    }

    @Override
    public void onTick() {
        boolean consuming = isConsumingMainHandItem();
        if (wasConsuming && !consuming) resetSpoof();
        wasConsuming = consuming;
        if (!consuming && !isHoldingUseOnConsumable()) resetSpoof();
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (!isEnabled() || !eatEnabled()) return;
        Packet<?> packet = event.getPacket();

        if (packet instanceof ServerboundUseItemPacket) {
            useSessionActive = true;
            movesUntilSlotSpoof = SPOOF_DELAY_MOVES;
            slotSpoofsRemaining = SPOOF_PACKET_COUNT;
            ignoreNextMovementPacket = true;
            return;
        }

        if (packet instanceof ServerboundMovePlayerPacket move) {
            if (nullCheck() || !shouldSpoofActive()) return;

            if (!move.hasRotation() && move.hasPosition()) {
                event.cancel();
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        mc.player.getYRot(), mc.player.getXRot(),
                        move.isOnGround(), mc.player.horizontalCollision));
                return;
            }

            handleMovementPacketSend(move);
        }
    }

    private void handleMovementPacketSend(ServerboundMovePlayerPacket packet) {
        if (!shouldSpoofActive() || !packet.hasPosition() || !packet.hasRotation()) return;

        if (ignoreNextMovementPacket) {
            ignoreNextMovementPacket = false;
            return;
        }

        if (movesUntilSlotSpoof > 0) {
            movesUntilSlotSpoof--;
            return;
        }

        if (slotSpoofsRemaining <= 0) return;
        if (mc.player == null || mc.getConnection() == null) return;

        int slot = mc.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot > 8) return;

        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        slotSpoofsRemaining--;
    }

    private boolean eatEnabled() {
        return eat.getValue() && Homovore.tpsCounterService.getLatestTPS() > MIN_EAT_TPS;
    }

    private boolean shouldSpoofActive() {
        return eatEnabled() && (
                isConsumingMainHandItem()
                        || movesUntilSlotSpoof > 0
                        || slotSpoofsRemaining > 0
                        || useSessionActive);
    }

    private void resetSpoof() {
        movesUntilSlotSpoof = 0;
        slotSpoofsRemaining = 0;
        useSessionActive = false;
        ignoreNextMovementPacket = false;
    }

    private boolean isConsumingMainHandItem() {
        if (mc.player == null) return false;
        if (!mc.player.isUsingItem() || mc.player.getUsedItemHand() != InteractionHand.MAIN_HAND) return false;
        return isConsumable(mc.player.getUseItem());
    }

    private boolean isHoldingUseOnConsumable() {
        if (mc.player == null || !mc.options.keyUse.isDown()) return false;
        return isConsumable(mc.player.getMainHandItem());
    }

    private boolean isConsumable(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.CONSUMABLE);
    }

    public static boolean shouldCancelConsumeSlow() {
        NoSlowModule module = getInstance();
        return module != null && module.isEnabled() && module.shouldSpoofActive();
    }

    public static NoSlowModule getInstance() {
        if (Homovore.moduleManager == null) return null;
        return Homovore.moduleManager.getModuleByClass(NoSlowModule.class);
    }

    public static boolean isActive(Predicate<NoSlowModule> predicate) {
        NoSlowModule module = getInstance();
        return module != null && module.isEnabled() && predicate.test(module);
    }
}
