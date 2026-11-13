package io.github.kltyton.kltytonui.client.gui.pip;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.dev.resource.ResourcePreviewDialog;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.fabric.RenderService;
import io.github.kltyton.kltytonui.render.Base;
import io.github.kltyton.kltytonui.render.Mask;
import io.github.kltyton.kltytonui.render.RenderNode;
import io.github.kltyton.kltytonui.style.Cursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.jspecify.annotations.NonNull;

/**
 * Renders KUI documents into the PIP texture.
 *
 * <p>The vanilla PIP pose (translate-to-centre + guiScale) targets physical
 * pixels and centres the coordinate system, which does not match KUI's
 * immediate-mode GUI-coordinate drawing — feeding KUI content through it puts
 * everything half a texture off-centre. Instead this renderer installs the
 * same state KUI used on 1.21.1's main target: an orthographic projection in
 * GUI units, an identity model-view, and a fresh identity {@link PoseStack}.
 * Scissor conversion uses the live PIP attachment size, including the rounding
 * from GUI units to physical pixels.</p>
 */
public final class KltytonUiPipRenderer extends PictureInPictureRenderer<KltytonUiPipRenderState> {
    private static final float MIN_PIP_DEPTH_RANGE = 1024.0F;
    private static final double DEPTH_CLEAR_VALUE = 1.0;

    private GpuTexture ownedDepthTexture;
    private GpuTextureView ownedDepthTextureView;
    private int ownedDepthWidth;
    private int ownedDepthHeight;

    public KltytonUiPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public @NonNull Class<KltytonUiPipRenderState> getRenderStateClass() {
        return KltytonUiPipRenderState.class;
    }

    @Override
    protected void renderToTexture(KltytonUiPipRenderState renderState, @NonNull PoseStack poseStack) {
        GpuTextureView previousDepthOverride = RenderSystem.outputDepthTextureOverride;
        boolean completedNormally = false;
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            modelView.identity();
            int guiWidth = Math.max(1, renderState.x1() - renderState.x0());
            int guiHeight = Math.max(1, renderState.y1() - renderState.y0());
            float depthRange = Math.max(
                    MIN_PIP_DEPTH_RANGE,
                    Base.getFlatOverlayZ() + Base.getGuiItemDecorationZ() + 1.0F
            );
            RenderService.INSTANCE.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0f, guiWidth, guiHeight, 0.0f, -depthRange, depthRange));

            PoseStack guiPose = new PoseStack();
            if (renderState.mode() == KltytonUiPipRenderState.Mode.UI) {
                // Vanilla's PIP depth attachment is DEPTH32 without COPY_SRC, so
                // clip layers cannot copy it. Own a matching copyable attachment
                // and expose it through the live depth override for this frame.
                installOwnedDepth();
                Mask.resetDepth();
                Base.FlatLayerPlan flatLayers = Base.planFlatLayers();
                for (Document document : flatLayers.orderedDocuments()) {
                    Base.drawOverlayDocument(guiPose, document, flatLayers.baseZOf(document));
                    // Resource previews are intentionally excluded from the
                    // normal document pass and draw themselves inside the owner's
                    // viewport. Draw them right after the owner so the previewed
                    // HTML stays below the DevTools tool document and the toast.
                    ResourcePreviewDialog.draw(guiPose, document);
                }
                // Floating container items share the same PoseStack backend but
                // must not inherit the final document's clip or stencil state.
                Mask.resetDepth();
                guiPose.pushPose();
                guiPose.translate(0.0F, 0.0F, flatLayers.overlayZ());
                try {
                    for (KltytonUiPipRenderState.FloatingItem item : renderState.floatingItems().items()) {
                        RenderNode.ItemNode.positioned(
                                item::stack,
                                item.x(),
                                item.y(),
                                1.0D,
                                232,
                                true,
                                item.overlayText(),
                                item.decorationOffsetY(),
                                false
                        ).render(guiPose);
                    }
                } finally {
                    guiPose.popPose();
                }
                // Ensure scissor/mask state never leaks into later GUI rendering.
                Mask.resetDepth();
            } else if (renderState.mode() == KltytonUiPipRenderState.Mode.CURSOR) {
                Cursor.drawPseudoCursor(guiPose);
            }
            // Font text queued by the documents lives in the shared buffer
            // source; flush it while the PIP override is still active.
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            completedNormally = true;
        } finally {
            try {
                modelView.popMatrix();
            } catch (RuntimeException | Error failure) {
                RenderSystem.outputDepthTextureOverride = previousDepthOverride;
                throw failure;
            } finally {
                if (!completedNormally) {
                    // The base PIP renderer clears both overrides itself on normal
                    // return, so only an exceptional exit needs an explicit restore.
                    RenderSystem.outputDepthTextureOverride = previousDepthOverride;
                }
            }
        }
    }

    private void installOwnedDepth() {
        GpuTextureView colorOverride = RenderSystem.outputColorTextureOverride;
        if (colorOverride == null) throw new IllegalStateException("KUI PIP color attachment is missing");
        int width = colorOverride.texture().getWidth(0);
        int height = colorOverride.texture().getHeight(0);
        if (ownedDepthTexture == null || ownedDepthWidth != width || ownedDepthHeight != height) {
            destroyOwnedDepth();
            int usage = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
                    | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
            GpuTexture texture = RenderSystem.getDevice().createTexture(
                    () -> "kltytonui_pip_depth", usage, TextureFormat.DEPTH32,
                    width, height, 1, 1);
            GpuTextureView view;
            try {
                view = RenderSystem.getDevice().createTextureView(texture);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
            ownedDepthTexture = texture;
            ownedDepthTextureView = view;
            ownedDepthWidth = width;
            ownedDepthHeight = height;
        }
        RenderSystem.getDevice().createCommandEncoder()
                .clearDepthTexture(ownedDepthTexture, DEPTH_CLEAR_VALUE);
        RenderSystem.outputDepthTextureOverride = ownedDepthTextureView;
    }

    private void destroyOwnedDepth() {
        GpuTextureView view = ownedDepthTextureView;
        GpuTexture texture = ownedDepthTexture;
        ownedDepthTextureView = null;
        ownedDepthTexture = null;
        ownedDepthWidth = 0;
        ownedDepthHeight = 0;
        try {
            if (view != null) view.close();
        } finally {
            if (texture != null) texture.close();
        }
    }

    @Override
    public void close() {
        try {
            destroyOwnedDepth();
        } finally {
            super.close();
        }
    }

    @Override
    protected @NonNull String getTextureLabel() {
        return "kltytonui";
    }
}
