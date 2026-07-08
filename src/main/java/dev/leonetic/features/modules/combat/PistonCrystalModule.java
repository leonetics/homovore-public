package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.modules.combat.OffhandModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public class PistonCrystalModule extends Module {

    private static final String ROTATION_ID = "PistonCrystal";
    private static final int ROTATION_PRIORITY = 70;
    private static final double TARGET_RANGE = 10.0;
    private static final int EXTEND_TIMEOUT_TICKS = 10;

    private final Setting<Double> minDamage = num("MinDamage", 6.0, 0.0, 36.0).setPage("General");
    private final Setting<Integer> delay = num("Delay", 10, 0, 20).setPage("General");
    private final Setting<Boolean> rotate = bool("Rotate", true).setPage("General");
    private final Setting<Boolean> offhandPlace = bool("OffhandPlace", true).setPage("General");
    private final Setting<Boolean> autoBase = bool("AutoBase", true).setPage("General");
    private final Setting<Double> placeRange = num("PlaceRange", 4.5, 1.0, 6.0).setPage("General");
    private final Setting<Double> breakRange = num("BreakRange", 3.0, 1.0, 6.0).setPage("General");

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<Float> fadeTime = num("FadeTime", 0.2f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color> fillColor = color("FillColor", 0, 62, 122, 148).setPage("Render");
    private final Setting<Color> outlineColor = color("OutlineColor", 0, 62, 122, 148).setPage("Render");

    private final Map<BlockPos, Long> renderMap = new HashMap<>();

    private Setup pending;
    private Setup active;
    private int delayTicks;
    private int rotateHeld;
    private int waitTicks;
    private float lastDamage;
    private SwapManager.SwapHandle pendingSwapHandle;

    public PistonCrystalModule() {
        super("PistonCrystal", "Pushes a crystal over a surrounded target with a piston and explodes it.",
                Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetState();
        delayTicks = delay.getValue();
    }

    @Override
    public void onDisable() {
        Homovore.rotationManager.cancel(ROTATION_ID);
        if (pendingSwapHandle != null) {
            Homovore.swapManager.release(pendingSwapHandle);
            pendingSwapHandle = null;
        }
        resetState();
    }

    @Subscribe(priority = -100)
    private void onPreTickRestore(PreTickEvent event) {
        if (pendingSwapHandle != null) {
            Homovore.swapManager.release(pendingSwapHandle);
            pendingSwapHandle = null;
        }
    }

    private void resetState() {
        pending = null;
        active = null;
        delayTicks = 0;
        rotateHeld = 0;
        waitTicks = 0;
        lastDamage = 0;
        renderMap.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying())
            return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat())
            return;

        long fadeMs = (long) (fadeTime.getValue() * 1000);
        renderMap.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > fadeMs);

        if (active != null) {
            tickActive();
            return;
        }

        if (delayTicks < delay.getValue()) {
            delayTicks++;
            return;
        }

        Setup setup = findSetup();
        if (setup == null) {
            clearPending();
            return;
        }

        if (breakCrystalsAround(setup.head()))
            return;

        if (rotate.getValue()) {
            if (pending == null || pending.dir() != setup.dir())
                rotateHeld = 0;
            pending = setup;
            Homovore.rotationManager.submit(new RotationRequest(
                    ROTATION_ID, ROTATION_PRIORITY,
                    setup.dir().toYRot(), 0f,
                    RotationRequest.Mode.MOTION, true, false));
            if (Homovore.rotationManager.isCompleted(ROTATION_ID))
                rotateHeld++;
            else
                rotateHeld = 0;
            if (rotateHeld < 2)
                return;
        }

        place(setup);
    }

    private void clearPending() {
        if (pending != null) {
            Homovore.rotationManager.cancel(ROTATION_ID);
            pending = null;
        }
        rotateHeld = 0;
        lastDamage = 0;
    }

    private void place(Setup setup) {
        int pistonSlot = pistonSlot();
        int redstoneSlot = hotbarSlotOf(Items.REDSTONE_BLOCK);
        int crystalSlot = hotbarSlotOf(Items.END_CRYSTAL);
        if (pistonSlot < 0 || redstoneSlot < 0 || crystalSlot < 0)
            return;

        int obsidianSlot = -1;
        if (setup.placeBase()) {
            obsidianSlot = hotbarSlotOf(Items.OBSIDIAN);
            if (obsidianSlot < 0)
                return;
        }

        if (setup.placeBase()
                && !Homovore.placementManager.placeDirect(setup.base(), null, obsidianSlot))
            return;
        if (!Homovore.placementManager.placeDirect(setup.piston(), null, pistonSlot))
            return;
        if (setup.placeRedstone()) {
            Homovore.placementManager.placeDirect(setup.redstone(), null, redstoneSlot);
        }
        placeCrystal(setup.base(), crystalSlot);

        long now = System.currentTimeMillis();
        if (setup.placeBase())
            renderMap.put(setup.base(), now);
        renderMap.put(setup.piston(), now);
        if (setup.placeRedstone())
            renderMap.put(setup.redstone(), now);
        renderMap.put(setup.crystal(), now);

        Homovore.rotationManager.cancel(ROTATION_ID);
        pending = null;
        rotateHeld = 0;
        active = setup;
        waitTicks = 0;
        delayTicks = 0;
    }

    private void tickActive() {
        waitTicks++;

        BlockState pistonState = mc.level.getBlockState(active.piston());
        boolean extended = pistonState.hasProperty(BlockStateProperties.EXTENDED)
                && pistonState.getValue(BlockStateProperties.EXTENDED);
        if (!extended) {
            extended = mc.level.getBlockState(active.crystal()).is(Blocks.PISTON_HEAD)
                    || mc.level.getBlockState(active.crystal()).is(Blocks.MOVING_PISTON);
        }

        if (extended) {
            breakCrystalsAround(active.head());
            active = null;
            return;
        }

        if (waitTicks > EXTEND_TIMEOUT_TICKS)
            active = null;
    }

    private void placeCrystal(BlockPos base, int slot) {
        if (offhandPlace.getValue()) {
            Homovore.placementManager.placeCrystalOffhand(base, slot, true);
            return;
        }

        int originalSlot = InventoryUtil.selected();

        if (pendingSwapHandle != null && pendingSwapHandle.isReleased()) {
            pendingSwapHandle = null;
        }

        SwapManager.SwapHandle handle = pendingSwapHandle;
        boolean acquiredNow = false;
        if (slot != originalSlot && handle == null) {
            handle = Homovore.swapManager.acquire("PistonCrystal", 68);
            if (handle == null)
                return;
            acquiredNow = true;
        }

        boolean sent = Homovore.placementManager.placeCrystal(base, slot, true);

        if (sent) {
            if (handle != null)
                pendingSwapHandle = handle;
        } else if (acquiredNow) {
            Homovore.swapManager.release(handle);
        }
    }

    private boolean breakCrystalsAround(BlockPos head) {
        Vec3 eye = mc.player.getEyePosition();
        double rangeSq = breakRange.getValue() * breakRange.getValue();
        AABB area = new AABB(head).inflate(1.0);
        boolean found = false;
        for (Entity e : mc.level.getEntities(null, area)) {
            if (!(e instanceof EndCrystal crystal))
                continue;
            found = true;
            if (distSqToBox(eye, crystal.getBoundingBox()) > rangeSq)
                continue;
            breakCrystal(crystal);
        }
        return found;
    }

    private void breakCrystal(EndCrystal crystal) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 hit = getClosestPointToEye(eyePos, crystal.getBoundingBox());
        float[] angles = MathUtil.calcAngle(eyePos, hit);
        if (!canBreakCrystal(crystal, angles[0], angles[1]))
            return;
        Homovore.rotationManager.submit(new RotationRequest(
                "PistonCrystal_break", 60, angles[0], angles[1], RotationRequest.Mode.SILENT));
        mc.gameMode.attack(mc.player, crystal);
    }

    private boolean canBreakCrystal(EndCrystal crystal, float yaw, float pitch) {
        AABB bb = crystal.getBoundingBox();
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        if (bb.contains(eyePos))
            return true;
        Vec3 look = getLookVector(yaw, pitch);
        Vec3 reachEnd = eyePos.add(look.scale(breakRange.getValue()));
        return bb.clip(eyePos, reachEnd).isPresent();
    }

    private Vec3 getClosestPointToEye(Vec3 eye, AABB box) {
        double x = eye.x, y = eye.y, z = eye.z;
        final double VEC = 1.0 / 16.0;
        final double EPS = 1e-9;

        if (eye.x < box.minX)
            x = box.minX;
        else if (eye.x > box.maxX)
            x = box.maxX;
        if (eye.y < box.minY)
            y = box.minY;
        else if (eye.y > box.maxY)
            y = box.maxY;
        if (eye.z < box.minZ)
            z = box.minZ;
        else if (eye.z > box.maxZ)
            z = box.maxZ;

        if (Math.abs(x - box.minX) < EPS)
            x = Math.min(box.minX + VEC, box.maxX - EPS);
        else if (Math.abs(x - box.maxX) < EPS)
            x = Math.max(box.maxX - VEC, box.minX + EPS);
        if (Math.abs(z - box.minZ) < EPS)
            z = Math.min(box.minZ + VEC, box.maxZ - EPS);
        else if (Math.abs(z - box.maxZ) < EPS)
            z = Math.max(box.maxZ - VEC, box.minZ + EPS);

        return new Vec3(x, y, z);
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    private Setup findSetup() {
        LivingEntity target = findTarget();
        if (target == null) {
            lastDamage = 0;
            return null;
        }

        if (pistonSlot() < 0
                || hotbarSlotOf(Items.REDSTONE_BLOCK) < 0
                || hotbarSlotOf(Items.END_CRYSTAL) < 0) {
            lastDamage = 0;
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        double range = placeRange.getValue();
        BlockPos head = target.blockPosition().above();

        Setup best = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Setup side = sideSetup(target, head, dir, eye, range);
            if (side != null && (best == null || side.damage() > best.damage()))
                best = side;
            Setup top = topSetup(target, head, dir, eye, range);
            if (top != null && (best == null || top.damage() > best.damage()))
                best = top;
        }

        lastDamage = best != null ? best.damage() : 0;
        return best;
    }

    private Setup sideSetup(LivingEntity target, BlockPos head, Direction dir, Vec3 eye, double range) {
        BlockPos crystalPos = head.relative(dir);
        BlockPos base = crystalPos.below();
        BlockPos pistonPos = head.relative(dir, 2);

        boolean placeBase = needsBase(base);
        if (placeBase && !canAutoBase(base))
            return null;
        if (!mc.level.getBlockState(crystalPos).isAir())
            return null;
        if (!mc.level.getBlockState(head).isAir())
            return null;

        Vec3 explosionPos = new Vec3(head.getX() + 0.5, head.getY(), head.getZ() + 0.5);
        return buildSetup(target, head, dir, crystalPos, base, pistonPos, explosionPos, eye, range, placeBase);
    }

    private Setup topSetup(LivingEntity target, BlockPos head, Direction dir, Vec3 eye, double range) {
        BlockPos base = head.relative(dir);
        BlockPos crystalPos = base.above();
        BlockPos pistonPos = crystalPos.relative(dir);

        boolean placeBase = needsBase(base);
        if (placeBase && !canAutoBase(base))
            return null;
        if (!mc.level.getBlockState(crystalPos).isAir())
            return null;

        BlockPos dest = head.above();
        Vec3 explosionPos = mc.level.getBlockState(dest).isAir()
                ? new Vec3(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5)
                : new Vec3(crystalPos.getX() + 0.5 - dir.getStepX() * 0.5, crystalPos.getY(),
                crystalPos.getZ() + 0.5 - dir.getStepZ() * 0.5);
        return buildSetup(target, head, dir, crystalPos, base, pistonPos, explosionPos, eye, range, placeBase);
    }

    private boolean needsBase(BlockPos base) {
        var baseState = mc.level.getBlockState(base);
        return !baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK);
    }

    private boolean canAutoBase(BlockPos base) {
        return autoBase.getValue()
                && hotbarSlotOf(Items.OBSIDIAN) >= 0
                && PlaceUtil.canPlace(base);
    }

    private Setup buildSetup(LivingEntity target, BlockPos head, Direction dir, BlockPos crystalPos,
                             BlockPos base, BlockPos pistonPos, Vec3 explosionPos, Vec3 eye, double range,
                             boolean placeBase) {
        if (!PlaceUtil.canPlace(pistonPos))
            return null;
        if (eye.distanceTo(Vec3.atCenterOf(pistonPos)) > range)
            return null;
        if (eye.distanceTo(Vec3.atCenterOf(base)) > range)
            return null;

        AABB crystalBox = new AABB(explosionPos.x - 1, explosionPos.y, explosionPos.z - 1,
                explosionPos.x + 1, explosionPos.y + 2, explosionPos.z + 1);
        if (distSqToBox(eye, crystalBox) > breakRange.getValue() * breakRange.getValue())
            return null;

        RedstoneSpot redstone = findRedstoneSpot(pistonPos, dir, explosionPos, eye, range);
        if (redstone == null)
            return null;

        float damage = calcDamage(target, explosionPos);
        if (damage < minDamage.getValue())
            return null;

        return new Setup(dir, pistonPos, redstone.pos(), crystalPos, base, head,
                redstone.place(), placeBase, damage);
    }

    private RedstoneSpot findRedstoneSpot(BlockPos pistonPos, Direction dir,
                                          Vec3 explosionPos, Vec3 eye, double range) {
        BlockPos[] candidates = {
                pistonPos.above(),
                pistonPos.relative(dir),
                pistonPos.relative(dir.getClockWise()),
                pistonPos.relative(dir.getCounterClockWise()),
                pistonPos.below()
        };

        for (BlockPos pos : candidates) {
            if (mc.level.getBlockState(pos).is(Blocks.REDSTONE_BLOCK)) {
                return new RedstoneSpot(pos, false);
            }
        }

        BlockPos fallback = null;
        for (BlockPos pos : candidates) {
            if (!PlaceUtil.canPlace(pos))
                continue;
            if (eye.distanceTo(Vec3.atCenterOf(pos)) > range)
                continue;
            if (redstoneSafe(pos, explosionPos))
                return new RedstoneSpot(pos, true);
            if (fallback == null)
                fallback = pos;
        }
        return fallback == null ? null : new RedstoneSpot(fallback, true);
    }

    private boolean redstoneSafe(BlockPos pos, Vec3 explosionPos) {
        Vec3 to = Vec3.atCenterOf(pos);
        if (explosionPos.distanceTo(to) > 6.0)
            return true;

        Vec3 diff = to.subtract(explosionPos);
        int steps = (int) Math.ceil(diff.length() / 0.25);
        for (int i = 1; i < steps; i++) {
            Vec3 point = explosionPos.add(diff.scale((double) i / steps));
            BlockPos cursor = BlockPos.containing(point);
            if (cursor.equals(pos))
                break;
            var state = mc.level.getBlockState(cursor);
            if (state.getBlock().getExplosionResistance() >= 600.0f)
                return true;
        }
        return false;
    }

    private static double distSqToBox(Vec3 p, AABB box) {
        double dx = p.x < box.minX ? box.minX - p.x : (p.x > box.maxX ? p.x - box.maxX : 0);
        double dy = p.y < box.minY ? box.minY - p.y : (p.y > box.maxY ? p.y - box.maxY : 0);
        double dz = p.z < box.minZ ? box.minZ - p.z : (p.z > box.maxZ ? p.z - box.maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private LivingEntity findTarget() {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeSq = TARGET_RANGE * TARGET_RANGE;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        AABB area = mc.player.getBoundingBox().inflate(TARGET_RANGE);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof Player p) || p.isDeadOrDying())
                continue;
            if (targets != null && !targets.isValidPlayerTarget(p))
                continue;
            double dSq = mc.player.distanceToSqr(p);
            if (dSq > rangeSq || dSq >= bestSq)
                continue;
            bestSq = dSq;
            best = p;
        }
        return best;
    }

    private float calcDamage(LivingEntity target, Vec3 explosionPos) {
        double dist = target.position().distanceTo(explosionPos);
        if (dist > 12.0)
            return 0;
        double impact = 1.0 - dist / 12.0;
        if (impact <= 0)
            return 0;

        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (mc.level.getDifficulty()) {
            case EASY -> damage = Math.min(damage / 2f + 1f, damage);
            case HARD -> damage *= 1.5f;
            default -> {
            }
        }

        float armor = (float) target.getAttributeValue(Attributes.ARMOR);
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float i = 2.0f + toughness / 4.0f;
        float j = Mth.clamp(armor - damage / i, armor * 0.2f, 20.0f);
        damage *= 1.0f - j / 25.0f;

        MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
        if (resistance != null)
            damage *= 1.0f - 0.2f * (resistance.getAmplifier() + 1);

        int protPoints = 0;
        if (!target.getItemBySlot(EquipmentSlot.HEAD).isEmpty())
            protPoints += 4;
        if (!target.getItemBySlot(EquipmentSlot.CHEST).isEmpty())
            protPoints += 4;
        if (!target.getItemBySlot(EquipmentSlot.LEGS).isEmpty())
            protPoints += 8;
        if (!target.getItemBySlot(EquipmentSlot.FEET).isEmpty())
            protPoints += 4;
        damage = CombatRules.getDamageAfterMagicAbsorb(damage, protPoints);

        return Math.max(damage, 0f);
    }

    private int pistonSlot() {
        int slot = hotbarSlotOf(Items.PISTON);
        return slot >= 0 ? slot : hotbarSlotOf(Items.STICKY_PISTON);
    }

    private int hotbarSlotOf(Item item) {
        var r = InventoryUtil.find(item, InventoryUtil.HOTBAR_SCOPE);
        return (r.found() && r.type() != ResultType.OFFHAND) ? r.slot() : -1;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue())
            return;

        long now = System.currentTimeMillis();
        double fadeMs = fadeTime.getValue() * 1000.0;

        for (Map.Entry<BlockPos, Long> entry : renderMap.entrySet()) {
            long age = now - entry.getValue();
            if (age > fadeMs)
                continue;

            double t = age / fadeMs;

            Color fc = fillColor.getValue();
            Color oc = outlineColor.getValue();

            RenderUtil.drawBoxFilled(event.getMatrix(), entry.getKey(),
                    withAlpha(fc, (int) (fc.getAlpha() * (1 - t))));
            RenderUtil.drawBox(event.getMatrix(), entry.getKey(),
                    withAlpha(oc, (int) (oc.getAlpha() * (1 - t))), 1.0f);
        }
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    @Override
    public String getDisplayInfo() {
        return lastDamage > 0 ? String.format("%.1f", lastDamage) : null;
    }

    private record Setup(Direction dir, BlockPos piston, BlockPos redstone,
                         BlockPos crystal, BlockPos base, BlockPos head,
                         boolean placeRedstone, boolean placeBase, float damage) {
    }

    private record RedstoneSpot(BlockPos pos, boolean place) {
    }
}