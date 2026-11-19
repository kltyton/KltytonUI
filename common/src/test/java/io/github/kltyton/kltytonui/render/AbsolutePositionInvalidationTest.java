package io.github.kltyton.kltytonui.render;

import io.github.kltyton.kltytonui.dom.RenderElement;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.layout.Size;
import io.github.kltyton.kltytonui.parser.HTML;
import io.github.kltyton.kltytonui.style.Style;
import io.github.kltyton.kltytonui.webapi.TestDocumentFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbsolutePositionInvalidationTest {
    @Test
    void nestedAbsoluteInsertResizeAndRemoveUseLocalCommitsWhileFlowInsertionRelayouts() {
        String path = "test://absolute-local-commit";
        HTML.putTemple(path, """
                <html><head><style>body{width:800px;height:600px;overflow:hidden}
                #parent{position:relative;width:400px;height:200px}
                #normal{width:40px;height:30px}</style></head>
                <body><div id="parent"><div id="normal"></div></div></body></html>
                """);
        Document document = new Document(path, false);
        try {
            document.refresh();
            document.commitRenderState();
            Element parent = document.querySelector("#parent");
            Element normal = document.querySelector("#normal");
            double normalY = normal.getRenderer().getCommittedRect().position.y;
            Element overlay = document.createElement("div");
            overlay.setAttribute("style", "position:absolute;left:12px;top:18px;width:20px;height:16px");
            RenderBatchStats.beginFrame();
            parent.appendChild(overlay);
            document.flushPendingStyleUpdates();
            document.commitRenderState();
            RenderBatchStats.endFrame();
            assertEquals(0, RenderBatchStats.lastFullCommits());
            assertTrue(RenderBatchStats.lastTransformCommits() > 0);
            assertEquals(20, overlay.getRenderer().getCommittedRect().getElementSize().width(), 0.001);
            assertEquals(normalY, normal.getRenderer().getCommittedRect().position.y, 0.001);

            RenderBatchStats.beginFrame();
            overlay.setInlineStyleProperty("padding", "3px");
            overlay.setInlineStyleProperty("width", "36px");
            document.flushPendingStyleUpdates();
            document.commitRenderState();
            RenderBatchStats.endFrame();
            assertEquals(0, RenderBatchStats.lastFullCommits());
            assertEquals(42, overlay.getRenderer().getCommittedRect().getElementSize().width(), 0.001);

            RenderBatchStats.beginFrame();
            parent.removeChild(overlay);
            document.flushPendingStyleUpdates();
            document.commitRenderState();
            RenderBatchStats.endFrame();
            assertEquals(0, RenderBatchStats.lastFullCommits());

            RenderBatchStats.beginFrame();
            parent.appendChild(document.createElement("div"));
            document.flushPendingStyleUpdates();
            document.commitRenderState();
            RenderBatchStats.endFrame();
            assertTrue(RenderBatchStats.lastFullCommits() > 0);
        } finally {
            document.remove();
        }
    }

    @Test
    void absoluteGeometryChangeKeepsAncestorAndSiblingSizeCaches() {
        Document document = TestDocumentFactory.createDocument();
        Element parent = document.createElement("div");
        Element absolute = document.createElement("div");
        Element sibling = document.createElement("div");
        parent.appendChild(absolute);
        parent.appendChild(sibling);
        document.body.appendChild(parent);

        Size parentSize = new Size(300, 100);
        Size siblingSize = new Size(20, 20);
        absolute.getRenderer().size.set(new Size(10, 8));
        parent.getRenderer().size.set(parentSize);
        sibling.getRenderer().size.set(siblingSize);

        Style origin = new Style();
        origin.update("position", "absolute");
        origin.update("left", "0%");
        origin.update("width", "10%");
        Style current = origin.clone();
        current.update("left", "80%");
        current.update("width", "80%");

        RenderElement.observeStyle(absolute, origin, current);

        assertNull(absolute.getRenderer().size.get());
        assertSame(parentSize, parent.getRenderer().size.get());
        assertSame(siblingSize, sibling.getRenderer().size.get());
        assertTrue(absolute.hasDirtyFlag(Drawer.COMMIT_LAYOUT));
    }
}
