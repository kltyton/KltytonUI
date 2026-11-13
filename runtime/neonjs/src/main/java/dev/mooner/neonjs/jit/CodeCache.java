package dev.mooner.neonjs.jit;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares weakly held compiled code for each code definer and input identity.
 * Derived from NeonJS and adapted to Java.
 */
public final class CodeCache {
    private static final Map<CodeDefiner, CodeCache> CACHES = new WeakHashMap<>();

    private final ConcurrentHashMap<String, Ref> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<CompiledCode> queue = new ReferenceQueue<>();

    private CodeCache() {
    }

    public CompiledCode get(String identity) {
        expunge();
        Ref ref = map.get(identity);
        return ref == null ? null : ref.get();
    }

    public CompiledCode putIfAbsent(String identity, CompiledCode code) {
        expunge();
        while (true) {
            Ref previous = map.putIfAbsent(identity, new Ref(identity, code, queue));
            if (previous == null) {
                return code;
            }
            CompiledCode existing = previous.get();
            if (existing != null) {
                return existing;
            }
            if (map.replace(identity, previous, new Ref(identity, code, queue))) {
                return code;
            }
        }
    }

    public int getSize() {
        return map.size();
    }

    private void expunge() {
        Reference<? extends CompiledCode> reference;
        while ((reference = queue.poll()) instanceof Ref ref) {
            map.remove(ref.key, ref);
        }
    }

    public static CodeCache of(CodeDefiner definer) {
        synchronized (CACHES) {
            return CACHES.computeIfAbsent(definer, ignored -> new CodeCache());
        }
    }

    private static final class Ref extends WeakReference<CompiledCode> {
        private final String key;

        private Ref(String key, CompiledCode code, ReferenceQueue<CompiledCode> queue) {
            super(code, queue);
            this.key = key;
        }
    }
}
