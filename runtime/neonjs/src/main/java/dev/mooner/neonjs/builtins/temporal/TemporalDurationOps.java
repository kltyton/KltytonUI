/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Undefined;

import java.math.BigInteger;

/** The result of nudging a duration to a calendar unit. */
final class NudgeResult {
    public final InternalDuration duration;
    public final BigInteger nudgedNs;
    public final boolean didExpand;

    NudgeResult(InternalDuration duration, BigInteger nudgedNs, boolean didExpand) {
        this.duration = duration;
        this.nudgedNs = nudgedNs;
        this.didExpand = didExpand;
    }
}

/** The settings used when computing a difference. */
final class DiffSettings {
    public final TUnit smallest;
    public final TUnit largest;
    public final RMode mode;
    public final long increment;

    DiffSettings(TUnit smallest, TUnit largest, RMode mode, long increment) {
        this.smallest = smallest;
        this.largest = largest;
        this.mode = mode;
        this.increment = increment;
    }
}

/** Duration records, time durations and relative rounding (the "Abstract Operations" of Temporal.Duration). */
final class TDur {
    private static final BigInteger BI_NS_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger BI_NS_MINUTE = BigInteger.valueOf(60_000_000_000L);

    private static final String[] PARTIAL_KEYS = {"days", "hours", "microseconds", "milliseconds", "minutes",
            "months", "nanoseconds", "seconds", "weeks", "years"};
    private static final int[] PARTIAL_SLOTS = {3, 4, 8, 7, 5, 1, 9, 6, 2, 0};

    private TDur() {
    }

    // ------------------------------------------------------------------ records

    /** CreateDateDurationRecord */
    static DateDuration dateDuration(long y, long mo, long w, long d) {
        if (!TCreate.isValidDuration(y, mo, w, d, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)) {
            throw TemporalAbstractOps.tRangeErr("invalid duration");
        }
        return new DateDuration(y, mo, w, d);
    }

    /** AdjustDateDurationRecord */
    static DateDuration adjust(DateDuration dd, long days) {
        return adjust(dd, days, dd.weeks, dd.months);
    }

    /** AdjustDateDurationRecord */
    static DateDuration adjust(DateDuration dd, long days, long weeks) {
        return adjust(dd, days, weeks, dd.months);
    }

    /** AdjustDateDurationRecord */
    static DateDuration adjust(DateDuration dd, long days, long weeks, long months) {
        return dateDuration(dd.years, months, weeks, days);
    }

    /** TimeDurationFromComponents */
    static BigInteger timeFromComponents(double h, double mi, double s, double ms, double us, double ns) {
        // each term stays below 6e17 in magnitude, so the Long sum cannot overflow
        if (Math.abs(h) < 1e5 && Math.abs(mi) < 1e7 && Math.abs(s) < 1e8 && Math.abs(ms) < 1e11
                && Math.abs(us) < 1e14 && Math.abs(ns) < 1e17) {
            long total = (long) h * 3_600_000_000_000L + (long) mi * 60_000_000_000L
                    + (long) s * 1_000_000_000L + (long) ms * 1_000_000L + (long) us * 1000L + (long) ns;
            return BigInteger.valueOf(total);
        }
        return TNum.big(h).multiply(BI_NS_HOUR).add(TNum.big(mi).multiply(BI_NS_MINUTE))
                .add(TNum.big(s).multiply(TNum.BI_1E9)).add(TNum.big(ms).multiply(TNum.BI_1E6))
                .add(TNum.big(us).multiply(TNum.BI_1000)).add(TNum.big(ns));
    }

    private static BigInteger checkTime(BigInteger d) {
        if (d.abs().compareTo(TM.MAX_TIME_DURATION) > 0) {
            throw TemporalAbstractOps.tRangeErr("time duration out of range");
        }
        return d;
    }

    /** AddTimeDuration */
    static BigInteger addTime(BigInteger a, BigInteger b) {
        return checkTime(a.add(b));
    }

    /** Add24HourDaysToTimeDuration */
    static BigInteger add24HourDays(BigInteger d, long days) {
        if (days == 0L) return checkTime(d);
        return checkTime(d.add(BigInteger.valueOf(days).multiply(TM.BI_NS_PER_DAY)));
    }

    /** RoundTimeDurationToIncrement */
    static BigInteger roundTimeToIncrement(BigInteger d, BigInteger inc, RMode mode) {
        return checkTime(TNum.roundToIncrement(d, inc, mode));
    }

    /** RoundTimeDuration */
    static BigInteger roundTime(BigInteger d, long inc, TUnit unit, RMode mode) {
        return roundTimeToIncrement(d, BigInteger.valueOf(unit.nanos).multiply(BigInteger.valueOf(inc)), mode);
    }

    /** TotalTimeDuration */
    static double totalTime(BigInteger d, TUnit unit) {
        return TNum.divToDouble(d, unit.nanos);
    }

    /** ToInternalDurationRecord */
    static InternalDuration toInternal(JSTemporalDuration d) {
        return new InternalDuration(
                new DateDuration((long) d.years, (long) d.months, (long) d.weeks, (long) d.days),
                timeFromComponents(d.hours, d.minutes, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds));
    }

    /** ToInternalDurationRecordWith24HourDays */
    static InternalDuration toInternal24(JSTemporalDuration d) {
        BigInteger t = add24HourDays(timeFromComponents(d.hours, d.minutes, d.seconds, d.milliseconds,
                d.microseconds, d.nanoseconds), (long) d.days);
        return new InternalDuration(new DateDuration((long) d.years, (long) d.months, (long) d.weeks, 0), t);
    }

    /** ToDateDurationRecordWithoutTime */
    static DateDuration toDateDurationWithoutTime(JSTemporalDuration d) {
        InternalDuration id = toInternal24(d);
        long days = id.time.divide(TM.BI_NS_PER_DAY).longValue();
        return new DateDuration(id.date.years, id.date.months, id.date.weeks, days);
    }

    /** TemporalDurationFromInternal */
    static JSTemporalDuration fromInternal(InternalDuration id, TUnit largest) {
        int sign = id.time.signum();
        BigInteger ns = id.time.abs();
        BigInteger us = BigInteger.ZERO;
        BigInteger ms = BigInteger.ZERO;
        BigInteger s = BigInteger.ZERO;
        BigInteger mi = BigInteger.ZERO;
        BigInteger h = BigInteger.ZERO;
        BigInteger days = BigInteger.ZERO;
        if (largest.ordinal() <= TUnit.MICROSECOND.ordinal()) {
            BigInteger[] a = ns.divideAndRemainder(TNum.BI_1000);
            us = a[0];
            ns = a[1];
            if (largest.ordinal() <= TUnit.MILLISECOND.ordinal()) {
                BigInteger[] b = us.divideAndRemainder(TNum.BI_1000);
                ms = b[0];
                us = b[1];
                if (largest.ordinal() <= TUnit.SECOND.ordinal()) {
                    BigInteger[] c = ms.divideAndRemainder(TNum.BI_1000);
                    s = c[0];
                    ms = c[1];
                    if (largest.ordinal() <= TUnit.MINUTE.ordinal()) {
                        BigInteger[] e = s.divideAndRemainder(TNum.BI_60);
                        mi = e[0];
                        s = e[1];
                        if (largest.ordinal() <= TUnit.HOUR.ordinal()) {
                            BigInteger[] f = mi.divideAndRemainder(TNum.BI_60);
                            h = f[0];
                            mi = f[1];
                            if (largest.isDate) {
                                BigInteger[] g = h.divideAndRemainder(BigInteger.valueOf(24));
                                days = g[0];
                                h = g[1];
                            }
                        }
                    }
                }
            }
        }
        double totalDays = BigInteger.valueOf(id.date.days)
                .add(sign < 0 ? days.negate() : days).doubleValue();
        return TCreate.duration(
                id.date.years, id.date.months, id.date.weeks, totalDays,
                sd(h, sign), sd(mi, sign), sd(s, sign), sd(ms, sign), sd(us, sign), sd(ns, sign));
    }

    private static double sd(BigInteger v, int sign) {
        return sign < 0 ? -v.doubleValue() + 0.0 : v.doubleValue();
    }

    static JSTemporalDuration negated(JSTemporalDuration d) {
        return TCreate.duration(
                TNum.neg(d.years), TNum.neg(d.months), TNum.neg(d.weeks), TNum.neg(d.days),
                TNum.neg(d.hours), TNum.neg(d.minutes), TNum.neg(d.seconds), TNum.neg(d.milliseconds),
                TNum.neg(d.microseconds), TNum.neg(d.nanoseconds));
    }

    static int sign(JSTemporalDuration d) {
        double[] values = {d.years, d.months, d.weeks, d.days, d.hours, d.minutes, d.seconds, d.milliseconds,
                d.microseconds, d.nanoseconds};
        for (double v : values) {
            if (v < 0) return -1;
            if (v > 0) return 1;
        }
        return 0;
    }

    /** DefaultTemporalLargestUnit */
    static TUnit defaultLargestUnit(JSTemporalDuration d) {
        if (d.years != 0.0) return TUnit.YEAR;
        if (d.months != 0.0) return TUnit.MONTH;
        if (d.weeks != 0.0) return TUnit.WEEK;
        if (d.days != 0.0) return TUnit.DAY;
        if (d.hours != 0.0) return TUnit.HOUR;
        if (d.minutes != 0.0) return TUnit.MINUTE;
        if (d.seconds != 0.0) return TUnit.SECOND;
        if (d.milliseconds != 0.0) return TUnit.MILLISECOND;
        if (d.microseconds != 0.0) return TUnit.MICROSECOND;
        return TUnit.NANOSECOND;
    }

    /** ToTemporalPartialDurationRecord: NaN marks undefined fields. */
    static double[] toPartial(Object item) {
        if (!(item instanceof JSObject)) {
            throw TemporalAbstractOps.tTypeErr("duration-like must be an object");
        }
        JSObject object = (JSObject) item;
        double[] out = new double[10];
        for (int i = 0; i < out.length; i++) out[i] = Double.NaN;
        boolean any = false;
        for (int k = 0; k < PARTIAL_KEYS.length; k++) {
            Object v = object.get(PARTIAL_KEYS[k], object);
            if (v != Undefined.INSTANCE) {
                out[PARTIAL_SLOTS[k]] = TemporalAbstractOps.toIntegerIfIntegral(v);
                any = true;
            }
        }
        if (!any) {
            throw TemporalAbstractOps.tTypeErr("duration-like object must have at least one duration property");
        }
        return out;
    }

    /** ToTemporalDuration */
    static JSTemporalDuration toDuration(Object item) {
        if (item instanceof JSTemporalDuration) {
            JSTemporalDuration duration = (JSTemporalDuration) item;
            return TCreate.duration(duration.years, duration.months, duration.weeks, duration.days, duration.hours,
                    duration.minutes, duration.seconds, duration.milliseconds, duration.microseconds,
                    duration.nanoseconds);
        }
        if (!(item instanceof JSObject)) {
            if (!(item instanceof CharSequence)) {
                throw TemporalAbstractOps.tTypeErr("cannot convert " + dev.mooner.neonjs.runtime.Ops.typeOf(item)
                        + " to a duration");
            }
            return TCreate.duration(TemporalParser.parseDuration(item.toString()));
        }
        double[] p = toPartial(item);
        for (int k = 0; k < 10; k++) if (Double.isNaN(p[k])) p[k] = 0.0;
        return TCreate.duration(p);
    }

    /** AddDurations */
    static JSTemporalDuration addDurations(boolean subtract, JSTemporalDuration d, Object otherV) {
        JSTemporalDuration other = toDuration(otherV);
        if (subtract) other = negated(other);
        TUnit largest = TUnit.larger(defaultLargestUnit(d), defaultLargestUnit(other));
        if (largest.isCalendar()) {
            throw TemporalAbstractOps.tRangeErr("cannot add durations with calendar units without a relative date");
        }
        InternalDuration d1 = toInternal24(d);
        InternalDuration d2 = toInternal24(other);
        BigInteger t = addTime(d1.time, d2.time);
        return fromInternal(new InternalDuration(DateDuration.ZERO, t), largest);
    }

    /** DateDurationDays */
    static long dateDurationDays(DateDuration dd, JSTemporalPlainDate rel) {
        DateDuration ymw = adjust(dd, 0);
        if (ymw.sign() == 0) return dd.days;
        IsoDate later = TCal.dateAdd(rel.calendar, rel.date, ymw, Overflow.CONSTRAIN);
        return dd.days + (TM.epochDays(later) - TM.epochDays(rel.date));
    }

    // ------------------------------------------------------------------ difference settings

    /** GetDifferenceSettings; {@code group}: 0 date, 1 time, 2 datetime. */
    static DiffSettings differenceSettings(boolean since, JSObject options, int group, TUnit[] disallowed,
                                            TUnit fallbackSmallest, TUnit smallestLargestDefault) {
        int largest = TOpt.unit(options, "largestUnit", false);
        long inc = TOpt.roundingIncrement(options);
        RMode mode = TOpt.roundingMode(options, RMode.TRUNC);
        int smallest = TOpt.unit(options, "smallestUnit", false);
        TOpt.validateUnit(largest, group, new int[]{TOpt.UNIT_AUTO});
        if (largest == TOpt.UNIT_UNSET) largest = TOpt.UNIT_AUTO;
        if (largest >= 0 && contains(disallowed, TUnit.ALL[largest])) {
            throw TemporalAbstractOps.tRangeErr("largestUnit " + TUnit.ALL[largest].singular + " is not allowed");
        }
        TOpt.validateUnit(smallest, group);
        if (smallest == TOpt.UNIT_UNSET) smallest = fallbackSmallest.ordinal();
        TUnit smallestU = TUnit.ALL[smallest];
        if (contains(disallowed, smallestU)) {
            throw TemporalAbstractOps.tRangeErr("smallestUnit " + smallestU.singular + " is not allowed");
        }
        TUnit defaultLargest = TUnit.larger(smallestLargestDefault, smallestU);
        TUnit largestU = largest == TOpt.UNIT_AUTO ? defaultLargest : TUnit.ALL[largest];
        if (TUnit.larger(largestU, smallestU) != largestU) {
            throw TemporalAbstractOps.tRangeErr("largestUnit must be larger than smallestUnit");
        }
        if (smallestU.maxIncrement != 0) {
            TOpt.validateIncrement(inc, smallestU.maxIncrement, false);
        }
        if (since) mode = mode.negate();
        return new DiffSettings(smallestU, largestU, mode, inc);
    }

    private static boolean contains(TUnit[] values, TUnit value) {
        for (TUnit v : values) if (v == value) return true;
        return false;
    }

    // ------------------------------------------------------------------ epoch helpers

    private static BigInteger epochNsOf(IsoDate d, TimeRec t, TimeZone tz) {
        return tz == null ? TM.utcEpochNs(d, t) : TZ.epochNsFor(tz, new IsoDateTime(d, t), Disambiguation.COMPATIBLE);
    }

    /** AddInstant */
    static BigInteger addInstant(BigInteger ns, BigInteger d) {
        BigInteger r = ns.add(d);
        if (!TM.isValidEpochNs(r)) throw TemporalAbstractOps.tRangeErr("instant outside of supported range");
        return r;
    }

    /** AddZonedDateTime */
    static BigInteger addZoned(BigInteger ns, TimeZone tz, String cal, InternalDuration d, Overflow overflow) {
        if (d.date.sign() == 0) return addInstant(ns, d.time);
        IsoDateTime dt = TZ.isoDateTimeFor(tz, ns);
        IsoDate added = TCal.dateAdd(cal, dt.date, d.date, overflow);
        IsoDateTime intermediate = new IsoDateTime(added, dt.time);
        if (!TM.isoDateTimeWithinLimits(intermediate)) {
            throw TemporalAbstractOps.tRangeErr("date-time outside of supported range");
        }
        BigInteger intermediateNs = TZ.epochNsFor(tz, intermediate, Disambiguation.COMPATIBLE);
        return addInstant(intermediateNs, d.time);
    }

    // ------------------------------------------------------------------ differences

    /** DifferenceISODateTime */
    static InternalDuration differenceIsoDateTime(IsoDateTime dt1, IsoDateTime dt2, String cal, TUnit largest) {
        long timeNs = TM.differenceTime(dt1.time, dt2.time);
        int timeSign = Long.signum(timeNs);
        int dateSign = TM.compareDate(dt1.date, dt2.date);
        IsoDate adjusted = dt2.date;
        if (timeSign == dateSign) {
            adjusted = TM.addDays(adjusted, timeSign);
            timeNs -= timeSign * TM.NS_PER_DAY;
        }
        TUnit dateLargest = TUnit.larger(TUnit.DAY, largest);
        DateDuration dateDiff = TCal.dateUntil(cal, dt1.date, adjusted, dateLargest);
        BigInteger time = BigInteger.valueOf(timeNs);
        if (largest != dateLargest) {
            time = add24HourDays(time, dateDiff.days);
            dateDiff = new DateDuration(dateDiff.years, dateDiff.months, dateDiff.weeks, 0);
        }
        return new InternalDuration(dateDiff, time);
    }

    /** DifferencePlainDateTimeWithRounding */
    static InternalDuration differencePlainDateTimeWithRounding(IsoDateTime dt1, IsoDateTime dt2, String cal,
                                                                 TUnit largest, long inc, TUnit smallest, RMode mode) {
        if (TM.compareDateTime(dt1, dt2) == 0) return new InternalDuration(DateDuration.ZERO, BigInteger.ZERO);
        if (!TM.isoDateTimeWithinLimits(dt1) || !TM.isoDateTimeWithinLimits(dt2)) {
            throw TemporalAbstractOps.tRangeErr("date-time outside of supported range");
        }
        InternalDuration diff = differenceIsoDateTime(dt1, dt2, cal, largest);
        if (smallest == TUnit.NANOSECOND && inc == 1L) return diff;
        BigInteger origin = TM.utcEpochNs(dt1);
        BigInteger dest = TM.utcEpochNs(dt2);
        return roundRelative(diff, origin, dest, dt1, null, cal, largest, inc, smallest, mode);
    }

    /** DifferencePlainDateTimeWithTotal */
    static double differencePlainDateTimeWithTotal(IsoDateTime dt1, IsoDateTime dt2, String cal, TUnit unit) {
        if (TM.compareDateTime(dt1, dt2) == 0) return 0.0;
        if (!TM.isoDateTimeWithinLimits(dt1) || !TM.isoDateTimeWithinLimits(dt2)) {
            throw TemporalAbstractOps.tRangeErr("date-time outside of supported range");
        }
        InternalDuration diff = differenceIsoDateTime(dt1, dt2, cal, unit);
        if (unit == TUnit.NANOSECOND) return diff.time.doubleValue();
        BigInteger origin = TM.utcEpochNs(dt1);
        BigInteger dest = TM.utcEpochNs(dt2);
        return totalRelative(diff, origin, dest, dt1, null, cal, unit);
    }

    /** DifferenceInstant */
    static InternalDuration differenceInstant(BigInteger ns1, BigInteger ns2, long inc, TUnit smallest, RMode mode) {
        return new InternalDuration(DateDuration.ZERO, roundTime(ns2.subtract(ns1), inc, smallest, mode));
    }

    /** DifferenceZonedDateTime */
    static InternalDuration differenceZoned(BigInteger ns1, BigInteger ns2, TimeZone tz, String cal, TUnit largest) {
        if (ns1.equals(ns2)) return new InternalDuration(DateDuration.ZERO, BigInteger.ZERO);
        IsoDateTime start = TZ.isoDateTimeFor(tz, ns1);
        IsoDateTime end = TZ.isoDateTimeFor(tz, ns2);
        if (TM.compareDate(start.date, end.date) == 0) {
            return new InternalDuration(DateDuration.ZERO, ns2.subtract(ns1));
        }
        int sign = ns2.compareTo(ns1) < 0 ? 1 : -1;
        int maxDayCorrection = sign == -1 ? 2 : 1;
        int dayCorrection = 0;
        BigInteger timeDuration = BigInteger.valueOf(TM.differenceTime(start.time, end.time));
        if (timeDuration.signum() == sign) dayCorrection++;
        boolean success = false;
        IsoDateTime intermediate = end;
        while (dayCorrection <= maxDayCorrection && !success) {
            IsoDate idate = TM.addDays(end.date, (long) dayCorrection * sign);
            intermediate = new IsoDateTime(idate, start.time);
            BigInteger ins = TZ.epochNsFor(tz, intermediate, Disambiguation.COMPATIBLE);
            timeDuration = ns2.subtract(ins);
            if (sign != timeDuration.signum()) success = true;
            dayCorrection++;
        }
        TUnit dateLargest = TUnit.larger(largest, TUnit.DAY);
        DateDuration dateDiff = TCal.dateUntil(cal, start.date, intermediate.date, dateLargest);
        return new InternalDuration(dateDiff, timeDuration);
    }

    /** DifferenceZonedDateTimeWithRounding */
    static InternalDuration differenceZonedWithRounding(BigInteger ns1, BigInteger ns2, TimeZone tz, String cal,
                                                         TUnit largest, long inc, TUnit smallest, RMode mode) {
        if (!largest.isDate) return differenceInstant(ns1, ns2, inc, smallest, mode);
        InternalDuration diff = differenceZoned(ns1, ns2, tz, cal, largest);
        if (smallest == TUnit.NANOSECOND && inc == 1L) return diff;
        IsoDateTime dt = TZ.isoDateTimeFor(tz, ns1);
        return roundRelative(diff, ns1, ns2, dt, tz, cal, largest, inc, smallest, mode);
    }

    /** DifferenceZonedDateTimeWithTotal */
    static double differenceZonedWithTotal(BigInteger ns1, BigInteger ns2, TimeZone tz, String cal, TUnit unit) {
        if (!unit.isDate) return totalTime(ns2.subtract(ns1), unit);
        InternalDuration diff = differenceZoned(ns1, ns2, tz, cal, unit);
        IsoDateTime dt = TZ.isoDateTimeFor(tz, ns1);
        return totalRelative(diff, ns1, ns2, dt, tz, cal, unit);
    }

    // ------------------------------------------------------------------ relative rounding

    private static final class Window {
        final long r1;
        final BigInteger startNs;
        final BigInteger endNs;
        final DateDuration startDur;
        final DateDuration endDur;

        Window(long r1, BigInteger startNs, BigInteger endNs, DateDuration startDur, DateDuration endDur) {
            this.r1 = r1;
            this.startNs = startNs;
            this.endNs = endNs;
            this.startDur = startDur;
            this.endDur = endDur;
        }
    }

    /** ComputeNudgeWindow */
    private static Window nudgeWindow(int sign, InternalDuration d, BigInteger originNs, IsoDateTime dt,
                                      TimeZone tz, String cal, long inc, TUnit unit, boolean additionalShift) {
        long r1;
        long r2;
        DateDuration startDD;
        DateDuration endDD;
        switch (unit) {
            case YEAR: {
                long years = TNum.truncToIncrement(d.date.years, inc);
                r1 = !additionalShift ? years : years + inc * sign;
                r2 = r1 + inc * sign;
                startDD = dateDuration(r1, 0, 0, 0);
                endDD = dateDuration(r2, 0, 0, 0);
                break;
            }
            case MONTH: {
                long months = TNum.truncToIncrement(d.date.months, inc);
                r1 = !additionalShift ? months : months + inc * sign;
                r2 = r1 + inc * sign;
                startDD = adjust(d.date, 0, 0, r1);
                endDD = adjust(d.date, 0, 0, r2);
                break;
            }
            case WEEK: {
                DateDuration yearsMonths = adjust(d.date, 0, 0);
                IsoDate weeksStart = TCal.dateAdd(cal, dt.date, yearsMonths, Overflow.CONSTRAIN);
                IsoDate weeksEnd = TM.addDays(weeksStart, d.date.days);
                DateDuration untilResult = TCal.dateUntil(cal, weeksStart, weeksEnd, TUnit.WEEK);
                long weeks = TNum.truncToIncrement(d.date.weeks + untilResult.weeks, inc);
                r1 = weeks;
                r2 = weeks + inc * sign;
                startDD = adjust(d.date, 0, r1);
                endDD = adjust(d.date, 0, r2);
                break;
            }
            default: {
                long days = TNum.truncToIncrement(d.date.days, inc);
                r1 = days;
                r2 = days + inc * sign;
                startDD = adjust(d.date, r1);
                endDD = adjust(d.date, r2);
                break;
            }
        }
        // when the window starts at the origin itself, use the origin's exact epoch nanoseconds
        BigInteger startNs;
        if (startDD.sign() == 0) {
            startNs = originNs;
        } else {
            IsoDate start = TCal.dateAdd(cal, dt.date, startDD, Overflow.CONSTRAIN);
            startNs = epochNsOf(start, dt.time, tz);
        }
        IsoDate end = TCal.dateAdd(cal, dt.date, endDD, Overflow.CONSTRAIN);
        BigInteger endNs = epochNsOf(end, dt.time, tz);
        return new Window(r1, startNs, endNs, startDD, endDD);
    }

    private static boolean between(BigInteger lo, BigInteger x, BigInteger hi) {
        return lo.compareTo(x) <= 0 && x.compareTo(hi) <= 0;
    }

    /** NudgeToCalendarUnit; the total is stored in {@code totalOut[0]} when given. */
    private static NudgeResult nudgeToCalendarUnit(
            int sign, InternalDuration d, BigInteger originNs, BigInteger destNs, IsoDateTime dt, TimeZone tz,
            String cal, long inc, TUnit unit, RMode mode, double[] totalOut) {
        boolean didExpand = false;
        Window w = nudgeWindow(sign, d, originNs, dt, tz, cal, inc, unit, false);
        boolean inside = sign == 1 ? between(w.startNs, destNs, w.endNs) : between(w.endNs, destNs, w.startNs);
        if (!inside) {
            w = nudgeWindow(sign, d, originNs, dt, tz, cal, inc, unit, true);
            didExpand = true;
        }
        BigInteger num = destNs.subtract(w.startNs);
        BigInteger den = w.endNs.subtract(w.startNs);
        if (totalOut != null) {
            // total = r1 + (num / den) * inc * sign
            BigInteger n = BigInteger.valueOf(w.r1).multiply(den)
                    .add(num.multiply(BigInteger.valueOf(inc * sign)));
            totalOut[0] = TNum.divToDouble(n, den);
        }
        boolean up;
        if (num.equals(den)) {
            up = true;
        } else if (num.signum() == 0) {
            up = false;
        } else {
            URM urm = mode.unsigned(sign < 0);
            int cmp = num.abs().shiftLeft(1).compareTo(den.abs());
            if (urm == URM.ZERO) {
                up = false;
            } else if (urm == URM.INFINITY) {
                up = true;
            } else if (cmp < 0) {
                up = false;
            } else if (cmp > 0) {
                up = true;
            } else if (urm == URM.HALF_ZERO) {
                up = false;
            } else if (urm == URM.HALF_INFINITY) {
                up = true;
            } else {
                up = (Math.abs(w.r1) / inc) % 2 != 0L;
            }
        }
        if (up) {
            return new NudgeResult(new InternalDuration(w.endDur, BigInteger.ZERO), w.endNs, true);
        }
        return new NudgeResult(new InternalDuration(w.startDur, BigInteger.ZERO), w.startNs, didExpand);
    }

    /** NudgeToZonedTime */
    private static NudgeResult nudgeToZonedTime(int sign, InternalDuration d, IsoDateTime dt, TimeZone tz,
                                                String cal, long inc, TUnit unit, RMode mode) {
        IsoDate start = TCal.dateAdd(cal, dt.date, d.date, Overflow.CONSTRAIN);
        IsoDate endDate = TM.addDays(start, sign);
        BigInteger startNs = TZ.epochNsFor(tz, new IsoDateTime(start, dt.time), Disambiguation.COMPATIBLE);
        BigInteger endNs = TZ.epochNsFor(tz, new IsoDateTime(endDate, dt.time), Disambiguation.COMPATIBLE);
        BigInteger daySpan = endNs.subtract(startNs);
        BigInteger incNs = BigInteger.valueOf(unit.nanos).multiply(BigInteger.valueOf(inc));
        BigInteger rounded = roundTimeToIncrement(d.time, incNs, mode);
        BigInteger beyond = rounded.subtract(daySpan);
        boolean didRoundBeyondDay;
        long dayDelta;
        BigInteger nudged;
        if (beyond.signum() != -sign) {
            didRoundBeyondDay = true;
            dayDelta = sign;
            rounded = roundTimeToIncrement(beyond, incNs, mode);
            nudged = endNs.add(rounded);
        } else {
            didRoundBeyondDay = false;
            dayDelta = 0;
            nudged = startNs.add(rounded);
        }
        DateDuration dd = adjust(d.date, d.date.days + dayDelta);
        return new NudgeResult(new InternalDuration(dd, rounded), nudged, didRoundBeyondDay);
    }

    /** NudgeToDayOrTime */
    private static NudgeResult nudgeToDayOrTime(InternalDuration d, BigInteger destNs, TUnit largest, long inc,
                                                TUnit smallest, RMode mode) {
        BigInteger time = add24HourDays(d.time, d.date.days);
        BigInteger incNs = BigInteger.valueOf(smallest.nanos).multiply(BigInteger.valueOf(inc));
        BigInteger roundedTime = roundTimeToIncrement(time, incNs, mode);
        BigInteger diffTime = roundedTime.subtract(time);
        BigInteger wholeDays = time.divide(TM.BI_NS_PER_DAY);
        BigInteger roundedWholeDays = roundedTime.divide(TM.BI_NS_PER_DAY);
        BigInteger dayDelta = roundedWholeDays.subtract(wholeDays);
        boolean didExpandDays = dayDelta.signum() == time.signum();
        BigInteger nudged = destNs.add(diffTime);
        long days = 0L;
        BigInteger remainder = roundedTime;
        if (largest.isDate) {
            days = roundedWholeDays.longValue();
            remainder = roundedTime.subtract(roundedWholeDays.multiply(TM.BI_NS_PER_DAY));
        }
        DateDuration dd = adjust(d.date, days);
        return new NudgeResult(new InternalDuration(dd, remainder), nudged, didExpandDays);
    }

    /** BubbleRelativeDuration */
    private static InternalDuration bubble(int sign, InternalDuration d0, BigInteger nudgedNs, IsoDateTime dt,
                                           TimeZone tz, String cal, TUnit largest, TUnit smallest) {
        InternalDuration d = d0;
        if (smallest == largest) return d;
        int idx = smallest.ordinal() - 1;
        while (idx >= largest.ordinal()) {
            TUnit unit = TUnit.ALL[idx];
            if (unit != TUnit.WEEK || largest == TUnit.WEEK) {
                DateDuration endDD;
                switch (unit) {
                    case YEAR:
                        endDD = dateDuration(d.date.years + sign, 0, 0, 0);
                        break;
                    case MONTH:
                        endDD = adjust(d.date, 0, 0, d.date.months + sign);
                        break;
                    default:
                        endDD = adjust(d.date, 0, d.date.weeks + sign);
                        break;
                }
                IsoDate end = TCal.dateAdd(cal, dt.date, endDD, Overflow.CONSTRAIN);
                BigInteger endNs = epochNsOf(end, dt.time, tz);
                int beyondSign = nudgedNs.subtract(endNs).signum();
                if (beyondSign != -sign) {
                    d = new InternalDuration(endDD, BigInteger.ZERO);
                } else {
                    break;
                }
            }
            idx--;
        }
        return d;
    }

    /** RoundRelativeDuration */
    static InternalDuration roundRelative(
            InternalDuration d, BigInteger originNs, BigInteger destNs, IsoDateTime dt, TimeZone tz, String cal,
            TUnit largest, long inc, TUnit smallest, RMode mode) {
        boolean irregular = smallest.isCalendar() || (tz != null && smallest == TUnit.DAY);
        int sign = d.sign() < 0 ? -1 : 1;
        NudgeResult nudge;
        if (irregular) {
            nudge = nudgeToCalendarUnit(sign, d, originNs, destNs, dt, tz, cal, inc, smallest, mode, null);
        } else if (tz != null) {
            nudge = nudgeToZonedTime(sign, d, dt, tz, cal, inc, smallest, mode);
        } else {
            nudge = nudgeToDayOrTime(d, destNs, largest, inc, smallest, mode);
        }
        InternalDuration result = nudge.duration;
        if (nudge.didExpand && smallest != TUnit.WEEK) {
            TUnit startUnit = TUnit.larger(smallest, TUnit.DAY);
            result = bubble(sign, result, nudge.nudgedNs, dt, tz, cal, largest, startUnit);
        }
        return result;
    }

    /** TotalRelativeDuration */
    static double totalRelative(InternalDuration d, BigInteger originNs, BigInteger destNs, IsoDateTime dt,
                                TimeZone tz, String cal, TUnit unit) {
        if (unit.isCalendar() || (tz != null && unit == TUnit.DAY)) {
            int sign = d.sign() < 0 ? -1 : 1;
            double[] out = new double[1];
            nudgeToCalendarUnit(sign, d, originNs, destNs, dt, tz, cal, 1, unit, RMode.TRUNC, out);
            return out[0];
        }
        BigInteger time = add24HourDays(d.time, d.date.days);
        return totalTime(time, unit);
    }
}
