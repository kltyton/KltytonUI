package io.github.kltyton.kltytonui.parser;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.loader.ClientLoader;
import io.github.kltyton.kltytonui.loader.Loader;
import io.github.kltyton.kltytonui.util.KuiLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JS {

    public static class Extractor extends TagExtractor {
        private static final Pattern SCRIPT_TAG_PATTERN =
                Pattern.compile("(?i)<script\\b([^>]*)>(.*?)</script\\s*>", Pattern.DOTALL);

        private static final Pattern SRC_ATTR_PATTERN =
                Pattern.compile("(?i)\\bsrc\\s*=\\s*(['\"])(.*?)\\1");
        private static final Pattern SCRIPT_OPEN_MARKER = Pattern.compile("(?i)<script\\b");
        private static final Pattern SCRIPT_CLOSE_MARKER = Pattern.compile("(?i)</script\\s*>");

        public Extractor(String contextPath) {
            super(contextPath, SCRIPT_OPEN_MARKER, SCRIPT_CLOSE_MARKER, "JS", "script");
        }

        @Override
        protected String extract(String html) {
            return removeTags(html, SCRIPT_TAG_PATTERN);
        }

        @Override
        protected void onTag(String attrText, String inner) {
            boolean hasSrc = false;

            // 1. 尝试提取 src
            if (attrText != null) {
                Matcher srcMatcher = SRC_ATTR_PATTERN.matcher(attrText);
                if (srcMatcher.find()) {
                    String srcValue = srcMatcher.group(2);
                    if (srcValue != null && !srcValue.isEmpty()) {
                        cachedSrcs.add(srcValue);
                        hasSrc = true;
                    }
                } else if (attrText.toLowerCase().contains("src")) {
                    KltytonUI.LOGGER.warn(
                            "[KUI JS] script src attribute is malformed path={} attributes={}",
                            KuiLog.source(contextPath),
                            KuiLog.compact(attrText)
                    );
                }
            }

            // 2. 如果没有 src 或者有内部代码，则提取内部代码
            // 注意：HTML标准中如果带src通常忽略内部代码，但在你的UI引擎中可以根据需求决定是否允许两者共存
            // 这里逻辑为：如果有内容，就加入缓存
            if (inner != null && !inner.isBlank()) {
                cachedContents.add(inner.trim());
                if (hasSrc) {
                    KltytonUI.LOGGER.warn(
                            "[KUI JS] script has both src and inline code; both will execute path={} src={}",
                            KuiLog.source(contextPath),
                            KuiLog.compact(attrText)
                    );
                }
            }
        }

        @Override
        public void pushToDocument(Document document) {
            if (document == null) {
                KltytonUI.LOGGER.error("[KUI JS] cannot attach scripts without a document path={}", KuiLog.source(contextPath));
                return;
            }
            ResourceUsageIndex.recordJs(contextPath, cachedSrcs);
            document.JSCache.addAll(loadScripts());
        }

        public List<String> loadScripts() {
            ArrayList<String> scripts = new ArrayList<>();
            for (String src : cachedSrcs) {
                String resolvedPath = Loader.resolve(contextPath, src);
                if (Loader.isRemotePath(resolvedPath)) {
                    KltytonUI.LOGGER.warn(
                            "[KUI JS] remote external script is unsupported; skipped document={} src={}",
                            KuiLog.source(contextPath),
                            resolvedPath
                    );
                    continue;
                }
                try (InputStream is = ClientLoader.getResourceStream(resolvedPath)) {
                    if (is == null) {
                        KltytonUI.LOGGER.error(
                                "[KUI JS] external script resource is missing document={} src={} resolved={}",
                                KuiLog.source(contextPath),
                                src,
                                resolvedPath
                        );
                        continue;
                    }
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    if (content.isBlank()) {
                        KltytonUI.LOGGER.debug("[KUI JS] external script is empty resolved={}", resolvedPath);
                    }
                    scripts.add(content);
                } catch (IOException e) {
                    KltytonUI.LOGGER.error(
                            "[KUI JS] failed to read external script document={} resolved={}",
                            KuiLog.source(contextPath),
                            resolvedPath,
                            e
                    );
                }
            }

            scripts.addAll(cachedContents);
            return List.copyOf(scripts);
        }
    }
}
