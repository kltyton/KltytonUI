package dev.mooner.neonjs.vm;

import java.util.function.Function;

/** JavaScript or host promise reaction; derived from NeonJS and adapted to Java. */
public final class PromiseReaction {
    public final PromiseCapability capability;
    public final boolean isFulfill;
    public final Object handler;
    public final Function<Object, Object> hostHandler;

    public PromiseReaction(PromiseCapability capability, boolean fulfill, Object handler) {
        this(capability, fulfill, handler, null);
    }

    public PromiseReaction(PromiseCapability capability, boolean fulfill, Object handler,
                           Function<Object, Object> hostHandler) {
        this.capability = capability;
        isFulfill = fulfill;
        this.handler = handler;
        this.hostHandler = hostHandler;
    }
}
