package dev.leonetic.features.modules.player;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.modules.Module;
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
//hello leonetic your old autoxp was dogshit
public class AutoXPModule extends Module {

    private final Setting<Bind> throwBind = key("Throw", Bind.none());
    private final Setting<Boolean> pauseInAir = bool("PauseInAir", true);
    private final Setting<Boolean> autoRepair = bool("AutoRepair", true);

    private static final int THROWS_PER_BATCH = 6;
    private static final long BATCH_INTERVAL_MS = 300;

    private boolean throwing;
    private boolean keyWasDown;
    private long lastThrowMs;

    public AutoXPModule() {
        super("AutoXP", "Throws XP bottles to mend your armor and tools.", Category.PLAYER);
    }

    @Override
    public void onDisable() {
        throwing = false;
        keyWasDown = false;
        lastThrowMs = 0;
    }

    @Override
    public String getDisplayInfo() {
        if (!throwing) return null;
        if (!nullCheck() && isPaused()) return "Paused";
        return xpNeeded() + " XP";
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) return;

        handleThrowBind();

        if (mc.screen != null) return;

        if (throwing) {
            if (!isPaused()) tickThrow();
            return;
        }

        if (!autoRepair.getValue()) return;
        if (xpNeeded() <= 0) return;
        if (isPaused()) return;
        if (!shouldThrowNow()) return;
        throwBottles(THROWS_PER_BATCH);
    }

    private boolean isPaused() {
        return pauseInAir.getValue() && !mc.player.onGround();
    }

    private void handleThrowBind() {
        if (mc.screen != null) {
            keyWasDown = false;
            return;
        }
        boolean down = !throwBind.getValue().isEmpty() && throwBind.getValue().isDown();
        if (down && !keyWasDown) {
            throwing = !throwing;
            if (throwing) {
                lastThrowMs = 0;
                Command.sendMessage("{green} AutoXP mending, " + xpNeeded() + " XP required.");
            } else {
                Command.sendMessage("{red} AutoXP throw disabled.");
            }
        }
        keyWasDown = down;
    }

    private void tickThrow() {
        if (xpNeeded() <= 0) {
            throwing = false;
            Command.sendMessage("{green} AutoXP mending complete.");
            return;
        }
        if (!InventoryUtil.find(Items.EXPERIENCE_BOTTLE, FULL_SCOPE).found()) {
            throwing = false;
            Command.sendMessage("{red} AutoXP out of XP bottles.");
            return;
        }
        if (!shouldThrowNow()) return;
        throwBottles(THROWS_PER_BATCH);
    }

    private void throwBottles(int amount) {
        Result xp = InventoryUtil.find(Items.EXPERIENCE_BOTTLE, FULL_SCOPE);
        if (!xp.found()) return;

        float yaw = mc.player.getYRot();
        float pitch = 90f;
        Homovore.rotationManager.submit(new RotationRequest("AutoXP", 40, yaw, pitch, RotationRequest.Mode.SILENT));
        mc.gameMode.ensureHasSentCarriedItem();
        Homovore.swapManager.submit(new SwapRequest("AutoXP", 100, xp, r -> {
            for (int i = 0; i < amount; i++) {
                try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                    mc.getConnection().send(new ServerboundUseItemPacket(r.hand(), handler.currentSequence(), yaw, pitch));
                }
            }
        }));
    }

    private boolean shouldThrowNow() {
        long now = System.currentTimeMillis();
        if (now - lastThrowMs < BATCH_INTERVAL_MS) return false;
        lastThrowMs = now;
        return true;
    }

    private int xpNeeded() {
        int total = 0;
        for (ItemStack stack : gear()) total += xpForItem(stack);
        return total;
    }

    private int xpForItem(ItemStack stack) {
        if (!isMendable(stack)) return 0;
        int damage = stack.getDamageValue();
        return damage <= 0 ? 0 : (int) Math.ceil(damage / 2.0);
    }

    private ItemStack[] gear() {
        return new ItemStack[]{
                mc.player.getItemBySlot(EquipmentSlot.HEAD),
                mc.player.getItemBySlot(EquipmentSlot.CHEST),
                mc.player.getItemBySlot(EquipmentSlot.LEGS),
                mc.player.getItemBySlot(EquipmentSlot.FEET),
                mc.player.getMainHandItem(),
                mc.player.getOffhandItem()
        };
    }

    private boolean isMendable(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxDamage() > 0 && EnchantmentUtil.has(Enchantments.MENDING, stack);
    }
}
