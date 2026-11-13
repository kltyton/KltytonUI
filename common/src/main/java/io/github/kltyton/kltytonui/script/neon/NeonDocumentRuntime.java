package io.github.kltyton.kltytonui.script.neon;

import dev.mooner.neonjs.NeonContext;
import dev.mooner.neonjs.NeonValue;
import dev.mooner.neonjs.runtime.Realm;
import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Node;
import io.github.kltyton.kltytonui.script.neon.host.NeonDocumentBindings;

/** Owns the lifecycle of one page generation, independently of exported language values. */
public final class NeonDocumentRuntime {
    private final Document document;
    private final long generation;
    private final NeonEngine engine;
    private Realm realm;
    private NeonValue globals;
    private NeonDocumentBindings bindings;
    private volatile boolean released;

    NeonDocumentRuntime(Document document) {
        this.document = document;
        generation = document.getRefreshGeneration();
        engine = NeonPageRuntime.engine();
    }
    public Document document() { return document; }
    public NeonEngine engine() { return engine; }
    public NeonDocumentBindings bindings() { return bindings; }
    Realm realm() { return realm; }
    public boolean ownsGeneration(long value) { return !released && generation == value; }
    public boolean isLive() { return !released && !document.isDisposed() && generation == document.getRefreshGeneration(); }

    public <T> T run(Operation<T> operation) {
        return engine.run(this, context -> {
            if (!isLive()) throw new IllegalStateException("NeonJS document generation has been released");
            ensureRealm();
            return realm.enter(() -> operation.run(context));
        });
    }

    public <T> T accessValue(Operation<T> operation) {
        NeonDocumentRuntime active = NeonPageRuntime.activeDocument();
        NeonDocumentRuntime contextOwner = active != null && active.engine() == engine && active.isLive() ? active : this;
        return engine.run(contextOwner, operation);
    }

    public <T> T runCallback(Class<?> resultType, Operation<T> operation) {
        if (!isLive()) return releasedCallback(resultType);
        boolean[] entered = {false};
        try {
            return engine.run(this, context -> {
                entered[0] = true;
                if (!isLive()) return releasedCallback(resultType);
                return operation.run(context);
            });
        } catch (IllegalStateException failure) {
            if (!entered[0] && engine.isClosed() && !isLive() && NeonEngine.isClosedFailure(failure)) {
                return releasedCallback(resultType);
            }
            throw failure;
        }
    }

    private static <T> T releasedCallback(Class<?> resultType) {
        if (resultType == void.class) return null;
        throw new IllegalStateException("JavaScript callback owner has been released");
    }

    void evaluate(String code, Event event, String source) {
        run(context -> {
            boolean hadEvent = event != null && engine.api().callMember("hasOwn", globals, "event").asBoolean();
            NeonValue previous = event == null ? null : globals.getMember("event");
            if (event != null) globals.putMember("event", engine.converter().toGuest(event));
            try { engine.evaluate(realm, code, source); }
            finally {
                if (event != null) {
                    if (hadEvent) globals.putMember("event", previous);
                    else globals.removeMember("event");
                }
            }
            return null;
        });
    }

    void evaluateBootstrap(String code, String uuid) {
        run(context -> {
            globals.putMember("__auiDocumentUuid", uuid == null ? "" : uuid);
            try { engine.evaluate(realm, code, "global.js"); }
            finally { globals.removeMember("__auiDocumentUuid"); }
            return null;
        });
    }

    private void ensureRealm() {
        if (realm != null) return;
        realm = engine.createRealm();
        globals = engine.value(realm.getGlobalObject());
        NeonValue bridge = engine.createBridge(this, realm);
        bindings = new NeonDocumentBindings(this, bridge);
        bridge.callMember("install");
        globals.putMember("KltytonUI", engine.converter().toGuest(KltytonUI.class));
    }

    public void release() { released = true; engine.retire(this); }
    void treeChanged(Node node) { if (bindings != null) bindings.treeChanged(node); }
    void synchronizeOwnership() { if (bindings != null) bindings.synchronizeOwnership(); }

    void closeRealm() {
        if (realm == null) return;
        try { bindings.release(); }
        finally {
            // Closures and objects exported to another page retain their realm and environment.
            realm = null;
            globals = null;
            bindings = null;
        }
    }

    @FunctionalInterface public interface Operation<T> { T run(NeonContext context); }
}

