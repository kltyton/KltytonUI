/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class JSFinalizationRegistry extends JSObject {
    public static final class Cell extends WeakReference<Object> {
        public final Object held;
        public final Object token;
        public final JSFinalizationRegistry owner;

        public Cell(Object target, Object held, Object token, ReferenceQueue<Object> queue,
                    JSFinalizationRegistry owner) {
            super(target, queue);
            this.held = held;
            this.token = token;
            this.owner = owner;
        }
    }

    public final JSObject cleanup;
    public final Realm realm;
    public final Set<Cell> cells =
            Collections.newSetFromMap(new IdentityHashMap<Cell, Boolean>());
    public final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    public JSFinalizationRegistry(JSObject prototype, JSObject cleanup, Realm realm) {
        super(prototype);
        this.cleanup = cleanup;
        this.realm = realm;
    }
}
