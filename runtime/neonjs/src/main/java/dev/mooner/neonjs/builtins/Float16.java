/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

/** IEEE 754 binary16 rounding (Math.f16round, Float16Array). */
public final class Float16 {
    private Float16() {
    }

    public static double round(double x) {
        return toDouble(fromDouble(x));
    }

    public static short fromDouble(double d) {
        if (Double.isNaN(d)) return (short) 0x7E00;
        long bits = Double.doubleToRawLongBits(d);
        int sign = (int) ((bits >>> 48) & 0x8000L);
        double ad = Math.abs(d);
        if (ad >= 65520.0) return (short) (sign | 0x7C00);
        if (ad < 2.9802322387695312E-8) return (short) sign;
        int e = Math.getExponent(ad);
        if (e < -14) {
            double m = ad / Math.scalb(1.0, -24);
            double r = Math.rint(m);
            return (short) (sign | (int) r);
        }
        double mant = ad / Math.scalb(1.0, e) - 1.0;
        double m = Math.rint(mant * 1024.0);
        int exp = e;
        if (m >= 1024.0) {
            m = 0.0;
            exp++;
        }
        if (exp > 15) return (short) (sign | 0x7C00);
        return (short) (sign | ((exp + 15) << 10) | (int) m);
    }

    public static double toDouble(short h) {
        int v = h & 0xFFFF;
        double sign = (v & 0x8000) != 0 ? -1.0 : 1.0;
        int exp = (v >>> 10) & 0x1F;
        int mant = v & 0x3FF;
        if (exp == 0) return sign * Math.scalb((double) mant, -24);
        if (exp == 31) return mant == 0 ? sign * Double.POSITIVE_INFINITY : Double.NaN;
        return sign * Math.scalb(1.0 + mant / 1024.0, exp - 15);
    }
}
