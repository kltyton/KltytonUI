/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;

import java.math.BigInteger;

/** JavaScript Temporal.ZonedDateTime instance. */
public final class JSTemporalZonedDateTime extends JSObject {
    public final BigInteger epochNs;
    public final TimeZone timeZone;
    public final String calendar;

    public JSTemporalZonedDateTime(JSObject proto, BigInteger epochNs, TimeZone timeZone, String calendar) {
        super(proto);
        this.epochNs = epochNs;
        this.timeZone = timeZone;
        this.calendar = calendar;
    }

    public BigInteger getEpochNs() {
        return epochNs;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }

    public String getCalendar() {
        return calendar;
    }
}
