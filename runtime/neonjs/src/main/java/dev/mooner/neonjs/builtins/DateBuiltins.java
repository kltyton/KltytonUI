/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.util.function.DoubleUnaryOperator;

/** Date constructor, prototype methods and accessors. */
final class DateBuiltins {
    private DateBuiltins() {
    }

    private interface Format {
        String apply(double time);
    }

    private static JSDate thisDate(Object target, String method) {
        if (target instanceof JSDate) return (JSDate) target;
        throw JSException.typeError("Date.prototype." + method
                + " called on incompatible receiver " + Ops.toDisplayString(target));
    }

    private static double thisTime(Object target, String method) {
        return thisDate(target, method).timeValue;
    }

    static void install(Realm realm) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Date.prototype%", proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "Date", 7, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        return DateTime.toDateString(DateTime.now());
                    }
                    double tv;
                    if (args.length == 0) {
                        tv = DateTime.now();
                    } else if (args.length == 1) {
                        Object value = args[0];
                        double raw;
                        if (value instanceof JSDate) {
                            raw = ((JSDate) value).timeValue;
                        } else {
                            Object primitive = Ops.toPrimitive(value);
                            raw = primitive instanceof CharSequence
                                    ? DateParser.parse(primitive.toString()) : Ops.toNumber(primitive);
                        }
                        tv = DateTime.timeClip(raw);
                    } else {
                        tv = DateTime.timeClip(DateTime.utc(fromComponents(args)));
                    }
                    return new JSDate(Ops.getPrototypeFromConstructor(newTarget,
                            r -> r.intrinsic("%Date.prototype%")), tv);
                });
        realm.getIntrinsics().put("%Date%", ctor);
        BuiltinsKt.global(realm, "Date", ctor);

        BuiltinsKt.method(ctor, realm, "now", 0, (function, thisArg, args, newTarget) -> DateTime.now());
        BuiltinsKt.method(ctor, realm, "parse", 1,
                (function, thisArg, args, newTarget) -> DateParser.parse(Ops.toString(Values.arg(args, 0))));
        BuiltinsKt.method(ctor, realm, "UTC", 7,
                (function, thisArg, args, newTarget) -> DateTime.timeClip(fromComponents(args)));

        installGetters(realm, proto);
        installSetters(realm, proto);
        installConversions(realm, proto);
    }

    /** Date(...) and Date.UTC(...) before UTC adjustment / TimeClip. */
    private static double fromComponents(Object[] args) {
        double y = Ops.toNumber(Values.arg(args, 0));
        double m = args.length > 1 ? Ops.toNumber(args[1]) : 0.0;
        double dt = args.length > 2 ? Ops.toNumber(args[2]) : 1.0;
        double h = args.length > 3 ? Ops.toNumber(args[3]) : 0.0;
        double min = args.length > 4 ? Ops.toNumber(args[4]) : 0.0;
        double s = args.length > 5 ? Ops.toNumber(args[5]) : 0.0;
        double milli = args.length > 6 ? Ops.toNumber(args[6]) : 0.0;
        return DateTime.makeDate(
                DateTime.makeDay(DateTime.makeFullYear(y), m, dt),
                DateTime.makeTime(h, min, s, milli));
    }

    private static void installGetters(Realm realm, JSObject proto) {
        for (boolean local : new boolean[]{true, false}) {
            String utc = local ? "" : "UTC";
            getter(realm, proto, "get" + utc + "Date", local, DateTime::dateFromTime);
            getter(realm, proto, "get" + utc + "Day", local, DateTime::weekDay);
            getter(realm, proto, "get" + utc + "FullYear", local, DateTime::yearFromTime);
            getter(realm, proto, "get" + utc + "Hours", local, DateTime::hourFromTime);
            getter(realm, proto, "get" + utc + "Milliseconds", local, DateTime::msFromTime);
            getter(realm, proto, "get" + utc + "Minutes", local, DateTime::minFromTime);
            getter(realm, proto, "get" + utc + "Month", local, DateTime::monthFromTime);
            getter(realm, proto, "get" + utc + "Seconds", local, DateTime::secFromTime);
        }
        getter(realm, proto, "getTime", false, t -> t);
        BuiltinsKt.method(proto, realm, "getTimezoneOffset", 0, (function, target, args, newTarget) -> {
            double tv = thisTime(target, "getTimezoneOffset");
            if (Double.isNaN(tv)) return Double.NaN;
            return (tv - DateTime.localTime(tv)) / DateTime.MS_PER_MINUTE;
        });
        getter(realm, proto, "getYear", true, t -> DateTime.yearFromTime(t) - 1900);
    }

    private static void getter(Realm realm, JSObject proto, String name, boolean local,
                               DoubleUnaryOperator field) {
        BuiltinsKt.method(proto, realm, name, 0, (function, target, args, newTarget) -> {
            double tv = thisTime(target, name);
            if (Double.isNaN(tv)) return Double.NaN;
            return field.applyAsDouble(local ? DateTime.localTime(tv) : tv);
        });
    }

    private static void installSetters(Realm realm, JSObject proto) {
        for (boolean local : new boolean[]{true, false}) {
            String utc = local ? "" : "UTC";
            dateSetter(realm, proto, "set" + utc + "Date", local, 2);
            dateSetter(realm, proto, "set" + utc + "FullYear", local, 0);
            timeSetter(realm, proto, "set" + utc + "Hours", local, 0);
            timeSetter(realm, proto, "set" + utc + "Milliseconds", local, 3);
            timeSetter(realm, proto, "set" + utc + "Minutes", local, 1);
            dateSetter(realm, proto, "set" + utc + "Month", local, 1);
            timeSetter(realm, proto, "set" + utc + "Seconds", local, 2);
        }
        BuiltinsKt.method(proto, realm, "setTime", 1, (function, target, args, newTarget) -> {
            JSDate date = thisDate(target, "setTime");
            double value = DateTime.timeClip(Ops.toNumber(Values.arg(args, 0)));
            date.timeValue = value;
            return value;
        });
        BuiltinsKt.method(proto, realm, "setYear", 1, (function, target, args, newTarget) -> {
            JSDate date = thisDate(target, "setYear");
            double tv = date.timeValue;
            double y = Ops.toNumber(Values.arg(args, 0));
            double lt = Double.isNaN(tv) ? 0.0 : DateTime.localTime(tv);
            double day = DateTime.makeDay(DateTime.makeFullYear(y),
                    DateTime.monthFromTime(lt), DateTime.dateFromTime(lt));
            double value = DateTime.timeClip(DateTime.utc(DateTime.makeDate(day, DateTime.timeWithinDay(lt))));
            date.timeValue = value;
            return value;
        });
    }

    private static void timeSetter(Realm realm, JSObject proto, String name, boolean local, int first) {
        int count = 4 - first;
        BuiltinsKt.method(proto, realm, name, count, (function, target, args, newTarget) -> {
            JSDate date = thisDate(target, name);
            double tv = date.timeValue;
            int present = Math.max(1, Math.min(args.length, count));
            double[] values = new double[present];
            for (int i = 0; i < present; i++) values[i] = Ops.toNumber(Values.arg(args, i));
            if (Double.isNaN(tv)) return Double.NaN;
            double lt = local ? DateTime.localTime(tv) : tv;
            double hour = first <= 0 && present > 0 ? values[0 - first] : DateTime.hourFromTime(lt);
            double minute = first <= 1 && 1 - first < present ? values[1 - first] : DateTime.minFromTime(lt);
            double second = first <= 2 && 2 - first < present ? values[2 - first] : DateTime.secFromTime(lt);
            double milli = first <= 3 && 3 - first < present ? values[3 - first] : DateTime.msFromTime(lt);
            double time = DateTime.makeTime(hour, minute, second, milli);
            double day = DateTime.makeDate(DateTime.day(lt), time);
            double result = DateTime.timeClip(local ? DateTime.utc(day) : day);
            date.timeValue = result;
            return result;
        });
    }

    private static void dateSetter(Realm realm, JSObject proto, String name, boolean local, int first) {
        int count = 3 - first;
        BuiltinsKt.method(proto, realm, name, count, (function, target, args, newTarget) -> {
            JSDate date = thisDate(target, name);
            double tv = date.timeValue;
            int present = Math.max(1, Math.min(args.length, count));
            double[] values = new double[present];
            for (int i = 0; i < present; i++) values[i] = Ops.toNumber(Values.arg(args, i));
            if (Double.isNaN(tv) && first != 0) return Double.NaN;
            double lt = Double.isNaN(tv) ? 0.0 : (local ? DateTime.localTime(tv) : tv);
            double year = first <= 0 && present > 0 ? values[0 - first] : DateTime.yearFromTime(lt);
            double month = first <= 1 && 1 - first < present ? values[1 - first] : DateTime.monthFromTime(lt);
            double day = first <= 2 && 2 - first < present ? values[2 - first] : DateTime.dateFromTime(lt);
            double dayNumber = DateTime.makeDay(year, month, day);
            double value = DateTime.makeDate(dayNumber, DateTime.timeWithinDay(lt));
            double result = DateTime.timeClip(local ? DateTime.utc(value) : value);
            date.timeValue = result;
            return result;
        });
    }

    private static void installConversions(Realm realm, JSObject proto) {
        format(realm, proto, "toDateString",
                tv -> Double.isNaN(tv) ? DateTime.INVALID_DATE : DateTime.dateString(DateTime.localTime(tv)));
        format(realm, proto, "toISOString", tv -> {
            if (!Double.isFinite(tv)) throw JSException.rangeError("Invalid time value");
            return DateTime.toISOString(tv);
        });
        format(realm, proto, "toLocaleDateString", DateTime::toLocaleDateString);
        format(realm, proto, "toLocaleString", DateTime::toLocaleString);
        format(realm, proto, "toLocaleTimeString", DateTime::toLocaleTimeString);
        format(realm, proto, "toString", DateTime::toDateString);
        format(realm, proto, "toTimeString", tv -> Double.isNaN(tv) ? DateTime.INVALID_DATE
                : DateTime.timeString(DateTime.localTime(tv)) + DateTime.timeZoneString(tv));
        NativeFunction toUTCString = format(realm, proto, "toUTCString", DateTime::toUTCString);
        BuiltinsKt.value(proto, "toGMTString", toUTCString);

        BuiltinsKt.method(proto, realm, "toJSON", 1, (function, target, args, newTarget) -> {
            JSObject object = Ops.toObject(target);
            Object tv = Ops.toPrimitive(object, Ops.HINT_NUMBER);
            if (tv instanceof Double number && !Double.isFinite(number)) return Null.INSTANCE;
            return Ops.invoke(object, "toISOString", Values.EMPTY_ARGS);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0,
                (function, target, args, newTarget) -> thisTime(target, "valueOf"));
        BuiltinsKt.method(proto, realm, "toTemporalInstant", 0,
                (function, target, args, newTarget) ->
                        dev.mooner.neonjs.builtins.temporal.TemporalBuiltins.dateToTemporalInstant(target));
        BuiltinsKt.method(proto, realm, JSSymbol.toPrimitive, 1, Attr.CONFIGURABLE,
                (function, target, args, newTarget) -> {
                    if (!(target instanceof JSObject)) {
                        throw JSException.typeError(
                                "Date.prototype[Symbol.toPrimitive] called on non-object");
                    }
                    Object hintValue = Values.arg(args, 0);
                    String hintText = hintValue instanceof CharSequence ? hintValue.toString() : null;
                    int tryFirst;
                    if ("string".equals(hintText) || "default".equals(hintText)) {
                        tryFirst = Ops.HINT_STRING;
                    } else if ("number".equals(hintText)) {
                        tryFirst = Ops.HINT_NUMBER;
                    } else {
                        throw JSException.typeError("Invalid hint: " + Ops.toDisplayString(hintValue));
                    }
                    return Ops.ordinaryToPrimitive((JSObject) target, tryFirst);
                });
    }

    private static NativeFunction format(Realm realm, JSObject proto, String name, Format impl) {
        return BuiltinsKt.method(proto, realm, name, 0,
                (function, target, args, newTarget) -> impl.apply(thisTime(target, name)));
    }
}
