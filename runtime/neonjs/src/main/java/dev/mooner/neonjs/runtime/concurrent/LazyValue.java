package dev.mooner.neonjs.runtime.concurrent;

import java.util.Objects;
import java.util.function.Supplier;

/** Publishes a lazily initialized value, retaining the initializer if initialization fails. */
public final class LazyValue<T> implements Supplier<T> {
    private volatile boolean initialized;
    private T value;
    private Supplier<? extends T> initializer;

    public LazyValue(Supplier<? extends T> initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    @Override
    public T get() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    T result = initializer.get();
                    value = result;
                    initializer = null;
                    initialized = true;
                }
            }
        }
        return value;
    }
}
