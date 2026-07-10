package dev.leonetic.util.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.opengl.GlBuffer;
import dev.leonetic.mixin.render.GLBufferAccessor;
import dev.leonetic.mixin.render.PostChainAccessor;
import dev.leonetic.mixin.render.PostPassAccessor;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.UniformValue;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL31.*;

/**
 * Writes uniforms directly to a {@link PostChain}.
 */
public class UniformWriter
{
    private final Map<String, Integer> cache = new HashMap<>();

    public void setUniforms(PostChain chain, Map<String, List<UniformValue>> configs)
    {
        if (configs.isEmpty())
        {
            return;
        }

        List<PostPass> passes = ((PostChainAccessor) chain).homovore$getPasses();
        for (PostPass pass : passes)
        {
            Map<String, GpuBuffer> uniformBuffers = ((PostPassAccessor) pass).homovore$getUniformBuffers();
            for (Map.Entry<String, List<UniformValue>> entry : configs.entrySet())
            {
                GpuBuffer dest = uniformBuffers.get(entry.getKey());
                if (!(dest instanceof GlBuffer))
                {
                    continue;
                }

                int handle    = ((GLBufferAccessor) dest).homovore$getHandle();
                int size      = getSize(entry.getValue());
                int stagingId = createStagingBuffer(entry.getKey(), size);

                try (MemoryStack stack = MemoryStack.stackPush())
                {
                    ByteBuffer buf = stack.malloc(size);
                    Std140Builder builder = Std140Builder.intoBuffer(buf);
                    for (UniformValue value : entry.getValue())
                    {
                        value.writeTo(builder);
                    }

                    buf.flip();
                    glBindBuffer(GL_COPY_READ_BUFFER, stagingId);
                    glBufferSubData(GL_COPY_READ_BUFFER, 0, buf);
                    glBindBuffer(GL_COPY_READ_BUFFER, 0);
                }

                glBindBuffer(GL_COPY_READ_BUFFER, stagingId);
                glBindBuffer(GL_COPY_WRITE_BUFFER, handle);
                glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, Math.min(size, (int) dest.size()));
                glBindBuffer(GL_COPY_READ_BUFFER, 0);
                glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
            }
        }
    }

    /**
     * Lazily retrieves this configs id.
     *
     * @param name the name of the uniform config.
     * @param size the size of this uniform config. See {@link UniformWriter#getSize(List)}.
     * @return the staging id of this config.
     */
    private int createStagingBuffer(String name, int size)
    {
        Integer cached = cache.get(name);
        if (cached != null)
        {
            return cached;
        }

        int id = glGenBuffers();
        glBindBuffer(GL_COPY_READ_BUFFER, id);
        glBufferData(GL_COPY_READ_BUFFER, size, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        cache.put(name, id);
        return id;
    }

    /**
     * Gets the total size of the uniforms in a config.
     *
     * @param values the uniforms.
     * @return the size of the uniforms.
     */
    private int getSize(List<UniformValue> values)
    {
        Std140SizeCalculator calc = new Std140SizeCalculator();
        for (UniformValue v : values)
        {
            v.addSize(calc);
        }

        return Math.max(calc.get(), 16);
    }
}
