/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import java.lang.ref.WeakReference;

public final class JSWeakRef extends JSObject {
    public final WeakReference<Object> ref;

    public JSWeakRef(JSObject prototype, Object target) {
        super(prototype);
        this.ref = new WeakReference<>(target);
    }
}
