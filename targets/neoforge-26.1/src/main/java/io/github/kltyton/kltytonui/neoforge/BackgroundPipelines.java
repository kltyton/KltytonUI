package io.github.kltyton.kltytonui.neoforge;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;

final class BackgroundPipelines {
    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static RenderPipeline texturePipeline;

    static RenderPipeline texture() {
        RenderPipeline pipeline = texturePipeline;
        if (pipeline == null) {
            Identifier shader = Identifier.fromNamespaceAndPath("kltytonui", "core/filter_copy");
            pipeline = RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("kltytonui", "pipeline/screen_texture"))
                    .withVertexShader(shader)
                    .withFragmentShader(shader)
                    .withSampler("Sampler0")
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(java.util.Optional.empty())
                    .withColorTargetState(new ColorTargetState(
                            java.util.Optional.of(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA)),
                            ColorTargetState.WRITE_ALL))
                    .withCull(false)
                    .build();
            texturePipeline = pipeline;
        }
        return pipeline;
    }

    static RenderPipeline shader() {
        return adapt(PipelineRegistry.getFilterCopy());
    }

    static RenderPipeline adapt(RenderPipeline source) {
        return PIPELINES.computeIfAbsent(source, pipeline -> pipeline.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath("kltytonui", "pipeline/background_" + PIPELINES.size()))
                .withVertexShader(Identifier.fromNamespaceAndPath("kltytonui", "core/background"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("kltytonui", "core/background"))
                .withUniform("BackgroundParams", UniformType.UNIFORM_BUFFER)
                .build());
    }
}

