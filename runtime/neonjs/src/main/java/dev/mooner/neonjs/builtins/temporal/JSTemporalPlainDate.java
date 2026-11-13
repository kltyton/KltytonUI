/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;

/** JavaScript Temporal.PlainDate instance. */
public final class JSTemporalPlainDate extends JSObject {
    public final IsoDate date;
    public final String calendar;

    public JSTemporalPlainDate(JSObject proto, IsoDate date, String calendar) {
        super(proto);
        this.date = date;
        this.calendar = calendar;
    }

    public IsoDate getDate() {
        return date;
    }

    public String getCalendar() {
        return calendar;
    }
}
