package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.compiler.ScopeInfo;
import java.util.LinkedHashMap;

/** Fixed-slot declarative environment; derived from NeonJS and adapted to Java. */
public final class DeclEnv extends Env {
    public final ScopeInfo info;
    public final Object[] slots;
    public LinkedHashMap<String, Object> extension;

    public DeclEnv(Env parent, ScopeInfo info) {
        this(parent, info, info.newSlots());
    }

    public DeclEnv(Env parent, ScopeInfo info, Object[] slots) {
        super(parent);
        this.info = info;
        this.slots = slots;
    }

    public DeclEnv copy() {
        DeclEnv copy = new DeclEnv(parent, info, slots.clone());
        copy.extension = extension;
        return copy;
    }
}
