/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.BuiltinsKt;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Temporal.Instant constructor, prototype and methods. */
final class InstantBuiltins {
    public static final InstantBuiltins INSTANCE = new InstantBuiltins();

    private InstantBuiltins() {
    }

    private static JSTemporalInstant thisInstant(Object t, String name) {
        if (t instanceof JSTemporalInstant instant) return instant;
        throw TemporalAbstractOps.tTypeErr(
                "Temporal.Instant.prototype." + name + " called on incompatible receiver");
    }

    private static BigInteger checkNs(BigInteger ns) {
        if (!TM.isValidEpochNs(ns)) {
            throw TemporalAbstractOps.tRangeErr("epoch nanoseconds outside of supported range");
        }
        return ns;
    }

    /** NumberToBigInt(ms) times 10^6. */
    static BigInteger msToNs(double ms) {
        if (Double.isNaN(ms) || Double.isInfinite(ms) || ms != Math.floor(ms)) {
            throw TemporalAbstractOps.tRangeErr(Ops.toString(ms) + " is not an integer");
        }
        return TNum.big(ms).multiply(TNum.BI_1E6);
    }

    public static void install(Realm realm, JSObject temporal) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put(TCreate.P_INSTANT, proto);
        var ctor = BuiltinsKt.makeCtor(realm, "Instant", 1, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw TemporalAbstractOps.tTypeErr("Constructor Temporal.Instant requires 'new'");
            }
            BigInteger ns = checkNs(Ops.toBigInt(Values.arg(args, 0)));
            return TCreate.instant(ns, newTarget);
        });
        temporal.defineOwn("Instant", ctor, Attr.WC);
        BuiltinsKt.method(ctor, realm, "from", 1,
                (function, thisArg, args, newTarget) ->
                        TCreate.instant(TConv.toInstantNs(Values.arg(args, 0))));
        BuiltinsKt.method(ctor, realm, "fromEpochMilliseconds", 1, (function, thisArg, args, newTarget) -> {
            double ms = Ops.toNumber(Values.arg(args, 0));
            return TCreate.instant(checkNs(msToNs(ms)));
        });
        BuiltinsKt.method(ctor, realm, "fromEpochNanoseconds", 1, (function, thisArg, args, newTarget) ->
                TCreate.instant(checkNs(Ops.toBigInt(Values.arg(args, 0)))));
        BuiltinsKt.method(ctor, realm, "compare", 2, (function, thisArg, args, newTarget) -> {
            BigInteger a = TConv.toInstantNs(Values.arg(args, 0));
            BigInteger b = TConv.toInstantNs(Values.arg(args, 1));
            return (double) Math.max(-1, Math.min(1, a.compareTo(b)));
        });
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.Instant", Attr.CONFIGURABLE);
        BuiltinsKt.getter(proto, realm, "epochMilliseconds", (function, t, args, newTarget) ->
                floorDiv(thisInstant(t, "epochMilliseconds").epochNs, TNum.BI_1E6).doubleValue());
        BuiltinsKt.getter(proto, realm, "epochNanoseconds",
                (function, t, args, newTarget) -> thisInstant(t, "epochNanoseconds").epochNs);
        installMethods(realm, proto);
    }

    static BigInteger floorDiv(BigInteger a, BigInteger b) {
        BigInteger[] qr = a.divideAndRemainder(b);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static void installMethods(Realm realm, JSObject proto) {
        BuiltinsKt.method(proto, realm, "add", 1,
                (function, t, args, newTarget) -> addDuration(false, thisInstant(t, "add"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "subtract", 1, (function, t, args, newTarget) ->
                addDuration(true, thisInstant(t, "subtract"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "until", 1, (function, t, args, newTarget) ->
                difference(false, thisInstant(t, "until"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "since", 1, (function, t, args, newTarget) ->
                difference(true, thisInstant(t, "since"), Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(proto, realm, "round", 1,
                (function, t, args, newTarget) -> round(thisInstant(t, "round"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "equals", 1, (function, t, args, newTarget) -> {
            JSTemporalInstant i = thisInstant(t, "equals");
            return i.epochNs.equals(TConv.toInstantNs(Values.arg(args, 0)));
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, t, args, newTarget) ->
                toStringImpl(thisInstant(t, "toString"), Values.arg(args, 0)));
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, t, args, newTarget) ->
                TFormat.instantToString(thisInstant(t, "toLocaleString").epochNs, null, Precision.AUTO));
        BuiltinsKt.method(proto, realm, "toJSON", 0, (function, t, args, newTarget) ->
                TFormat.instantToString(thisInstant(t, "toJSON").epochNs, null, Precision.AUTO));
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisArg, args, newTarget) -> {
            throw TemporalAbstractOps.tTypeErr(
                    "use Temporal.Instant.compare() or equals() instead of valueOf()");
        });
        BuiltinsKt.method(proto, realm, "toZonedDateTimeISO", 1, (function, t, args, newTarget) -> {
            JSTemporalInstant i = thisInstant(t, "toZonedDateTimeISO");
            TimeZone tz = TZ.toTimeZone(Values.arg(args, 0));
            return TCreate.zoned(i.epochNs, tz, TCal.ISO);
        });
    }

    private static JSTemporalInstant addDuration(boolean subtract, JSTemporalInstant i, Object like) {
        JSTemporalDuration d = TDur.toDuration(like);
        if (subtract) d = TDur.negated(d);
        TUnit largest = TDur.defaultLargestUnit(d);
        if (largest.isDate) {
            throw TemporalAbstractOps.tRangeErr("cannot add a duration with date units to an Instant");
        }
        InternalDuration id = TDur.toInternal24(d);
        return TCreate.instant(TDur.addInstant(i.epochNs, id.time));
    }

    private static JSTemporalDuration difference(boolean since, JSTemporalInstant i, Object otherV,
                                                 Object options) {
        BigInteger other = TConv.toInstantNs(otherV);
        JSObject o = TOpt.optionsObject(options);
        DiffSettings s = TDur.differenceSettings(since, o, 1, new TUnit[0], TUnit.NANOSECOND, TUnit.SECOND);
        InternalDuration id = TDur.differenceInstant(i.epochNs, other, s.increment, s.smallest, s.mode);
        JSTemporalDuration r = TDur.fromInternal(id, s.largest);
        return since ? TDur.negated(r) : r;
    }

    private static JSTemporalInstant round(JSTemporalInstant i, Object roundToV) {
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
        long maximum = TM.NS_PER_DAY / smallest.nanos;
        TOpt.validateIncrement(inc, maximum, true);
        BigInteger ns = TNum.roundAsIfPositive(i.epochNs,
                BigInteger.valueOf(inc * smallest.nanos), mode);
        return TCreate.instant(ns);
    }

    private static String toStringImpl(JSTemporalInstant i, Object options) {
        JSObject o = TOpt.optionsObject(options);
        int digits = TOpt.fractionalSecondDigits(o);
        RMode mode = TOpt.roundingMode(o, RMode.TRUNC);
        int smallest = TOpt.unit(o, "smallestUnit", false);
        Object tzV = o.get("timeZone", o);
        TOpt.validateUnit(smallest, 1);
        if (smallest == TUnit.HOUR.ordinal()) {
            throw TemporalAbstractOps.tRangeErr("smallestUnit must not be hour");
        }
        TimeZone tz = tzV != Undefined.INSTANCE ? TZ.toTimeZone(tzV) : null;
        Precision p = Precision.of(smallest, digits);
        BigInteger ns = TNum.roundAsIfPositive(i.epochNs,
                BigInteger.valueOf(p.increment * p.unit.nanos), mode);
        return TFormat.instantToString(ns, tz, p.precision);
    }

    /** SystemUTCEpochNanoseconds from the agent's (possibly deterministic) clock. */
    static BigInteger systemNs(Realm realm) {
        double ms = realm.agent.currentTimeMillis();
        if (Double.isNaN(ms)) return BigInteger.ZERO;
        BigInteger ns = new BigDecimal(ms).multiply(BigDecimal.valueOf(1_000_000L))
                .setScale(0, RoundingMode.FLOOR).toBigInteger();
        return ns.max(TM.NS_MIN_INSTANT).min(TM.NS_MAX_INSTANT);
    }
}
