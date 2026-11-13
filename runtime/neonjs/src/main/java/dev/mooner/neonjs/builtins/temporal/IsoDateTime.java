/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/** ISO Date-Time Record. */
public final class IsoDateTime {
    public final IsoDate date;
    public final TimeRec time;

    public IsoDateTime(IsoDate date, TimeRec time) {
        this.date = date;
        this.time = time;
    }

    public IsoDate getDate() {
        return date;
    }

    public TimeRec getTime() {
        return time;
    }
}
