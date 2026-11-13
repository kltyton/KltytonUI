package dev.mooner.neonjs.runtime;

/** Host implementation of a built-in function; derived from NeonJS and adapted to Java. */
@FunctionalInterface
public interface NativeImpl {
    Object invoke(NativeFunction function, Object receiver, Object[] arguments, JSObject newTarget);
}
