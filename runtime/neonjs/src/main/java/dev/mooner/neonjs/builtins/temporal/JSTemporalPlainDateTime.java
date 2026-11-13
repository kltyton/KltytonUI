/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;

/** JavaScript Temporal.PlainDateTime instance. */
public final class JSTemporalPlainDateTime extends JSObject {
    public final IsoDateTime dt;
    public final String calendar;

    public JSTemporalPlainDateTime(JSObject proto, IsoDateTime dt, String calendar) {
        super(proto);
        this.dt = dt;
        this.calendar = calendar;
    }

    public IsoDateTime getDt() {
        return dt;
    }

    public String getCalendar() {
        return calendar;
    }
}
