package io.github.kltyton.kltytonui.client.gui.pip;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.dev.resource.ResourcePreviewDialog;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.fabric.RenderService;
import io.github.kltyton.kltytonui.render.Base;
import io.github.kltyton.kltytonui.render.DocumentLayerOrder;
import io.github.kltyton.kltytonui.render.Mask;
import io.github.kltyton.kltytonui.render.RenderNode;
import io.github.kltyton.kltytonui.style.Cursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
    /**
     * Vanilla allocates the PIP depth attachment as render-attachment/copy-dst
     * only. Clip layers copy the live depth into their content target, so the UI
     * PIP renders against an owned depth texture that also allows COPY_SRC and
     * sampling.
     */
    private static final int CLIP_DEPTH_USAGE = GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING;

    private GpuTexture clipDepthTexture;
    private GpuTextureView clipDepthView;

    @Override
    public @NonNull Class<KltytonUiPipRenderState> getRenderStateClass() {
        return KltytonUiPipRenderState.class;
    }

    @Override
    protected void renderToTexture(KltytonUiPipRenderState renderState, @NonNull PoseStack poseStack,
                                   SubmitNodeCollector submitNodeCollector) {
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        GpuTextureView previousDepthOverride = RenderSystem.outputDepthTextureOverride;
        boolean completed = false;
        try {
            modelView.identity();
            int guiWidth = Math.max(1, renderState.x1() - renderState.x0());
            int guiHeight = Math.max(1, renderState.y1() - renderState.y0());
            float depthRange = Math.max(
                    MIN_PIP_DEPTH_RANGE,
                    Base.getFlatOverlayZ() + Base.getGuiItemDecorationZ() + 1.0F
            );
            Projection guiProjection = new Projection();
            guiProjection.setupOrtho(-depthRange, depthRange, guiWidth, guiHeight, true);
            RenderService.INSTANCE.setProjectionMatrix(guiProjection.getMatrix(new Matrix4f()));

            PoseStack guiPose = new PoseStack();
            if (renderState.mode() == KltytonUiPipRenderState.Mode.UI) {
                installClipDepth();
                Mask.resetDepth();
                for (Document document : DocumentLayerOrder.backToFront(Document.getAll())) {
                    if (document == null || document.inWorld || document.isManuallyRendered()) continue;
                    Base.drawOverlayDocument(guiPose, document);
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
                guiPose.translate(0.0F, 0.0F, Base.getFlatOverlayZ());
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
            // Native text and item submissions are flushed at their draw sites
            // while the PIP output override is active.
            completed = true;
        } finally {
            // The base renderer resets the overrides on a normal return; only an
            // exceptional exit needs ours restored here.
            try {
                try {
                    RenderService.INSTANCE.endFrame();
                } finally {
                    modelView.popMatrix();
                }
            } catch (RuntimeException | Error failure) {
                RenderSystem.outputDepthTextureOverride = previousDepthOverride;
                throw failure;
            } finally {
                if (!completed) RenderSystem.outputDepthTextureOverride = previousDepthOverride;
            }
        }
    }

    /**
     * Makes the UI PIP render against an owned, copyable depth attachment so
     * clip layers can clone its live depth. The base PIP renderer has already
     * installed the color/vanilla-depth overrides before calling
     * {@link #renderToTexture}.
     */
    private void installClipDepth() {
        GpuTextureView colorView = RenderSystem.outputColorTextureOverride;
        if (colorView == null) {
            throw new IllegalStateException("KUI PIP color override is missing");
        }
        int width = colorView.texture().getWidth(0);
        int height = colorView.texture().getHeight(0);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("KUI PIP output has invalid dimensions: " + width + "x" + height);
        }
        if (clipDepthTexture == null
                || clipDepthTexture.getWidth(0) != width
                || clipDepthTexture.getHeight(0) != height) {
            destroyClipDepth();
            var device = RenderSystem.getDevice();
            GpuTexture texture = device.createTexture(
                    () -> "kltytonui_clip_pip_depth", CLIP_DEPTH_USAGE,
                    GpuFormat.D32_FLOAT, width, height, 1, 1);
            GpuTextureView view;
            try {
                view = device.createTextureView(texture);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
            clipDepthTexture = texture;
            clipDepthView = view;
        }
        // Match the clear value the vanilla PIP depth attachment receives each
        // frame (0.0) before UI content writes its own depth.
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(clipDepthTexture, 0.0);
        RenderSystem.outputDepthTextureOverride = clipDepthView;
    }

    private void destroyClipDepth() {
        GpuTextureView view = clipDepthView;
        GpuTexture texture = clipDepthTexture;
        clipDepthView = null;
        clipDepthTexture = null;
        try {
            if (view != null) view.close();
        } finally {
            if (texture != null) texture.close();
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            destroyClipDepth();
        }
    }

    @Override
    protected @NonNull String getTextureLabel() {
        return "kltytonui";
    }
}
