package io.github.kltyton.kltytonui.webapi;

import io.github.kltyton.kltytonui.element.Body;
import io.github.kltyton.kltytonui.element.Head;
import io.github.kltyton.kltytonui.element.Html;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.parser.HTML;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import io.github.kltyton.kltytonui.spi.KuiScriptService;
import io.github.kltyton.kltytonui.spi.KuiServices;

import java.util.UUID;
import java.util.function.Consumer;

public final class TestDocumentFactory {
    private TestDocumentFactory() {
    }

    public static Document createDocument() {
        Document document = new Document("test://doc", false);
        document.documentElement = new Html(document);
        document.head = new Head(document);
        document.body = new Body(document);
        document.documentElement.appendChild(document.head);
        document.documentElement.appendChild(document.body);
        return document;
    }

    public static void withNeonDocument(Consumer<Document> action) {
        try (NeonPage page = new NeonPage();
             Document.ContextScope ignored = Document.withContext(page.document())) {
            action.accept(page.document());
        }
    }

    public static final class NeonPage implements AutoCloseable {
        private final KuiScriptService previous;
        private final Document document;
        private Consumer<Object> lastCallback;

        public NeonPage() {
            previous = KuiServices.script();
            KuiServices.setScript(new KuiScriptService() {
                public void eval(String code, Event event, String source) { NeonPageRuntime.eval(code, event, source); }
                public void evalGlobal(String code, String uuid) { NeonPageRuntime.evalGlobal(code, uuid); }
                public void reload() { NeonPageRuntime.reload(); }
                public void releaseDocument(Document document) { NeonPageRuntime.release(document); }
                public Consumer<Object> createCallback(Object callback) {
                    lastCallback = NeonPageRuntime.createCallback(callback);
                    return lastCallback;
                }
                public Object callbackIdentity(Object callback) { return NeonPageRuntime.callbackIdentity(callback); }
            });
            try {
                String path = "test://neon-" + UUID.randomUUID();
                HTML.putTemple(path, "<html><body></body></html>");
                document = Document.create(path);
                if (document == null) throw new IllegalStateException("Could not create test document");
            } catch (RuntimeException | Error failure) {
                KuiServices.setScript(previous);
                throw failure;
            }
        }

        public Document document() { return document; }
        public Consumer<Object> lastCallback() { return lastCallback; }

        @Override
        public void close() {
            try { document.remove(); }
            finally { KuiServices.setScript(previous); }
        }
    }
}
