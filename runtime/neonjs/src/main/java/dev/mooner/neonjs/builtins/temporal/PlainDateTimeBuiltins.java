/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.BuiltinsKt;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;

/** Temporal.PlainDateTime builtins. */
final class PlainDateTimeBuiltins {
    public static final PlainDateTimeBuiltins INSTANCE = new PlainDateTimeBuiltins();

    private PlainDateTimeBuiltins() {
    }

    private static JSTemporalPlainDateTime thisDT(Object t, String name) {
        if (t instanceof JSTemporalPlainDateTime) return (JSTemporalPlainDateTime) t;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.PlainDateTime.prototype." + name + " called on incompatible receiver");
    }

    static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put(TCreate.P_DATETIME, proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "PlainDateTime", 3, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        throw TemporalAbstractOps.tTypeErr("Constructor Temporal.PlainDateTime requires 'new'");
                    }
                    double y = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 0));
                    double m = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 1));
                    double d = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 2));
                    double[] f = new double[6];
                    for (int i = 0; i < 6; i++) {
                        Object v = Values.arg(args, 3 + i);
                        f[i] = v == Undefined.INSTANCE ? 0.0 : TemporalAbstractOps.toIntegerWithTruncation(v);
                    }
                    String cal = TCal.ctorCalendar(Values.arg(args, 9));
                    if (!TM.isValidIsoDate(y, m, d)) throw TemporalAbstractOps.tRangeErr("invalid date");
                    IsoDate date = TM.makeDate(y, (int) m, (int) d);
                    if (!TConv.isValidTime(f[0], f[1], f[2], f[3], f[4], f[5])) {
                        throw TemporalAbstractOps.tRangeErr("invalid time");
                    }
                    TimeRec time = new TimeRec((int) f[0], (int) f[1], (int) f[2], (int) f[3], (int) f[4],
                            (int) f[5]);
                    return TCreate.plainDateTime(new IsoDateTime(date, time), cal, newTarget);
                });
        temporal.defineOwn("PlainDateTime", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1, (function, thisArg, args, newTarget) ->
                TConv.toDateTime(Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) -> {
            JSTemporalPlainDateTime a = TConv.toDateTime(Values.arg(args, 0));
            JSTemporalPlainDateTime b = TConv.toDateTime(Values.arg(args, 1));
            return (double) TM.compareDateTime(a.dt, b.dt);
        });
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainDateTime", Attr.CONFIGURABLE);
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_DATE,
                (t, n) -> thisDT(t, n).calendar,
                (t, n) -> thisDT(t, n).dt.date);
        BuiltinsKt.getter(proto, realm, "hour",
                (function, t, args, newTarget) -> (double) thisDT(t, "hour").dt.time.hour);
        BuiltinsKt.getter(proto, realm, "minute",
                (function, t, args, newTarget) -> (double) thisDT(t, "minute").dt.time.minute);
        BuiltinsKt.getter(proto, realm, "second",
                (function, t, args, newTarget) -> (double) thisDT(t, "second").dt.time.second);
        BuiltinsKt.getter(proto, realm, "millisecond",
                (function, t, args, newTarget) -> (double) thisDT(t, "millisecond").dt.time.millisecond);
        BuiltinsKt.getter(proto, realm, "microsecond",
                (function, t, args, newTarget) -> (double) thisDT(t, "microsecond").dt.time.microsecond);
        BuiltinsKt.getter(proto, realm, "nanosecond",
                (function, t, args, newTarget) -> (double) thisDT(t, "nanosecond").dt.time.nanosecond);
        installMethods(realm, proto);
        installConversions(realm, proto);
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "with");
            Object like = Values.arg(args, 0);
            if (!TConv.isPartialTemporalObject(like)) {
                throw TemporalAbstractOps.tTypeErr("argument must be a partial date-time object");
            }
            String cal = p.calendar;
            CalFields fields = TCal.isoDateToFields(cal, p.dt.date, TCal.TYPE_DATE);
            fields.setTime(p.dt.time);
            CalFields partial = TCal.prepareFields(cal, (JSObject) like,
                    TCal.DATE_FIELDS | TCal.TIME_FIELDS, TCal.PARTIAL);
            fields = TCal.mergeFields(cal, fields, partial);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(Values.arg(args, 1)));
            return TCreate.plainDateTime(TConv.interpretDateTimeFields(cal, fields, overflow), cal);
        });
        BuiltinsKt.method(proto, realm, "withPlainTime", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "withPlainTime");
            TimeRec time = TConv.toTimeOrMidnight(Values.arg(args, 0));
            return TCreate.plainDateTime(new IsoDateTime(p.dt.date, time), p.calendar);
        });
        BuiltinsKt.method(proto, realm, "withCalendar", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "withCalendar");
            return TCreate.plainDateTime(p.dt, TCal.toCalendar(Values.arg(args, 0)));
        });
        BuiltinsKt.method(proto, realm, "add", 1, (function, t, args, newTarget) ->
                addDuration(false, thisDT(t, "add"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                addDuration(true, thisDT(t, "subtract"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "until", 1, (function, t, args, newTarget) ->
                difference(false, thisDT(t, "until"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "since", 1, (function, t, args, newTarget) ->
                difference(true, thisDT(t, "since"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "round", 1, (function, t, args, newTarget) ->
                round(thisDT(t, "round"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "equals");
            JSTemporalPlainDateTime other = TConv.toDateTime(Values.arg(args, 0));
            return TM.compareDateTime(p.dt, other.dt) == 0 && p.calendar.equals(other.calendar);
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) ->
                toStringImpl(thisDT(t, "toString"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "toLocaleString");
            return TFormat.dateTimeToString(p.dt, p.calendar, Precision.AUTO, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "toJSON");
            return TFormat.dateTimeToString(p.dt, p.calendar, Precision.AUTO, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, t, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.PlainDateTime.compare() or equals() instead of valueOf()");
        });
    }

    private static void installConversions(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "toZonedDateTime", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "toZonedDateTime");
            TimeZone tz = TZ.toTimeZone(Values.arg(args, 0));
            Disambiguation disambiguation =
                    TOpt.disambiguation(TOpt.optionsObject(Values.arg(args, 1)));
            BigInteger ns = TZ.epochNsFor(tz, p.dt, disambiguation);
            return TCreate.zoned(ns, tz, p.calendar);
        });
        BuiltinsKt.method(proto, realm, "toPlainDate", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDateTime p = thisDT(t, "toPlainDate");
            return TCreate.plainDate(p.dt.date, p.calendar);
        });
        BuiltinsKt.method(proto, realm, "toPlainTime", 0, (function, t, args, newTarget) ->
                TCreate.plainTime(thisDT(t, "toPlainTime").dt.time));
    }

    private static JSTemporalPlainDateTime addDuration(boolean subtract, JSTemporalPlainDateTime p,
                                                       Object like, Object options) {
        JSTemporalDuration d = TDur.toDuration(like);
        if (subtract) d = TDur.negated(d);
        Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
        InternalDuration id = TDur.toInternal24(d);
        TimeRec timeResult = TM.addTime(p.dt.time, id.time);
        DateDuration dd = TDur.adjust(id.date, timeResult.days);
        IsoDate added = TCal.dateAdd(p.calendar, p.dt.date, dd, overflow);
        return TCreate.plainDateTime(new IsoDateTime(added, timeResult), p.calendar);
    }

    private static JSTemporalDuration difference(boolean since, JSTemporalPlainDateTime p, Object otherV,
                                                 Object options) {
        JSTemporalPlainDateTime other = TConv.toDateTime(otherV);
        if (!p.calendar.equals(other.calendar)) {
            throw TemporalAbstractOps.tRangeErr(
                    "cannot compute the difference between date-times of different calendars");
        }
        JSObject o = TOpt.optionsObject(options);
        DiffSettings s = TDur.differenceSettings(since, o, 2, new TUnit[0], TUnit.NANOSECOND, TUnit.DAY);
        if (TM.compareDateTime(p.dt, other.dt) == 0) return TCreate.duration(new double[10]);
        InternalDuration id = TDur.differencePlainDateTimeWithRounding(p.dt, other.dt, p.calendar,
                s.largest, s.increment, s.smallest, s.mode);
        JSTemporalDuration r = TDur.fromInternal(id, s.largest);
        return since ? TDur.negated(r) : r;
    }

    private static JSTemporalPlainDateTime round(JSTemporalPlainDateTime p, Object roundToV) {
        if (roundToV == Undefined.INSTANCE) {
            throw TemporalAbstractOps.tTypeErr("options argument is required");
        }
        JSObject roundTo;
        if (roundToV instanceof CharSequence) {
            roundTo = new JSObject(null);
            roundTo.createDataPropertyOrThrow("smallestUnit", roundToV.toString());
        } else {
            roundTo = TOpt.optionsObject(roundToV);
        }
        long inc = TOpt.roundingIncrement(roundTo);
        RMode mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND);
        int smallestOpt = TOpt.unit(roundTo, "smallestUnit", true);
        TOpt.validateUnit(smallestOpt, 1, new int[]{TUnit.DAY.ordinal()});
        TUnit smallest = TUnit.ALL[smallestOpt];
        if (smallest == TUnit.DAY) TOpt.validateIncrement(inc, 1, true);
        else TOpt.validateIncrement(inc, smallest.maxIncrement, false);
        if (smallest == TUnit.NANOSECOND && inc == 1L) return TCreate.plainDateTime(p.dt, p.calendar);
        return TCreate.plainDateTime(TM.roundDateTime(p.dt, inc, smallest, mode), p.calendar);
    }

    private static String toStringImpl(JSTemporalPlainDateTime p, Object options) {
        JSObject o = TOpt.optionsObject(options);
        ShowCalendar show = TOpt.showCalendar(o);
        int digits = TOpt.fractionalSecondDigits(o);
        RMode mode = TOpt.roundingMode(o, RMode.TRUNC);
        int smallest = TOpt.unit(o, "smallestUnit", false);
        TOpt.validateUnit(smallest, 1);
        if (smallest == TUnit.HOUR.ordinal()) {
            throw TemporalAbstractOps.tRangeErr("smallestUnit must not be hour");
        }
        Precision pr = Precision.of(smallest, digits);
        IsoDateTime r = TM.roundDateTime(p.dt, pr.increment, pr.unit, mode);
        if (!TM.isoDateTimeWithinLimits(r)) {
            throw TemporalAbstractOps.tRangeErr("date-time outside of supported range");
        }
        return TFormat.dateTimeToString(r, p.calendar, pr.precision, show);
    }
}
