/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.regexp.RegExpProgram;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;

/** RegExp instance: an ordinary object with the original source/flags and compiled matcher slots. */
public final class JSRegExp extends JSObject {
    public String originalSource = "";
    public String originalFlags = "";
    public RegExpProgram program;
    /** The current realm when the object was allocated (legacy RegExp features). */
    public Realm realm;
    /** Allocated by the realm's own %RegExp% (not a subclass or a cross-realm newTarget). */
    public boolean legacyFeatures = false;

    public JSRegExp(JSObject prototype) {
        super(prototype);
    }

    @Override
    public String getClassName() {
        return "RegExp";
    }
}
