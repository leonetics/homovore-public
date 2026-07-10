package dev.leonetic.util.render;

import dev.leonetic.Homovore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;

import java.util.*;

public final class WorldChamsChain {

    private static final Identifier SCREENQUAD = Identifier.fromNamespaceAndPath("minecraft", "core/screenquad");
    private static final Identifier H_FSH      = Identifier.fromNamespaceAndPath("homovore", "post/chams_default_h");
    private static final Identifier C_FSH      = Identifier.fromNamespaceAndPath("homovore", "post/chams_default_c");
    private static final Identifier V_FSH      = Identifier.fromNamespaceAndPath("homovore", "post/chams_default_v");
    private static final Identifier CHAMS_H    = Identifier.fromNamespaceAndPath("homovore", "chams_h");
    private static final Identifier CHAMS_C    = Identifier.fromNamespaceAndPath("homovore", "chams_c");
    private static final Identifier CHAIN_NAME = Identifier.fromNamespaceAndPath("homovore", "chams_default_runtime");
    private static final UniformWriter UNIFORM_WRITER = new UniformWriter();

    private static final int LINE_WIDTH = 2;

    private static CachedOrthoProjectionMatrixBuffer projection;
    private static PostChain cached;
    private static int   lastGlowRadius    = Integer.MIN_VALUE;
    private static float lastGlowIntensity = Float.NaN;
    private static float lastFillTint      = Float.NaN;
    private static float lastFillAlpha     = Float.NaN;

    private WorldChamsChain() {}

    public static PostChain get(Set<Identifier> externalTargets, int glowRadius, float glowIntensity,
                                float fillTint, float fillAlpha) {
        if (cached != null && glowRadius == lastGlowRadius && glowIntensity == lastGlowIntensity
                && fillTint == lastFillTint && fillAlpha == lastFillAlpha) {
            return cached;
        }
        else if (cached != null)
        {
            lastGlowRadius = glowRadius;
            lastGlowIntensity = glowIntensity;
            lastFillTint = fillTint;
            lastFillAlpha = fillAlpha;

            Map<String, List<UniformValue>> configs = new HashMap<>();
            List<UniformValue> dilateUniforms = List.of(integer(LINE_WIDTH), integer(glowRadius));
            List<UniformValue> colorUniforms = List.of(integer(LINE_WIDTH), integer(glowRadius));
            List<UniformValue> chamsUniforms = List.of(
                    flt(glowIntensity),
                    flt(fillTint),
                    flt(fillAlpha),
                    integer(glowRadius),
                    integer(LINE_WIDTH)
            );

            configs.put("DilateConfig", dilateUniforms);
            configs.put("ColorConfig", colorUniforms);
            configs.put("ChamsConfig", chamsUniforms);
            UNIFORM_WRITER.setUniforms(cached, configs);
            return cached;
        }

        PostChain rebuilt = build(externalTargets, glowRadius, glowIntensity, fillTint, fillAlpha);
        if (rebuilt == null) return cached;
        if (cached != null) cached.close();
        cached = rebuilt;
        lastGlowRadius = glowRadius;
        lastGlowIntensity = glowIntensity;
        lastFillTint = fillTint;
        lastFillAlpha = fillAlpha;
        return cached;
    }

    private static PostChain build(Set<Identifier> externalTargets, int glowThickness, float glowIntensity,
                                   float fillTint, float fillAlpha) {
        try {
            if (projection == null) {
                projection = new CachedOrthoProjectionMatrixBuffer("homovore_chams", 0.1f, 1000.0f, false);
            }

            Identifier outlineTarget = LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID;

            PostChainConfig.Pass passH = new PostChainConfig.Pass(
                    SCREENQUAD, H_FSH,
                    List.of(new PostChainConfig.TargetInput("In", outlineTarget, false, false)),
                    CHAMS_H,
                    Map.of("DilateConfig", List.<UniformValue>of(integer(LINE_WIDTH), integer(glowThickness))));

            PostChainConfig.Pass passC = new PostChainConfig.Pass(
                    SCREENQUAD, C_FSH,
                    List.of(new PostChainConfig.TargetInput("In", outlineTarget, false, false)),
                    CHAMS_C,
                    Map.of("ColorConfig", List.<UniformValue>of(integer(LINE_WIDTH), integer(glowThickness))));

            List<UniformValue> chamsConfig = List.of(
                    flt(glowIntensity),
                    flt(fillTint),
                    flt(fillAlpha),
                    integer(glowThickness),
                    integer(LINE_WIDTH)
            );
            PostChainConfig.Pass passV = new PostChainConfig.Pass(
                    SCREENQUAD, V_FSH,
                    List.of(new PostChainConfig.TargetInput("In", CHAMS_H, false, false),
                            new PostChainConfig.TargetInput("Color", CHAMS_C, false, false),
                            new PostChainConfig.TargetInput("Orig", outlineTarget, false, false)),
                    outlineTarget,
                    Map.of("ChamsConfig", chamsConfig));

            PostChainConfig config = new PostChainConfig(
                    Map.of(CHAMS_H, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0),
                           CHAMS_C, new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0)),
                    List.of(passH, passC, passV));

            return PostChain.load(config, Minecraft.getInstance().getTextureManager(),
                    externalTargets, CHAIN_NAME, projection);
        } catch (Exception e) {
            return null;
        }
    }

    private static UniformValue flt(float v) {
        return new UniformValue.FloatUniform(v);
    }

    private static UniformValue integer(int v) {
        return new UniformValue.IntUniform(v);
    }
}
