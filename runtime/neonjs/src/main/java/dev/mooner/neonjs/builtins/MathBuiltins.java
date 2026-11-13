/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Values;

import java.util.function.DoubleUnaryOperator;

/** Math built-ins; derived from NeonJS and adapted to Java. */
final class MathBuiltins {
    private MathBuiltins() {
    }

    private static void f1(Realm realm, JSObject math, String name, DoubleUnaryOperator op) {
        BuiltinsKt.method(math, realm, name, 1, (function, receiver, args, newTarget) ->
                op.applyAsDouble(Ops.toNumber(Values.arg(args, 0))));
    }

    static void install(Realm realm) {
        JSObject m = new JSObject(realm.getObjectPrototype());
        BuiltinsKt.global(realm, "Math", m);
        BuiltinsKt.value(m, "E", Math.E, Attr.NONE);
        BuiltinsKt.value(m, "LN10", Math.log(10.0), Attr.NONE);
        BuiltinsKt.value(m, "LN2", Math.log(2.0), Attr.NONE);
        BuiltinsKt.value(m, "LOG10E", 1.0 / Math.log(10.0), Attr.NONE);
        BuiltinsKt.value(m, "LOG2E", 1.0 / Math.log(2.0), Attr.NONE);
        BuiltinsKt.value(m, "PI", Math.PI, Attr.NONE);
        BuiltinsKt.value(m, "SQRT1_2", Math.sqrt(0.5), Attr.NONE);
        BuiltinsKt.value(m, "SQRT2", Math.sqrt(2.0), Attr.NONE);
        BuiltinsKt.value(m, JSSymbol.toStringTag, "Math", Attr.CONFIGURABLE);
        f1(realm, m, "abs", Math::abs);
        f1(realm, m, "acos", Math::acos);
        f1(realm, m, "acosh", MathExtra::acosh);
        f1(realm, m, "asin", Math::asin);
        f1(realm, m, "asinh", MathExtra::asinh);
        f1(realm, m, "atan", Math::atan);
        f1(realm, m, "atanh", MathExtra::atanh);
        f1(realm, m, "cbrt", Math::cbrt);
        f1(realm, m, "ceil", Math::ceil);
        f1(realm, m, "clz32", x -> (double) Integer.numberOfLeadingZeros(Ops.toInt32(x)));
        f1(realm, m, "cos", Math::cos);
        f1(realm, m, "cosh", Math::cosh);
        f1(realm, m, "exp", Math::exp);
        f1(realm, m, "expm1", Math::expm1);
        f1(realm, m, "floor", Math::floor);
        f1(realm, m, "fround", x -> (double) (float) x);
        f1(realm, m, "log", Math::log);
        f1(realm, m, "log1p", Math::log1p);
        f1(realm, m, "log10", Math::log10);
        f1(realm, m, "log2", x -> {
            double r = Math.log(x) / Math.log(2.0);
            double ri = Math.rint(r);
            if (x > 0 && Math.abs(r - ri) < 1e-12 && Math.pow(2.0, ri) == x) return ri;
            return r;
        });
        f1(realm, m, "round", x -> {
            if (Double.isNaN(x) || Double.isInfinite(x)) return x;
            if (x == 0.0) return x;
            if (x > 0 && x < 0.5) return 0.0;
            if (x < 0 && x >= -0.5) return -0.0;
            double fl = Math.floor(x);
            return x - fl >= 0.5 ? fl + 1 : fl;
        });
        f1(realm, m, "sign", x -> {
            if (Double.isNaN(x)) return x;
            if (x > 0) return 1.0;
            if (x < 0) return -1.0;
            return x;
        });
        f1(realm, m, "sin", Math::sin);
        f1(realm, m, "sinh", Math::sinh);
        f1(realm, m, "sqrt", Math::sqrt);
        f1(realm, m, "tan", Math::tan);
        f1(realm, m, "tanh", Math::tanh);
        f1(realm, m, "trunc", x -> {
            if (Double.isNaN(x) || Double.isInfinite(x)) return x;
            if (x < 0) return Math.ceil(x);
            return Math.floor(x);
        });
        f1(realm, m, "f16round", Float16::round);
        BuiltinsKt.method(m, realm, "atan2", 2, (function, receiver, args, newTarget) -> {
            double y = Ops.toNumber(Values.arg(args, 0));
            double x = Ops.toNumber(Values.arg(args, 1));
            return Math.atan2(y, x);
        });
        BuiltinsKt.method(m, realm, "hypot", 2, (function, receiver, args, newTarget) -> {
            double[] nums = new double[args.length];
            for (int i = 0; i < args.length; i++) nums[i] = Ops.toNumber(args[i]);
            boolean inf = false;
            boolean nan = false;
            for (double d : nums) {
                if (Double.isInfinite(d)) inf = true;
                if (Double.isNaN(d)) nan = true;
            }
            if (inf) return Double.POSITIVE_INFINITY;
            if (nan) return Double.NaN;
            double max = 0.0;
            for (double d : nums) max = Math.max(max, Math.abs(d));
            if (max == 0.0) return 0.0;
            double sum = 0.0;
            double comp = 0.0;
            for (double d : nums) {
                double r = d / max;
                double y = r * r - comp;
                double t = sum + y;
                comp = (t - sum) - y;
                sum = t;
            }
            return Math.sqrt(sum) * max;
        });
        BuiltinsKt.method(m, realm, "imul", 2, (function, receiver, args, newTarget) ->
                (double) (Ops.toInt32(Values.arg(args, 0)) * Ops.toInt32(Values.arg(args, 1))));
        BuiltinsKt.method(m, realm, "max", 2, (function, receiver, args, newTarget) -> {
            double[] nums = new double[args.length];
            for (int i = 0; i < args.length; i++) nums[i] = Ops.toNumber(args[i]);
            double r = Double.NEGATIVE_INFINITY;
            for (double d : nums) {
                if (Double.isNaN(d)) {
                    r = Double.NaN;
                    break;
                }
                if (d > r || (d == 0.0 && r == 0.0 && 1.0 / r < 0)) r = d;
            }
            return r;
        });
        BuiltinsKt.method(m, realm, "min", 2, (function, receiver, args, newTarget) -> {
            double[] nums = new double[args.length];
            for (int i = 0; i < args.length; i++) nums[i] = Ops.toNumber(args[i]);
            double r = Double.POSITIVE_INFINITY;
            for (double d : nums) {
                if (Double.isNaN(d)) {
                    r = Double.NaN;
                    break;
                }
                if (d < r || (d == 0.0 && r == 0.0 && 1.0 / d < 0)) r = d;
            }
            return r;
        });
        BuiltinsKt.method(m, realm, "pow", 2, (function, receiver, args, newTarget) ->
                Ops.pow(Ops.toNumber(Values.arg(args, 0)), Ops.toNumber(Values.arg(args, 1))));
        BuiltinsKt.method(m, realm, "random", 0, (function, receiver, args, newTarget) ->
                function.realm.agent.nextRandom());
        BuiltinsKt.method(m, realm, "sumPrecise", 1, (function, receiver, args, newTarget) ->
                SumPrecise.sum(function.realm, Values.arg(args, 0)));
    }
}
