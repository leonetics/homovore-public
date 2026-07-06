package dev.leonetic.mixin.render;

import dev.leonetic.features.modules.render.NoRenderModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class MixinParticleEngine {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void homovore$noParticles(ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir) {
        if (NoRenderModule.isActive(m -> m.noPotion.getValue())
                && (particle.getType() == ParticleTypes.ENTITY_EFFECT
                || particle.getType() == ParticleTypes.EFFECT
                || particle.getType() == ParticleTypes.INSTANT_EFFECT
                || particle.getType() == ParticleTypes.WITCH)) {
            cir.setReturnValue(null);
            return;
        }

        if (NoRenderModule.isActive(m -> m.noExplosion.getValue())
                && (particle.getType() == ParticleTypes.EXPLOSION || particle.getType() == ParticleTypes.EXPLOSION_EMITTER)) {
            cir.setReturnValue(null);
            return;
        }

        if (NoRenderModule.isActive(m -> m.noTotemParticle.getValue())
                && particle.getType() == ParticleTypes.TOTEM_OF_UNDYING) {
            cir.setReturnValue(null);
            return;
        }

        if (NoRenderModule.isActive(m -> m.noWaterParticle.getValue())
                && (particle.getType() == ParticleTypes.RAIN
                || particle.getType() == ParticleTypes.SPLASH
                || particle.getType() == ParticleTypes.UNDERWATER
                || particle.getType() == ParticleTypes.BUBBLE
                || particle.getType() == ParticleTypes.BUBBLE_POP
                || particle.getType() == ParticleTypes.BUBBLE_COLUMN_UP
                || particle.getType() == ParticleTypes.DRIPPING_DRIPSTONE_WATER
                || particle.getType() == ParticleTypes.DRIPPING_WATER
                || particle.getType() == ParticleTypes.FALLING_DRIPSTONE_WATER
                || particle.getType() == ParticleTypes.FALLING_WATER)) {
            cir.setReturnValue(null);
        }
    }
}
