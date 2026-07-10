package dev.leonetic.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(PostPass.class)
public interface PostPassAccessor
{
    @Accessor(value = "customUniforms")
    Map<String, GpuBuffer> homovore$getUniformBuffers();
}
