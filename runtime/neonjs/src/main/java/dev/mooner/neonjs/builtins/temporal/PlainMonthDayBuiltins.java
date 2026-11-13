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

/** Temporal.PlainMonthDay builtins. */
final class PlainMonthDayBuiltins {
    public static final PlainMonthDayBuiltins INSTANCE = new PlainMonthDayBuiltins();

    private PlainMonthDayBuiltins() {
    }

    private static JSTemporalPlainMonthDay thisMD(Object t, String name) {
        if (t instanceof JSTemporalPlainMonthDay) return (JSTemporalPlainMonthDay) t;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.PlainMonthDay.prototype." + name + " called on incompatible receiver");
    }

    static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put(TCreate.P_MONTHDAY, proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "PlainMonthDay", 2, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        throw TemporalAbstractOps.tTypeErr(
                                "Constructor Temporal.PlainMonthDay requires 'new'");
                    }
                    Object refV = Values.arg(args, 3);
                    double m = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 0));
                    double d = TemporalAbstractOps.toIntegerWithTruncation(Values.arg(args, 1));
                    String cal = TCal.ctorCalendar(Values.arg(args, 2));
                    double y = refV == Undefined.INSTANCE
                            ? 1972.0
                            : TemporalAbstractOps.toIntegerWithTruncation(refV);
                    if (!TM.isValidIsoDate(y, m, d)) {
                        throw TemporalAbstractOps.tRangeErr("invalid month-day");
                    }
                    return TCreate.plainMonthDay(TM.makeDate(y, (int) m, (int) d), cal, newTarget);
                });
        temporal.defineOwn("PlainMonthDay", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1, (function, thisArg, args, newTarget) ->
                TConv.toMonthDay(Values.arg(args, 0), Values.arg(args, 1)));
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainMonthDay", Attr.CONFIGURABLE);
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_MONTH_DAY,
                (t, n) -> thisMD(t, n).calendar,
                (t, n) -> thisMD(t, n).date);
        installMethods(realm, proto);
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainMonthDay md = thisMD(t, "with");
            Object like = Values.arg(args, 0);
            if (!TConv.isPartialTemporalObject(like)) {
                throw TemporalAbstractOps.tTypeErr("argument must be a partial month-day object");
            }
            String cal = md.calendar;
            CalFields fields = TCal.isoDateToFields(cal, md.date, TCal.TYPE_MONTH_DAY);
            CalFields partial = TCal.prepareFields(cal, (JSObject) like, TCal.DATE_FIELDS, TCal.PARTIAL);
            fields = TCal.mergeFields(cal, fields, partial);
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(Values.arg(args, 1)));
            return TCreate.plainMonthDay(TCal.monthDayFromFields(cal, fields, overflow), cal);
        });
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainMonthDay md = thisMD(t, "equals");
            JSTemporalPlainMonthDay other = TConv.toMonthDay(Values.arg(args, 0));
            return TM.compareDate(md.date, other.date) == 0 && md.calendar.equals(other.calendar);
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainMonthDay md = thisMD(t, "toString");
            ShowCalendar show = TOpt.showCalendar(TOpt.optionsObject(Values.arg(args, 0)));
            return TFormat.monthDayToString(md.date, md.calendar, show);
        });
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainMonthDay md = thisMD(t, "toLocaleString");
            return TFormat.monthDayToString(md.date, md.calendar, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) -> {
            JSTemporalPlainMonthDay md = thisMD(t, "toJSON");
            return TFormat.monthDayToString(md.date, md.calendar, ShowCalendar.AUTO);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, t, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.PlainMonthDay.prototype.equals() instead of valueOf()");
        });
        BuiltinsKt.method(proto, realm, "toPlainDate", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainMonthDay md = thisMD(t, "toPlainDate");
            Object item = Values.arg(args, 0);
            if (!(item instanceof JSObject)) {
                throw TemporalAbstractOps.tTypeErr("argument must be an object");
            }
            String cal = md.calendar;
            CalFields fields = TCal.isoDateToFields(cal, md.date, TCal.TYPE_MONTH_DAY);
            CalFields input = TCal.prepareFields(cal, (JSObject) item, TCal.F_YEAR, 0);
            CalFields merged = TCal.mergeFields(cal, fields, input);
            return TCreate.plainDate(TCal.dateFromFields(cal, merged, Overflow.CONSTRAIN), cal);
        });
    }
}
