/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Numeric helpers. */
final class TNum {
    public static final TNum INSTANCE = new TNum();

    public static final BigInteger BI_1000 = BigInteger.valueOf(1000);
    public static final BigInteger BI_1E6 = BigInteger.valueOf(1_000_000);
    public static final BigInteger BI_1E9 = BigInteger.valueOf(1_000_000_000);
    public static final BigInteger BI_60 = BigInteger.valueOf(60);
    public static final BigInteger BI_3600 = BigInteger.valueOf(3600);

    private TNum() {
    }

    /** Exact BigInteger of an integral Double. */
    public static BigInteger big(double d) {
        if (Math.abs(d) < 9.0e18) return BigInteger.valueOf((long) d);
        return new BigDecimal(d).toBigInteger();
    }

    /** Normalizes -0 to +0. */
    public static double pz(double d) {
        return d == 0.0 ? 0.0 : d;
    }

    /** Negation without producing -0. */
    public static double neg(double d) {
        return d == 0.0 ? 0.0 : -d;
    }

    /** Correctly rounded n / d as a Double. */
    public static double divToDouble(BigInteger n, BigInteger d) {
        if (n.signum() == 0) return 0.0;
        BigInteger num = n.abs();
        BigInteger den = d.abs();
        boolean negative = (n.signum() < 0) != (d.signum() < 0);
        // scale so that the integer quotient has at least 66 significant bits
        int shift = 66 - (num.bitLength() - den.bitLength());
        if (shift > 0) num = num.shiftLeft(shift);
        else if (shift < 0) den = den.shiftLeft(-shift);
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].signum() != 0) q = q.setBit(0);
        double r = Math.scalb(q.doubleValue(), -shift);
        return negative ? -r : r;
    }

    public static double divToDouble(BigInteger n, long d) {
        return divToDouble(n, BigInteger.valueOf(d));
    }

    /** ApplyUnsignedRoundingMode on |quotient| between r1 (floor) and r1+1. */
    private static boolean applyURM(URM urm, boolean r1Odd, int halfCmp) {
        if (urm == URM.ZERO) return false;
        if (urm == URM.INFINITY) return true;
        if (halfCmp < 0) return false;
        if (halfCmp > 0) return true;
        if (urm == URM.HALF_ZERO) return false;
        if (urm == URM.HALF_INFINITY) return true;
        return r1Odd;
    }

    /** RoundNumberToIncrement(x, increment, mode) for integers. */
    public static BigInteger roundToIncrement(BigInteger x, BigInteger inc, RMode mode) {
        BigInteger[] qr = x.divideAndRemainder(inc);
        BigInteger r = qr[1];
        if (r.signum() == 0) return x;
        boolean negative = x.signum() < 0;
        BigInteger absQ = qr[0].abs();
        boolean up = applyURM(mode.unsigned(negative), absQ.testBit(0),
                r.abs().shiftLeft(1).compareTo(inc));
        BigInteger rounded = up ? absQ.add(BigInteger.ONE) : absQ;
        return (negative ? rounded.negate() : rounded).multiply(inc);
    }

    public static long roundToIncrement(long x, long inc, RMode mode) {
        long r = x % inc;
        if (r == 0L) return x;
        boolean negative = x < 0;
        long absQ = Math.abs(x / inc);
        long twice = Math.abs(r) * 2;
        boolean up = applyURM(mode.unsigned(negative), (absQ & 1L) == 1L, Long.compare(twice, inc));
        long rounded = up ? absQ + 1 : absQ;
        return (negative ? -rounded : rounded) * inc;
    }

    /** RoundNumberToIncrementAsIfPositive. */
    public static BigInteger roundAsIfPositive(BigInteger x, BigInteger inc, RMode mode) {
        BigInteger[] qr = x.divideAndRemainder(inc);
        BigInteger q = qr[0];
        BigInteger r = qr[1];
        if (r.signum() == 0) return x;
        if (r.signum() < 0) {
            q = q.subtract(BigInteger.ONE);
            r = r.add(inc);
        }
        boolean up = applyURM(mode.unsigned(false), q.testBit(0), r.shiftLeft(1).compareTo(inc));
        return (up ? q.add(BigInteger.ONE) : q).multiply(inc);
    }

    /** truncate(x / inc) * inc for integer x. */
    public static long truncToIncrement(long x, long inc) {
        return (x / inc) * inc;
    }
}
