package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;

/** Promise and resolving functions; derived from NeonJS and adapted to Java. */
public final class PromiseCapability {
    public final JSObject promise;
    public final Object resolve;
    public final Object reject;

    public PromiseCapability(JSObject promise, Object resolve, Object reject) {
        this.promise = promise;
        this.resolve = resolve;
        this.reject = reject;
    }
}
