package io.github.kltyton.kltytonui.script.neon;

import dev.mooner.neonjs.HostAccess;
import dev.mooner.neonjs.NeonContext;
import dev.mooner.neonjs.NeonValue;
import dev.mooner.neonjs.builtins.Builtins;
import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.vm.Evaluator;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Window;
import io.github.kltyton.kltytonui.loader.ClientLoader;
import io.github.kltyton.kltytonui.script.neon.host.NeonHostConverter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** One locked agent shares compiled code and values between independent document realms. */
public final class NeonEngine {
    private dev.mooner.neonjs.NeonEngine engine;
    private volatile NeonContext context;
    private Realm anchor;
    private NeonValue api;
    private volatile NeonHostConverter converter;
    private final Map<ScriptKey, CodeBlock> scripts = new HashMap<>();
    private final Set<NeonDocumentRuntime> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<NeonDocumentRuntime> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private int depth;
    private volatile boolean closed;

    public <T> T run(NeonDocumentRuntime owner, NeonDocumentRuntime.Operation<T> operation) {
        NeonContext current = initialize();
        return current.enter(() -> {
            if (closed) throw new IllegalStateException("NeonJS engine has been closed");
            if (depth > 0) return inDocument(owner, operation);
            depth++;
            Window.window.beginScriptTask();
            try {
                return inDocument(owner, operation);
            } finally {
                try {
                    try {
                        synchronizeOwnership();
                        inDocument(owner, runtime -> { context.runJobs(); return null; });
                    } finally {
                        Window.window.endScriptTask();
                    }
                    try {
                        inDocument(owner, runtime -> { context.runJobs(); return null; });
                    } finally {
                        synchronizeOwnership();
                    }
                } finally {
                    depth--;
                    touched.clear();
                    for (NeonDocumentRuntime page : new ArrayList<>(retired)) page.closeRealm();
                    retired.clear();
                    if (closed) closeContext();
                }
            }
        });
    }

    public <T> T inDocument(NeonDocumentRuntime owner, NeonDocumentRuntime.Operation<T> operation) {
        NeonDocumentRuntime previous = NeonPageRuntime.activate(owner);
        if (owner != null && owner.isLive()) touched.add(owner);
        try (Document.ContextScope ignored = Document.withContext(owner == null ? null : owner.document())) {
            return owner != null && owner.realm() != null
                    ? owner.realm().enter(() -> operation.run(context)) : operation.run(context);
        } finally {
            NeonPageRuntime.restore(previous);
        }
    }

    Realm createRealm() {
        Realm realm = new Realm(anchor.agent);
        realm.enter(() -> { Builtins.install(realm); return null; });
        return realm;
    }

    NeonValue createBridge(NeonDocumentRuntime owner, Realm realm) {
        NeonValue enter = context.createFunction("enterDocument", args ->
                inDocument(owner, runtime -> args[0].call()));
        return api.callMember("createBridge", enter, value(realm.getGlobalObject()));
    }

    void evaluate(Realm realm, String code, String source) {
        CodeBlock compiled = compile(code, source);
        realm.enter(() -> { Evaluator.runScript(realm, compiled); return null; });
    }

    private CodeBlock compile(String code, String source) {
        ScriptKey key = new ScriptKey(source, code);
        CodeBlock prepared = scripts.get(key);
        return prepared == null ? Evaluator.compileScript(anchor, code, source) : prepared;
    }

    public void prepare(String code, String source) {
        if (code == null || code.isBlank()) return;
        NeonContext current = initialize();
        current.enter(() -> {
            if (closed) throw new IllegalStateException("NeonJS engine has been closed");
            scripts.computeIfAbsent(new ScriptKey(source, code),
                    key -> Evaluator.compileScript(anchor, key.code(), key.source()));
            return null;
        });
    }

    public void beginResourceGeneration() {
        NeonContext current = initialize();
        current.enter(() -> {
            if (closed) throw new IllegalStateException("NeonJS engine has been closed");
            scripts.clear();
            return null;
        });
    }

    public NeonValue value(Object raw) { return (NeonValue) context.wrapValue(raw); }
    public Object raw(NeonValue value) { return context.unwrapValue(value); }
    public NeonContext context() { return context; }
    public NeonValue api() { return api; }
    public NeonHostConverter converter() { return converter; }
    boolean isClosed() { return closed; }
    static boolean isClosedFailure(IllegalStateException failure) {
        return "NeonJS engine has been closed".equals(failure.getMessage())
                || "context is closed".equals(failure.getMessage());
    }

    void retire(NeonDocumentRuntime page) {
        NeonContext current = context;
        if (current == null) return;
        boolean[] entered = {false};
        try {
            current.enter(() -> {
                entered[0] = true;
                if (context != current) return null;
                retired.add(page);
                if (depth == 0) { page.closeRealm(); retired.remove(page); }
                return null;
            });
        } catch (IllegalStateException failure) {
            if (!entered[0] && closed && isClosedFailure(failure)) return;
            throw failure;
        }
    }

    void close() {
        NeonContext current;
        synchronized (this) {
            current = context;
            if (current == null) { closed = true; return; }
        }
        boolean[] entered = {false};
        try {
            current.enter(() -> {
                entered[0] = true;
                if (closed) return null;
                closed = true;
                if (depth == 0) closeContext();
                return null;
            });
        } catch (IllegalStateException failure) {
            if (!entered[0] && closed && isClosedFailure(failure)) return;
            throw failure;
        }
    }

    private synchronized NeonContext initialize() {
        if (closed) throw new IllegalStateException("NeonJS engine has been closed");
        NeonContext current = context;
        if (current != null) return current;
        if (closed) throw new IllegalStateException("NeonJS engine has been closed");
        HostAccess access = NeonHostConverter.hostAccess();
        engine = dev.mooner.neonjs.NeonEngine.builder()
                .hostAccess(access).autoRunJobs(false).intl(true).console(null)
                .jitThreshold(32).backgroundCompilation(true)
                .extension(realm -> anchor = realm).build();
        NeonContext created = null;
        try {
            created = engine.newContext();
            context = created;
            created.setUncaughtErrorHandler((failure, rejection) ->
                    NeonPageRuntime.logFailure(rejection ? "uncaught promise rejection" : "uncaught job error",
                            "jobs", failure));
            for (String path : new String[]{"runtime/neon/engine.js", "runtime/neon/host.js"}) {
                try (InputStream stream = ClientLoader.getResourceStream(path)) {
                    if (stream == null) throw new IllegalStateException("Missing NeonJS bootstrap: " + path);
                    created.eval(new String(stream.readAllBytes(), StandardCharsets.UTF_8), path);
                }
            }
            api = created.get("__kuiEngine");
            converter = new NeonHostConverter(this, anchor, access);
            converter.installPackages();
        } catch (IOException | RuntimeException failure) {
            if (created != null) created.close();
            engine.close();
            context = null;
            engine = null;
            throw new IllegalStateException("Could not initialize NeonJS", failure);
        }
        return created;
    }

    private void synchronizeOwnership() {
        for (NeonDocumentRuntime page : new ArrayList<>(touched)) if (page.isLive()) {
            inDocument(page, runtime -> { page.synchronizeOwnership(); return null; });
        }
        converter.prune();
    }

    private void closeContext() {
        if (context == null) return;
        try {
            converter.close();
            scripts.clear();
            context.close();
        } finally {
            engine.close();
            context = null;
            api = null;
            converter = null;
            anchor = null;
            engine = null;
        }
    }

    private record ScriptKey(String source, String code) { }
}

