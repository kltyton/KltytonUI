/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/** ToSecondsStringPrecisionRecord: precision (-1 auto, -2 minute, else digits), unit, increment. */
final class Precision {
    public static final int AUTO = -1;
    public static final int MINUTE = -2;

    public final int precision;
    public final TUnit unit;
    public final long increment;

    public Precision(int precision, TUnit unit, long increment) {
        this.precision = precision;
        this.unit = unit;
        this.increment = increment;
    }

    public static Precision of(int smallestUnit, int digits) {
        if (smallestUnit >= 0) {
            switch (TUnit.ALL[smallestUnit]) {
                case MINUTE:
                    return new Precision(MINUTE, TUnit.MINUTE, 1);
                case SECOND:
                    return new Precision(0, TUnit.SECOND, 1);
                case MILLISECOND:
                    return new Precision(3, TUnit.MILLISECOND, 1);
                case MICROSECOND:
                    return new Precision(6, TUnit.MICROSECOND, 1);
                default:
                    return new Precision(9, TUnit.NANOSECOND, 1);
            }
        }
        if (digits < 0) return new Precision(AUTO, TUnit.NANOSECOND, 1);
        if (digits == 0) return new Precision(0, TUnit.SECOND, 1);
        if (digits <= 3) return new Precision(digits, TUnit.MILLISECOND, pow10(3 - digits));
        if (digits <= 6) return new Precision(digits, TUnit.MICROSECOND, pow10(6 - digits));
        return new Precision(digits, TUnit.NANOSECOND, pow10(9 - digits));
    }

    private static long pow10(int n) {
        long r = 1L;
        for (int i = 0; i < n; i++) r *= 10;
        return r;
    }
}
