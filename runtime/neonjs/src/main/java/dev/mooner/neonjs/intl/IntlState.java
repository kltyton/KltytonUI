/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Realm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-realm Intl state. A realm (like its agent) is used by one thread at a time, so the caches here need no
 * synchronization; they hold ICU objects that are not thread-safe and must never be shared between realms.
 */
final class IntlState {
    static final String KEY = "%neonjs.IntlState%";

    /** DefaultLocale(), resolved on first use. */
    String defaultLocale;

    /** {@code %Intl%.[[FallbackSymbol]]} */
    final JSSymbol fallbackSymbol = new JSSymbol("IntlLegacyConstructedSymbol");

    /** Small LRU cache for the locale-sensitive convenience methods (toLocaleString & co.). */
    private final LinkedHashMap<Object, Object> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
            return size() > 32;
        }
    };

    @SuppressWarnings("unchecked")
    <T> T cached(Object key, Supplier<T> create) {
        Object value = cache.get(key);
        if (value != null) {
            return (T) value;
        }
        T created = create.get();
        cache.put(key, created);
        return created;
    }

    static IntlState of(Realm realm) {
        Map<String, Object> state = realm.getIntrinsicsAny();
        Object existing = state.get(KEY);
        if (existing instanceof IntlState) {
            return (IntlState) existing;
        }
        IntlState created = new IntlState();
        state.put(KEY, created);
        return created;
    }
}
