package dev.leonetic.features.modules.world;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.AttackBlockEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.LitematicaSchematics;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NukerModule extends Module {

    private static final double NUKE_PRIORITY = 50.0;

    enum Shape { All, Flat, Schematic }

    enum BlockMode { All, Select }

    private final Setting<Double>    range     = num("Range", 5.14, 0.0, 7.0);
    private final Setting<Shape>     shape     = mode("Shape", Shape.All);
    private final Setting<BlockMode> blockMode = mode("Blocks", BlockMode.All);

    private final Setting<Boolean> schematicAllLayers     = bool("AllLayers", false).setVisibility(v -> shape.getValue() == Shape.Schematic);
    private final Setting<Boolean> schematicExtraBlocks   = bool("ExtraBlocks", false).setVisibility(v -> shape.getValue() == Shape.Schematic);
    private final Setting<Boolean> schematicIncorrectState = bool("IncorrectState", false).setVisibility(v -> shape.getValue() == Shape.Schematic);

    private final Setting<Boolean> render    = bool("Render", true).setPage("Render");
    private final Setting<Float>   lineWidth = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color>   lineColor = color("LineColor", 255, 40, 40, 255).setPage("Render");
    private final Setting<Color>   fillColor = color("FillColor", 255, 40, 40, 0).setPage("Render");

    private final List<BlockPos> targetedBlocks = new ArrayList<>();

    private Block selectedBlock = null;

    public NukerModule() {
        super("Nuker", "Breaks all blocks around you through SpeedMine. Requires SpeedMine.", Category.WORLD);
    }

    @Override
    public void onDisable() {
        targetedBlocks.clear();
        selectedBlock = null;
    }

    @Subscribe
    private void onAttackBlock(AttackBlockEvent event) {
        if (nullCheck()) return;
        if (blockMode.getValue() != BlockMode.Select) return;

        BlockState state = mc.level.getBlockState(event.getPos());
        if (state.isAir()) return;

        selectedBlock = state.getBlock();
        Command.sendMessage("Nuker selected: " + selectedBlock.getName().getString());
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        targetedBlocks.clear();

        SpeedMineModule mine = Homovore.moduleManager.getModuleByClass(SpeedMineModule.class);
        if (mine == null || !mine.isEnabled()) return;

        Vec3 eye = mc.player.getEyePosition();
        double r = range.getValue();
        double rSq = r * r;
        BlockPos origin = mc.player.blockPosition();
        int reach = (int) Math.ceil(r);

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -reach; x <= reach; x++) {
            for (int y = -reach; y <= reach; y++) {
                for (int z = -reach; z <= reach; z++) {

                    if (shape.getValue() == Shape.Flat && y < 0) continue;

                    BlockPos pos = origin.offset(x, y, z);
                    if (new AABB(pos).distanceToSqr(eye) > rSq) continue;

                    BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (!canBreak(pos, state)) continue;
                    if (!passesFilter(state)) continue;
                    if (shape.getValue() == Shape.Schematic && !passesSchematicFilter(pos, state)) continue;
                    if (!mine.inBreakRange(pos)) continue;

                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> new AABB(p).distanceToSqr(eye)));

        for (BlockPos pos : candidates) {
            if (mine.alreadyBreaking(pos)) targetedBlocks.add(pos);
        }

        int activeSlots = (mine.getDelayedDestroyBlockPos() != null ? 1 : 0)
                        + (mine.getRebreakBlockPos() != null ? 1 : 0);

        for (BlockPos pos : candidates) {
            if (activeSlots >= 2) break;
            if (mine.alreadyBreaking(pos)) continue;

            mine.silentBreakBlock(pos, Direction.UP, NUKE_PRIORITY);

            if (mine.alreadyBreaking(pos)) {
                targetedBlocks.add(pos);
                activeSlots++;
            }
        }
    }

    @Subscribe
    private void onRenderTargets(Render3DEvent event) {
        if (nullCheck() || !render.getValue()) return;

        Color line = lineColor.getValue();
        Color fill = fillColor.getValue();
        float lw = lineWidth.getValue();

        for (BlockPos pos : targetedBlocks) {
            if (mc.level.getBlockState(pos).isAir()) continue;
            if (fill.getAlpha() > 0) RenderUtil.drawBoxFilled(event.getMatrix(), pos, fill);
            if (line.getAlpha() > 0) RenderUtil.drawBox(event.getMatrix(), pos, line, lw);
        }
    }

    private boolean passesFilter(BlockState state) {
        if (blockMode.getValue() == BlockMode.All) return true;

        return selectedBlock != null && state.getBlock() == selectedBlock;
    }

    private boolean canBreak(BlockPos pos, BlockState state) {
        return !state.isAir() && state.getDestroySpeed(mc.level, pos) >= 0;
    }

    /**
     * Schematic filter: only target world blocks that don't match the loaded Litematica schematic.
     * <ul>
     *   <li><b>Incorrect Block:</b> schematic has a different non-air block — always targeted.</li>
     *   <li><b>Incorrect State:</b> same block type but different state — only if IncorrectState is enabled.</li>
     *   <li><b>Extra Block:</b> schematic is air but the world has a block — only if ExtraBlocks is enabled.</li>
     * </ul>
     */
    private boolean passesSchematicFilter(BlockPos pos, BlockState worldState) {
        if (!LitematicaSchematics.isAvailable()) return false;

        BlockState schematicState = LitematicaSchematics.getSchematicBlockState(pos, !schematicAllLayers.getValue());
        if (schematicState == null) return false; // Position not covered by any schematic

        if (schematicState.isAir()) {
            // Schematic expects air here but the world has a block — this is an "extra block".
            if (!schematicExtraBlocks.getValue()) return false;
            // Only nuke if the position is actually inside a schematic placement's sub-region box.
            // (The schematic world loads whole chunks, so positions outside the real sub-regions
            //  also read as air — we must not nuke those.)
            return LitematicaSchematics.isInsideLoadedSchematicBox(pos);
        }

        // Schematic wants a non-air block here
        if (worldState.getBlock() != schematicState.getBlock()) {
            // Incorrect block: completely wrong block type — always target
            return true;
        }

        // Same block type — check if the state differs
        if (!worldState.equals(schematicState)) {
            // Incorrect state: right block, wrong properties
            return schematicIncorrectState.getValue();
        }

        // Block matches the schematic perfectly — don't break
        return false;
    }
}
