package dev.mooner.neonjs.runtime;

/** Scope-chain environment record; derived from NeonJS and adapted to Java. */
public abstract class Env {
    public final Env parent;

    public Env(Env parent) {
        this.parent = parent;
    }
}
