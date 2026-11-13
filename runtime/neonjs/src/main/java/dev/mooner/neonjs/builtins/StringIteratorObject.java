/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** %StringIteratorPrototype% instance. */
public final class StringIteratorObject extends JSObject {
    public String s;
    public int pos = 0;

    public StringIteratorObject(JSObject prototype, String s) {
        super(prototype);
        this.s = s;
    }
}
