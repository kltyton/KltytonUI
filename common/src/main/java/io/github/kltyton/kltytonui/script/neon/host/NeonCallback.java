package io.github.kltyton.kltytonui.script.neon.host;

import dev.mooner.neonjs.NeonValue;
import io.github.kltyton.kltytonui.event.Event;
import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import java.util.function.Consumer;

/** A stable function identity and a document-scoped Java callback. */
public final class NeonCallback implements Consumer<Object>, Runnable, NeonScriptValue {
    private final NeonHostConverter converter;
    private final NeonDocumentRuntime owner;
    private final NeonValue function;
    private final Object identity;
    NeonCallback(NeonHostConverter converter, NeonDocumentRuntime owner, NeonValue function, Object identity) {
        this.converter = converter;
        this.owner = owner;
        this.function = function;
        this.identity = identity;
    }
    public Object identityKey() { return identity; }
    @Override public NeonDocumentRuntime owner() { return owner; }
    @Override public NeonValue toGuestValue() { return owner.accessValue(context -> function); }
    @Override public void accept(Object value) { invoke(value, true); }
    @Override public void run() { invoke(null, false); }
    private void invoke(Object value, boolean argumentRequired) {
        if (!owner.isLive()) return;
        owner.runCallback(void.class, context -> {
            Object receiver = value instanceof Event event && event.currentTarget != null ? event.currentTarget : null;
            converter.invoke(function, receiver, value, argumentRequired);
            return null;
        });
    }
}

