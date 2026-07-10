package dev.leonetic.features.modules.world;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScaffoldModule extends Module {

    private final Setting<Double>  radius = num("Radius", 1.0, 1.0, 5.0);
    private final Setting<Integer> depth  = num("Depth", 1, 1, 5);

    private final Setting<Double>  lookahead = num("Lookahead", 1.0, 0.0, 5.0);

    private final Setting<Boolean> render    = bool("Render", true).setPage("Render");
    private final Setting<Float>   fadeTime  = num("FadeTime", 0.5f, 0.05f, 2.0f).setPage("Render");
    private final Setting<Float>   lineWidth = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color>   lineColor = color("LineColor", 85, 255, 85, 180).setPage("Render");
    private final Setting<Color>   fillColor = color("FillColor", 85, 255, 85, 35).setPage("Render");

    private final Map<BlockPos, Long> renderMap = new LinkedHashMap<>();

    public ScaffoldModule() {
        super("Scaffold", "Places blocks beneath you as you walk, through the PlacementManager. No rotations.", Category.WORLD);
    }

    @Override
    public void onDisable() {
        renderMap.clear();
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (mc.player.isSpectator()) return;

        int slot = findBlockHotbarSlot();
        if (slot < 0) return;

        Vec3 eye = mc.player.getEyePosition();

        Vec3 vel = mc.player.getDeltaMovement();
        double centerX = mc.player.getX() + vel.x * lookahead.getValue();
        double centerZ = mc.player.getZ() + vel.z * lookahead.getValue();
        int baseX = Mth.floor(centerX);
        int baseZ = Mth.floor(centerZ);
        int feetY = mc.player.blockPosition().getY();

        int drop = mc.options.keyShift.isDown() ? 1 : 0;

        double r = radius.getValue() - 1;
        double rSq = r * r;
        int scanR = (int) Math.ceil(r);
        int d = depth.getValue();

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -scanR; x <= scanR; x++) {
            for (int z = -scanR; z <= scanR; z++) {
                if (x * x + z * z > rSq) continue;
                for (int dy = 1; dy <= d; dy++) {
                    BlockPos pos = new BlockPos(baseX + x, feetY - dy - drop, baseZ + z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (!state.canBeReplaced()) continue;
                    if (!PlaceUtil.canPlace(pos)) continue;

                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> eye.distanceToSqr(Vec3.atCenterOf(p))));

        long now = System.currentTimeMillis();
        for (BlockPos pos : Homovore.placementManager.placeBatchOffhand(candidates, slot)) {
            renderMap.put(pos, now);
        }

        long fadeMs = (long) (fadeTime.getValue() * 1000);
        renderMap.entrySet().removeIf(e -> now - e.getValue() > fadeMs);
    }

    private int findBlockHotbarSlot() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getItem(selected).getItem() instanceof BlockItem) return selected;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) return i;
        }
        return -1;
    }

    @Subscribe
    private void onRenderTargets(Render3DEvent event) {
        if (nullCheck() || !render.getValue()) return;

        Color line = lineColor.getValue();
        Color fill = fillColor.getValue();
        float lw = lineWidth.getValue();
        long now = System.currentTimeMillis();
        double fadeMs = fadeTime.getValue() * 1000.0;

        for (Map.Entry<BlockPos, Long> entry : renderMap.entrySet()) {
            long age = now - entry.getValue();
            if (age > fadeMs) continue;

            float t = (float) (1.0 - age / fadeMs);
            BlockPos pos = entry.getKey();
            if (fill.getAlpha() > 0) RenderUtil.drawBoxFilled(event.getMatrix(), pos, withAlpha(fill, (int) (fill.getAlpha() * t)));
            if (line.getAlpha() > 0) RenderUtil.drawBox(event.getMatrix(), pos, withAlpha(line, (int) (line.getAlpha() * t)), lw);
        }
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Mth.clamp(a, 0, 255));
    }
}
