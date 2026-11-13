package io.github.kltyton.kltytonui.init;

import io.github.kltyton.kltytonui.element.Body;
import io.github.kltyton.kltytonui.element.Head;
import io.github.kltyton.kltytonui.element.Html;
import io.github.kltyton.kltytonui.dom.TextNode;
import io.github.kltyton.kltytonui.layout.Layout;
import io.github.kltyton.kltytonui.layout.Position;
import io.github.kltyton.kltytonui.parser.CSS;
import io.github.kltyton.kltytonui.style.Text;
import io.github.kltyton.kltytonui.test.TestRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.kltyton.kltytonui.util.TextMetrics;

class ElementTextAlignmentTest {
    @Test
    void flexDirectTextPaintDoesNotApplyContentOriginTwice() {
        Document document = createDocument();
        document.body.setAttribute("style", "width:300px;height:200px;");

        Element button = new Element(document, "button");
        button.setAttribute("style", "display:flex;width:109px;height:30px;padding:0 14px;border:1px solid transparent;gap:8px;align-items:center;");
        Element icon = new Element(document, "span");
        icon.setAttribute("style", "width:14px;height:14px;flex-shrink:0;");
        button.appendChild(icon);
        button.appendChild(new TextNode(document, "INSPECT"));
        document.body.appendChild(button);

        Layout.DirectTextLayout textLayout = Layout.computeDirectTextLayouts(button).get(0);
        Position paint = button.getDirectLayoutTextPaintPosition(textLayout);
        assertEquals(37, textLayout.position().x, 0.01);
        assertEquals(Position.of(button).x + 37, paint.x, 0.01);
    }

    @Test
    void outOfFlowPseudoElementDoesNotDisableDirectTextAlignment() throws Exception {
        TestRuntime.assumeClassUsable("com.mojang.blaze3d.vertex.PoseStack", "render element pseudo geometry");
        Document document = createDocument();
        Map<String, Map<String, CSS.Declaration>> cache = new LinkedHashMap<>();
        CSS.readCSS(".label::after { content: '+'; position: absolute; left: 50%; }",
                cache, "test://text-align-pseudo.css");
        document.CSSCache.putAll(cache);
        document.rebuildSelectorIndex();

        Element label = new Element(document, "div");
        label.setAttribute("class", "label");
        label.setAttribute("style", "text-align: center;");
        label.setTextContent("Viewport");
        document.body.appendChild(label);

        Method method = Element.class.getDeclaredMethod("shouldAlignDirectNormalFlowTextRuns");
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(label));
    }

    @Test
    void normalFlowTextAlignmentUsesTheContainingLineWidth() {
        Text text = new Text();
        text.textAlign = "center";
        assertEquals(30, TextMetrics.computeAlignedX(text, 100, 40, false), 0.001);

        text.textAlign = "right";
        assertEquals(60, TextMetrics.computeAlignedX(text, 100, 40, false), 0.001);
    }

    private static Document createDocument() {
        Document document = new Document("test://text-align", false);
        document.documentElement = new Html(document);
        document.head = new Head(document);
        document.body = new Body(document);
        document.documentElement.appendChild(document.head);
        document.documentElement.appendChild(document.body);
        return document;
    }

}
