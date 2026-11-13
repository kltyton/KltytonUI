package io.github.kltyton.kltytonui.neoforge;

import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import io.github.kltyton.kltytonui.spi.KuiScriptService;

import java.util.function.Consumer;

/**
 * NeoForge 26.1 script bridge for page scripts on NeonJS.
 */
public final class ScriptService implements KuiScriptService {
    public static final ScriptService INSTANCE = new ScriptService();

    private ScriptService() {
    }

    @Override
    public void eval(String code, Event event, String source) {
        NeonPageRuntime.eval(code, event, source);
    }

    @Override
    public void evalGlobal(String code, String documentUuid) {
        NeonPageRuntime.evalGlobal(code, documentUuid);
    }

    @Override
    public void reload() {
        NeonPageRuntime.reload();
    }

    @Override
    public void warmUp() {
        NeonPageRuntime.warmUp();
    }

    @Override
    public void releaseDocument(Document document) {
        NeonPageRuntime.release(document);
    }

    @Override
    public Object wrapHostObject(Object value) {
        return NeonPageRuntime.wrapHostValue(value);
    }

    @Override
    public Consumer<Object> createCallback(Object callback) {
        return NeonPageRuntime.createCallback(callback);
    }

    @Override
    public Object callbackIdentity(Object callback) {
        return NeonPageRuntime.callbackIdentity(callback);
    }
}
