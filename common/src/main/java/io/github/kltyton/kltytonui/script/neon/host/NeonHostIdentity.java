package io.github.kltyton.kltytonui.script.neon.host;

import dev.mooner.neonjs.NeonValue;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Neither a weak host key nor a language WeakRef roots the wrapped host. */
final class NeonHostIdentity {
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<Key, NeonValue> values = new HashMap<>();
    private final NeonValue api;
    NeonHostIdentity(NeonValue api) { this.api = api; }
    NeonValue get(Object host) {
        Key key;
        while ((key = (Key) collected.poll()) != null) values.remove(key);
        NeonValue weak = values.get(new Key(host, null));
        if (weak == null) return null;
        NeonValue result = api.callMember("deref", weak);
        if (api.callMember("isUndefined", result).asBoolean()) {
            values.remove(new Key(host, null));
            return null;
        }
        return result;
    }
    void put(Object host, NeonValue value) { values.put(new Key(host, collected), api.callMember("weak", value)); }
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object host, ReferenceQueue<Object> queue) { super(host, queue); hash = System.identityHashCode(host); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }
}

