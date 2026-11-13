/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** Array iterator instance. kind: 0 keys, 1 values, 2 entries. */
public final class ArrayIteratorObject extends JSObject {
    public JSObject iterated;
    public final int kind;
    public long index = 0L;

    public ArrayIteratorObject(JSObject prototype, JSObject iterated, int kind) {
        super(prototype);
        this.iterated = iterated;
        this.kind = kind;
    }
}
