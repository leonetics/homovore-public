package dev.leonetic.features.modules.render;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.InteractionUtil;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BreakIndicatorsModule extends Module {

    private static final long TTL_MS = 2000L;

    public Setting<Boolean> ignoreFriends = bool("IgnoreFriends", false).setPage("General");
    public Setting<Float> completion = num("Completion", 1.0f, 0.1f, 1.5f).setPage("General");

    public Setting<Float> lineWidth = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    public Setting<Color> lineColor = color("LineColor", 255, 255, 255, 120).setPage("Render");
    public Setting<Color> sideColor = color("SideColor", 255, 0, 80, 40).setPage("Render");

    private final Map<Long, BreakEntry> breaks = new ConcurrentHashMap<>();

    public BreakIndicatorsModule() {
        super("BreakIndicators", "Renders the progress of blocks being broken by others", Category.RENDER);
    }

    @Override
    public void onEnable() {
        breaks.clear();
    }

    @Override
    public void onDisable() {
        breaks.clear();
    }

    @Subscribe
    private void onPacket(PacketEvent.Receive event) {
        if (nullCheck()) return;
        if (!(event.getPacket() instanceof ClientboundBlockDestructionPacket pkt)) return;
        if (mc.player != null && pkt.getId() == mc.player.getId()) return;

        BlockPos pos = pkt.getPos();
        int stage = pkt.getProgress();

        if (stage < 0 || stage > 9) {
            breaks.remove(pos.asLong());
            return;
        }

        Entity entity = mc.level.getEntity(pkt.getId());
        if (ignoreFriends.getValue() && entity instanceof Player player && Homovore.friendManager.isFriend(player)) {
            breaks.remove(pos.asLong());
            return;
        }

        BreakEntry entry = breaks.computeIfAbsent(pos.asLong(), k -> new BreakEntry(pos.immutable(), mc.level.getGameTime()));
        entry.entity = entity;
        entry.stage = stage;
        entry.lastUpdate = System.currentTimeMillis();
    }

    @Subscribe
    private void onRenderEvent(Render3DEvent event) {
        if (nullCheck() || breaks.isEmpty()) return;

        long time = System.currentTimeMillis();

        Iterator<Map.Entry<Long, BreakEntry>> it = breaks.entrySet().iterator();
        while (it.hasNext()) {
            BreakEntry entry = it.next().getValue();
            BlockPos pos = entry.pos;
            BlockState state = mc.level.getBlockState(pos);

            if (time - entry.lastUpdate > TTL_MS || state.isAir() || !InteractionUtil.canBreak(pos, state)) {
                it.remove();
                continue;
            }
            if (ignoreFriends.getValue() && entry.entity instanceof Player player && Homovore.friendManager.isFriend(player)) {
                it.remove();
                continue;
            }

            double progress = (entry.stage + 1) / 10.0;

            AABB box = shrunkBox(pos, state, progress);
            RenderUtil.drawBoxFilled(event.getMatrix(), box, sideColor.getValue());
            RenderUtil.drawBox(event.getMatrix(), box, lineColor.getValue(), lineWidth.getValue());
        }
    }

    private AABB shrunkBox(BlockPos pos, BlockState state, double progress) {
        VoxelShape shape = state.getShape(mc.level, pos);
        AABB base = shape.isEmpty() ? new AABB(BlockPos.ZERO) : shape.bounds();
        double scale = Math.clamp(progress / completion.getValue(), 0.0, 1.0);

        double cx = (base.minX + base.maxX) / 2.0;
        double cy = (base.minY + base.maxY) / 2.0;
        double cz = (base.minZ + base.maxZ) / 2.0;
        double hw = (base.maxX - base.minX) / 2.0 * scale;
        double hh = (base.maxY - base.minY) / 2.0 * scale;
        double hd = (base.maxZ - base.minZ) / 2.0 * scale;

        return new AABB(cx - hw, cy - hh, cz - hd, cx + hw, cy + hh, cz + hd)
            .move(pos.getX(), pos.getY(), pos.getZ());
    }

    private static class BreakEntry {
        final BlockPos pos;
        final double startTick;
        Entity entity;
        int stage;
        long lastUpdate;

        BreakEntry(BlockPos pos, double startTick) {
            this.pos = pos;
            this.startTick = startTick;
        }
    }
}
