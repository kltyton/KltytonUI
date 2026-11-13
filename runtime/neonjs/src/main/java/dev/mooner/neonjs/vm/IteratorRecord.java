package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;

/** Iterator state record; derived from NeonJS and adapted to Java. */
public final class IteratorRecord {
    public final JSObject iterator;
    public final Object nextMethod;
    public boolean done;

    public IteratorRecord(JSObject iterator, Object nextMethod) {
        this(iterator, nextMethod, false);
    }

    public IteratorRecord(JSObject iterator, Object nextMethod, boolean done) {
        this.iterator = iterator;
        this.nextMethod = nextMethod;
        this.done = done;
    }
}
