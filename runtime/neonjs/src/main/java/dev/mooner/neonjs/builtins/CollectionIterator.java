/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

public final class CollectionIterator extends JSObject {
    public TableCursor cursor;
    public final int kind;

    public CollectionIterator(JSObject prototype, TableCursor cursor, int kind) {
        super(prototype);
        this.cursor = cursor;
        this.kind = kind;
    }
}
