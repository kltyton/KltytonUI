/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.BuiltinsKt;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;

/** Temporal.ZonedDateTime constructor, prototype and methods. */
final class ZonedDateTimeBuiltins {
    public static final ZonedDateTimeBuiltins INSTANCE = new ZonedDateTimeBuiltins();

    private ZonedDateTimeBuiltins() {
    }

    private static JSTemporalZonedDateTime thisZ(Object t, String name) {
        if (t instanceof JSTemporalZonedDateTime zoned) return zoned;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.ZonedDateTime.prototype." + name + " called on incompatible receiver");
    }

    private static IsoDateTime dtOf(JSTemporalZonedDateTime z) {
        return TZ.isoDateTimeFor(z.timeZone, z.epochNs);
    }

    public static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put(TCreate.P_ZONED, proto);
        var ctor = BuiltinsKt.makeCtor(realm, "ZonedDateTime", 2, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw TemporalAbstractOps.tTypeErr("Constructor Temporal.ZonedDateTime requires 'new'");
            }
            BigInteger ns = Ops.toBigInt(Values.arg(args, 0));
            if (!TM.isValidEpochNs(ns)) {
                throw TemporalAbstractOps.tRangeErr("epoch nanoseconds outside of supported range");
            }
            Object tzV = Values.arg(args, 1);
            if (!(tzV instanceof CharSequence text)) {
                throw TemporalAbstractOps.tTypeErr("time zone must be a string");
            }
            TimeZone tz = TZ.fromIdentifier(text.toString());
            String cal = TCal.ctorCalendar(Values.arg(args, 2));
            return TCreate.zoned(ns, tz, cal, newTarget);
        });
        temporal.defineOwn("ZonedDateTime", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1, (function, thisArg, args, newTarget) ->
                TConv.toZoned(Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) -> {
            JSTemporalZonedDateTime a = TConv.toZoned(Values.arg(args, 0));
            JSTemporalZonedDateTime b = TConv.toZoned(Values.arg(args, 1));
            return (double) Math.max(-1, Math.min(1, a.epochNs.compareTo(b.epochNs)));
        });
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.ZonedDateTime", Attr.CONFIGURABLE);
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_DATE,
                (t, n) -> thisZ(t, n).calendar,
                (t, n) -> dtOf(thisZ(t, n)).date);
        installGetters(realm, proto);
        installMethods(realm, proto);
        installConversions(realm, proto);
    }

    private static void installGetters(Realm realm, JSObject proto) {
        BuiltinsKt.getter(proto, realm, "timeZoneId",
                (function, t, args, newTarget) -> thisZ(t, "timeZoneId").timeZone.id);
        BuiltinsKt.getter(proto, realm, "hour", (function, t, args, newTarget) ->
                (double) dtOf(thisZ(t, "hour")).time.hour);
        BuiltinsKt.getter(proto, realm, "minute", (function, t, args, newTarget) ->
                (double) dtOf(thisZ(t, "minute")).time.minute);
        BuiltinsKt.getter(proto, realm, "second", (function, t, args, newTarget) ->
                (double) dtOf(thisZ(t, "second")).time.second);
        BuiltinsKt.getter(proto, realm, "millisecond", (function, t, args, newTarget) ->
                (double) dtOf(thisZ(t, "millisecond")).time.millisecond);
        BuiltinsKt.getter(proto, realm, "microsecond", (function, t, args, newTarget) ->
                (double) dtOf(thisZ(t, "microsecond")).time.microsecond);
        BuiltinsKt.getter(proto, realm, "nanosecond", (function, t, args, newTarget) ->
                (double) dtOf(thisZ(t, "nanosecond")).time.nanosecond);
        BuiltinsKt.getter(proto, realm, "epochMilliseconds", (function, t, args, newTarget) ->
                InstantBuiltins.floorDiv(thisZ(t, "epochMilliseconds").epochNs, TNum.BI_1E6).doubleValue());
        BuiltinsKt.getter(proto, realm, "epochNanoseconds",
                (function, t, args, newTarget) -> thisZ(t, "epochNanoseconds").epochNs);
        BuiltinsKt.getter(proto, realm, "hoursInDay", (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "hoursInDay");
            IsoDate today = dtOf(z).date;
            IsoDate tomorrow = TM.addDays(today, 1);
            BigInteger todayNs = TZ.startOfDay(z.timeZone, today);
            BigInteger tomorrowNs = TZ.startOfDay(z.timeZone, tomorrow);
            return TDur.totalTime(tomorrowNs.subtract(todayNs), TUnit.HOUR);
        });
        BuiltinsKt.getter(proto, realm, "offsetNanoseconds", (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "offsetNanoseconds");
            return (double) TZ.offsetNs(z.timeZone, z.epochNs);
        });
        BuiltinsKt.getter(proto, realm, "offset", (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "offset");
            return TFormat.formatUtcOffsetNs(TZ.offsetNs(z.timeZone, z.epochNs));
        });
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) ->
                with(thisZ(t, "with"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "withPlainTime", 0, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "withPlainTime");
            IsoDateTime dt = dtOf(z);
            Object v = Values.arg(args, 0);
            BigInteger ns;
            if (v == Undefined.INSTANCE) {
                ns = TZ.startOfDay(z.timeZone, dt.date);
            } else {
                TimeRec time = TConv.toTime(v);
                ns = TZ.epochNsFor(z.timeZone, new IsoDateTime(dt.date, time), Disambiguation.COMPATIBLE);
            }
            return TCreate.zoned(ns, z.timeZone, z.calendar);
        });
        BuiltinsKt.method(proto, realm, "withTimeZone", 1, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "withTimeZone");
            return TCreate.zoned(z.epochNs, TZ.toTimeZone(Values.arg(args, 0)), z.calendar);
        });
        BuiltinsKt.method(proto, realm, "withCalendar", 1, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "withCalendar");
            return TCreate.zoned(z.epochNs, z.timeZone, TCal.toCalendar(Values.arg(args, 0)));
        });
        BuiltinsKt.method(proto, realm, "add", 1, (function, t, args, newTarget) ->
                addDuration(false, thisZ(t, "add"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                addDuration(true, thisZ(t, "subtract"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "until", 1, (function, t, args, newTarget) ->
                difference(false, thisZ(t, "until"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "since", 1, (function, t, args, newTarget) ->
                difference(true, thisZ(t, "since"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "round", 1,
                (function, t, args, newTarget) -> round(thisZ(t, "round"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "equals");
            JSTemporalZonedDateTime other = TConv.toZoned(Values.arg(args, 0));
            return z.epochNs.equals(other.epochNs) && TZ.equals(z.timeZone, other.timeZone)
                    && z.calendar.equals(other.calendar);
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) ->
                toStringImpl(thisZ(t, "toString"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) ->
                TFormat.zonedToString(thisZ(t, "toLocaleString"), Precision.AUTO, ShowCalendar.AUTO, 0, true));
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) ->
                TFormat.zonedToString(thisZ(t, "toJSON"), Precision.AUTO, ShowCalendar.AUTO, 0, true));
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisArg, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.ZonedDateTime.compare() or equals() instead of valueOf()");
        });
        BuiltinsKt.method(proto, realm, "startOfDay", 0, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "startOfDay");
            return TCreate.zoned(TZ.startOfDay(z.timeZone, dtOf(z).date), z.timeZone, z.calendar);
        });
        BuiltinsKt.method(proto, realm, "getTimeZoneTransition", 1, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "getTimeZoneTransition");
            Object v = Values.arg(args, 0);
            if (v == Undefined.INSTANCE) {
                throw TemporalAbstractOps.tTypeErr("direction argument is required");
            }
            JSObject o;
            if (v instanceof CharSequence text) {
                o = new JSObject(null);
                o.createDataPropertyOrThrow("direction", text.toString());
            } else {
                o = TOpt.optionsObject(v);
            }
            boolean next = TOpt.directionNext(o);
            BigInteger ns = TZ.transition(z.timeZone, z.epochNs, next);
            return ns == null ? Null.INSTANCE : TCreate.zoned(ns, z.timeZone, z.calendar);
        });
    }

    private static void installConversions(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "toInstant", 0,
                (function, t, args, newTarget) -> TCreate.instant(thisZ(t, "toInstant").epochNs));
        BuiltinsKt.method(proto, realm, "toPlainDate", 0, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "toPlainDate");
            return TCreate.plainDate(dtOf(z).date, z.calendar);
        });
        BuiltinsKt.method(proto, realm, "toPlainTime", 0, (function, t, args, newTarget) ->
                TCreate.plainTime(dtOf(thisZ(t, "toPlainTime")).time));
        BuiltinsKt.method(proto, realm, "toPlainDateTime", 0, (function, t, args, newTarget) -> {
            JSTemporalZonedDateTime z = thisZ(t, "toPlainDateTime");
            return TCreate.plainDateTime(dtOf(z), z.calendar);
        });
    }

    private static JSTemporalZonedDateTime with(JSTemporalZonedDateTime z, Object like, Object options) {
        if (!TConv.isPartialTemporalObject(like)) {
            throw TemporalAbstractOps.tTypeErr("argument must be a partial date-time object");
        }
        TimeZone tz = z.timeZone;
        long offsetNs = TZ.offsetNs(tz, z.epochNs);
        IsoDateTime dt = dtOf(z);
        String cal = z.calendar;
        CalFields fields = TCal.isoDateToFields(cal, dt.date, TCal.TYPE_DATE);
        fields.setTime(dt.time);
        fields.offset = TFormat.formatUtcOffsetNs(offsetNs);
        CalFields partial = TCal.prepareFields(cal, (JSObject) like,
                TCal.DATE_FIELDS | TCal.TIME_FIELDS | TCal.F_OFFSET, TCal.PARTIAL);
        fields = TCal.mergeFields(cal, fields, partial);
        JSObject o = TOpt.optionsObject(options);
        Disambiguation disambiguation = TOpt.disambiguation(o);
        OffsetOption offsetOption = TOpt.offset(o, OffsetOption.PREFER);
        Overflow overflow = TOpt.overflow(o);
        IsoDateTime r = TConv.interpretDateTimeFields(cal, fields, overflow);
        long newOffsetNs = TemporalParser.parseUtcOffset(fields.offset);
        BigInteger ns = TConv.interpretOffset(r.date, r.time, TConv.OFFSET_OPTION, newOffsetNs, tz,
                disambiguation, offsetOption, false);
        return TCreate.zoned(ns, tz, z.calendar);
    }

    private static JSTemporalZonedDateTime addDuration(boolean subtract, JSTemporalZonedDateTime z,
                                                       Object like, Object options) {
        JSTemporalDuration d = TDur.toDuration(like);
        if (subtract) d = TDur.negated(d);
        Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
        InternalDuration id = TDur.toInternal(d);
        BigInteger ns = TDur.addZoned(z.epochNs, z.timeZone, z.calendar, id, overflow);
        return TCreate.zoned(ns, z.timeZone, z.calendar);
    }

    private static JSTemporalDuration difference(boolean since, JSTemporalZonedDateTime z, Object otherV,
                                                 Object options) {
        JSTemporalZonedDateTime other = TConv.toZoned(otherV);
        if (!z.calendar.equals(other.calendar)) {
            throw TemporalAbstractOps.tRangeErr(
                    "cannot compute the difference between date-times of different calendars");
        }
        JSObject o = TOpt.optionsObject(options);
        DiffSettings s = TDur.differenceSettings(since, o, 2, new TUnit[0], TUnit.NANOSECOND, TUnit.HOUR);
        if (!s.largest.isDate) {
            InternalDuration id = TDur.differenceInstant(z.epochNs, other.epochNs, s.increment, s.smallest,
                    s.mode);
            JSTemporalDuration r = TDur.fromInternal(id, s.largest);
            return since ? TDur.negated(r) : r;
        }
        if (!TZ.equals(z.timeZone, other.timeZone)) {
            throw TemporalAbstractOps.tRangeErr(
                    "cannot compute day-based differences between different time zones");
        }
        if (z.epochNs.equals(other.epochNs)) return TCreate.duration(new double[10]);
        InternalDuration id = TDur.differenceZonedWithRounding(z.epochNs, other.epochNs, z.timeZone,
                z.calendar, s.largest, s.increment, s.smallest, s.mode);
        JSTemporalDuration r = TDur.fromInternal(id, TUnit.HOUR);
        return since ? TDur.negated(r) : r;
    }

    private static JSTemporalZonedDateTime round(JSTemporalZonedDateTime z, Object roundToV) {
        if (roundToV == Undefined.INSTANCE) {
            throw TemporalAbstractOps.tTypeErr("options argument is required");
        }
        JSObject roundTo;
        if (roundToV instanceof CharSequence text) {
            roundTo = new JSObject(null);
            roundTo.createDataPropertyOrThrow("smallestUnit", text.toString());
        } else {
            roundTo = TOpt.optionsObject(roundToV);
        }
        long inc = TOpt.roundingIncrement(roundTo);
        RMode mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND);
        int smallestOpt = TOpt.unit(roundTo, "smallestUnit", true);
        TOpt.validateUnit(smallestOpt, 1, new int[]{TUnit.DAY.ordinal()});
        TUnit smallest = TUnit.ALL[smallestOpt];
        if (smallest == TUnit.DAY) {
            TOpt.validateIncrement(inc, 1, true);
        } else {
            TOpt.validateIncrement(inc, (long) smallest.maxIncrement, false);
        }
        if (smallest == TUnit.NANOSECOND && inc == 1L) {
            return TCreate.zoned(z.epochNs, z.timeZone, z.calendar);
        }
        TimeZone tz = z.timeZone;
        BigInteger thisNs = z.epochNs;
        IsoDateTime dt = dtOf(z);
        BigInteger ns;
        if (smallest == TUnit.DAY) {
            IsoDate dateStart = dt.date;
            IsoDate dateEnd = TM.addDays(dateStart, 1);
            BigInteger startNs = TZ.startOfDay(tz, dateStart);
            BigInteger endNs = TZ.startOfDay(tz, dateEnd);
            BigInteger dayLength = endNs.subtract(startNs);
            BigInteger progress = thisNs.subtract(startNs);
            // a backward transition across midnight can put a wall-clock time of this date after the start
            // of the next one (proposal-temporal#3312): such times round down to this date and up/nearest
            // to the next
            if (progress.compareTo(dayLength) >= 0) progress = dayLength.subtract(BigInteger.ONE);
            BigInteger rounded = TNum.roundToIncrement(progress, dayLength, mode);
            ns = startNs.add(rounded);
        } else {
            IsoDateTime r = TM.roundDateTime(dt, inc, smallest, mode);
            long offsetNs = TZ.offsetNs(tz, thisNs);
            ns = TConv.interpretOffset(r.date, r.time, TConv.OFFSET_OPTION, offsetNs, tz,
                    Disambiguation.COMPATIBLE, OffsetOption.PREFER, false);
        }
        if (!TM.isValidEpochNs(ns)) {
            throw TemporalAbstractOps.tRangeErr("result outside of supported range");
        }
        return TCreate.zoned(ns, tz, z.calendar);
    }

    private static String toStringImpl(JSTemporalZonedDateTime z, Object options) {
        JSObject o = TOpt.optionsObject(options);
        ShowCalendar showCalendar = TOpt.showCalendar(o);
        int digits = TOpt.fractionalSecondDigits(o);
        boolean showOffset = TOpt.showOffset(o);
        RMode mode = TOpt.roundingMode(o, RMode.TRUNC);
        int smallest = TOpt.unit(o, "smallestUnit", false);
        int showTimeZone = TOpt.showTimeZone(o);
        TOpt.validateUnit(smallest, 1);
        if (smallest == TUnit.HOUR.ordinal()) {
            throw TemporalAbstractOps.tRangeErr("smallestUnit must not be hour");
        }
        Precision p = Precision.of(smallest, digits);
        return TFormat.zonedToString(z, p.precision, showCalendar, showTimeZone, showOffset, p.increment,
                p.unit, mode);
    }
}
