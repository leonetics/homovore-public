package dev.leonetic.features.modules.player;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.combat.OffhandModule;
import dev.leonetic.features.settings.Bind;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapRequest;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.util.EnchantmentUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import static dev.leonetic.util.inventory.InventoryUtil.FULL_SCOPE;

public class AutoXPModule extends Module {

    private final Setting<Integer> minDurability = num("MinDurability", 40, 1, 100);
    private final Setting<Integer> stopDurability = num("StopDurability", 70, 0, 100);
    private final Setting<Bind> throwBind = key("Throw", Bind.none());

    private static final int THROWS_PER_BATCH = 9;
    private static final long BATCH_INTERVAL_MS = 300;

    private boolean throwing;
    private long lastThrowMs;

    public AutoXPModule() {
        super("AutoXP", "Throws XP bottles when your head is phased in a block and armor durability is low.", Category.PLAYER);
    }

    @Override
    public void onDisable() {
        throwing = false;
        lastThrowMs = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck() || mc.screen != null) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        boolean manual = !throwBind.getValue().isEmpty() && throwBind.getValue().isDown();

        if (anyArmorAtOrBelow(minDurability.getValue())) throwing = true;
        else if (throwing && allArmorAbove(stopDurability.getValue())) throwing = false;

        // Holding the Throw bind forces a throw regardless of the durability
        // auto-gate; otherwise fall back to the automatic mending behaviour.
        if (!manual && !throwing) return;
        if (!shouldThrowNow()) return;

        Result xpBottle = InventoryUtil.find(Items.EXPERIENCE_BOTTLE, FULL_SCOPE);
        if (xpBottle.found()) {
            int amount = THROWS_PER_BATCH;
            float yaw = mc.player.getYRot();
            float pitch = 90f;
            Homovore.rotationManager.submit(new RotationRequest(
                "AutoXP", 40, yaw, pitch, RotationRequest.Mode.SILENT
            ));
            mc.gameMode.ensureHasSentCarriedItem();

            Homovore.swapManager.submit(new SwapRequest("AutoXP", 40, xpBottle, r -> {
                for (int i = 0; i < amount; i++) {
                    try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                        mc.getConnection().send(new ServerboundUseItemPacket(r.hand(), handler.currentSequence(), yaw, pitch));
                    }
                }
            }));
        }
    }

    private boolean shouldThrowNow() {
        long now = System.currentTimeMillis();
        if (now - lastThrowMs < BATCH_INTERVAL_MS) return false;
        lastThrowMs = now;
        return true;
    }

    private boolean anyArmorAtOrBelow(int pct) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = mc.player.getItemBySlot(slot);
            if (!armor.isEmpty() && armor.getMaxDamage() > 0 && EnchantmentUtil.has(Enchantments.MENDING, armor)) {
                float durabilityPct = (float)(armor.getMaxDamage() - armor.getDamageValue()) / armor.getMaxDamage() * 100f;
                if (durabilityPct <= pct) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean allArmorAbove(int pct) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = mc.player.getItemBySlot(slot);
            if (!armor.isEmpty() && armor.getMaxDamage() > 0 && EnchantmentUtil.has(Enchantments.MENDING, armor)) {
                float durabilityPct = (float)(armor.getMaxDamage() - armor.getDamageValue()) / armor.getMaxDamage() * 100f;
                if (durabilityPct <= pct) return false;
            }
        }
        return true;
    }
}
