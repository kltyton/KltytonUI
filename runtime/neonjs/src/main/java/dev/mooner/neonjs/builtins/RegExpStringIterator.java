/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** %RegExpStringIteratorPrototype% instance (result of {@code RegExp.prototype[Symbol.matchAll]}). */
public final class RegExpStringIterator extends JSObject {
    public final JSObject matcher;
    public final String string;
    public final boolean global;
    public final boolean fullUnicode;
    public boolean done = false;

    public RegExpStringIterator(JSObject prototype, JSObject matcher, String string,
                                boolean global, boolean fullUnicode) {
        super(prototype);
        this.matcher = matcher;
        this.string = string;
        this.global = global;
        this.fullUnicode = fullUnicode;
    }
}
