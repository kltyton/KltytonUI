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

/** Temporal.PlainDate builtins. */
final class PlainDateBuiltins {
    public static final PlainDateBuiltins INSTANCE = new PlainDateBuiltins();

    private PlainDateBuiltins() {
    }

    private static JSTemporalPlainDate thisDate(Object t, String name) {
        if (t instanceof JSTemporalPlainDate) return (JSTemporalPlainDate) t;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.PlainDate.prototype." + name + " called on incompatible receiver");
    }

    static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put(TCreate.P_DATE, proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "PlainDate", 3, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        throw TemporalAbstractOps.tTypeErr("Constructor Temporal.PlainDate requires 'new'");
                    }
                    double y = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 0));
                    double m = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 1));
                    double d = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 2));
                    String cal = TCal.ctorCalendar(Values.arg(args, 3));
                    if (!TM.isValidIsoDate(y, m, d)) throw TemporalAbstractOps.tRangeErr("invalid date");
                    return TCreate.plainDate(TM.makeDate(y, (int) m, (int) d), cal, newTarget);
                });
        temporal.defineOwn("PlainDate", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1, (function, thisArg, args, newTarget) ->
                TConv.toDate(Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) -> {
            JSTemporalPlainDate a = TConv.toDate(Values.arg(args, 0));
            JSTemporalPlainDate b = TConv.toDate(Values.arg(args, 1));
            return (double) TM.compareDate(a.date, b.date);
        });
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainDate", Attr.CONFIGURABLE);
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_DATE,
                (t, n) -> thisDate(t, n).calendar,
                (t, n) -> thisDate(t, n).date);
        installMethods(realm, proto);
        installConversions(realm, proto);
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "add", 1, (function, t, args, newTarget) ->
                addDuration(false, thisDate(t, "add"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                addDuration(true, thisDate(t, "subtract"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "with");
            Object like = Values.arg(args, 0);
            if (!TConv.isPartialTemporalObject(like)) {
                throw TemporalAbstractOps.tTypeErr("argument must be a partial date object");
            }
            String cal = pd.calendar;
            CalFields fields = TCal.isoDateToFields(cal, pd.date, TCal.TYPE_DATE);
            CalFields partial = TCal.prepareFields(cal, (JSObject) like, TCal.DATE_FIELDS, TCal.PARTIAL);
            fields = TCal.mergeFields(cal, fields, partial);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(Values.arg(args, 1)));
            return TCreate.plainDate(TCal.dateFromFields(cal, fields, overflow), cal);
        });
        BuiltinsKt.method(proto, realm, "withCalendar", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "withCalendar");
            return TCreate.plainDate(pd.date, TCal.toCalendar(Values.arg(args, 0)));
        });
        BuiltinsKt.method(proto, realm, "until", 1, (function, t, args, newTarget) ->
                difference(false, thisDate(t, "until"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "since", 1, (function, t, args, newTarget) ->
                difference(true, thisDate(t, "since"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "equals");
            JSTemporalPlainDate other = TConv.toDate(Values.arg(args, 0));
            return TM.compareDate(pd.date, other.date) == 0 && pd.calendar.equals(other.calendar);
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "toString");
            ShowCalendar show = TOpt.showCalendar(TOpt.optionsObject(Values.arg(args, 0)));
            return TFormat.dateToString(pd.date, pd.calendar, show);
        });
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "toLocaleString");
            return TFormat.dateToString(pd.date, pd.calendar, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "toJSON");
            return TFormat.dateToString(pd.date, pd.calendar, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, t, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.PlainDate.compare() or equals() instead of valueOf()");
        });
    }

    private static void installConversions(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "toPlainYearMonth", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "toPlainYearMonth");
            CalFields f = TCal.isoDateToFields(pd.calendar, pd.date, TCal.TYPE_DATE);
            return TCreate.plainYearMonth(
                    TCal.yearMonthFromFields(pd.calendar, f, Overflow.CONSTRAIN), pd.calendar);
        });
        BuiltinsKt.method(proto, realm, "toPlainMonthDay", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "toPlainMonthDay");
            CalFields f = TCal.isoDateToFields(pd.calendar, pd.date, TCal.TYPE_DATE);
            return TCreate.plainMonthDay(
                    TCal.monthDayFromFields(pd.calendar, f, Overflow.CONSTRAIN), pd.calendar);
        });
        BuiltinsKt.method(proto, realm, "toPlainDateTime", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainDate pd = thisDate(t, "toPlainDateTime");
            TimeRec time = TConv.toTimeOrMidnight(Values.arg(args, 0));
            return TCreate.plainDateTime(new IsoDateTime(pd.date, time), pd.calendar);
        });
        BuiltinsKt.method(proto, realm, "toZonedDateTime", 1, (function, t, args, newTarget) ->
                toZoned(thisDate(t, "toZonedDateTime"), Values.arg(args, 0)));
    }

    private static JSTemporalZonedDateTime toZoned(JSTemporalPlainDate pd, Object item) {
        TimeZone tz;
        Object temporalTime = Undefined.INSTANCE;
        if (item instanceof JSObject) {
            JSObject itemObject = (JSObject) item;
            Object tzLike = itemObject.get("timeZone", itemObject);
            if (tzLike == Undefined.INSTANCE) {
                tz = TZ.toTimeZone(itemObject);
            } else {
                tz = TZ.toTimeZone(tzLike);
                temporalTime = itemObject.get("plainTime", itemObject);
            }
        } else {
            tz = TZ.toTimeZone(item);
        }
        BigInteger ns;
        if (temporalTime == Undefined.INSTANCE) {
            ns = TZ.startOfDay(tz, pd.date);
        } else {
            TimeRec time = TConv.toTime(temporalTime);
            IsoDateTime dt = new IsoDateTime(pd.date, time);
            if (!TM.isoDateTimeWithinLimits(dt)) {
                throw TemporalAbstractOps.tRangeErr("date-time outside of supported range");
            }
            ns = TZ.epochNsFor(tz, dt, Disambiguation.COMPATIBLE);
        }
        return TCreate.zoned(ns, tz, pd.calendar);
    }

    private static JSTemporalPlainDate addDuration(boolean subtract, JSTemporalPlainDate pd, Object like,
                                                   Object options) {
        JSTemporalDuration d = TDur.toDuration(like);
        if (subtract) d = TDur.negated(d);
        DateDuration dd = TDur.toDateDurationWithoutTime(d);
        Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
        return TCreate.plainDate(TCal.dateAdd(pd.calendar, pd.date, dd, overflow), pd.calendar);
    }

    private static JSTemporalDuration difference(boolean since, JSTemporalPlainDate pd, Object otherV,
                                                 Object options) {
        JSTemporalPlainDate other = TConv.toDate(otherV);
        if (!pd.calendar.equals(other.calendar)) {
            throw TemporalAbstractOps.tRangeErr(
                    "cannot compute the difference between dates of different calendars");
        }
        JSObject o = TOpt.optionsObject(options);
        DiffSettings s = TDur.differenceSettings(since, o, 0, new TUnit[0], TUnit.DAY, TUnit.DAY);
        if (TM.compareDate(pd.date, other.date) == 0) return TCreate.duration(new double[10]);
        DateDuration dateDiff = TCal.dateUntil(pd.calendar, pd.date, other.date, s.largest);
        InternalDuration dur = new InternalDuration(dateDiff, BigInteger.ZERO);
        if (s.smallest != TUnit.DAY || s.increment != 1L) {
            IsoDateTime dt = new IsoDateTime(pd.date, TimeRec.MIDNIGHT);
            BigInteger origin = TM.utcEpochNs(dt);
            BigInteger dest = TM.utcEpochNs(other.date, TimeRec.MIDNIGHT);
            dur = TDur.roundRelative(dur, origin, dest, dt, null, pd.calendar, s.largest, s.increment,
                    s.smallest, s.mode);
        }
        JSTemporalDuration r = TDur.fromInternal(dur, TUnit.DAY);
        return since ? TDur.negated(r) : r;
    }
}
