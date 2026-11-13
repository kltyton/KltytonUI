/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.BuiltinsKt;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;

/** Temporal.Duration constructor, prototype and methods. */
final class DurationBuiltins {
    public static final DurationBuiltins INSTANCE = new DurationBuiltins();

    private DurationBuiltins() {
    }

    private static JSTemporalDuration thisDuration(Object t, String name) {
        if (t instanceof JSTemporalDuration duration) return duration;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.Duration.prototype." + name + " called on incompatible receiver");
    }

    public static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put(TCreate.P_DURATION, proto);
        var ctor = BuiltinsKt.makeCtor(realm, "Duration", 0, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw TemporalAbstractOps.tTypeErr("Constructor Temporal.Duration requires 'new'");
            }
            double[] f = new double[10];
            for (int i = 0; i < 10; i++) {
                Object v = Values.arg(args, i);
                f[i] = v == Undefined.INSTANCE ? 0.0 : TemporalAbstractOps.toIntegerIfIntegral(v);
            }
            return TCreate.duration(f, newTarget);
        });
        temporal.defineOwn("Duration", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1,
                (function, thisArg, args, newTarget) -> TDur.toDuration(Values.arg(args, 0)));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) ->
                compare(Values.arg(args, 0), Values.arg(args, 1), Values.arg(args, 2)));
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.Duration", Attr.CONFIGURABLE);
        installGetters(realm, proto);
        installMethods(realm, proto);
    }

    private static void installGetters(Realm realm, JSObject proto) {
        BuiltinsKt.getter(proto, realm, "years",
                (function, t, arguments, newTarget) -> thisDuration(t, "years").years);
        BuiltinsKt.getter(proto, realm, "months",
                (function, t, arguments, newTarget) -> thisDuration(t, "months").months);
        BuiltinsKt.getter(proto, realm, "weeks",
                (function, t, arguments, newTarget) -> thisDuration(t, "weeks").weeks);
        BuiltinsKt.getter(proto, realm, "days",
                (function, t, arguments, newTarget) -> thisDuration(t, "days").days);
        BuiltinsKt.getter(proto, realm, "hours",
                (function, t, arguments, newTarget) -> thisDuration(t, "hours").hours);
        BuiltinsKt.getter(proto, realm, "minutes",
                (function, t, arguments, newTarget) -> thisDuration(t, "minutes").minutes);
        BuiltinsKt.getter(proto, realm, "seconds",
                (function, t, arguments, newTarget) -> thisDuration(t, "seconds").seconds);
        BuiltinsKt.getter(proto, realm, "milliseconds",
                (function, t, arguments, newTarget) -> thisDuration(t, "milliseconds").milliseconds);
        BuiltinsKt.getter(proto, realm, "microseconds",
                (function, t, arguments, newTarget) -> thisDuration(t, "microseconds").microseconds);
        BuiltinsKt.getter(proto, realm, "nanoseconds",
                (function, t, arguments, newTarget) -> thisDuration(t, "nanoseconds").nanoseconds);
        BuiltinsKt.getter(proto, realm, "sign", (function, t, arguments, newTarget) ->
                Double.valueOf((double) TDur.sign(thisDuration(t, "sign"))));
        BuiltinsKt.getter(proto, realm, "blank", (function, t, arguments, newTarget) ->
                TDur.sign(thisDuration(t, "blank")) == 0);
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) -> {
            JSTemporalDuration d = thisDuration(t, "with");
            double[] p = TDur.toPartial(Values.arg(args, 0));
            double[] cur = {d.years, d.months, d.weeks, d.days, d.hours, d.minutes, d.seconds,
                    d.milliseconds, d.microseconds, d.nanoseconds};
            for (int k = 0; k < 10; k++) {
                if (!Double.isNaN(p[k])) cur[k] = p[k];
            }
            return TCreate.duration(cur);
        });
        BuiltinsKt.method(proto, realm, "negated", 0,
                (function, t, args, newTarget) -> TDur.negated(thisDuration(t, "negated")));
        BuiltinsKt.method(proto, realm, "abs", 0, (function, t, args, newTarget) -> {
            JSTemporalDuration d = thisDuration(t, "abs");
            return TCreate.duration(
                    Math.abs(d.years), Math.abs(d.months), Math.abs(d.weeks), Math.abs(d.days),
                    Math.abs(d.hours), Math.abs(d.minutes), Math.abs(d.seconds),
                    Math.abs(d.milliseconds), Math.abs(d.microseconds), Math.abs(d.nanoseconds));
        });
        BuiltinsKt.method(proto, realm, "add", 1, (function, t, args, newTarget) ->
                TDur.addDurations(false, thisDuration(t, "add"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                TDur.addDurations(true, thisDuration(t, "subtract"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "round", 1,
                (function, t, args, newTarget) -> round(thisDuration(t, "round"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "total", 1,
                (function, t, args, newTarget) -> total(thisDuration(t, "total"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "toString", 0,
                (function, t, args, newTarget) -> toStringImpl(thisDuration(t, "toString"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) ->
                TFormat.durationToString(thisDuration(t, "toJSON"), Precision.AUTO));
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) ->
                TFormat.durationToString(thisDuration(t, "toLocaleString"), Precision.AUTO));
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisArg, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.Duration.compare() or toString() instead of valueOf()");
        });
    }

    private static double compare(Object a, Object b, Object options) {
        JSTemporalDuration one = TDur.toDuration(a);
        JSTemporalDuration two = TDur.toDuration(b);
        JSObject o = TOpt.optionsObject(options);
        JSObject rel = TConv.relativeTo(o);
        if (one.years == two.years && one.months == two.months && one.weeks == two.weeks
                && one.days == two.days && one.hours == two.hours && one.minutes == two.minutes
                && one.seconds == two.seconds && one.milliseconds == two.milliseconds
                && one.microseconds == two.microseconds && one.nanoseconds == two.nanoseconds) {
            return 0.0;
        }
        JSTemporalZonedDateTime zoned = rel instanceof JSTemporalZonedDateTime z ? z : null;
        JSTemporalPlainDate plain = rel instanceof JSTemporalPlainDate p ? p : null;
        TUnit l1 = TDur.defaultLargestUnit(one);
        TUnit l2 = TDur.defaultLargestUnit(two);
        InternalDuration d1 = TDur.toInternal(one);
        InternalDuration d2 = TDur.toInternal(two);
        if (zoned != null && (l1.isDate || l2.isDate)) {
            BigInteger after1 = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, d1,
                    Overflow.CONSTRAIN);
            BigInteger after2 = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, d2,
                    Overflow.CONSTRAIN);
            return (double) Math.max(-1, Math.min(1, after1.compareTo(after2)));
        }
        long days1;
        long days2;
        if (l1.isCalendar() || l2.isCalendar()) {
            if (plain == null) {
                throw TemporalAbstractOps.tRangeErr(
                        "a relativeTo date is required to compare durations with calendar units");
            }
            days1 = TDur.dateDurationDays(d1.date, plain);
            days2 = TDur.dateDurationDays(d2.date, plain);
        } else {
            days1 = (long) one.days;
            days2 = (long) two.days;
        }
        BigInteger t1 = TDur.add24HourDays(d1.time, days1);
        BigInteger t2 = TDur.add24HourDays(d2.time, days2);
        return (double) Math.max(-1, Math.min(1, t1.compareTo(t2)));
    }

    private static JSObject optionsFromString(Object v, String key) {
        if (v == Undefined.INSTANCE) {
            throw TemporalAbstractOps.tTypeErr("options argument is required");
        }
        if (v instanceof CharSequence text) {
            JSObject o = new JSObject(null);
            o.createDataPropertyOrThrow(key, text.toString());
            return o;
        }
        return TOpt.optionsObject(v);
    }

    private static JSTemporalDuration round(JSTemporalDuration d, Object roundToV) {
        JSObject roundTo = optionsFromString(roundToV, "smallestUnit");
        boolean smallestPresent = true;
        boolean largestPresent = true;
        int largestOpt = TOpt.unit(roundTo, "largestUnit", false);
        JSObject rel = TConv.relativeTo(roundTo);
        JSTemporalZonedDateTime zoned = rel instanceof JSTemporalZonedDateTime z ? z : null;
        JSTemporalPlainDate plain = rel instanceof JSTemporalPlainDate p ? p : null;
        long inc = TOpt.roundingIncrement(roundTo);
        RMode mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND);
        int smallestOpt = TOpt.unit(roundTo, "smallestUnit", false);
        TOpt.validateUnit(smallestOpt, 2);
        if (smallestOpt == TOpt.UNIT_UNSET) {
            smallestPresent = false;
            smallestOpt = TUnit.NANOSECOND.ordinal();
        }
        TUnit smallest = TUnit.ALL[smallestOpt];
        TUnit existingLargest = TDur.defaultLargestUnit(d);
        TUnit defaultLargest = TUnit.larger(existingLargest, smallest);
        TUnit largest;
        if (largestOpt == TOpt.UNIT_UNSET) {
            largestPresent = false;
            largest = defaultLargest;
        } else if (largestOpt == TOpt.UNIT_AUTO) {
            largest = defaultLargest;
        } else {
            largest = TUnit.ALL[largestOpt];
        }
        if (!smallestPresent && !largestPresent) {
            throw TemporalAbstractOps.tRangeErr(
                    "at least one of smallestUnit or largestUnit is required");
        }
        if (TUnit.larger(largest, smallest) != largest) {
            throw TemporalAbstractOps.tRangeErr("largestUnit must be larger than smallestUnit");
        }
        if (smallest.maxIncrement != 0) {
            TOpt.validateIncrement(inc, (long) smallest.maxIncrement, false);
        }
        if (inc > 1 && largest != smallest && smallest.isDate) {
            throw TemporalAbstractOps.tRangeErr("roundingIncrement must be 1 for this rounding");
        }
        if (zoned != null) {
            InternalDuration id = TDur.toInternal(d);
            BigInteger target = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, id,
                    Overflow.CONSTRAIN);
            InternalDuration r = TDur.differenceZonedWithRounding(zoned.epochNs, target, zoned.timeZone,
                    zoned.calendar, largest, inc, smallest, mode);
            if (largest.isDate) largest = TUnit.HOUR;
            return TDur.fromInternal(r, largest);
        }
        if (plain != null) {
            InternalDuration id = TDur.toInternal24(d);
            TimeRec targetTime = TM.addTime(TimeRec.MIDNIGHT, id.time);
            DateDuration dd = TDur.adjust(id.date, targetTime.days);
            IsoDate targetDate = TCal.dateAdd(plain.calendar, plain.date, dd, Overflow.CONSTRAIN);
            InternalDuration r = TDur.differencePlainDateTimeWithRounding(
                    new IsoDateTime(plain.date, TimeRec.MIDNIGHT),
                    new IsoDateTime(targetDate, targetTime), plain.calendar, largest, inc, smallest, mode);
            return TDur.fromInternal(r, largest);
        }
        if (existingLargest.isCalendar() || largest.isCalendar()) {
            throw TemporalAbstractOps.tRangeErr(
                    "a relativeTo date is required to round durations with calendar units");
        }
        InternalDuration id = TDur.toInternal24(d);
        InternalDuration r;
        if (smallest == TUnit.DAY) {
            BigInteger inc2 = TM.BI_NS_PER_DAY.multiply(BigInteger.valueOf(inc));
            BigInteger days = TNum.roundToIncrement(id.time, inc2, mode).divide(TM.BI_NS_PER_DAY);
            if (days.abs().compareTo(BigInteger.valueOf(Long.MAX_VALUE / 2)) > 0) {
                throw TemporalAbstractOps.tRangeErr("duration out of range");
            }
            r = new InternalDuration(TDur.dateDuration(0, 0, 0, days.longValue()), BigInteger.ZERO);
        } else {
            r = new InternalDuration(DateDuration.ZERO, TDur.roundTime(id.time, inc, smallest, mode));
        }
        return TDur.fromInternal(r, largest);
    }

    private static double total(JSTemporalDuration d, Object totalOfV) {
        JSObject totalOf = optionsFromString(totalOfV, "unit");
        JSObject rel = TConv.relativeTo(totalOf);
        JSTemporalZonedDateTime zoned = rel instanceof JSTemporalZonedDateTime z ? z : null;
        JSTemporalPlainDate plain = rel instanceof JSTemporalPlainDate p ? p : null;
        int unitOpt = TOpt.unit(totalOf, "unit", true);
        TOpt.validateUnit(unitOpt, 2);
        TUnit unit = TUnit.ALL[unitOpt];
        if (zoned != null) {
            InternalDuration id = TDur.toInternal(d);
            BigInteger target = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, id,
                    Overflow.CONSTRAIN);
            return TDur.differenceZonedWithTotal(zoned.epochNs, target, zoned.timeZone, zoned.calendar, unit);
        }
        if (plain != null) {
            InternalDuration id = TDur.toInternal24(d);
            TimeRec targetTime = TM.addTime(TimeRec.MIDNIGHT, id.time);
            DateDuration dd = TDur.adjust(id.date, targetTime.days);
            IsoDate targetDate = TCal.dateAdd(plain.calendar, plain.date, dd, Overflow.CONSTRAIN);
            return TDur.differencePlainDateTimeWithTotal(
                    new IsoDateTime(plain.date, TimeRec.MIDNIGHT),
                    new IsoDateTime(targetDate, targetTime), plain.calendar, unit);
        }
        TUnit largest = TDur.defaultLargestUnit(d);
        if (largest.isCalendar() || unit.isCalendar()) {
            throw TemporalAbstractOps.tRangeErr(
                    "a relativeTo date is required for calendar units");
        }
        InternalDuration id = TDur.toInternal24(d);
        return TDur.totalTime(id.time, unit);
    }

    private static String toStringImpl(JSTemporalDuration d, Object options) {
        JSObject o = TOpt.optionsObject(options);
        int digits = TOpt.fractionalSecondDigits(o);
        RMode mode = TOpt.roundingMode(o, RMode.TRUNC);
        int smallest = TOpt.unit(o, "smallestUnit", false);
        TOpt.validateUnit(smallest, 1);
        if (smallest == TUnit.HOUR.ordinal() || smallest == TUnit.MINUTE.ordinal()) {
            throw TemporalAbstractOps.tRangeErr("smallestUnit must be seconds or smaller");
        }
        Precision p = Precision.of(smallest, digits);
        if (p.unit == TUnit.NANOSECOND && p.increment == 1L) {
            return TFormat.durationToString(d, p.precision);
        }
        TUnit largest = TDur.defaultLargestUnit(d);
        InternalDuration id = TDur.toInternal(d);
        BigInteger t = TDur.roundTime(id.time, p.increment, p.unit, mode);
        JSTemporalDuration rd = TDur.fromInternal(new InternalDuration(id.date, t),
                TUnit.larger(largest, TUnit.SECOND));
        return TFormat.durationToString(rd, p.precision);
    }
}
