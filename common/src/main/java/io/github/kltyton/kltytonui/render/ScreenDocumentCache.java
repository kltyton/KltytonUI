package io.github.kltyton.kltytonui.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.spi.FboHandle;
import io.github.kltyton.kltytonui.spi.KuiRenderService;
import io.github.kltyton.kltytonui.spi.KuiServices;
import io.github.kltyton.kltytonui.style.MaskImage;
import io.github.kltyton.kltytonui.viewport.KltytonViewport;

import java.util.Set;

public final class ScreenDocumentCache implements DocumentPaintCache, AutoCloseable {
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final Set<String> SAFE_TYPES = Set.of(
            "io.github.kltyton.kltytonui.element.Html",
            "io.github.kltyton.kltytonui.element.Body",
            "io.github.kltyton.kltytonui.element.Div",
            "io.github.kltyton.kltytonui.element.Span",
            "io.github.kltyton.kltytonui.element.Pre",
            "io.github.kltyton.kltytonui.element.A",
            "io.github.kltyton.kltytonui.element.Path",
            "io.github.kltyton.kltytonui.element.Svg",
            "io.github.kltyton.kltytonui.init.Element"
    );
    private static final Set<String> SAFE_TAGS = Set.of("HTML", "BODY", "DIV", "SPAN", "PRE", "A",
            "P", "H1", "H2", "H3", "H4", "H5", "H6", "SECTION", "MAIN", "HEADER", "FOOTER",
            "ARTICLE", "UL", "OL", "LI", "STRONG", "EM", "B", "I", "SMALL", "LABEL",
            "BLOCKQUOTE", "CODE", "BR", "HR", "SVG", "PATH", "::BEFORE", "::AFTER");

    public static ScreenDocumentCache attach(Document document) {
        if (document == null) return null;
        DocumentPaintCache existing = DocumentPaintCache.forDocument(document);
        if (existing instanceof ScreenDocumentCache cache) return cache;
        ScreenDocumentCache cache = new ScreenDocumentCache();
        DocumentPaintCache.register(document, cache);
        return cache;
    }

    public static void detach(Document document) {
        DocumentPaintCache cache = DocumentPaintCache.forDocument(document);
        if (cache instanceof ScreenDocumentCache screenCache) {
            DocumentPaintCache.unregister(document, screenCache);
            screenCache.close();
        }
    }

    private FboHandle target;
    private Key key;
    private long checkedVersion = Long.MIN_VALUE;
    private long checkedGeneration = Long.MIN_VALUE;
    private boolean safe;

    @Override
    public boolean render(Document document, PoseStack poseStack, Runnable paint) {
        long visualVersion = document.getVisualVersion();
        long generation = document.getRefreshGeneration();
        if (visualVersion != checkedVersion || generation != checkedGeneration) {
            safe = isSafe(document);
            checkedVersion = visualVersion;
            checkedGeneration = generation;
        }
        if (!Boolean.parseBoolean(System.getProperty("kltytonui.screenCache", "true")) || !safe) {
            paint.run();
            return true;
        }

        KltytonViewport viewport = document.getViewport();
        int width = Math.max(1, (int) Math.ceil(viewport.layoutWidth() * viewport.renderScale()));
        int height = Math.max(1, (int) Math.ceil(viewport.layoutHeight() * viewport.renderScale()));
        if ((long) width * height * 12L > MAX_BYTES) {
            paint.run();
            return true;
        }
        Key next = new Key(document, generation, visualVersion, width, height,
                viewport.layoutWidth(), viewport.layoutHeight(), Float.floatToIntBits(viewport.renderScale()),
                Double.doubleToLongBits(viewport.scissorScale()));
        if (target == null || target.width != width || target.height != height) {
            releaseTarget();
            KuiRenderService render = KuiServices.render();
            FboHandle created = render.createOffscreenTarget(width, height, true);
            if (created == null) {
                paint.run();
                return true;
            }
            boolean initialized = false;
            try {
                render.enableStencil(created);
                initialized = true;
            } finally {
                if (!initialized) render.destroyBackgroundTarget(created);
            }
            target = created;
        }

        if (!next.equals(key)) {
            if (!repaint(paint)) {
                key = null;
                paint.run();
                return true;
            }
            key = next;
        }
        KuiServices.render().drawOffscreenTexture(PoseMatrices.of(poseStack), target,
                viewport.layoutWidth(), viewport.layoutHeight());
        return true;
    }

    private boolean repaint(Runnable paint) {
        KuiRenderService render = KuiServices.render();
        FontDrawer.beginPaintCompletenessTracking();
        boolean tracking = true;
        try (KuiRenderService.RenderStateScope targetScope = render.pushBackgroundTarget(target);
             KuiRenderService.RenderStateScope logicalTarget = FilterRenderer.pushScreenTarget(target)) {
            Base.commitDraws();
            render.clear(target, 0, 0, 0, 0);
            render.enableStencil(target);
            render.clearStencilBuffer();
            paint.run();
            Base.commitDraws();
            boolean complete = FontDrawer.endPaintCompletenessTracking();
            tracking = false;
            return complete;
        } finally {
            if (tracking) FontDrawer.endPaintCompletenessTracking();
        }
    }

    static boolean isDefaultOpacity(String opacity) {
        try {
            return Math.abs(Double.parseDouble(opacity) - 1.0d) < 0.000001d;
        } catch (NumberFormatException invalidOpacity) {
            return false;
        }
    }

    private static boolean isSafe(Document document) {
        if (document == null || document.getPaintList().isEmpty()) return false;
        for (Element element : document.getElements()) {
            if (!element.isConnected() || !SAFE_TYPES.contains(element.getClass().getName())) return false;
            String tag = element.tagName == null ? "" : element.tagName.toUpperCase(java.util.Locale.ROOT);
            if (!SAFE_TAGS.contains(tag)) return false;
            var style = element.getRawComputedStyle();
            if (style == null || !isDefaultOpacity(style.opacity) || !"standard".equalsIgnoreCase(style.dynamicRangeLimit)
                    || !"none".equals(style.filter) || !"none".equals(style.backdropFilter)
                    || !"normal".equalsIgnoreCase(style.mixBlendMode) || !"auto".equalsIgnoreCase(style.isolation)
                    || MaskImage.hasMask(element)) return false;
        }
        for (RenderNode node : document.getPaintList()) {
            if (node instanceof RenderNode.ElementPhaseNode || node instanceof RenderNode.ElementBackgroundNode
                    || node instanceof RenderNode.MaskPushNode || node instanceof RenderNode.MaskPopNode) continue;
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        releaseTarget();
    }

    private void releaseTarget() {
        if (target != null) {
            KuiServices.render().destroyBackgroundTarget(target);
            target = null;
        }
        key = null;
    }

    private record Key(Document document, long generation, long visualVersion, int width, int height,
                       int layoutWidth, int layoutHeight, int renderScale, long scissorScale) {
    }
}
