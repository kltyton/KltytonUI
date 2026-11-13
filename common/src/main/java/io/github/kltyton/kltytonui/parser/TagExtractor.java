package io.github.kltyton.kltytonui.parser;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.util.KuiLog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 HTML 中提取 {@code <tag>} 块（{@code <style>}/{@code <script>}）的公共骨架：
 * 统计开闭标签、移除标签、缓存 src 与内联内容，由子类决定如何应用进文档。
 * CSS/JS 各自的 Extractor 收拢于此。
 */
abstract class TagExtractor {
    private final Pattern openMarker;
    private final Pattern closeMarker;
    private final String logPrefix; // "CSS"/"JS"
    private final String tagName;   // "style"/"script"

    protected final String contextPath;
    protected final List<String> cachedSrcs = new ArrayList<>();
    protected final List<String> cachedContents = new ArrayList<>();

    TagExtractor(String contextPath, Pattern openMarker, Pattern closeMarker, String logPrefix, String tagName) {
        this.contextPath = contextPath;
        this.openMarker = openMarker;
        this.closeMarker = closeMarker;
        this.logPrefix = logPrefix;
        this.tagName = tagName;
    }

    public String handle(String html) {
        if (html == null || html.isEmpty()) return html;
        /* Count and extract against one shared mask; comments and raw text are not markup. */
        String scanned = HTML.maskForTagScan(html);
        int openCount = HTML.countMatches(openMarker, scanned);
        int closeCount = HTML.countMatches(closeMarker, scanned);
        if (openCount != closeCount) {
            KltytonUI.LOGGER.warn(
                    "[KUI {}] unmatched {} tag path={} openTags={} closeTags={}",
                    logPrefix, tagName, KuiLog.source(contextPath), openCount, closeCount
            );
        }
        return extract(html, scanned);
    }

    protected String extract(String html, String scanned) {
        return extract(html);
    }

    /**
     * 移除所有匹配的标签，逐个回调 onTag。
     *
     * <p>匹配跑在 {@link HTML#maskForTagScan(String)} 的遮罩文本上（注释与 raw text 正文不算标记），
     * 回调拿到的是**原文**里的属性/正文片段，所以注释与真实内容都不会被遮罩吃掉。</p>
     */
    protected String removeTags(String html, Pattern tagPattern) {
        return removeTags(html, HTML.maskForTagScan(html), tagPattern);
    }

    protected String removeTags(String html, String scanned, Pattern tagPattern) {
        Matcher matcher = tagPattern.matcher(scanned);
        StringBuilder sb = null;
        int cursor = 0;
        while (matcher.find()) {
            onTag(
                    html.substring(matcher.start(1), matcher.end(1)),
                    html.substring(matcher.start(2), matcher.end(2))
            );
            if (sb == null) sb = new StringBuilder(html.length());
            sb.append(html, cursor, matcher.start());
            cursor = matcher.end();
        }
        if (sb == null) return html;
        sb.append(html, cursor, html.length());
        return sb.toString();
    }

    protected abstract String extract(String html);

    protected abstract void onTag(String attrText, String inner);

    public List<String> sourceSnapshot() {
        return List.copyOf(cachedSrcs);
    }

    public List<String> contentSnapshot() {
        return List.copyOf(cachedContents);
    }

    public abstract void pushToDocument(Document document);
}
