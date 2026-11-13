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

/** Temporal.PlainTime constructor, prototype and methods. */
final class PlainTimeBuiltins {
    public static final PlainTimeBuiltins INSTANCE = new PlainTimeBuiltins();

    private PlainTimeBuiltins() {
    }

    private static JSTemporalPlainTime thisTime(Object t, String name) {
        if (t instanceof JSTemporalPlainTime time) return time;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.PlainTime.prototype." + name + " called on incompatible receiver");
    }

    public static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put(TCreate.P_TIME, proto);
        var ctor = BuiltinsKt.makeCtor(realm, "PlainTime", 0, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw TemporalAbstractOps.tTypeErr("Constructor Temporal.PlainTime requires 'new'");
            }
            double[] f = new double[6];
            for (int i = 0; i < 6; i++) {
                Object v = Values.arg(args, i);
                f[i] = v == Undefined.INSTANCE ? 0.0 : TemporalAbstractOps.toIntegerWithTruncation(v);
            }
            if (!TConv.isValidTime(f[0], f[1], f[2], f[3], f[4], f[5])) {
                throw TemporalAbstractOps.tRangeErr("time is out of range");
            }
            return TCreate.plainTime(new TimeRec((int) f[0], (int) f[1], (int) f[2],
                    (int) f[3], (int) f[4], (int) f[5]), newTarget);
        });
        temporal.defineOwn("PlainTime", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1, (function, thisArg, args, newTarget) ->
                TCreate.plainTime(TConv.toTime(Values.arg(args, 0), Values.arg(args, 1))));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) -> {
            TimeRec a = TConv.toTime(Values.arg(args, 0));
            TimeRec b = TConv.toTime(Values.arg(args, 1));
            return (double) TM.compareTime(a, b);
        });
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainTime", Attr.CONFIGURABLE);
        BuiltinsKt.getter(proto, realm, "hour", (function, t, args, newTarget) ->
                (double) thisTime(t, "hour").time.hour);
        BuiltinsKt.getter(proto, realm, "minute", (function, t, args, newTarget) ->
                (double) thisTime(t, "minute").time.minute);
        BuiltinsKt.getter(proto, realm, "second", (function, t, args, newTarget) ->
                (double) thisTime(t, "second").time.second);
        BuiltinsKt.getter(proto, realm, "millisecond", (function, t, args, newTarget) ->
                (double) thisTime(t, "millisecond").time.millisecond);
        BuiltinsKt.getter(proto, realm, "microsecond", (function, t, args, newTarget) ->
                (double) thisTime(t, "microsecond").time.microsecond);
        BuiltinsKt.getter(proto, realm, "nanosecond", (function, t, args, newTarget) ->
                (double) thisTime(t, "nanosecond").time.nanosecond);
        installMethods(realm, proto);
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "add", 1, (function, t, args, newTarget) ->
                addDuration(false, thisTime(t, "add"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                addDuration(true, thisTime(t, "subtract"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "with", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainTime pt = thisTime(t, "with");
            Object like = Values.arg(args, 0);
            if (!TConv.isPartialTemporalObject(like)) {
                throw TemporalAbstractOps.tTypeErr("argument must be a partial time object");
            }
            double[] p = TConv.toTimeRecordFields((JSObject) like, true);
            TimeRec cur = pt.time;
            double h = Double.isNaN(p[0]) ? (double) cur.hour : p[0];
            double mi = Double.isNaN(p[1]) ? (double) cur.minute : p[1];
            double s = Double.isNaN(p[2]) ? (double) cur.second : p[2];
            double ms = Double.isNaN(p[3]) ? (double) cur.millisecond : p[3];
            double us = Double.isNaN(p[4]) ? (double) cur.microsecond : p[4];
            double ns = Double.isNaN(p[5]) ? (double) cur.nanosecond : p[5];
            Overflow overflow = TOpt.overflow(TOpt.optionsObject(Values.arg(args, 1)));
            return TCreate.plainTime(TConv.regulateTime(h, mi, s, ms, us, ns, overflow));
        });
        BuiltinsKt.method(proto, realm, "until", 1, (function, t, args, newTarget) ->
                difference(false, thisTime(t, "until"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "since", 1, (function, t, args, newTarget) ->
                difference(true, thisTime(t, "since"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "round", 1,
                (function, t, args, newTarget) -> round(thisTime(t, "round"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalPlainTime pt = thisTime(t, "equals");
            return TM.compareTime(pt.time, TConv.toTime(Values.arg(args, 0))) == 0;
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) ->
                toStringImpl(thisTime(t, "toString"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) ->
                TFormat.timeRecordToString(thisTime(t, "toLocaleString").time, Precision.AUTO));
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) ->
                TFormat.timeRecordToString(thisTime(t, "toJSON").time, Precision.AUTO));
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisArg, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.PlainTime.compare() or equals() instead of valueOf()");
        });
    }

    private static JSTemporalPlainTime addDuration(boolean subtract, JSTemporalPlainTime pt, Object like) {
        JSTemporalDuration d = TDur.toDuration(like);
        if (subtract) d = TDur.negated(d);
        InternalDuration id = TDur.toInternal(d);
        return TCreate.plainTime(TM.addTime(pt.time, id.time));
    }

    private static JSTemporalDuration difference(boolean since, JSTemporalPlainTime pt, Object otherV,
                                                 Object options) {
        TimeRec other = TConv.toTime(otherV);
        JSObject o = TOpt.optionsObject(options);
        DiffSettings s = TDur.differenceSettings(since, o, 1, new TUnit[0], TUnit.NANOSECOND, TUnit.HOUR);
        BigInteger td = BigInteger.valueOf(TM.differenceTime(pt.time, other));
        td = TDur.roundTime(td, s.increment, s.smallest, s.mode);
        JSTemporalDuration r = TDur.fromInternal(new InternalDuration(DateDuration.ZERO, td), s.largest);
        return since ? TDur.negated(r) : r;
    }

    private static JSTemporalPlainTime round(JSTemporalPlainTime pt, Object roundToV) {
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
        TOpt.validateUnit(smallestOpt, 1);
        TUnit smallest = TUnit.ALL[smallestOpt];
        TOpt.validateIncrement(inc, (long) smallest.maxIncrement, false);
        return TCreate.plainTime(TM.roundTime(pt.time, inc, smallest, mode));
    }

    private static String toStringImpl(JSTemporalPlainTime pt, Object options) {
        JSObject o = TOpt.optionsObject(options);
        int digits = TOpt.fractionalSecondDigits(o);
        RMode mode = TOpt.roundingMode(o, RMode.TRUNC);
        int smallest = TOpt.unit(o, "smallestUnit", false);
        TOpt.validateUnit(smallest, 1);
        if (smallest == TUnit.HOUR.ordinal()) {
            throw TemporalAbstractOps.tRangeErr("smallestUnit must not be hour");
        }
        Precision p = Precision.of(smallest, digits);
        TimeRec r = TM.roundTime(pt.time, p.increment, p.unit, mode);
        return TFormat.timeRecordToString(r, p.precision);
    }
}
