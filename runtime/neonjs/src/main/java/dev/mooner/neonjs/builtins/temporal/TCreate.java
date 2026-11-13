/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;

import java.math.BigInteger;

/** Temporal object creation. */
final class TCreate {
    public static final TCreate INSTANCE = new TCreate();

    public static final String P_DURATION = "%Temporal.Duration.prototype%";
    public static final String P_INSTANT = "%Temporal.Instant.prototype%";
    public static final String P_DATE = "%Temporal.PlainDate.prototype%";
    public static final String P_TIME = "%Temporal.PlainTime.prototype%";
    public static final String P_DATETIME = "%Temporal.PlainDateTime.prototype%";
    public static final String P_YEARMONTH = "%Temporal.PlainYearMonth.prototype%";
    public static final String P_MONTHDAY = "%Temporal.PlainMonthDay.prototype%";
    public static final String P_ZONED = "%Temporal.ZonedDateTime.prototype%";

    private TCreate() {
    }

    private static Realm cur() {
        return Agent.currentRealm();
    }

    private static JSObject proto(JSObject nt, String name) {
        Realm realm = cur();
        if (nt == null) return realm.intrinsic(name);
        return Ops.getPrototypeFromConstructor(nt, r -> r.intrinsic(name));
    }

    /** IsValidDuration. */
    public static boolean isValidDuration(double y, double mo, double w, double d, double h, double mi, double s,
                                          double ms, double us, double ns) {
        int sign = 0;
        for (double v : new double[]{y, mo, w, d, h, mi, s, ms, us, ns}) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return false;
            if (v < 0) {
                if (sign > 0) return false;
                sign = -1;
            } else if (v > 0) {
                if (sign < 0) return false;
                sign = 1;
            }
        }
        double lim = 4294967296.0;
        if (Math.abs(y) >= lim || Math.abs(mo) >= lim || Math.abs(w) >= lim) return false;
        // fast path: everything comfortably small
        if (Math.abs(d) < 1e6 && Math.abs(h) < 1e7 && Math.abs(mi) < 1e9 && Math.abs(s) < 1e11
                && Math.abs(ms) < 1e14 && Math.abs(us) < 1e17 && Math.abs(ns) < 1e18) {
            return true;
        }
        BigInteger total = TNum.big(d).multiply(TM.BI_NS_PER_DAY)
                .add(TNum.big(h).multiply(BigInteger.valueOf(3_600_000_000_000L)))
                .add(TNum.big(mi).multiply(BigInteger.valueOf(60_000_000_000L)))
                .add(TNum.big(s).multiply(TNum.BI_1E9))
                .add(TNum.big(ms).multiply(TNum.BI_1E6))
                .add(TNum.big(us).multiply(TNum.BI_1000))
                .add(TNum.big(ns));
        return total.abs().compareTo(TM.TWO_POW_53_NS) < 0;
    }

    /** CreateTemporalDuration. */
    public static JSTemporalDuration duration(double y, double mo, double w, double d, double h, double mi,
                                              double s, double ms, double us, double ns) {
        return duration(y, mo, w, d, h, mi, s, ms, us, ns, null);
    }

    /** CreateTemporalDuration. */
    public static JSTemporalDuration duration(double y, double mo, double w, double d, double h, double mi,
                                              double s, double ms, double us, double ns, JSObject nt) {
        if (!isValidDuration(y, mo, w, d, h, mi, s, ms, us, ns)) {
            throw TemporalAbstractOps.tRangeErr("invalid duration");
        }
        return new JSTemporalDuration(proto(nt, P_DURATION),
                TNum.pz(y), TNum.pz(mo), TNum.pz(w), TNum.pz(d), TNum.pz(h), TNum.pz(mi), TNum.pz(s),
                TNum.pz(ms), TNum.pz(us), TNum.pz(ns));
    }

    public static JSTemporalDuration duration(double[] f) {
        return duration(f, null);
    }

    public static JSTemporalDuration duration(double[] f, JSObject nt) {
        return duration(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], nt);
    }

    public static JSTemporalInstant instant(BigInteger ns) {
        return instant(ns, null);
    }

    public static JSTemporalInstant instant(BigInteger ns, JSObject nt) {
        return new JSTemporalInstant(proto(nt, P_INSTANT), ns);
    }

    public static JSTemporalPlainDate plainDate(IsoDate d, String cal) {
        return plainDate(d, cal, null);
    }

    public static JSTemporalPlainDate plainDate(IsoDate d, String cal, JSObject nt) {
        if (!TM.isoDateWithinLimits(d)) throw TemporalAbstractOps.tRangeErr("date outside of supported range");
        return new JSTemporalPlainDate(proto(nt, P_DATE), d, cal);
    }

    public static JSTemporalPlainTime plainTime(TimeRec t) {
        return plainTime(t, null);
    }

    public static JSTemporalPlainTime plainTime(TimeRec t, JSObject nt) {
        TimeRec value = t.days == 0L ? t
                : new TimeRec(t.hour, t.minute, t.second, t.millisecond, t.microsecond, t.nanosecond);
        return new JSTemporalPlainTime(proto(nt, P_TIME), value);
    }

    public static JSTemporalPlainDateTime plainDateTime(IsoDateTime dt, String cal) {
        return plainDateTime(dt, cal, null);
    }

    public static JSTemporalPlainDateTime plainDateTime(IsoDateTime dt, String cal, JSObject nt) {
        if (!TM.isoDateTimeWithinLimits(dt)) {
            throw TemporalAbstractOps.tRangeErr("date-time outside of supported range");
        }
        return new JSTemporalPlainDateTime(proto(nt, P_DATETIME), dt, cal);
    }

    public static JSTemporalPlainYearMonth plainYearMonth(IsoDate d, String cal) {
        return plainYearMonth(d, cal, null);
    }

    public static JSTemporalPlainYearMonth plainYearMonth(IsoDate d, String cal, JSObject nt) {
        if (!TM.isoYearMonthWithinLimits(d.year, d.month)) {
            throw TemporalAbstractOps.tRangeErr("year-month outside of supported range");
        }
        return new JSTemporalPlainYearMonth(proto(nt, P_YEARMONTH), d, cal);
    }

    public static JSTemporalPlainMonthDay plainMonthDay(IsoDate d, String cal) {
        return plainMonthDay(d, cal, null);
    }

    public static JSTemporalPlainMonthDay plainMonthDay(IsoDate d, String cal, JSObject nt) {
        if (!TM.isoDateWithinLimits(d)) {
            throw TemporalAbstractOps.tRangeErr("month-day outside of supported range");
        }
        return new JSTemporalPlainMonthDay(proto(nt, P_MONTHDAY), d, cal);
    }

    public static JSTemporalZonedDateTime zoned(BigInteger ns, TimeZone tz, String cal) {
        return zoned(ns, tz, cal, null);
    }

    public static JSTemporalZonedDateTime zoned(BigInteger ns, TimeZone tz, String cal, JSObject nt) {
        return new JSTemporalZonedDateTime(proto(nt, P_ZONED), ns, tz, cal);
    }
}
