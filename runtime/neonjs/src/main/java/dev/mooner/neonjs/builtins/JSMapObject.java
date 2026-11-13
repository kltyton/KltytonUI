/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

public final class JSMapObject extends JSObject {
    public final boolean isSet;
    public final OrderedTable table = new OrderedTable();

    public JSMapObject(JSObject prototype, boolean isSet) {
        super(prototype);
        this.isSet = isSet;
    }
}
