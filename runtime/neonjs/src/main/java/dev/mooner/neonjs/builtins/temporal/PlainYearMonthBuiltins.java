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

/** Temporal.PlainYearMonth builtins. */
final class PlainYearMonthBuiltins {
    public static final PlainYearMonthBuiltins INSTANCE = new PlainYearMonthBuiltins();

    private PlainYearMonthBuiltins() {
    }

    private static JSTemporalPlainYearMonth thisYM(Object t, String name) {
        if (t instanceof JSTemporalPlainYearMonth) return (JSTemporalPlainYearMonth) t;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.PlainYearMonth.prototype." + name + " called on incompatible receiver");
    }

    static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put(TCreate.P_YEARMONTH, proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "PlainYearMonth", 2, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        throw TemporalAbstractOps.tTypeErr(
                                "Constructor Temporal.PlainYearMonth requires 'new'");
                    }
                    Object refV = Values.arg(args, 3);
                    double y = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 0));
                    double m = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 1));
                    String cal = TCal.ctorCalendar(Values.arg(args, 2));
                    double ref = refV == Undefined.INSTANCE
                            ? 1.0
                            : TemporalAbstractOps.toIntegerWithTruncation(refV);
                    if (!TM.isValidIsoDate(y, m, ref)) {
                        throw TemporalAbstractOps.tRangeErr("invalid year-month");
                    }
                    return TCreate.plainYearMonth(TM.makeDate(y, (int) m, (int) ref), cal, newTarget);
                });
        temporal.defineOwn("PlainYearMonth", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1, (function, thisArg, args, newTarget) ->
                TConv.toYearMonth(Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) -> {
            JSTemporalPlainYearMonth a = TConv.toYearMonth(Values.arg(args, 0));
            JSTemporalPlainYearMonth b = TConv.toYearMonth(Values.arg(args, 1));
            return (double) TM.compareDate(a.date, b.date);
        });
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainYearMonth", Attr.CONFIGURABLE);
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_YEAR_MONTH,
                (t, n) -> thisYM(t, n).calendar,
                (t, n) -> thisYM(t, n).date);
        installMethods(realm, proto);
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainYearMonth ym = thisYM(t, "with");
            Object like = Values.arg(args, 0);
            if (!TConv.isPartialTemporalObject(like)) {
                throw TemporalAbstractOps.tTypeErr("argument must be a partial year-month object");
            }
            String cal = ym.calendar;
            CalFields fields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH);
            CalFields partial = TCal.prepareFields(cal, (JSObject) like,
                    TCal.F_YEAR | TCal.F_MONTH | TCal.F_MONTHCODE, TCal.PARTIAL);
            fields = TCal.mergeFields(cal, fields, partial);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(Values.arg(args, 1)));
            return TCreate.plainYearMonth(TCal.yearMonthFromFields(cal, fields, overflow), cal);
        });
        BuiltinsKt.method(proto, realm, "add", 1, (function, t, args, newTarget) ->
                addDuration(false, thisYM(t, "add"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                addDuration(true, thisYM(t, "subtract"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "until", 1, (function, t, args, newTarget) ->
                difference(false, thisYM(t, "until"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "since", 1, (function, t, args, newTarget) ->
                difference(true, thisYM(t, "since"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainYearMonth ym = thisYM(t, "equals");
            JSTemporalPlainYearMonth other = TConv.toYearMonth(Values.arg(args, 0));
            return TM.compareDate(ym.date, other.date) == 0 && ym.calendar.equals(other.calendar);
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainYearMonth ym = thisYM(t, "toString");
            ShowCalendar show = TOpt.showCalendar(TOpt.optionsObject(Values.arg(args, 0)));
            return TFormat.yearMonthToString(ym.date, ym.calendar, show);
        });
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainYearMonth ym = thisYM(t, "toLocaleString");
            return TFormat.yearMonthToString(ym.date, ym.calendar, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainYearMonth ym = thisYM(t, "toJSON");
            return TFormat.yearMonthToString(ym.date, ym.calendar, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, t, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.PlainYearMonth.compare() or equals() instead of valueOf()");
        });
        BuiltinsKt.method(proto, realm, "toPlainDate", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainYearMonth ym = thisYM(t, "toPlainDate");
            Object item = Values.arg(args, 0);
            if (!(item instanceof JSObject)) {
                throw TemporalAbstractOps.tTypeErr("argument must be an object");
            }
            String cal = ym.calendar;
            CalFields fields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH);
            CalFields input = TCal.prepareFields(cal, (JSObject) item, TCal.F_DAY, 0);
            CalFields merged = TCal.mergeFields(cal, fields, input);
            return TCreate.plainDate(TCal.dateFromFields(cal, merged, Overflow.CONSTRAIN), cal);
        });
    }

    private static JSTemporalPlainYearMonth addDuration(boolean subtract, JSTemporalPlainYearMonth ym,
                                                        Object like, Object options) {
        JSTemporalDuration d = TDur.toDuration(like);
        if (subtract) d = TDur.negated(d);
        InternalDuration id = TDur.toInternal(d);
        Overflow overflow = TOpt.overflow(TOpt.optionsObject(options));
        DateDuration toAdd = id.date;
        if (toAdd.weeks != 0L || toAdd.days != 0L || id.time.signum() != 0) {
            throw TemporalAbstractOps.tRangeErr(
                    "only years and months can be added to a PlainYearMonth");
        }
        String cal = ym.calendar;
        CalFields fields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH);
        fields.day = 1.0;
        IsoDate date = TCal.dateFromFields(cal, fields, Overflow.CONSTRAIN);
        IsoDate added = TCal.dateAdd(cal, date, toAdd, overflow);
        CalFields addedFields = TCal.isoDateToFields(cal, added, TCal.TYPE_YEAR_MONTH);
        return TCreate.plainYearMonth(TCal.yearMonthFromFields(cal, addedFields, overflow), cal);
    }

    private static JSTemporalDuration difference(boolean since, JSTemporalPlainYearMonth ym, Object otherV,
                                                 Object options) {
        JSTemporalPlainYearMonth other = TConv.toYearMonth(otherV);
        if (!ym.calendar.equals(other.calendar)) {
            throw TemporalAbstractOps.tRangeErr(
                    "cannot compute the difference between year-months of different calendars");
        }
        JSObject o = TOpt.optionsObject(options);
        DiffSettings s = TDur.differenceSettings(since, o, 0, new TUnit[]{TUnit.WEEK, TUnit.DAY},
                TUnit.MONTH, TUnit.YEAR);
        if (TM.compareDate(ym.date, other.date) == 0) return TCreate.duration(new double[10]);
        String cal = ym.calendar;
        CalFields thisFields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH);
        thisFields.day = 1.0;
        IsoDate thisDate = TCal.dateFromFields(cal, thisFields, Overflow.CONSTRAIN);
        CalFields otherFields = TCal.isoDateToFields(cal, other.date, TCal.TYPE_YEAR_MONTH);
        otherFields.day = 1.0;
        IsoDate otherDate = TCal.dateFromFields(cal, otherFields, Overflow.CONSTRAIN);
        DateDuration dateDiff = TCal.dateUntil(cal, thisDate, otherDate, s.largest);
        InternalDuration dur = new InternalDuration(TDur.adjust(dateDiff, 0, 0), BigInteger.ZERO);
        if (s.smallest != TUnit.MONTH || s.increment != 1L) {
            IsoDateTime dt = new IsoDateTime(thisDate, TimeRec.MIDNIGHT);
            BigInteger origin = TM.utcEpochNs(dt);
            BigInteger dest = TM.utcEpochNs(otherDate, TimeRec.MIDNIGHT);
            dur = TDur.roundRelative(dur, origin, dest, dt, null, ym.calendar, s.largest, s.increment,
                    s.smallest, s.mode);
        }
        JSTemporalDuration r = TDur.fromInternal(dur, TUnit.DAY);
        return since ? TDur.negated(r) : r;
    }
}
