/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.vm.IteratorRecord;

/** %WrapForValidIteratorPrototype% instances. */
public final class WrappedIteratorObject extends JSObject {
    public final IteratorRecord rec;

    public WrappedIteratorObject(JSObject prototype, IteratorRecord rec) {
        super(prototype);
        this.rec = rec;
    }
}
