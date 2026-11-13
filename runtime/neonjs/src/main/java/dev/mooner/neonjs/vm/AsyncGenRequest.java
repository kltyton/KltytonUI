package dev.mooner.neonjs.vm;

/** Queued asynchronous generator request; derived from NeonJS and adapted to Java. */
public final class AsyncGenRequest {
    public final int mode;
    public final Object value;
    public final PromiseCapability capability;

    public AsyncGenRequest(int mode, Object value, PromiseCapability capability) {
        this.mode = mode;
        this.value = value;
        this.capability = capability;
    }
}
