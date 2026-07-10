package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public class PistonCrystalModule extends Module {

    private static final String ROTATION_ID     = "PistonCrystal";
    private static final int    ROTATION_PRIORITY = 70;
    private static final double TARGET_RANGE     = 10.0;
    private static final double TARGET_RANGE_SQ  = TARGET_RANGE * TARGET_RANGE;
    private static final int    EXTEND_TIMEOUT   = 10;

    private static final double NUDGE = 1.0 / 16.0;

    private static final double PLACE_RANGE = 6.0;
    private static final double BREAK_RANGE = 3.0;

    private final Setting<Double>  minDamage    = num("MinDamage",    6.0, 0.0, 36.0).setPage("General");
    private final Setting<Integer> delay        = num("Delay",       10, 0, 20).setPage("General");
    private final Setting<Boolean> offhandPlace = bool("OffhandPlace", true).setPage("General");
    private final Setting<Boolean> autoBase     = bool("AutoBase",   true).setPage("General");

    private final Setting<Boolean> render      = bool("Render",      true).setPage("Render");
    private final Setting<Float>   fadeTime    = num("FadeTime",     0.2f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor   = color("FillColor",  0, 62, 122, 148).setPage("Render");
    private final Setting<Color>   outlineColor = color("OutlineColor", 0, 62, 122, 148).setPage("Render");

    private final Map<BlockPos, Integer> renderMap = new HashMap<>();

    private Setup   pending;
    private Setup   active;
    private int     delayTicks;
    private int     rotateHeld;
    private int     waitTicks;
    private float   lastDamage;

    private ArmorProfile                     targetProfile;
    private net.minecraft.world.Difficulty   targetDifficulty;

    public PistonCrystalModule() {
        super("PistonCrystal",
              "Pushes a crystal over a surrounded target with a piston and explodes it.",
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
        resetState();
    }

    private void resetState() {
        pending    = null;
        active     = null;
        delayTicks = 0;
        rotateHeld = 0;
        waitTicks  = 0;
        lastDamage = 0;
        renderMap.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        int fadeTicks = (int)(fadeTime.getValue() * 20);
        int now = mc.player.tickCount;
        renderMap.entrySet().removeIf(e -> now - e.getValue() > fadeTicks);

        if (active != null) {
            tickActive();
            return;
        }

        if (delayTicks < delay.getValue()) {
            delayTicks++;
            return;
        }

        int pistonSlot   = pistonSlot();
        int redstoneSlot = hotbarSlotOf(Items.REDSTONE_BLOCK);
        int crystalSlot  = hotbarSlotOf(Items.END_CRYSTAL);
        if (pistonSlot < 0 || redstoneSlot < 0 || crystalSlot < 0) {
            clearPending();
            lastDamage = 0;
            return;
        }

        Setup setup = findSetup(pistonSlot, redstoneSlot, crystalSlot);
        if (setup == null) {
            clearPending();
            return;
        }

        if (breakCrystalsAround(setup.head())) return;

        if (pending == null || pending.dir() != setup.dir()) rotateHeld = 0;
        pending = setup;
        Homovore.rotationManager.submit(new RotationRequest(
                ROTATION_ID, ROTATION_PRIORITY,
                setup.dir().toYRot(), 0f,
                RotationRequest.Mode.MOTION, true, false));
        if (Homovore.rotationManager.isCompleted(ROTATION_ID)) rotateHeld++;
        else rotateHeld = 0;
        if (rotateHeld < 2) return;

        place(setup, pistonSlot, redstoneSlot, crystalSlot);
    }

    private void clearPending() {
        if (pending != null) {
            Homovore.rotationManager.cancel(ROTATION_ID);
            pending = null;
        }
        rotateHeld = 0;
        lastDamage = 0;
    }

    private void place(Setup setup, int pistonSlot, int redstoneSlot, int crystalSlot) {
        int obsidianSlot = -1;
        if (setup.placeBase()) {
            obsidianSlot = hotbarSlotOf(Items.OBSIDIAN);
            if (obsidianSlot < 0) return;
        }

        if (setup.placeBase()
                && !Homovore.placementManager.placeDirect(setup.base(), null, obsidianSlot))
            return;
        if (!Homovore.placementManager.placeDirect(setup.piston(), null, pistonSlot))
            return;
        if (setup.placeRedstone())
            Homovore.placementManager.placeDirect(setup.redstone(), null, redstoneSlot);
        placeCrystal(setup.base(), crystalSlot);

        int tick = mc.player.tickCount;
        if (setup.placeBase())   renderMap.put(setup.base(),    tick);
        renderMap.put(setup.piston(),                           tick);
        if (setup.placeRedstone()) renderMap.put(setup.redstone(), tick);
        renderMap.put(setup.crystal(),                          tick);

        Homovore.rotationManager.cancel(ROTATION_ID);
        pending    = null;
        rotateHeld = 0;
        active     = setup;
        waitTicks  = 0;
        delayTicks = 0;
    }

    private void placeCrystal(BlockPos base, int slot) {
        if (offhandPlace.getValue()) {
            Homovore.placementManager.placeCrystalOffhand(base, slot, true);
        } else {
            Homovore.placementManager.placeCrystal(base, slot, true);
        }
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

        if (waitTicks > EXTEND_TIMEOUT) active = null;
    }

    private boolean breakCrystalsAround(BlockPos head) {
        Vec3 eye       = mc.player.getEyePosition();
        double rangeSq = BREAK_RANGE * BREAK_RANGE;
        AABB area      = new AABB(head).inflate(1.0);
        boolean found  = false;
        for (var e : mc.level.getEntities(null, area)) {
            if (!(e instanceof EndCrystal crystal)) continue;
            found = true;
            if (distSqToBox(eye, crystal.getBoundingBox()) > rangeSq) continue;
            breakCrystal(crystal);
        }
        return found;
    }

    private void breakCrystal(EndCrystal crystal) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 hit    = closestPointOnBox(eyePos, crystal.getBoundingBox());
        float[] angles = MathUtil.calcAngle(eyePos, hit);
        if (!canHitCrystal(crystal, angles[0], angles[1])) return;
        Homovore.rotationManager.submit(new RotationRequest(
                "PistonCrystal_break", 60, angles[0], angles[1], RotationRequest.Mode.SILENT));
        mc.gameMode.attack(mc.player, crystal);
    }

    private boolean canHitCrystal(EndCrystal crystal, float yaw, float pitch) {
        AABB bb = crystal.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition(1.0f);
        if (bb.contains(eye)) return true;
        Vec3 look     = getLookVector(yaw, pitch);
        Vec3 reachEnd = eye.add(look.scale(BREAK_RANGE));
        return bb.clip(eye, reachEnd).isPresent();
    }

    private Setup findSetup(int pistonSlot, int redstoneSlot, int crystalSlot) {
        LivingEntity target = findTarget();
        if (target == null) { lastDamage = 0; return null; }

        targetProfile    = profileOf(target);
        targetDifficulty = mc.level.getDifficulty();

        Vec3  eye   = mc.player.getEyePosition();
        double range = PLACE_RANGE;

        AABB bb = target.getBoundingBox();
        int minX = Mth.floor(bb.minX), maxX = Mth.floor(bb.maxX - 1e-7);
        int minY = Mth.floor(bb.minY), maxY = Mth.floor(bb.maxY - 1e-7);
        int minZ = Mth.floor(bb.minZ), maxZ = Mth.floor(bb.maxZ - 1e-7);

        Setup best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean lowPose = target.getBbHeight() <= 1.0f;

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    cursor.set(bx, by, bz);
                    BlockPos cell = cursor.immutable();
                    BlockPos[] heads = lowPose
                            ? new BlockPos[]{cell, cell.above()}
                            : new BlockPos[]{cell.above()};

                    for (BlockPos head : heads) {
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            Setup side = sideSetup(target, head, dir, eye, range);
                            if (side != null && (best == null || side.damage() > best.damage()))
                                best = side;
                            Setup top = topSetup(target, head, dir, eye, range);
                            if (top != null && (best == null || top.damage() > best.damage()))
                                best = top;
                        }
                    }
                }
            }
        }

        lastDamage = best != null ? best.damage() : 0;
        return best;
    }

    private Setup sideSetup(LivingEntity target, BlockPos head, Direction dir,
                            Vec3 eye, double range) {
        BlockPos crystalPos = head.relative(dir);
        BlockPos base       = crystalPos.below();
        BlockPos pistonPos  = head.relative(dir, 2);

        boolean placeBase = needsBase(base);
        if (placeBase && !canAutoBase(base)) return null;
        if (!mc.level.getBlockState(crystalPos).isAir()) return null;
        if (!mc.level.getBlockState(head).isAir()) return null;

        Vec3 explosionPos = new Vec3(head.getX() + 0.5, head.getY(), head.getZ() + 0.5);
        return buildSetup(target, head, dir, crystalPos, base, pistonPos,
                          explosionPos, eye, range, placeBase);
    }

    private Setup topSetup(LivingEntity target, BlockPos head, Direction dir,
                           Vec3 eye, double range) {
        BlockPos base       = head.relative(dir);
        BlockPos crystalPos = base.above();
        BlockPos pistonPos  = crystalPos.relative(dir);

        boolean placeBase = needsBase(base);
        if (placeBase && !canAutoBase(base)) return null;
        if (!mc.level.getBlockState(crystalPos).isAir()) return null;

        BlockPos dest = head.above();
        Vec3 explosionPos = mc.level.getBlockState(dest).isAir()
                ? new Vec3(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5)
                : new Vec3(crystalPos.getX() + 0.5 - dir.getStepX() * 0.5,
                           crystalPos.getY(),
                           crystalPos.getZ() + 0.5 - dir.getStepZ() * 0.5);
        return buildSetup(target, head, dir, crystalPos, base, pistonPos,
                          explosionPos, eye, range, placeBase);
    }

    private boolean needsBase(BlockPos base) {
        var s = mc.level.getBlockState(base);
        return !s.is(Blocks.OBSIDIAN) && !s.is(Blocks.BEDROCK);
    }

    private boolean canAutoBase(BlockPos base) {
        return autoBase.getValue()
                && hotbarSlotOf(Items.OBSIDIAN) >= 0
                && PlaceUtil.canPlace(base);
    }

    private Setup buildSetup(LivingEntity target, BlockPos head, Direction dir,
                             BlockPos crystalPos, BlockPos base, BlockPos pistonPos,
                             Vec3 explosionPos, Vec3 eye, double range,
                             boolean placeBase) {
        if (!PlaceUtil.canPlace(pistonPos)) return null;

        double rangeSq = range * range;
        if (eye.distanceToSqr(Vec3.atCenterOf(pistonPos)) > rangeSq) return null;
        if (eye.distanceToSqr(Vec3.atCenterOf(base))      > rangeSq) return null;

        AABB crystalBox = new AABB(
                explosionPos.x - 1, explosionPos.y,     explosionPos.z - 1,
                explosionPos.x + 1, explosionPos.y + 2, explosionPos.z + 1);
        double breakRangeSq = BREAK_RANGE * BREAK_RANGE;
        if (distSqToBox(eye, crystalBox) > breakRangeSq) return null;

        float damage = calcDamage(target, explosionPos, placeBase ? base : null);
        if (damage < minDamage.getValue()) return null;

        RedstoneSpot redstone = findRedstoneSpot(pistonPos, dir, explosionPos, eye, rangeSq);
        if (redstone == null) return null;

        return new Setup(dir, pistonPos, redstone.pos(), crystalPos, base, head,
                         redstone.place(), placeBase, damage);
    }

    private RedstoneSpot findRedstoneSpot(BlockPos pistonPos, Direction dir,
                                          Vec3 explosionPos, Vec3 eye, double rangeSq) {
        BlockPos above       = pistonPos.above();
        BlockPos inDir       = pistonPos.relative(dir);
        BlockPos cw          = pistonPos.relative(dir.getClockWise());
        BlockPos ccw         = pistonPos.relative(dir.getCounterClockWise());
        BlockPos below       = pistonPos.below();

        if (mc.level.getBlockState(above).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(above, false);
        if (mc.level.getBlockState(inDir).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(inDir, false);
        if (mc.level.getBlockState(cw).is(Blocks.REDSTONE_BLOCK))    return new RedstoneSpot(cw,    false);
        if (mc.level.getBlockState(ccw).is(Blocks.REDSTONE_BLOCK))   return new RedstoneSpot(ccw,   false);
        if (mc.level.getBlockState(below).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(below, false);

        BlockPos fallback = null;
        for (BlockPos pos : new BlockPos[]{above, inDir, cw, ccw, below}) {
            if (!PlaceUtil.canPlace(pos)) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue;
            if (redstoneSafe(pos, explosionPos)) return new RedstoneSpot(pos, true);
            if (fallback == null) fallback = pos;
        }
        return fallback == null ? null : new RedstoneSpot(fallback, true);
    }

    private final BlockPos.MutableBlockPos rayCursor = new BlockPos.MutableBlockPos();

    private boolean redstoneSafe(BlockPos pos, Vec3 explosionPos) {
        Vec3 to = Vec3.atCenterOf(pos);
        if (explosionPos.distanceToSqr(to) > 36.0) return true;
        Vec3 diff  = to.subtract(explosionPos);
        int  steps = (int) Math.ceil(diff.length() / 0.25);
        long last  = Long.MIN_VALUE;
        for (int i = 1; i < steps; i++) {
            double s = (double) i / steps;
            rayCursor.set(Mth.floor(explosionPos.x + diff.x * s),
                          Mth.floor(explosionPos.y + diff.y * s),
                          Mth.floor(explosionPos.z + diff.z * s));
            long key = rayCursor.asLong();
            if (key == last) continue;
            last = key;
            if (rayCursor.equals(pos)) break;
            if (mc.level.getBlockState(rayCursor).getBlock().getExplosionResistance() >= 600.0f)
                return true;
        }
        return false;
    }

    private double calcExposure(Vec3 source, AABB box, BlockPos phantomBase) {
        double dx = box.getXsize();
        double dy = box.getYsize();
        double dz = box.getZsize();
        int steps = 2;
        int total = 0;
        int unblocked = 0;

        for (int xi = 0; xi <= steps; xi++) {
            for (int yi = 0; yi <= steps; yi++) {
                for (int zi = 0; zi <= steps; zi++) {
                    Vec3 point = new Vec3(
                            box.minX + dx * xi / steps,
                            box.minY + dy * yi / steps,
                            box.minZ + dz * zi / steps);
                    if (!explosionBlocked(point, source, phantomBase)) unblocked++;
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (double) unblocked / total;
    }

    private boolean explosionBlocked(Vec3 from, Vec3 to, BlockPos phantomBase) {
        Vec3 diff  = to.subtract(from);
        int  steps = (int) Math.ceil(diff.length() / 0.25);
        long last  = Long.MIN_VALUE;
        for (int i = 1; i < steps; i++) {
            double s = (double) i / steps;
            rayCursor.set(Mth.floor(from.x + diff.x * s),
                          Mth.floor(from.y + diff.y * s),
                          Mth.floor(from.z + diff.z * s));
            long key = rayCursor.asLong();
            if (key == last) continue;
            last = key;
            if (phantomBase != null && rayCursor.equals(phantomBase)) return true;
            if (mc.level.getBlockState(rayCursor).getBlock().getExplosionResistance() >= 600.0f) return true;
        }
        return false;
    }

    private LivingEntity findTarget() {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        LivingEntity best   = null;
        double       bestSq = Double.MAX_VALUE;

        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(p)) continue;
            if (isPhased(p)) continue;
            double dSq = mc.player.distanceToSqr(p);
            if (dSq > TARGET_RANGE_SQ || dSq >= bestSq) continue;
            bestSq = dSq;
            best   = p;
        }
        return best;
    }

    private boolean isPhased(LivingEntity target) {
        AABB bb = target.getBoundingBox().deflate(0.001);
        int minX = Mth.floor(bb.minX), maxX = Mth.floor(bb.maxX);
        int minY = Mth.floor(bb.minY), maxY = Mth.floor(bb.maxY);
        int minZ = Mth.floor(bb.minZ), maxZ = Mth.floor(bb.maxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state = mc.level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    VoxelShape shape = state.getCollisionShape(mc.level, cursor);
                    if (shape.isEmpty()) continue;
                    if (shape.bounds().move(x, y, z).intersects(bb)) return true;
                }
            }
        }
        return false;
    }

    private float calcDamage(LivingEntity target, Vec3 explosionPos, BlockPos phantomBase) {

        double distSq = target.position().distanceToSqr(explosionPos);
        if (distSq > 144.0) return 0;
        double exposure = calcExposure(explosionPos, target.getBoundingBox(), phantomBase);
        if (exposure <= 0) return 0;
        double impact = (1.0 - Math.sqrt(distSq) / 12.0) * exposure;
        if (impact <= 0) return 0;

        float damage = (float)((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (targetDifficulty) {
            case EASY -> damage = Math.min(damage / 2f + 1f, damage);
            case HARD -> damage *= 1.5f;
            default   -> {}
        }

        ArmorProfile p = targetProfile;
        float i = 2.0f + p.toughness() / 4.0f;
        float j = Mth.clamp(p.armor() - damage / i, p.armor() * 0.2f, 20.0f);
        damage *= 1.0f - j / 25.0f;
        damage *= p.resistanceMul();
        damage = CombatRules.getDamageAfterMagicAbsorb(damage, p.protPoints());

        return Math.max(damage, 0f);
    }

    private ArmorProfile profileOf(LivingEntity target) {
        float armor = (float) target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        float tough = (float) target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);

        float resistanceMul = 1.0f;
        MobEffectInstance res = target.getEffect(MobEffects.RESISTANCE);
        if (res != null) resistanceMul = 1.0f - 0.2f * (res.getAmplifier() + 1);

        int protPoints = 0;
        if (!target.getItemBySlot(EquipmentSlot.HEAD).isEmpty())  protPoints += 4;
        if (!target.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) protPoints += 4;
        if (!target.getItemBySlot(EquipmentSlot.LEGS).isEmpty())  protPoints += 8;
        if (!target.getItemBySlot(EquipmentSlot.FEET).isEmpty())  protPoints += 4;

        return new ArmorProfile(armor, tough, resistanceMul, protPoints);
    }

    private Vec3 closestPointOnBox(Vec3 eye, AABB box) {
        boolean minX = eye.x < box.minX;
        boolean maxX = eye.x > box.maxX;
        boolean minZ = eye.z < box.minZ;
        boolean maxZ = eye.z > box.maxZ;

        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);

        if      (minX) x += NUDGE;
        else if (maxX) x -= NUDGE;

        if      (minZ) z += NUDGE;
        else if (maxZ) z -= NUDGE;

        return new Vec3(x, y, z);
    }

    private static double distSqToBox(Vec3 p, AABB box) {
        double dx = p.x < box.minX ? box.minX - p.x : (p.x > box.maxX ? p.x - box.maxX : 0);
        double dy = p.y < box.minY ? box.minY - p.y : (p.y > box.maxY ? p.y - box.maxY : 0);
        double dz = p.z < box.minZ ? box.minZ - p.z : (p.z > box.maxZ ? p.z - box.maxZ : 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float k = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, k, f * h);
    }

    private int pistonSlot() {
        int s = hotbarSlotOf(Items.PISTON);
        return s >= 0 ? s : hotbarSlotOf(Items.STICKY_PISTON);
    }

    private int hotbarSlotOf(Item item) {
        var r = InventoryUtil.find(item, InventoryUtil.PLACE_SCOPE);
        return (r.found() && r.type() != ResultType.OFFHAND) ? r.slot() : -1;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue()) return;
        int  fadeTicks = (int)(fadeTime.getValue() * 20);
        int  now       = mc.player.tickCount;
        for (Map.Entry<BlockPos, Integer> entry : renderMap.entrySet()) {
            int age = now - entry.getValue();
            if (age > fadeTicks) continue;
            double t   = (double) age / fadeTicks;
            Color  fc  = fillColor.getValue();
            Color  oc  = outlineColor.getValue();
            RenderUtil.drawBoxFilled(event.getMatrix(), entry.getKey(),
                    withAlpha(fc, (int)(fc.getAlpha() * (1 - t))));
            RenderUtil.drawBox(event.getMatrix(), entry.getKey(),
                    withAlpha(oc, (int)(oc.getAlpha() * (1 - t))), 1.0f);
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
                         boolean placeRedstone, boolean placeBase, float damage) {}

    private record RedstoneSpot(BlockPos pos, boolean place) {}

    private record ArmorProfile(float armor, float toughness, float resistanceMul, int protPoints) {}
}
