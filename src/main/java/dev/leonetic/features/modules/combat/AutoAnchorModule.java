package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.client.TargetsModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.util.AnchorDamageUtil;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class AutoAnchorModule extends Module {

    private final Setting<Double>  minDamage     = num("MinDamage", 6.0, 0.0, 36.0).setPage("General");
    private final Setting<Double>  maxSelfDamage = num("MaxSelfDamage", 4.0, 0.0, 36.0).setPage("General");
    private static final double PLACE_RANGE = 6.0;
    private final Setting<Integer> delay         = num("Delay", 2, 0, 20).setPage("General");
    private final Setting<Boolean> render        = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime      = num("FadeTime", 1.0f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Color>   fillColor     = color("FillColor", 130, 80, 255, 45).setPage("Render");
    private final Setting<Color>   outlineColor  = color("OutlineColor", 130, 80, 255, 255).setPage("Render");

    private int lastBurstTick = -1;
    private float lastDamage = 0f;
    private BlockPos renderPos = null;
    private long renderStart = 0L;

    public AutoAnchorModule() {
        super("AutoAnchor", "Places, charges and detonates respawn anchors in a single burst.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        lastBurstTick = -1;
        lastDamage = 0f;
        renderPos = null;
    }

    @Override
    public void onDisable() {
        lastDamage = 0f;
        renderPos = null;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isDeadOrDying()) return;
        if (mc.level.dimension().equals(Level.NETHER)) return;
        if (mc.player.containerMenu.containerId != 0) return;
        int sinceLast = mc.player.tickCount - lastBurstTick;
        if (sinceLast >= 0 && sinceLast < delay.getValue()) return;

        if (mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;
        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;

        int anchorSlot = hotbarSlotOf(Items.RESPAWN_ANCHOR);
        int glowSlot   = hotbarSlotOf(Items.GLOWSTONE);
        if (anchorSlot < 0 || glowSlot < 0) {
            lastDamage = 0f;
            return;
        }

        List<AnchorDamageUtil.Target> targets = collectTargets();
        if (targets.isEmpty()) {
            lastDamage = 0f;
            return;
        }

        Candidate best = findBest(targets);
        if (best == null) {
            lastDamage = 0f;
            return;
        }

        if (burst(best.pos, best.needPlace, anchorSlot, glowSlot)) {
            lastBurstTick = mc.player.tickCount;
            lastDamage = best.damage;
            if (render.getValue()) {
                renderPos = best.pos;
                renderStart = System.currentTimeMillis();
            }
        }
    }

    private boolean burst(BlockPos pos, boolean needPlace, int anchorSlot, int glowSlot) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return false;

        BlockHitResult placeHit = null;
        if (needPlace) {
            placeHit = Homovore.placementManager.prepareAirPlaceHit(pos);
            if (placeHit == null) return false;
        }

        Vec3 eye = mc.player.getEyePosition(1.0f);
        BlockHitResult hit = anchorHit(pos, eye);
        float[] angles = MathUtil.calcAngle(eye, hit.getLocation());
        Homovore.rotationManager.submit(new RotationRequest(
                "AutoAnchor", 62, angles[0], angles[1], RotationRequest.Mode.SILENT));

        int originalSlot = Homovore.swapManager.serverSlot();
        int returnSlot = isGlowstone(originalSlot) ? anchorSlot : originalSlot;
        int currentSlot = originalSlot;

        if (needPlace) {
            if (anchorSlot != currentSlot) {
                conn.send(new ServerboundSetCarriedItemPacket(anchorSlot));
                currentSlot = anchorSlot;
            }

            conn.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            sendUseItemOn(conn, placeHit, InteractionHand.OFF_HAND);
            conn.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            Homovore.placementManager.notePlacement(pos);
        }

        if (glowSlot != currentSlot) {
            conn.send(new ServerboundSetCarriedItemPacket(glowSlot));
            currentSlot = glowSlot;
        }
        sendUseItemOn(conn, hit);

        if (returnSlot != currentSlot) {
            conn.send(new ServerboundSetCarriedItemPacket(returnSlot));
            currentSlot = returnSlot;
        }
        sendUseItemOn(conn, hit);
        conn.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        if (currentSlot != originalSlot) conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
        return true;
    }

    private void sendUseItemOn(ClientPacketListener conn, BlockHitResult hit) {
        sendUseItemOn(conn, hit, InteractionHand.MAIN_HAND);
    }

    private void sendUseItemOn(ClientPacketListener conn, BlockHitResult hit, InteractionHand hand) {
        try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
            conn.send(new ServerboundUseItemOnPacket(hand, hit, handler.currentSequence()));
        }
    }

    private boolean isGlowstone(int slot) {
        if (slot < 0 || slot > 8) return false;
        ItemStack stack = mc.player.getInventory().getItem(slot);
        return stack.is(Items.GLOWSTONE);
    }

    private BlockHitResult anchorHit(BlockPos pos, Vec3 eye) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x, dy = eye.y - center.y, dz = eye.z - center.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Direction face;
        if (ay >= ax && ay >= az)  face = dy > 0 ? Direction.UP    : Direction.DOWN;
        else if (ax >= az)         face = dx > 0 ? Direction.EAST  : Direction.WEST;
        else                       face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        Vec3 hitVec = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(hitVec, face, pos, false);
    }

    private Candidate findBest(List<AnchorDamageUtil.Target> targets) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        BlockPos playerPos = mc.player.blockPosition();
        double maxSelf = maxSelfDamage.getValue();
        double min = minDamage.getValue();
        double rangeSq = PLACE_RANGE * PLACE_RANGE;
        float playerHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int r = (int) Math.ceil(PLACE_RANGE);
        int rr = r * r;

        Candidate best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            int xx = x * x;
            for (int y = -r; y <= r; y++) {
                int xxyy = xx + y * y;
                for (int z = -r; z <= r; z++) {
                    if (xxyy + z * z > rr) continue;
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    Vec3 center = Vec3.atCenterOf(cursor);
                    if (eye.distanceToSqr(center) > rangeSq) continue;

                    BlockState state = mc.level.getBlockState(cursor);
                    boolean existing = state.is(Blocks.RESPAWN_ANCHOR);
                    BlockPos pos = null;
                    boolean bounded = false;
                    for (int i = 0, n = targets.size(); i < n; i++) {
                        AnchorDamageUtil.Target target = targets.get(i);
                        if (target.pos().distanceTo(center) > AnchorDamageUtil.DIAMETER) continue;
                        if (AnchorDamageUtil.maxDamage(target, center) >= min) { bounded = true; break; }
                    }
                    if (!bounded) continue;

                    if (existing) {
                        pos = cursor.immutable();
                    } else {
                        if (!state.canBeReplaced()) continue;
                        if (!PlaceUtil.canPlace(cursor)) continue;
                        pos = cursor.immutable();
                    }

                    float total = 0f;
                    boolean any = false;
                    for (int i = 0, n = targets.size(); i < n; i++) {
                        AnchorDamageUtil.Target target = targets.get(i);
                        if (target.pos().distanceTo(center) > AnchorDamageUtil.DIAMETER) continue;
                        float dmg = AnchorDamageUtil.damage(target, center, pos);
                        if (dmg <= 0f) continue;
                        total += dmg;
                        any = true;
                    }
                    if (!any || total < min) continue;

                    float self = AnchorDamageUtil.selfDamage(center, pos);
                    if (best != null && total <= best.damage) continue;
                    if (self > maxSelf) continue;

                    if (self > 0f && self + 1.5f >= playerHealth) continue;

                    best = new Candidate(pos, total, !existing);
                }
            }
        }
        return best;
    }

    private List<AnchorDamageUtil.Target> collectTargets() {
        TargetsModule targets = Homovore.moduleManager.getModuleByClass(TargetsModule.class);
        List<AnchorDamageUtil.Target> out = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(PLACE_RANGE + AnchorDamageUtil.DIAMETER);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.isDeadOrDying()) continue;
            if (targets != null && !targets.isValidPlayerTarget(e)) continue;
            out.add(AnchorDamageUtil.Target.of(living));
        }
        return out;
    }

    private int hotbarSlotOf(net.minecraft.world.item.Item item) {
        Result r = InventoryUtil.find(item, InventoryUtil.HOTBAR_SCOPE);
        return (r.found() && r.type() != ResultType.OFFHAND) ? r.slot() : -1;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || renderPos == null) return;
        long age = System.currentTimeMillis() - renderStart;
        double fadeMs = fadeTime.getValue() * 1000.0;
        if (age > fadeMs) {
            renderPos = null;
            return;
        }
        double t = age / fadeMs;
        Color fc = fillColor.getValue();
        Color oc = outlineColor.getValue();
        RenderUtil.drawBoxFilled(event.getMatrix(), renderPos,
                withAlpha(fc, (int) (fc.getAlpha() * (1 - t))));
        RenderUtil.drawBox(event.getMatrix(), renderPos,
                withAlpha(oc, (int) (oc.getAlpha() * (1 - t))), 1.0f);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }

    @Override
    public String getDisplayInfo() {
        return lastDamage > 0 ? String.format("%.1f", lastDamage) : null;
    }

    private record Candidate(BlockPos pos, float damage, boolean needPlace) {}
}
