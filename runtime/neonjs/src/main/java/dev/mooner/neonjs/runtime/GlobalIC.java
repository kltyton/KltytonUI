package dev.mooner.neonjs.runtime;

/** Immutable global-access cache entry; derived from NeonJS and adapted to Java. */
public final class GlobalIC {
    public final Object token;
    public final int index;
    public final Shape shape;
    public final int slot;
    public final int epoch;

    public GlobalIC(Object token, int index, Shape shape, int slot, int epoch) {
        this.token = token;
        this.index = index;
        this.shape = shape;
        this.slot = slot;
        this.epoch = epoch;
    }
}
