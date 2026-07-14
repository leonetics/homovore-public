package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.TickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    private final Setting<Double>  placeRange   = num("PlaceRange",   6.0, 1.0, 6.0).setPage("General");
    private final Setting<Double>  minDamage    = num("MinDamage",    6.0, 0.0, 36.0).setPage("General");
    private final Setting<Boolean> offhandPlace = bool("OffhandPlace", true).setPage("General");
    private final Setting<Boolean> autoBase     = bool("AutoBase",   true).setPage("General");
    private final Setting<Boolean> ignoreItems  = bool("IgnoreItems", true).setPage("General");

    private final Setting<Boolean> render      = bool("Render",      true).setPage("Render");
    private final Setting<Float>   fadeTime    = num("FadeTime",     0.2f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor   = color("FillColor",  0, 62, 122, 148).setPage("Render");
    private final Setting<Color>   outlineColor = color("OutlineColor", 0, 62, 122, 148).setPage("Render");

    private static final int CONTRAPTION_PROTECT_TICKS = 60;

    private final Map<BlockPos, Integer> renderMap = new HashMap<>();
    private final Map<BlockPos, Integer> placedContraption = new HashMap<>();

    private float   lastDamage;

    private ArmorProfile                     targetProfile;
    private net.minecraft.world.Difficulty   targetDifficulty;

    public PistonCrystalModule() {
        super("PistonCrystal",
              "piss crystal",
              Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        Homovore.rotationManager.cancel(ROTATION_ID);
        resetState();
    }

    private void resetState() {
        lastDamage = 0;
        renderMap.clear();
        placedContraption.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        int fadeTicks = (int)(fadeTime.getValue() * 20);
        int now = mc.player.tickCount;
        renderMap.entrySet().removeIf(e -> now - e.getValue() > fadeTicks);
        placedContraption.entrySet().removeIf(e -> now - e.getValue() > CONTRAPTION_PROTECT_TICKS);

        int pistonSlot   = pistonSlot();
        int redstoneSlot = hotbarSlotOf(Items.REDSTONE_BLOCK);
        int crystalSlot  = hotbarSlotOf(Items.END_CRYSTAL);
        if (pistonSlot < 0 || crystalSlot < 0) {
            lastDamage = 0;
            return;
        }

        Setup setup = findSetup();
        if (setup == null) return;
        if (setup.placeRedstone() && redstoneSlot < 0) return;

        if (!Homovore.rotationManager.submit(new RotationRequest(
                ROTATION_ID, ROTATION_PRIORITY,
                setup.dir().toYRot(), 0f,
                RotationRequest.Mode.SILENT))) return;

        place(setup, pistonSlot, redstoneSlot, crystalSlot);
    }

    private void place(Setup setup, int pistonSlot, int redstoneSlot, int crystalSlot) {
        int obsidianSlot = -1;
        if (setup.placeBase()) {
            obsidianSlot = hotbarSlotOf(Items.OBSIDIAN);
            if (obsidianSlot < 0) return;
        }

        if (setup.placeBase()
                && !Homovore.placementManager.placeDirect(setup.base(), setup.baseFace(), obsidianSlot))
            return;
        int tick = mc.player.tickCount;
        if (setup.placeBase()) renderMap.put(setup.base(), tick);

        if (!Homovore.placementManager.placeDirect(setup.piston(), setup.pistonFace(), pistonSlot)) return;
        renderMap.put(setup.piston(), tick);
        placedContraption.put(setup.piston(), tick);

        if (setup.placeRedstone()) {
            if (!Homovore.placementManager.placeDirect(setup.redstone(), setup.redstoneFace(), redstoneSlot)) return;
            renderMap.put(setup.redstone(), tick);
            placedContraption.put(setup.redstone(), tick);
        }

        if (!placeCrystal(setup.base(), crystalSlot)) return;
        renderMap.put(setup.crystal(), tick);
    }

    public boolean isOwnPistonBlock(BlockPos pos) {
        if (pos == null || isDisabled()) return false;

        Integer placedTick = placedContraption.get(pos);
        if (placedTick != null && mc.player != null
                && mc.player.tickCount - placedTick <= CONTRAPTION_PROTECT_TICKS) {
            return true;
        }

        return false;
    }

    private boolean placeCrystal(BlockPos base, int slot) {
        if (offhandPlace.getValue()) {
            return Homovore.placementManager.placeCrystalOffhand(base, slot, true);
        }
        return Homovore.placementManager.placeCrystal(base, slot, true);
    }

    private Setup findSetup() {
        LivingEntity target = findTarget();
        if (target == null) { lastDamage = 0; return null; }

        targetProfile    = profileOf(target);
        targetDifficulty = mc.level.getDifficulty();

        Vec3  eye   = mc.player.getEyePosition();
        double range = placeRange.getValue();

        AABB bb = target.getBoundingBox();
        int minX = Mth.floor(bb.minX), maxX = Mth.floor(bb.maxX - 1e-7);
        int minY = Mth.floor(bb.minY), maxY = Mth.floor(bb.maxY - 1e-7);
        int minZ = Mth.floor(bb.minZ), maxZ = Mth.floor(bb.maxZ - 1e-7);

        Setup best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    cursor.set(bx, by, bz);
                    BlockPos cell = cursor.immutable();
                    BlockPos[] heads = {cell, cell.above()};

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
        return buildSetup(target, dir, crystalPos, base, pistonPos,
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
        return buildSetup(target, dir, crystalPos, base, pistonPos,
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

    private Setup buildSetup(LivingEntity target, Direction dir,
                              BlockPos crystalPos, BlockPos base, BlockPos pistonPos,
                             Vec3 explosionPos, Vec3 eye, double range,
                             boolean placeBase) {
        if (crystalObscuresPiston(pistonPos)) return null;
        if (!PlaceUtil.canPlace(pistonPos)) return null;
        if (ignoreItems.getValue() && itemInCrystalSpot(crystalPos)) return null;

        Direction baseFace   = placeBase ? supportFace(base) : null;
        Direction pistonFace = supportFace(pistonPos, placeBase ? base : null);

        double rangeSq = range * range;
        if (eye.distanceToSqr(Vec3.atCenterOf(pistonPos)) > rangeSq) return null;
        if (eye.distanceToSqr(Vec3.atCenterOf(base))      > rangeSq) return null;

        float damage = calcDamage(target, explosionPos, placeBase ? base : null);
        if (damage < minDamage.getValue()) return null;

        RedstoneSpot redstone = findRedstoneSpot(pistonPos, dir, explosionPos, eye, rangeSq,
                                                 placeBase ? base : null);
        if (redstone == null) return null;

        return new Setup(dir, pistonPos, pistonFace, redstone.pos(), redstone.face(),
                         crystalPos, base, baseFace,
                         redstone.place(), placeBase, damage);
    }

    private boolean crystalObscuresPiston(BlockPos pistonPos) {
        AABB placementBox = new AABB(pistonPos);
        return !mc.level.getEntitiesOfClass(
                EndCrystal.class,
                placementBox,
                EndCrystal::isAlive).isEmpty();
    }

    private boolean itemInCrystalSpot(BlockPos crystalPos) {
        AABB box = new AABB(crystalPos.getX(),     crystalPos.getY(),     crystalPos.getZ(),
                            crystalPos.getX() + 1, crystalPos.getY() + 2, crystalPos.getZ() + 1);
        for (Entity e : mc.level.getEntities((Entity) null, box)) {
            if (e instanceof ItemEntity) return true;
        }
        return false;
    }

    private Direction supportFace(BlockPos pos, BlockPos... phantoms) {
        Direction real = Homovore.placementManager.getPlaceSide(pos);
        if (real != null) return real;
        for (Direction d : Direction.values()) {
            BlockPos neighbour = pos.relative(d);
            for (BlockPos phantom : phantoms) {
                if (phantom != null && phantom.equals(neighbour)) return d;
            }
        }
        return null;
    }

    private RedstoneSpot findRedstoneSpot(BlockPos pistonPos, Direction dir,
                                          Vec3 explosionPos, Vec3 eye, double rangeSq,
                                          BlockPos phantomBase) {
        BlockPos above       = pistonPos.above();
        BlockPos inDir       = pistonPos.relative(dir);
        BlockPos cw          = pistonPos.relative(dir.getClockWise());
        BlockPos ccw         = pistonPos.relative(dir.getCounterClockWise());
        BlockPos below       = pistonPos.below();

        if (mc.level.getBlockState(above).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(above, null, false);
        if (mc.level.getBlockState(inDir).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(inDir, null, false);
        if (mc.level.getBlockState(cw).is(Blocks.REDSTONE_BLOCK))    return new RedstoneSpot(cw,    null, false);
        if (mc.level.getBlockState(ccw).is(Blocks.REDSTONE_BLOCK))   return new RedstoneSpot(ccw,   null, false);
        if (mc.level.getBlockState(below).is(Blocks.REDSTONE_BLOCK)) return new RedstoneSpot(below, null, false);

        BlockPos  fallback     = null;
        Direction fallbackFace = null;
        boolean   hasFallback  = false;
        for (BlockPos pos : new BlockPos[]{above, inDir, cw, ccw, below}) {
            if (!PlaceUtil.canPlace(pos)) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue;
            Direction face = supportFace(pos, pistonPos, phantomBase);
            if (redstoneSafe(pos, explosionPos)) return new RedstoneSpot(pos, face, true);
            if (!hasFallback) {
                fallback     = pos;
                fallbackFace = face;
                hasFallback  = true;
            }
        }
        return hasFallback ? new RedstoneSpot(fallback, fallbackFace, true) : null;
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

    private record Setup(Direction dir,
                         BlockPos piston,   Direction pistonFace,
                         BlockPos redstone, Direction redstoneFace,
                         BlockPos crystal,
                         BlockPos base,     Direction baseFace,
                         boolean placeRedstone, boolean placeBase, float damage) {}

    private record RedstoneSpot(BlockPos pos, Direction face, boolean place) {}

    private record ArmorProfile(float armor, float toughness, float resistanceMul, int protPoints) {}
}
