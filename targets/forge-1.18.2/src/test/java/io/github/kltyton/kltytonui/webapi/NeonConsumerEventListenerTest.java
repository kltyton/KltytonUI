package io.github.kltyton.kltytonui.webapi;

import io.github.kltyton.kltytonui.forge.ScriptService;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.parser.HTML;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import io.github.kltyton.kltytonui.spi.KuiScriptService;
import io.github.kltyton.kltytonui.spi.KuiServices;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NeonConsumerEventListenerTest {
    @Test
    void javascriptFunctionConvertsToConsumerAndCanBeRemovedByIdentity() {
        String path = "test://consumer-event-" + UUID.randomUUID();
        HTML.putTemple(path, "<html><body></body></html>");
        KuiScriptService previousScript = KuiServices.script();
        KuiServices.setScript(ScriptService.INSTANCE);
        Document document = null;
        try {
            document = Document.create(path);
            assertNotNull(document);
            try (Document.ContextScope ignored = Document.withContext(document)) {
                NeonPageRuntime.eval("""
                        var calls = 0;
                        var listener = function(event) { calls++; };
                        window.addEventListener('custom', listener);
                        window.dispatchEvent(window.createEvent('custom', false));
                        window.removeEventListener('custom', listener);
                        window.dispatchEvent(window.createEvent('custom', false));
                        document.body.setAttribute('data-calls', String(calls));
                        """, null, "consumer-event-listener-test");
            }
            assertEquals("1", document.body.getAttribute("data-calls"));
        } finally {
            if (document != null) document.remove();
            KuiServices.setScript(previousScript);
        }
    }
}

