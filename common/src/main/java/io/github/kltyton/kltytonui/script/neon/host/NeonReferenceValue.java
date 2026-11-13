package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import dev.mooner.neonjs.NeonValue;
import java.util.Objects;

/** Retains opaque language objects without converting their type or identity. */
public final class NeonReferenceValue implements NeonScriptValue {
    private final NeonDocumentRuntime owner;
    private final NeonValue source;
    public NeonReferenceValue(NeonDocumentRuntime owner, NeonValue source) {
        this.owner = Objects.requireNonNull(owner);
        this.source = Objects.requireNonNull(source);
    }
    @Override public NeonDocumentRuntime owner() { return owner; }
    @Override public NeonValue toGuestValue() { return owner.accessValue(context -> source); }
}

