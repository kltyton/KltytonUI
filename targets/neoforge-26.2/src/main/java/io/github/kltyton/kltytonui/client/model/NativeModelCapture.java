package io.github.kltyton.kltytonui.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.math.MatrixUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.item.ItemStackRenderState.FoilType;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Replays submitted entity geometry into the preview mesh in renderer order. */
final class NativeModelCapture extends SubmitNodeStorage {
    private final ModelVertexCapture output;
    private final Map<Integer, Geometry> groups = new TreeMap<>();

    NativeModelCapture(ModelVertexCapture output) { this.output = output; }

    @Override
    public SubmitNodeCollection order(int order) {
        return groups.computeIfAbsent(order, ignored -> new Geometry());
    }

    void finish() {
        groups.values().forEach(group -> group.draws.forEach(Runnable::run));
    }

    private static PoseStack copy(PoseStack original) {
        PoseStack saved = new PoseStack();
        saved.last().pose().set(original.last().pose());
        saved.last().normal().set(original.last().normal());
        return saved;
    }

    private VertexConsumer vertices(RenderType type, TextureAtlasSprite sprite) {
        VertexConsumer vertices = output.getBuffer(type);
        return sprite == null ? vertices : sprite.wrap(vertices);
    }

    private void quads(PoseStack pose, List<BakedQuad> quads, RenderType forcedType,
                       int[] colors, int light, int overlay, ItemDisplayContext context, FoilType foil) {
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(light);
        instance.setOverlayCoords(overlay);
        PoseStack.Pose decal = foil == FoilType.SPECIAL ? pose.last().copy() : null;
        if (decal != null && context == ItemDisplayContext.GUI) MatrixUtil.mulComponentWise(decal.pose(), 0.5F);
        else if (decal != null && context.firstPerson()) MatrixUtil.mulComponentWise(decal.pose(), 0.75F);
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tint = material.tintIndex();
            instance.setColor(material.isTinted() && tint >= 0 && tint < colors.length ? colors[tint] : -1);
            RenderType type = forcedType == null ? material.itemRenderType() : forcedType;
            output.getBuffer(type).putBakedQuad(pose.last(), quad, instance);
            if (foil != FoilType.NONE) {
                boolean transparent = Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()
                        && type.outputTarget() == OutputTarget.ITEM_ENTITY_TARGET;
                VertexConsumer glint = output.getBuffer(transparent ? RenderTypes.glintTranslucent() : RenderTypes.glint());
                if (decal != null) glint = new SheetedDecalTextureGenerator(glint, decal, 0.0078125F);
                glint.putBakedQuad(pose.last(), quad, instance);
            }
        }
    }

    private final class Geometry extends SubmitNodeCollection {
        private final ArrayList<Runnable> draws = new ArrayList<>();

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type,
                                    int light, int overlay, int color, TextureAtlasSprite sprite,
                                    int outline, CrumblingOverlay crumbling) {
            PoseStack saved = copy(pose);
            draws.add(() -> {
                model.setupAnim(state);
                model.renderToBuffer(saved, vertices(type, sprite), light, overlay, color);
            });
        }

        @Override
        public void submitBlockModel(PoseStack pose, RenderType type, List<BlockStateModelPart> parts,
                                     int[] colors, int light, int overlay, int outline) {
            PoseStack saved = copy(pose);
            draws.add(() -> {
                for (BlockStateModelPart part : parts) {
                    quads(saved, part.getQuads(null), type, colors, light, overlay, ItemDisplayContext.NONE, FoilType.NONE);
                    for (Direction side : Direction.values())
                        quads(saved, part.getQuads(side), type, colors, light, overlay, ItemDisplayContext.NONE, FoilType.NONE);
                }
            });
        }

        public void submitMultiLayerBlockModel(PoseStack pose, List<BlockStateModelPart> parts,
                                               boolean translucent, int[] colors, int light, int overlay, int outline) {
            submitBlockModel(pose, null, parts, colors, light, overlay, outline);
        }

        @Override
        public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline,
                               int[] colors, List<BakedQuad> quads, FoilType foil) {
            PoseStack saved = copy(pose);
            draws.add(() -> NativeModelCapture.this.quads(saved, quads, null, colors, light, overlay, context, foil));
        }

        @Override
        public void submitCustomGeometry(PoseStack pose, RenderType type, CustomGeometryRenderer renderer) {
            PoseStack saved = copy(pose);
            draws.add(() -> renderer.render(saved.last(), output.getBuffer(type)));
        }

        @Override
        public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state, int outline) {
            throw new IllegalArgumentException("Entity preview contains unsupported moving-block geometry");
        }

        @Override
        public void submitBreakingBlockModel(PoseStack pose, List<BlockStateModelPart> parts, int progress) {
            throw new IllegalArgumentException("Entity preview contains unsupported breaking-block geometry");
        }
    }
}
