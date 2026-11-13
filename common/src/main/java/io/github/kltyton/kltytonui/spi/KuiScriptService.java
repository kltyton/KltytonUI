package io.github.kltyton.kltytonui.spi;

import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.init.Document;

import java.util.function.Consumer;

/**
 * Loader-side JavaScript execution.
 *
 * <p>Page execution uses a document-owned NeonJS realm.</p>
 */
public interface KuiScriptService {
    /** Evaluates JavaScript with the given event (may be {@code null}) and source label. */
    void eval(String code, Event event, String source);

    /** Evaluates the shared browser bootstrap for one document. */
    default void evalGlobal(String code, String documentUuid) {
        if (code == null) return;
        eval(code.replace("__KUI_DOCUMENT_UUID__", documentUuid == null ? "" : documentUuid), null, "global.js");
    }

    /** Drops document script scopes before a resource reload. */
    void reload();

    /** Initializes one-time engine state without executing page scripts. */
    default void warmUp() {
    }

    /** Releases one document's script scope and host-wrapper identity cache. */
    default void releaseDocument(Document document) {
    }

    /** Wraps a browser host object in the current document's script identity space. */
    default Object wrapHostObject(Object value) {
        return value;
    }

    /** Adapts a script function to a one-argument callback in the owning engine scope. */
    default Consumer<Object> createCallback(Object callback) {
        return null;
    }

    /** Returns the stable identity used to match additions and removals of one callback. */
    default Object callbackIdentity(Object callback) {
        return callback;
    }

    /** Adapts a script function to a DOM listener in the owning engine scope. */
    @SuppressWarnings("unchecked")
    default Consumer<Event> createEventListener(Object callback) {
        return (Consumer<Event>) (Consumer<?>) createCallback(callback);
    }

}
