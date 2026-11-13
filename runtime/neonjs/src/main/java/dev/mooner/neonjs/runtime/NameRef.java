package dev.mooner.neonjs.runtime;

/** Resolved dynamic binding reference; derived from NeonJS and adapted to Java. */
public final class NameRef {
    public final Env env;
    public final String name;
    public final int slot;
    public final boolean strict;

    public NameRef(Env env, String name, int slot, boolean strict) {
        this.env = env;
        this.name = name;
        this.slot = slot;
        this.strict = strict;
    }
}
