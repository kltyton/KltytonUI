/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;

/** Number built-ins; derived from NeonJS and adapted to Java. */
final class NumberBuiltins {
    private NumberBuiltins() {
    }

    static double thisNumber(Object value, String method) {
        if (value instanceof Double number) return number;
        if (value instanceof JSPrimitiveWrapper wrapper) {
            if (wrapper.primitive instanceof Double number) return number;
            throw BuiltinsKt.typeErr("Number.prototype." + method + " requires that 'this' be a Number");
        }
        throw BuiltinsKt.typeErr("Number.prototype." + method + " requires that 'this' be a Number");
    }

    static void install(Realm realm) {
        JSPrimitiveWrapper proto = new JSPrimitiveWrapper(realm.getObjectPrototype(), 0.0);
        realm.numberPrototype = proto;
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "Number", 1, proto, (function, receiver, args, newTarget) -> {
            double n;
            if (args.length == 0) {
                n = 0.0;
            } else {
                Object primitive = Ops.toNumeric(args[0]);
                n = primitive instanceof BigInteger integer ? integer.doubleValue() : (Double) primitive;
            }
            if (newTarget == null) return n;
            return new JSPrimitiveWrapper(Ops.getPrototypeFromConstructor(newTarget, r -> r.getNumberPrototype()), n);
        });
        BuiltinsKt.global(realm, "Number", ctor);
        BuiltinsKt.value(ctor, "EPSILON", Math.ulp(1.0), Attr.NONE);
        BuiltinsKt.value(ctor, "MAX_SAFE_INTEGER", 9007199254740991.0, Attr.NONE);
        BuiltinsKt.value(ctor, "MAX_VALUE", Double.MAX_VALUE, Attr.NONE);
        BuiltinsKt.value(ctor, "MIN_SAFE_INTEGER", -9007199254740991.0, Attr.NONE);
        BuiltinsKt.value(ctor, "MIN_VALUE", Double.MIN_VALUE, Attr.NONE);
        BuiltinsKt.value(ctor, "NaN", Double.NaN, Attr.NONE);
        BuiltinsKt.value(ctor, "NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY, Attr.NONE);
        BuiltinsKt.value(ctor, "POSITIVE_INFINITY", Double.POSITIVE_INFINITY, Attr.NONE);
        BuiltinsKt.method(ctor, realm, "isFinite", 1, (function, receiver, args, newTarget) -> {
            Object v = Values.arg(args, 0);
            return v instanceof Double number && !number.isNaN() && !number.isInfinite();
        });
        BuiltinsKt.method(ctor, realm, "isInteger", 1, (function, receiver, args, newTarget) -> {
            Object v = Values.arg(args, 0);
            return v instanceof Double number && Ops.isIntegral(number);
        });
        BuiltinsKt.method(ctor, realm, "isNaN", 1, (function, receiver, args, newTarget) -> {
            Object v = Values.arg(args, 0);
            return v instanceof Double number && number.isNaN();
        });
        BuiltinsKt.method(ctor, realm, "isSafeInteger", 1, (function, receiver, args, newTarget) -> {
            Object v = Values.arg(args, 0);
            return v instanceof Double number && Ops.isIntegral(number) && Math.abs(number) <= 9007199254740991.0;
        });

        BuiltinsKt.method(proto, realm, "toExponential", 1, (function, receiver, args, newTarget) -> {
            double x = thisNumber(receiver, "toExponential");
            double f = Ops.toIntegerOrInfinity(Values.arg(args, 0));
            if (Double.isNaN(x)) return "NaN";
            if (Double.isInfinite(x)) return x > 0 ? "Infinity" : "-Infinity";
            if (f < 0 || f > 100) throw BuiltinsKt.rangeErr("toExponential() argument must be between 0 and 100");
            return NumberConv.toExponential(x,
                    Values.arg(args, 0) == Undefined.INSTANCE ? null : Integer.valueOf((int) f));
        });
        BuiltinsKt.method(proto, realm, "toFixed", 1, (function, receiver, args, newTarget) -> {
            double x = thisNumber(receiver, "toFixed");
            double f = Ops.toIntegerOrInfinity(Values.arg(args, 0));
            if (Double.isInfinite(f) || f < 0 || f > 100) {
                throw BuiltinsKt.rangeErr("toFixed() digits argument must be between 0 and 100");
            }
            if (Double.isNaN(x)) return "NaN";
            if (Double.isInfinite(x)) return NumberConv.toString(x);
            return NumberConv.toFixed(x, (int) f);
        });
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, receiver, args, newTarget) ->
                NumberConv.toString(thisNumber(receiver, "toLocaleString")));
        BuiltinsKt.method(proto, realm, "toPrecision", 1, (function, receiver, args, newTarget) -> {
            double x = thisNumber(receiver, "toPrecision");
            if (Values.arg(args, 0) == Undefined.INSTANCE) return NumberConv.toString(x);
            double p = Ops.toIntegerOrInfinity(Values.arg(args, 0));
            if (Double.isNaN(x)) return "NaN";
            if (Double.isInfinite(x)) return NumberConv.toString(x);
            if (p < 1 || p > 100) throw BuiltinsKt.rangeErr("toPrecision() argument must be between 1 and 100");
            return NumberConv.toPrecision(x, (int) p);
        });
        BuiltinsKt.method(proto, realm, "toString", 1, (function, receiver, args, newTarget) -> {
            double x = thisNumber(receiver, "toString");
            Object r = Values.arg(args, 0);
            double radix = r == Undefined.INSTANCE ? 10.0 : Ops.toIntegerOrInfinity(r);
            if (radix < 2 || radix > 36) throw BuiltinsKt.rangeErr("toString() radix must be between 2 and 36");
            return NumberConv.toStringRadix(x, (int) radix);
        });
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, receiver, args, newTarget) ->
                thisNumber(receiver, "valueOf"));
    }
}
