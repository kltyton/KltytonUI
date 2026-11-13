/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

/** Inverse hyperbolic functions after fdlibm (s_asinh.c, e_acosh.c, e_atanh.c): log1p forms avoid cancellation. */
final class MathExtra {
    private static final double LN2 = 0.6931471805599453;
    private static final double TWO28 = 268435456.0;

    private MathExtra() {
    }

    static double asinh(double x) {
        if (x == 0.0 || !Double.isFinite(x)) return x;
        double a = Math.abs(x);
        double r;
        if (a > TWO28) r = Math.log(a) + LN2;
        else if (a > 2.0) r = Math.log(2.0 * a + 1.0 / (Math.sqrt(a * a + 1.0) + a));
        else if (a < 1.0 / TWO28) r = a;
        else r = Math.log1p(a + a * a / (1.0 + Math.sqrt(1.0 + a * a)));
        return x < 0 ? -r : r;
    }

    static double acosh(double x) {
        if (Double.isNaN(x) || x < 1.0) return Double.NaN;
        if (x >= TWO28) return Double.isInfinite(x) ? x : Math.log(x) + LN2;
        if (x == 1.0) return 0.0;
        if (x > 2.0) return Math.log(2.0 * x - 1.0 / (x + Math.sqrt(x * x - 1.0)));
        double t = x - 1.0;
        return Math.log1p(t + Math.sqrt(2.0 * t + t * t));
    }

    static double atanh(double x) {
        if (Double.isNaN(x)) return x;
        double a = Math.abs(x);
        if (a > 1.0) return Double.NaN;
        if (a == 1.0) return x > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        if (a < 1.0 / TWO28) return x;
        double r;
        if (a < 0.5) {
            double t = a + a;
            r = 0.5 * Math.log1p(t + t * a / (1.0 - a));
        } else {
            r = 0.5 * Math.log1p((a + a) / (1.0 - a));
        }
        return x < 0 ? -r : r;
    }
}
