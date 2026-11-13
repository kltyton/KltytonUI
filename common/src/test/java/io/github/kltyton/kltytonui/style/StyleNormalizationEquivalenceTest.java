package io.github.kltyton.kltytonui.style;

import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.layout.Grid;
import io.github.kltyton.kltytonui.layout.Layout;
import io.github.kltyton.kltytonui.layout.Position;
import io.github.kltyton.kltytonui.layout.Size;
import io.github.kltyton.kltytonui.webapi.TestDocumentFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * display / overflow / visibility 归一化等价性与 Grid placement 的公开布局行为回归。
 *
 * <p>这一阶段刻意保留了各调用点**不同**的归一化程度，本测试逐条锁住：</p>
 * <ul>
 *   <li>{@code Style.isFlexDisplay()/isGridDisplay()} 复刻 {@code Layout.isFlexDisplay(String)}，
 *       要 trim + lowercase；</li>
 *   <li>{@code Style.isDisplayNone()/isInFlow()} 复刻裸比较（不 trim、不 lowercase）；</li>
 *   <li>{@code Style.overflowX()/overflowY()} 复刻 {@code Interaction.resolveOverflowX/Y(Style)}，
 *       其中 {@code "unset"} 是大小写敏感精确比较；</li>
 *   <li>{@code Style.visibility()} 只归一化本 Style 自己的字段（祖先链逻辑仍在
 *       {@code Interaction.getVisibility(Element)}）。</li>
 * </ul>
 */
class StyleNormalizationEquivalenceTest {

    private static final String[] VALUES = {
            null, "", " ", "flex", " FLEX ", "inline-flex", "grid", "INLINE-GRID",
            "none", "NONE", "block", "unset", "absolute", "ABSOLUTE", "fixed", "static",
            "visible", "HIDDEN", "scroll", "clip", "bogus"
    };

    @Test
    void displayMemoMatchesLegacyStringApis() {
        for (String value : VALUES) {
            Style style = new Style();
            style.display = value;
            assertEquals(Layout.isFlexDisplay(value), style.isFlexDisplay(),
                    "isFlexDisplay mismatch for display=" + value);
            assertEquals(Layout.isGridDisplay(value), style.isGridDisplay(),
                    "isGridDisplay mismatch for display=" + value);
            // isInFlow 用的是裸比较，与上面两个归一化判定语义不同，必须单独对拍
            assertEquals(Layout.isInFlow(style), style.isInFlow(),
                    "isInFlow mismatch for display=" + value);
            assertEquals("none".equals(value), style.isDisplayNone(),
                    "isDisplayNone mismatch for display=" + value);
        }
    }

    @Test
    void inFlowMemoMatchesLegacyAcrossPositions() {
        String[] positions = {
                null, "", " ", "static", "absolute", "ABSOLUTE", "fixed", "FIXED", "relative", "bogus"
        };
        for (String display : new String[]{"block", "none", "NONE", "flex"}) {
            for (String position : positions) {
                Style style = new Style();
                style.display = display;
                style.position = position;
                assertEquals(Layout.isInFlow(style), style.isInFlow(),
                        "isInFlow mismatch display=" + display + " position=" + position);
            }
        }
    }

    /** 裸比较 vs 归一化：同一 Style 上两条判定刻意不同。 */
    @Test
    void bareDisplaySemanticsDifferFromNormalized() {
        Style style = new Style();

        style.display = " FLEX ";
        assertTrue(style.isFlexDisplay(), "归一化判定要认 ' FLEX '");
        assertFalse(style.isDisplayNone());
        assertTrue(style.isInFlow(), "裸比较：' FLEX ' 不是精确 none，仍在流");

        style.display = "NONE";
        style.resetMetrics(); // 直接赋值不失效，显式清 memo
        assertFalse(style.isFlexDisplay());
        assertFalse(style.isDisplayNone(), "裸比较不认大写 NONE");
        assertTrue(style.isInFlow(), "裸比较不认大写 NONE，position:static 时仍在流");

        style.display = "none";
        style.resetMetrics();
        assertTrue(style.isDisplayNone());
        assertFalse(style.isInFlow());
    }

    @Test
    void overflowMemoMatchesLegacyStringApis() {
        for (String value : VALUES) {
            Style style = new Style();
            style.overflow = value;
            assertEquals(Interaction.resolveOverflowX(style), style.overflowX().cssValue(),
                    "overflowX mismatch for overflow=" + value);
            assertEquals(Interaction.resolveOverflowY(style), style.overflowY().cssValue(),
                    "overflowY mismatch for overflow=" + value);
            assertEquals(Interaction.clipsOverflow(style), style.clipsOverflow(),
                    "clipsOverflow mismatch for overflow=" + value);
            assertEquals(Interaction.normalizeOverflow(value), Interaction.Overflow.parse(value).cssValue(),
                    "normalizeOverflow mismatch for overflow=" + value);
        }
    }

    /** {@code overflowX/overflowY} 的 {@code "unset"} 是精确比较（大小写敏感），空白/非法值要回退。 */
    @Test
    void overflowAxisUnsetSemanticsArePreserved() {
        String[][] cases = {
                // overflowX, overflowY, overflow
                {"unset", "unset", "hidden"},
                {"UNSET", "UNSET", "hidden"},   // "UNSET" != "unset" -> 用它自己 -> visible
                {" HIDDEN ", "unset", "visible"},
                {"", "unset", "scroll"},
                {" ", "clip", "visible"},
                {"bogus", "unset", "auto"},
                {"unset", "scroll", "hidden"},
        };
        for (String[] c : cases) {
            Style style = new Style();
            style.overflowX = c[0];
            style.overflowY = c[1];
            style.overflow = c[2];
            assertEquals(Interaction.resolveOverflowX(style), style.overflowX().cssValue(),
                    "overflowX mismatch case=" + String.join(",", c));
            assertEquals(Interaction.resolveOverflowY(style), style.overflowY().cssValue(),
                    "overflowY mismatch case=" + String.join(",", c));
            assertEquals(Interaction.clipsOverflow(style), style.clipsOverflow(),
                    "clipsOverflow mismatch case=" + String.join(",", c));
        }
    }

    @Test
    void visibilityMemoMatchesLegacyNormalization() {
        for (String value : VALUES) {
            Style style = new Style();
            style.visibility = value;
            assertEquals(Interaction.normalizeVisibility(value), style.visibility().cssValue(),
                    "visibility mismatch for visibility=" + value);
            assertEquals(Interaction.normalizeVisibility(value), Interaction.Visibility.parse(value).cssValue(),
                    "Visibility.parse mismatch for visibility=" + value);
        }
    }

    /** 锁住旧 switch 关键字表：只认列出的关键字，非法/空白/null 回退 visible。 */
    @Test
    void enumParseMatchesOriginalKeywordTables() {
        assertEquals("visible", Interaction.normalizeOverflow(null));
        assertEquals("visible", Interaction.normalizeOverflow("  "));
        assertEquals("hidden", Interaction.normalizeOverflow("HIDDEN"));
        assertEquals("scroll", Interaction.normalizeOverflow(" Scroll "));
        assertEquals("auto", Interaction.normalizeOverflow("AUTO"));
        assertEquals("clip", Interaction.normalizeOverflow("clip"));
        assertEquals("visible", Interaction.normalizeOverflow("bogus"));
        assertEquals("visible", Interaction.normalizeOverflow("unset"));

        assertEquals("visible", Interaction.normalizeVisibility(null));
        assertEquals("visible", Interaction.normalizeVisibility(""));
        assertEquals("hidden", Interaction.normalizeVisibility(" HIDDEN "));
        assertEquals("collapse", Interaction.normalizeVisibility("COLLAPSE"));
        assertEquals("visible", Interaction.normalizeVisibility("bogus"));
        assertEquals("visible", Interaction.normalizeVisibility("unset"));
    }

    /** memo 失效走真实写入路径 {@code setFieldValue}（内部 resetMetrics）。 */
    @Test
    void memoInvalidatedByFieldMutation() {
        Style style = new Style();
        style.display = "block";
        style.overflow = "visible";
        style.overflowX = "unset";
        style.overflowY = "unset";
        style.visibility = "visible";
        style.position = "static";

        assertFalse(style.isFlexDisplay());
        assertFalse(style.isGridDisplay());
        assertFalse(style.isDisplayNone());
        assertTrue(style.isInFlow());
        assertSame(Interaction.Overflow.VISIBLE, style.overflowX());
        assertFalse(style.clipsOverflow());
        assertSame(Interaction.Visibility.VISIBLE, style.visibility());

        style.setFieldValue("display", "flex");
        assertTrue(style.isFlexDisplay());
        style.setFieldValue("display", "grid");
        assertTrue(style.isGridDisplay());
        style.setFieldValue("display", "none");
        assertTrue(style.isDisplayNone());
        assertFalse(style.isInFlow());

        style.setFieldValue("display", "block");
        style.setFieldValue("position", "fixed");
        assertFalse(style.isInFlow());
        style.setFieldValue("position", "static");
        assertTrue(style.isInFlow());

        style.setFieldValue("overflowX", "hidden");
        assertSame(Interaction.Overflow.HIDDEN, style.overflowX());
        assertTrue(style.clipsOverflow());
        style.setFieldValue("overflowX", "unset");
        style.setFieldValue("overflow", "scroll");
        assertSame(Interaction.Overflow.SCROLL, style.overflowX());
        assertTrue(style.clipsOverflow());
        style.setFieldValue("overflow", "visible");
        assertFalse(style.clipsOverflow());

        style.setFieldValue("visibility", "HIDDEN");
        assertSame(Interaction.Visibility.HIDDEN, style.visibility());
    }

    /** 直接赋值不自动失效，必须显式 resetMetrics()——锁住 memo 契约。 */
    @Test
    void memoInvalidatedByResetMetricsAfterDirectAssignment() {
        Style style = new Style();
        style.display = "block";
        assertFalse(style.isFlexDisplay());

        style.display = "flex";
        assertFalse(style.isFlexDisplay(), "memo 未清时应读到旧值");

        style.resetMetrics();
        assertTrue(style.isFlexDisplay());
    }

    @Test
    void parseSpanSpecMatchesLegacyGolden() {
        Document document = TestDocumentFactory.createDocument();
        document.body.setAttribute("style", "width: 100px;");
        Element grid = document.createElement("div");
        grid.setAttribute("style", "display: grid; width: 50px; grid-template-columns: repeat(5, 10px); "
                + "grid-template-rows: repeat(3, 10px);");
        document.body.appendChild(grid);

        Element explicitRange = document.createElement("div");
        explicitRange.setAttribute("style", "grid-column: 2 / 4; grid-row: 1;");
        grid.appendChild(explicitRange);
        Element automaticSpan = document.createElement("div");
        automaticSpan.setAttribute("style", "grid-column: span 2; grid-row: 2;");
        grid.appendChild(automaticSpan);
        Element explicitLine = document.createElement("div");
        explicitLine.setAttribute("style", "grid-column: 4; grid-row: 3;");
        grid.appendChild(explicitLine);

        Position rangePosition = Grid.computeChildPosition(explicitRange, grid, grid.getChildren());
        Size rangeSize = Grid.resolveAssignedSize(explicitRange);
        Position spanPosition = Grid.computeChildPosition(automaticSpan, grid, grid.getChildren());
        Size spanSize = Grid.resolveAssignedSize(automaticSpan);
        Position linePosition = Grid.computeChildPosition(explicitLine, grid, grid.getChildren());
        Size lineSize = Grid.resolveAssignedSize(explicitLine);

        assertEquals(10, rangePosition.x, 0.01);
        assertEquals(20, rangeSize.width(), 0.01);
        assertEquals(0, spanPosition.x, 0.01);
        assertEquals(20, spanSize.width(), 0.01);
        assertEquals(30, linePosition.x, 0.01);
        assertEquals(10, lineSize.width(), 0.01);
    }

    /** Shorthand placement and its equivalent longhands retain the same visible grid area. */
    @Test
    void parseSpanSpecCacheReturnsSameInstance() {
        Document document = TestDocumentFactory.createDocument();
        document.body.setAttribute("style", "width: 100px;");
        Element grid = document.createElement("div");
        grid.setAttribute("style", "display: grid; width: 50px; grid-template-columns: repeat(5, 10px); "
                + "grid-template-rows: repeat(2, 10px);");
        document.body.appendChild(grid);

        Element shorthand = document.createElement("div");
        shorthand.setAttribute("style", "grid-column: 2 / span 2; grid-row: 1;");
        grid.appendChild(shorthand);
        Element longhands = document.createElement("div");
        longhands.setAttribute("style", "grid-column-start: 2; grid-column-end: span 2; grid-row: 2;");
        grid.appendChild(longhands);

        Position shorthandPosition = Grid.computeChildPosition(shorthand, grid, grid.getChildren());
        Size shorthandSize = Grid.resolveAssignedSize(shorthand);
        Position longhandPosition = Grid.computeChildPosition(longhands, grid, grid.getChildren());
        Size longhandSize = Grid.resolveAssignedSize(longhands);

        assertEquals(10, shorthandPosition.x, 0.01);
        assertEquals(20, shorthandSize.width(), 0.01);
        assertEquals(shorthandPosition.x, longhandPosition.x, 0.01);
        assertEquals(shorthandSize.width(), longhandSize.width(), 0.01);
    }
}
