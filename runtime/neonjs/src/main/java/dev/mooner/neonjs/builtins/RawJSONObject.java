/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** Object created by JSON.rawJSON ([[IsRawJSON]]). */
public final class RawJSONObject extends JSObject {
    public final String raw;

    public RawJSONObject(String raw) {
        super(null);
        this.raw = raw;
    }
}
