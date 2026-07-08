package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.util.EnchantmentUtil;
import dev.leonetic.util.MathUtil;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class AutoSwordModule extends Module {

    public enum TpsMode { NONE, LATEST, AVERAGE }

    private static final double RANGE = 3.0;

    private final Setting<Double> delay = num("Delay", 0.92, 0.0, 1.0);
    private final Setting<Boolean> render = bool("Render", true);
    private final Setting<TpsMode> tpsMode = mode("TPS", TpsMode.LATEST);

    private final Setting<Boolean> criticals = bool("Criticals", false);
    private final Setting<Boolean> critsWithSword = bool("CritsWithSword", true)
            .setVisibility(v -> criticals.getValue());
    private final Setting<Boolean> strict = bool("Strict", true)
            .setVisibility(v -> criticals.getValue());

    private Entity currentTarget = null;
    private float attackCooldownTicks = 0f;

    public AutoSwordModule() {
        super("AutoSword", "Automatically attacks nearby players with the best weapon.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        currentTarget = null;
        attackCooldownTicks = 0f;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        float tps;
        switch (tpsMode.getValue()) {
            case LATEST -> tps = Homovore.tpsCounterService.getLatestTPS();
            case AVERAGE -> tps = Homovore.tpsCounterService.getAverageTPS();
            default -> tps = 20f;
        }

        if (!Float.isFinite(tps) || tps < 1f) tps = 1f;

        attackCooldownTicks -= (tps / 20f);
        if (attackCooldownTicks < 0f) attackCooldownTicks = 0f;

        if (!isMaceAttackReady()) {
            AutoCrystalModule ac = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
            if (ac != null && ac.isEnabled() && ac.getLastBestDamage() > 0f) {
                currentTarget = null;
                return;
            }
        }

        currentTarget = findTarget();
        if (currentTarget == null) return;

        if (attackCooldownTicks > 0f) return;

        int weaponSlot = getWeapon();
        if (weaponSlot == -1) return;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 closestPoint = getClosestPointToEye(eyePos, currentTarget.getBoundingBox());
        float[] angles = MathUtil.calcAngle(eyePos, closestPoint);

        Homovore.rotationManager.submit(new RotationRequest(
            "AutoSword", 6, angles[0], angles[1], RotationRequest.Mode.SILENT
        ));

        float serverYaw = Homovore.rotationManager.getServerYaw();
        float serverPitch = Homovore.rotationManager.getServerPitch();
        if (!currentTarget.getBoundingBox().contains(eyePos)) {
            Vec3 lookVec = getLookVector(serverYaw, serverPitch);
            Vec3 reachEnd = eyePos.add(lookVec.scale(RANGE));
            if (currentTarget.getBoundingBox().clip(eyePos, reachEnd).isEmpty()) return;
        }

        ItemStack weaponStack = mc.player.getInventory().getItem(weaponSlot);
        boolean doCrit = shouldCrit(weaponStack);

        int originalSlot = Homovore.swapManager.serverSlot();
        boolean needSwap = weaponSlot != originalSlot;

        if (needSwap && mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            return;
        }

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (needSwap && offhand != null && offhand.shouldDeferForEat()) return;

        SwapManager.SwapHandle handle = null;
        if (needSwap) {
            handle = Homovore.swapManager.acquire("AutoSword", 70);
            if (handle == null) return;
        }

        try {
            if (needSwap) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(weaponSlot));
            }

            if (doCrit) {
                double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
                boolean hc = mc.player.horizontalCollision;
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y,          z, serverYaw, serverPitch, mc.player.onGround(), hc));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0625, z, serverYaw, serverPitch, false,                 hc));
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.045,  z, serverYaw, serverPitch, false,                 hc));
            }

            mc.gameMode.attack(mc.player, currentTarget);
            mc.player.swing(InteractionHand.MAIN_HAND);

            if (needSwap) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        } finally {
            if (handle != null) Homovore.swapManager.release(handle);
        }

        attackCooldownTicks = getBaseCooldownTicks(weaponStack, tps) * delay.getValue().floatValue();
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || currentTarget == null || nullCheck() || mc.player.isDeadOrDying()) return;

        AutoCrystalModule ac = Homovore.moduleManager.getModuleByClass(AutoCrystalModule.class);
        if (ac != null && ac.isEnabled() && ac.getLastBestDamage() >= ac.getMinDamage()) return;

        float partialTicks = event.getDelta();
        double interpX = currentTarget.xOld + (currentTarget.getX() - currentTarget.xOld) * partialTicks;
        double interpY = currentTarget.yOld + (currentTarget.getY() - currentTarget.yOld) * partialTicks;
        double interpZ = currentTarget.zOld + (currentTarget.getZ() - currentTarget.zOld) * partialTicks;
        AABB box = currentTarget.getBoundingBox().move(
            interpX - currentTarget.getX(),
            interpY - currentTarget.getY(),
            interpZ - currentTarget.getZ()
        );

        RenderUtil.drawBox(event.getMatrix(), box, Homovore.colorManager.get("ui"), 1.5f);
    }

    public boolean isMaceAttackReady() {
        if (mc.player == null) return false;

        if (!MaceItem.canSmashAttack(mc.player)) return false;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).getItem() instanceof MaceItem) return true;
        }
        return false;
    }

    private boolean shouldCrit(ItemStack weaponStack) {
        if (!criticals.getValue()) return false;

        if (!mc.player.onGround()) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;
        if (mc.player.onClimbable() || mc.player.isPassenger()) return false;
        if (mc.player.hasEffect(MobEffects.LEVITATION)) return false;

        if (critsWithSword.getValue() && !weaponStack.is(ItemTags.SWORDS)) return false;

        if (strict.getValue() && !isPlayerPhasedIntoBlock()) return false;
        return true;
    }

    private boolean isPlayerPhasedIntoBlock() {
        AABB bb = mc.player.getBoundingBox();
        double eyeY = mc.player.getEyeY();
        double headMinY = Mth.clamp(eyeY - 0.1, bb.minY, bb.maxY);
        AABB headBox = new AABB(bb.minX, headMinY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);

        int minX = Mth.floor(headBox.minX);
        int maxX = Mth.floor(headBox.maxX - 1.0E-7);
        int minY = Mth.floor(headBox.minY);
        int maxY = Mth.floor(headBox.maxY - 1.0E-7);
        int minZ = Mth.floor(headBox.minZ);
        int maxZ = Mth.floor(headBox.maxZ - 1.0E-7);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (blockIntersectsBox(new BlockPos(x, y, z), headBox)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean blockIntersectsBox(BlockPos pos, AABB box) {
        VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
        if (shape.isEmpty()) return false;
        return shape.bounds().move(pos).intersects(box);
    }

    private Entity findTarget() {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        if (targets == null) return null;

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.getEntities(null, mc.player.getBoundingBox().inflate(RANGE + 1))) {
            if (!targets.isValidTarget(entity)) continue;

            double dist = eyePos.distanceTo(clampToBox(eyePos, entity.getBoundingBox()));
            if (dist > RANGE) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
    }

    private float getBaseCooldownTicks(ItemStack stack, float tps) {
        float baseTicks;
        if (stack.is(ItemTags.SWORDS)) baseTicks = 13f;
        else if (stack.is(ItemTags.AXES)) baseTicks = 21f;
        else if (stack.getItem() instanceof TridentItem) baseTicks = 19f;
        else if (stack.getItem() instanceof MaceItem) baseTicks = 34f;
        else baseTicks = 20f / 4f;

        return (baseTicks * (20f / tps));
    }

    private int getWeapon() {
        int bestSlot = -1;
        float bestDamage = -1f;

        boolean prioritizeMace = MaceItem.canSmashAttack(mc.player);

        for (int slot = 0; slot < 9; slot++) {
            ItemStack held = mc.player.getInventory().getItem(slot);
            if (held.isEmpty()) continue;

            boolean isSword = held.is(ItemTags.SWORDS);
            boolean isAxe = held.is(ItemTags.AXES);
            boolean isTrident = held.getItem() instanceof TridentItem;
            boolean isMace = held.getItem() instanceof MaceItem;

            if (!isSword && !isAxe && !isTrident && !isMace) continue;

            if (isMace && prioritizeMace) return slot;

            float attackDamage = 0f;

            if (held.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
                ItemAttributeModifiers modifiers = held.get(DataComponents.ATTRIBUTE_MODIFIERS);
                for (var entry : modifiers.modifiers()) {
                    if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                        attackDamage += (float) entry.modifier().amount();
                    }
                }
            }

            if (isSword) attackDamage += 5f;

            attackDamage += EnchantmentUtil.getLevel(Enchantments.SHARPNESS, held) * 1.25f;
            attackDamage += EnchantmentUtil.getLevel(Enchantments.SMITE, held) * 2.5f;
            attackDamage += EnchantmentUtil.getLevel(Enchantments.BANE_OF_ARTHROPODS, held) * 2.5f;

            if (attackDamage > bestDamage) {
                bestDamage = attackDamage;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private Vec3 getClosestPointToEye(Vec3 eye, AABB box) {
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);

        final double VEC = 1.0 / 16.0;
        final double EPS = 1e-9;
        if (Math.abs(x - box.minX) < EPS) x = Math.min(box.minX + VEC, box.maxX - EPS);
        else if (Math.abs(x - box.maxX) < EPS) x = Math.max(box.maxX - VEC, box.minX + EPS);
        if (Math.abs(z - box.minZ) < EPS) z = Math.min(box.minZ + VEC, box.maxZ - EPS);
        else if (Math.abs(z - box.maxZ) < EPS) z = Math.max(box.maxZ - VEC, box.minZ + EPS);

        return new Vec3(x, y, z);
    }

    private Vec3 clampToBox(Vec3 point, AABB box) {
        return new Vec3(
            Mth.clamp(point.x, box.minX, box.maxX),
            Mth.clamp(point.y, box.minY, box.maxY),
            Mth.clamp(point.z, box.minZ, box.maxZ)
        );
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }
}
