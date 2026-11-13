package dev.mooner.neonjs.runtime;

/** Global declarative binding; derived from NeonJS and adapted to Java. */
public final class GlobalBinding {
    public Object value;
    public final boolean mutable;

    public GlobalBinding(Object value, boolean mutable) {
        this.value = value;
        this.mutable = mutable;
    }
}
