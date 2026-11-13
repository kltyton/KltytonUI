package io.github.kltyton.kltytonui.fabric;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

final class BackgroundPipelines {
    private static final BindGroupLayout BACKGROUND_PARAMS = BindGroupLayout.builder()
            .withUniform("BackgroundParams", UniformType.UNIFORM_BUFFER).build();
    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static RenderPipeline texturePipeline;

    static RenderPipeline texture() {
        RenderPipeline pipeline = texturePipeline;
        if (pipeline == null) {
            pipeline = copy(PipelineRegistry.getFilterCopy())
                    .withLocation(Identifier.fromNamespaceAndPath("kltytonui", "pipeline/screen_texture"))
                    .withDepthStencilState(Optional.empty())
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                    .build();
            texturePipeline = pipeline;
        }
        return pipeline;
    }

    static RenderPipeline shader() {
        return adapt(PipelineRegistry.getFilterCopy());
    }

    static RenderPipeline adapt(RenderPipeline source) {
        return PIPELINES.computeIfAbsent(source, pipeline -> copy(pipeline)
                .withLocation(Identifier.fromNamespaceAndPath("kltytonui", "pipeline/background_" + PIPELINES.size()))
                .withVertexShader(Identifier.fromNamespaceAndPath("kltytonui", "core/background"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("kltytonui", "core/background"))
                .withBindGroupLayout(BACKGROUND_PARAMS)
                .build());
    }

    private static RenderPipeline.Builder copy(RenderPipeline pipeline) {
        ColorTargetState[] colorTargets = pipeline.getColorTargetStates();
        return RenderPipeline.builder(new RenderPipeline.Snippet(
                Optional.of(pipeline.getVertexShader()),
                Optional.of(pipeline.getFragmentShader()),
                Optional.of(pipeline.getShaderDefines()),
                Optional.of(pipeline.getBindGroupLayouts()),
                colorTargets,
                colorTargets.length,
                Optional.ofNullable(pipeline.getDepthStencilState()),
                Optional.of(pipeline.getPolygonMode()),
                Optional.of(pipeline.isCull()),
                pipeline.getVertexFormatBindings(),
                Optional.of(pipeline.getPrimitiveTopology())));
    }
}

