package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.builtins.AsyncDisposal;
import dev.mooner.neonjs.builtins.DisposeCapability;
import dev.mooner.neonjs.builtins.DisposeHint;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.Realm;
import java.util.Objects;

/** Runtime support for using and await using; derived from NeonJS and adapted to Java. */
public final class Disposal {
    private Disposal() {
    }

    public static Object newCapability() {
        return new DisposeCapability();
    }

    public static void add(Realm realm, Object capability, Object value, int hint) {
        ((DisposeCapability) capability).add(realm, value, hint == 1 ? DisposeHint.ASYNC : DisposeHint.SYNC);
    }

    private static Throwable completion(Object kind, Object value) {
        return Objects.equals(kind, 1.0) ? new JSException(value) : null;
    }

    public static void disposeSync(Realm realm, Object capability, Object kind, Object value) {
        ((DisposeCapability) capability).disposeAll(realm, completion(kind, value));
    }

    public static Object begin(Realm realm, Object capability, Object kind, Object value) {
        return ((DisposeCapability) capability).beginAsyncDisposal(realm, completion(kind, value));
    }

    public static boolean step(Object disposal) {
        return ((AsyncDisposal) disposal).step();
    }

    public static Object awaitValue(Object disposal) {
        return ((AsyncDisposal) disposal).getAwaitValue();
    }

    public static void awaited(Object result, Object disposal, Object mode) {
        if (!Objects.equals(mode, 0.0)) ((AsyncDisposal) disposal).rejected(result);
    }

    public static void end(Object disposal) {
        Throwable completion = ((AsyncDisposal) disposal).getResult();
        if (completion != null) Disposal.<RuntimeException>rethrow(completion);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrow(Throwable error) throws E {
        throw (E) error;
    }
}
