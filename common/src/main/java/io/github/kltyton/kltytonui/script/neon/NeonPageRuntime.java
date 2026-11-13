package io.github.kltyton.kltytonui.script.neon;

import dev.mooner.neonjs.NeonException;
import dev.mooner.neonjs.runtime.JSException;
import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Node;
import io.github.kltyton.kltytonui.loader.Loader;
import io.github.kltyton.kltytonui.parser.HTML;
import io.github.kltyton.kltytonui.script.neon.host.NeonCallback;
import io.github.kltyton.kltytonui.script.neon.host.NeonCallbackAdapter;
import io.github.kltyton.kltytonui.script.neon.host.NeonHostConverter;
import io.github.kltyton.kltytonui.util.KuiLog;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Routes page scripts into document realms and preserves exported JavaScript values. */
public final class NeonPageRuntime {
    private static final Map<String, NeonDocumentRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final ThreadLocal<NeonDocumentRuntime> ACTIVE = new ThreadLocal<>();
    private static volatile NeonEngine engine = new NeonEngine();
    private NeonPageRuntime() { }

    public static void eval(String code, Event event, String source) {
        if (code == null || code.isBlank()) return;
        try { runtimeFor(currentDocument(source)).evaluate(code, event, KuiLog.source(source)); }
        catch (RuntimeException failure) { throw executionFailure(source, failure); }
    }

    public static void evalGlobal(String code, String documentUuid) {
        if (code == null || code.isBlank()) return;
        try { runtimeFor(currentDocument("global.js")).evaluateBootstrap(prepareBootstrap(code), documentUuid); }
        catch (RuntimeException failure) { throw executionFailure("global.js", failure); }
    }

    public static void warmUp() {
        engine.beginResourceGeneration();
        engine.prepare(prepareBootstrap(Loader.readGlobalJS()), "global.js");
        for (HTML.PreparedScript script : HTML.preparedScripts()) engine.prepare(script.source(), script.sourceName());
    }

    public static void release(Document document) {
        if (document == null) return;
        NeonDocumentRuntime runtime = RUNTIMES.remove(document.getUuid().toString());
        if (runtime != null) runtime.release();
    }
    public static void reload() {
        for (NeonDocumentRuntime runtime : RUNTIMES.values()) runtime.release();
        RUNTIMES.clear();
        engine.close();
        engine = new NeonEngine();
    }
    public static void shutdown() {
        for (NeonDocumentRuntime runtime : RUNTIMES.values()) runtime.release();
        RUNTIMES.clear();
        engine.close();
    }
    static NeonEngine engine() { return engine; }
    public static NeonDocumentRuntime activeDocument() { return ACTIVE.get(); }
    public static boolean isActive() { return ACTIVE.get() != null; }
    public static Object wrapHostValue(Object value) { return value; }
    public static void treeChanged(Node node) { treeChanged(node, node == null ? null : node.document); }
    public static void treeChanged(Node node, Document previousOwner) {
        if (node == null) return;
        notifyTreeChanged(node, previousOwner);
        if (node.document != previousOwner) notifyTreeChanged(node, node.document);
    }
    private static void notifyTreeChanged(Node node, Document document) {
        if (document == null) return;
        NeonDocumentRuntime runtime = RUNTIMES.get(document.getUuid().toString());
        if (runtime != null) runtime.treeChanged(node);
    }
    public static boolean isCallback(Object callback) {
        NeonHostConverter converter = engine.converter();
        return callback instanceof NeonCallback || callback instanceof NeonCallbackAdapter
                || converter != null && converter.isNativeCallback(callback);
    }
    public static Object callbackIdentity(Object callback) {
        if (callback instanceof NeonCallback function) return function.identityKey();
        if (callback instanceof NeonCallbackAdapter adapter) return adapter.identityKey();
        NeonHostConverter converter = engine.converter();
        return converter == null ? callback : converter.nativeIdentity(callback);
    }
    public static Consumer<Object> createCallback(Object callback) {
        if (callback instanceof NeonCallbackAdapter adapter) return adapter;
        NeonCallback function = callbackFunction(callback);
        return function == null ? null : new NeonCallbackAdapter(function);
    }
    private static NeonCallback callbackFunction(Object callback) {
        if (callback instanceof NeonCallback function) return function;
        if (callback instanceof NeonCallbackAdapter adapter) return adapter.function();
        NeonHostConverter converter = engine.converter();
        return converter == null ? null : converter.nativeCallback(callback);
    }
    static NeonDocumentRuntime activate(NeonDocumentRuntime runtime) {
        NeonDocumentRuntime previous = ACTIVE.get();
        ACTIVE.set(runtime);
        return previous;
    }
    static void restore(NeonDocumentRuntime previous) {
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
    private static IllegalStateException executionFailure(String source, RuntimeException failure) {
        logFailure("script failed", source, failure);
        return new IllegalStateException("NeonJS execution failed: " + KuiLog.source(source), failure);
    }
    static void logFailure(String operation, String source, Throwable failure) {
        String stack = failure instanceof NeonException neonFailure ? neonFailure.getJsStack()
                : failure instanceof JSException guestFailure ? guestFailure.getJsStack() : null;
        if (stack == null || stack.isBlank()) {
            KltytonUI.LOGGER.error("[KUI Neon] {} source={}", operation, KuiLog.source(source), failure);
        } else {
            KltytonUI.LOGGER.error("[KUI Neon] {} source={}\n{}", operation, KuiLog.source(source), stack, failure);
        }
    }
    private static String prepareBootstrap(String code) {
        return code == null ? "" : code.replace("\"__KUI_DOCUMENT_UUID__\"", "__auiDocumentUuid")
                .replace("'__KUI_DOCUMENT_UUID__'", "__auiDocumentUuid");
    }
    private static Document currentDocument(String source) {
        Document document = Document.getContextDocument();
        if (document == null || document.isDisposed()) throw new IllegalStateException(
                "Page script requires an active document: " + KuiLog.source(source));
        return document;
    }
    private static NeonDocumentRuntime runtimeFor(Document document) {
        String key = document.getUuid().toString();
        while (true) {
            NeonDocumentRuntime existing = RUNTIMES.get(key);
            if (existing != null && existing.ownsGeneration(document.getRefreshGeneration())) return existing;
            NeonDocumentRuntime replacement = new NeonDocumentRuntime(document);
            boolean installed = existing == null ? RUNTIMES.putIfAbsent(key, replacement) == null
                    : RUNTIMES.replace(key, existing, replacement);
            if (installed) {
                if (existing != null) existing.release();
                return replacement;
            }
        }
    }
}

