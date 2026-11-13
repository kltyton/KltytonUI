package io.github.kltyton.kltytonui.webapi;

import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeProbeTest {

    @Test
    void nestedContenteditableUpgrades() {
        Document document = TestDocumentFactory.createDocument();
        Element root = document.createHTML(
                "<div class=\"wrap\"><div class=\"toolbar\"></div>"
                        + "<div id=\"editor\" contenteditable=\"true\" spellcheck=\"false\"></div></div>");
        document.body.appendChild(root);
        Element editor = document.getElementById("editor");
        assertTrue(editor instanceof io.github.kltyton.kltytonui.element.RichText,
                "nested contenteditable div must upgrade to RichText, got "
                        + (editor == null ? "null" : editor.getClass().getSimpleName()));
    }

    /** 页面结构(多文本节点+strong)下 Range 偏移换算:readSelection 的 o 与末尾定位。 */
    @Test
    void rangeOffsetsOnMultiNodeBlock() {
        Document document = TestDocumentFactory.createDocument();
        Element root = document.createHTML(
                "<div contenteditable style=\"width: 320px; height: 140px;\">"
                        + "<h1>数据驱动编辑器</h1>"
                        + "<p>所有输入都被 <strong>beforeinput</strong> 拦截</p></div>");
        assertTrue(root instanceof io.github.kltyton.kltytonui.element.RichText);
        Element p = (Element) root.getChildNodes().get(1);

        // p 的文本节点:["所有输入都被 ", "beforeinput", " 拦截"]
        java.util.List<io.github.kltyton.kltytonui.init.Node> kids = p.getChildNodes();
        System.out.println("[probe] p children: " + kids.size() + " tags=" + kids);
        Object text0 = kids.get(0); // TextNode "所有输入都被 "
        Object strong = kids.get(1);
        java.util.List<io.github.kltyton.kltytonui.init.Node> strongKids = ((Element) strong).getChildNodes();
        Object text1 = strongKids.get(0); // "beforeinput"

        // readSelection 场景:点击在 "beforeinput" 中段
        // r.setStart(p, 0); r.setEnd(text1, 5) -> toString 应为 "所有输入都被 before" (11 字符)
        io.github.kltyton.kltytonui.behavior.richtext.RangeBridge range = new io.github.kltyton.kltytonui.behavior.richtext.RangeBridge();
        range.setStart(p, 0);
        range.setEnd((io.github.kltyton.kltytonui.init.Node) text1, 5);
        String s = range.toString();
        System.out.println("[probe] toString for offset 5 in beforeinput = " + s + " len=" + s.length());

        // writeSelection 场景:选区在文本节点中段 -> anchorNode/anchorOffset 往返
        io.github.kltyton.kltytonui.behavior.richtext.SelectionBridge sel =
                new io.github.kltyton.kltytonui.behavior.richtext.SelectionBridge(document);
        sel.setBaseAndExtent((io.github.kltyton.kltytonui.init.Node) text1, 3,
                (io.github.kltyton.kltytonui.init.Node) text1, 3);
        io.github.kltyton.kltytonui.init.Node anchorNode = sel.getAnchorNode();
        System.out.println("[probe] anchor after setBaseAndExtent(text1,3) = "
                + (anchorNode == null ? "null" : anchorNode.getClass().getSimpleName())
                + " off=" + sel.getAnchorOffset());
    }

    /** 页面 readSelection 依赖文本节点的 parentElement 和 nodeType。 */
    @Test
    void neonReadsTextNodeProperties() {
        TestDocumentFactory.withNeonDocument(document -> {
            Element p = document.createElement("p");
            p.setAttribute("id", "node-probe");
            p.appendChild(document.createTextNode("hello"));
            document.body.appendChild(p);
            io.github.kltyton.kltytonui.script.neon.NeonPageRuntime.eval(
                    "var tn = document.querySelector('#node-probe').firstChild;"
                            + "var host = tn.nodeType === 1 ? tn : tn.parentElement;"
                            + "document.body.setAttribute('data-result', host ? host.tagName + '|' + tn.nodeType : 'no-host');",
                    null, "raw-node-properties");
            assertTrue("P|3".equals(document.body.getAttribute("data-result")));
        });
    }

}
