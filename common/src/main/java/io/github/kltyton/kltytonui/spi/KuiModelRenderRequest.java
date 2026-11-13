package io.github.kltyton.kltytonui.spi;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.model.ModelSpec;

public record KuiModelRenderRequest(PoseStack poseStack, ModelSpec spec, float x, float y,
                                    float width, float height) { }
