package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.runtime.concurrent.LazyValue;
import dev.mooner.neonjs.vm.Frame;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Legacy ART class-hierarchy workaround; derived from NeonJS and adapted to Java. */
public final class ArtCha {
    public static final ArtCha INSTANCE = new ArtCha();
    private final LazyValue<Boolean> keepsUnloaded = new LazyValue<>(ArtCha::legacyArt);
    private final CompiledCode[] permanentImplementations = {new NotCompiled(), new AlsoNotCompiled()};
    private final Set<Class<?>> pinned = ConcurrentHashMap.newKeySet();

    private ArtCha() {
    }

    private static boolean legacyArt() {
        try {
            int sdk = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
            return sdk >= 26 && sdk <= 27;
        } catch (ReflectiveOperationException | SecurityException | LinkageError error) {
            return false;
        }
    }

    public boolean getKeepsUnloadedImplementations() {
        return keepsUnloaded.get();
    }

    public int linkCompiledCodeImplementations() {
        return permanentImplementations.length;
    }

    public void pinIfNeeded(Class<?> type) {
        if (keepsUnloaded.get()) pinned.add(type);
    }

    // Two permanent implementation classes prevent legacy ART from retaining an unloaded sole implementation.
    private static final class NotCompiled implements CompiledCode {
        @Override
        public Object run(Frame frame) {
            return null;
        }
    }

    private static final class AlsoNotCompiled implements CompiledCode {
        @Override
        public Object run(Frame frame) {
            return null;
        }
    }
}
