package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import java.lang.reflect.Type;
import java.util.function.Supplier;

/**
 * Re-enters the owning context and converts values crossing the host boundary.
 * Derived from NeonJS and adapted to Java.
 */
public interface ContextGate {
    <R> R enter(Supplier<? extends R> block);

    Object wrapValue(Object value);

    Object unwrapValue(Object value);

    Class<?> getValueClass();

    default Object normalizeGuest(Object value) {
        return value;
    }

    default Object undefinedToHost() {
        return null;
    }

    default JSObject wrapHostValue(JSObject raw, Object target) {
        return raw;
    }

    default ContextGate callbackGate() {
        return this;
    }

    default <R> R enterCallback(Class<?> resultType, Supplier<? extends R> block) {
        return enter(block);
    }

    default Object implementation(JSObject value, Class<?> type, Type declaredType,
                                  Supplier<?> factory) {
        return factory.get();
    }

    default Throwable rejectionToHost(Object reason) {
        return new JSException(reason);
    }
}
