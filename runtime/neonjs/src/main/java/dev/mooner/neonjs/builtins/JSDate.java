/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** Date instance: an ordinary object with a {@code [[DateValue]]} internal slot (or NaN). */
public final class JSDate extends JSObject {
    public double timeValue;

    public JSDate(JSObject proto, double timeValue) {
        super(proto);
        this.timeValue = timeValue;
    }

    @Override
    public String getClassName() {
        return "Date";
    }
}
