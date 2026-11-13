/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

public final class TableCursor {
    public final OrderedTable table;
    public int pos;
    public Object key;
    public Object value;

    public TableCursor(OrderedTable table) {
        this.table = table;
    }
}
