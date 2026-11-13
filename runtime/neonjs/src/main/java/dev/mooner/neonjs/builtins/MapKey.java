/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Ops;

/** Wrapper giving SameValueZero semantics to map keys. */
public final class MapKey {
    public final Object value;

    private MapKey(Object value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MapKey key && Ops.sameValueZero(value, key.value);
    }

    @Override
    public int hashCode() {
        Object v = value;
        if (v instanceof Double doubleValue) {
            return doubleValue == 0.0 ? 0 : doubleValue.hashCode();
        }
        if (v instanceof CharSequence text) return text.toString().hashCode();
        if (v == null) return 0;
        return v.hashCode();
    }

    public static MapKey of(Object value) {
        Object key;
        if (value instanceof CharSequence text) key = text.toString();
        else if (value instanceof Double doubleValue && doubleValue == 0.0) key = 0.0;
        else key = value;
        return new MapKey(key);
    }
}
