package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.DamageUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.BlockHitResult;

public class OffhandModule extends Module {

    private final Setting<Boolean> antiGhost = bool("AntiGhost",
            true);
    private final Setting<Boolean> debugLog = bool("DebugLog",
            true);

    private final Setting<Boolean> lethalPriority = bool("LethalPriority",
            true);
    private final Setting<Boolean> lethalCrystal = bool("LethalCrystal", true);
    private final Setting<Double> lethalRange = num("LethalRange", 8.0, 1.0, 12.0);

    private boolean managingGapple = false;

    private int originalSlot = -1;

    private int movedFromSlot = -1;

    private boolean eatingGappleLatch = false;
    private int eatGraceTicks = 0;

    private static final int EAT_LATCH_GRACE = 3;

    private static final int MAX_REFILL_ATTEMPTS = 3;

    public OffhandModule() {
        super("Offhand", "Keeps a totem in your offhand and swaps the mainhand to a gapple on right-click.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        if (!nullCheck() && managingGapple) restoreGapple();
        managingGapple = false;
        originalSlot = -1;
        movedFromSlot = -1;
        eatingGappleLatch = false;
        eatGraceTicks = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck()) { eatingGappleLatch = false; eatGraceTicks = 0; return; }

        updateEatingGappleLatch();

        if (mc.player.containerMenu.containerId != 0) return;

        if (!InventoryUtil.cursor().isEmpty()) return;

        if (!mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            refillOffhandTotem();
        }

        handleMainhandGapple();
    }

    private void handleMainhandGapple() {
        boolean rmb = mc.options.keyUse.isDown();
        boolean onInteractable = rmb && isInteractableBlock();
        boolean lethal = lethalPriority.getValue() && isLethalThreat();

        boolean want = rmb && !onInteractable && !lethal && (isMainhandWeapon() || managingGapple);

        if (managingGapple) {
            boolean mainhandIsGapple = mc.player.getMainHandItem().is(Items.ENCHANTED_GOLDEN_APPLE);

            if (!mainhandIsGapple) {
                restoreGapple();
                return;
            }

            if (!want) {
                if (isEatingGapple()) return;
                restoreGapple();
            }
            return;
        }

        if (!want) return;

        if (mc.player.isUsingItem() || !isMainhandWeapon()) return;

        int hotbarGapple = findGappleInHotbarExcludingSelected();
        if (hotbarGapple != -1) {
            originalSlot = InventoryUtil.selected();
            movedFromSlot = -1;
            InventoryUtil.swap(hotbarGapple);
            managingGapple = true;
            return;
        }

        int gappleSlot = findGappleInInventory();
        if (gappleSlot != -1) {
            originalSlot = InventoryUtil.selected();
            movedFromSlot = gappleSlot;
            InventoryUtil.swapToHotbarSlot(gappleSlot, originalSlot);
            managingGapple = true;
        }
    }

    private void restoreGapple() {
        if (movedFromSlot != -1) {

            InventoryUtil.swapToHotbarSlot(movedFromSlot, originalSlot);
        } else if (originalSlot != -1) {

            InventoryUtil.swap(originalSlot);
        }
        managingGapple = false;
        originalSlot = -1;
        movedFromSlot = -1;
    }

    private boolean refillOffhandTotem() {
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true;
        if (mc.player.containerMenu.containerId != 0) return false;
        if (!InventoryUtil.cursor().isEmpty()) return false;

        for (int attempt = 0; attempt < MAX_REFILL_ATTEMPTS; attempt++) {
            int slot = findTotem();
            if (slot == -1) {
                gapLog("offhand refill FAILED — no totem in inventory (attempt " + attempt + ")");
                return false;
            }

            if (!mc.player.getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) continue;

            gapLog("swapToOffhand src=" + slot + " (button 40) attempt " + attempt);
            InventoryUtil.swapToOffhand(slot);

            if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true;
        }

        gapLog("offhand refill EXHAUSTED — offhand still empty after " + MAX_REFILL_ATTEMPTS + " attempts");
        return false;
    }

    private boolean isLethalThreat() {
        if (nullCheck() || !lethalCrystal.getValue()) return false;

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double range = lethalRange.getValue();
        AABB area = mc.player.getBoundingBox().inflate(range);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof EndCrystal crystal)) continue;
            if (mc.player.distanceToSqr(crystal) > range * range) continue;
            if (DamageUtil.crystalMaxSelfDamage(crystal.position()) + 0.5f >= health) return true;
        }
        return false;
    }

    @Subscribe(priority = 1)
    private void onPacket(PacketEvent.Receive event) {
        if (nullCheck() || !antiGhost.getValue()) return;

        if (event.getPacket() instanceof ClientboundEntityEventPacket pkt) {
            if (pkt.getEventId() != 35 || pkt.getEntity(mc.level) != mc.player) return;

            if (mc.player.containerMenu.containerId != 0) return;
            gapLog("totem-pop refill (EntityEvent 35, netty thread)");
            mc.player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            refillOffhandTotem();
        }
    }

    private int findTotem() {
        for (int i = 35; i >= 9; i--) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
        }
        for (int i = 8; i >= 0; i--) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findGappleInHotbarExcludingSelected() {
        int selected = InventoryUtil.selected();
        for (int i = 0; i < 9; i++) {
            if (i == selected) continue;
            if (mc.player.getInventory().getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE)) return i;
        }
        return -1;
    }

    private int findGappleInInventory() {
        for (int i = 35; i >= 9; i--) {
            if (mc.player.getInventory().getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE)) return i;
        }
        return -1;
    }

    @Override
    public String getDisplayInfo() {
        if (nullCheck()) return null;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.TOTEM_OF_UNDYING)) count += s.getCount();
        }
        return String.valueOf(count);
    }

    private boolean isMainhandWeapon() {
        ItemStack mh = mc.player.getMainHandItem();
        if (mh.isEmpty()) return true;
        if (mh.is(ItemTags.SWORDS) || mh.is(ItemTags.AXES)) return true;
        Item item = mh.getItem();
        return item instanceof TridentItem || item instanceof MaceItem;
    }

    private boolean isInteractableBlock() {
        if (mc.hitResult instanceof BlockHitResult bhr) {
            var state = mc.level.getBlockState(bhr.getBlockPos());
            if (state.getMenuProvider(mc.level, bhr.getBlockPos()) != null) return true;
            var block = state.getBlock();
            if (block instanceof DoorBlock || block instanceof TrapDoorBlock
                    || block instanceof FenceGateBlock || block instanceof ButtonBlock
                    || block instanceof LeverBlock || block instanceof ShulkerBoxBlock
                    || block instanceof EnderChestBlock) return true;
        }
        return false;
    }

    public boolean isEatingGapple() {
        if (nullCheck() || !mc.player.isUsingItem()) return false;
        return mc.player.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    public boolean isManagingGapple() {
        return managingGapple || eatingGappleLatch;
    }

    public boolean shouldDeferForEat() {
        return isManagingGapple() || intendingToEat();
    }

    public boolean intendingToEat() {
        if (nullCheck() || !mc.options.keyUse.isDown()) return false;
        return isConsumable(mc.player.getMainHandItem()) || isConsumable(mc.player.getOffhandItem());
    }

    private boolean isConsumable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.has(DataComponents.FOOD)) return true;
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK;
    }

    private void updateEatingGappleLatch() {
        boolean eatingNow = mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && mc.player.getMainHandItem().is(Items.ENCHANTED_GOLDEN_APPLE);
        if (eatingNow) {
            eatingGappleLatch = true;
            eatGraceTicks = EAT_LATCH_GRACE;
        } else if (eatingGappleLatch && --eatGraceTicks <= 0) {
            eatingGappleLatch = false;
        }
    }

    private String gapState() {
        boolean eating = isEatingGapple();
        int remaining = (mc.player != null && mc.player.isUsingItem())
                ? mc.player.getUseItemRemainingTicks() : -1;
        return "managing=" + managingGapple + " latch=" + eatingGappleLatch
                + " eating=" + eating + " useRemaining=" + remaining + "t";
    }

    private void gapLog(String where) {
        if (debugLog.getValue()) Homovore.LOGGER.info("[Offhand] {} ({})", where, gapState());
    }
}
