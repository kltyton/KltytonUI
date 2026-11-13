/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.math.BigInteger;

/** Internal Duration Record: a date duration plus a time duration in nanoseconds. */
public final class InternalDuration {
    public final DateDuration date;
    public final BigInteger time;

    public InternalDuration(DateDuration date, BigInteger time) {
        this.date = date;
        this.time = time;
    }

    public int sign() {
        int d = date.sign();
        return d != 0 ? d : time.signum();
    }

    public DateDuration getDate() {
        return date;
    }

    public BigInteger getTime() {
        return time;
    }
}
