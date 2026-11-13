package io.github.kltyton.kltytonui.script.neon.host;

import java.util.Objects;
import java.util.function.Consumer;

/** Retains the source function while exposing Java callback interfaces. */
public final class NeonCallbackAdapter implements Consumer<Object>, Runnable {
    private final NeonCallback function;
    public NeonCallbackAdapter(NeonCallback function) { this.function = Objects.requireNonNull(function); }
    public NeonCallback function() { return function; }
    public Object identityKey() { return function.identityKey(); }
    @Override public void accept(Object value) { function.accept(value); }
    @Override public void run() { function.run(); }
}

