/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.Ops;

/** File-level helpers of the Temporal abstract operations. */
final class TemporalAbstractOps {
    public static final TemporalAbstractOps INSTANCE = new TemporalAbstractOps();

    private TemporalAbstractOps() {
    }

    /** Throws a TypeError; the return type lets callers use {@code throw tTypeErr(...)}. */
    public static JSException tTypeErr(String msg) {
        throw JSException.typeError(msg);
    }

    /** Throws a RangeError; the return type lets callers use {@code throw tRangeErr(...)}. */
    public static JSException tRangeErr(String msg) {
        throw JSException.rangeError(msg);
    }

    /** ASCII-case-insensitive match; {@code lower} must already be ASCII-lowercase. */
    public static boolean asciiEqualsIgnoreCase(String s, String lower) {
        if (s.length() != lower.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if (c != lower.charAt(i)) return false;
        }
        return true;
    }

    /** ToIntegerWithTruncation. */
    public static double toIntegerWithTruncation(Object v) {
        double n = Ops.toNumber(v);
        if (Double.isNaN(n) || Double.isInfinite(n)) throw tRangeErr("value must be a finite number");
        double t = n < 0 ? Math.ceil(n) : Math.floor(n);
        return t + 0.0;
    }

    /** ToPositiveIntegerWithTruncation. */
    public static double toPositiveIntegerWithTruncation(Object v) {
        double i = toIntegerWithTruncation(v);
        if (i <= 0) throw tRangeErr("value must be positive");
        return i;
    }

    /** ToIntegerIfIntegral. */
    public static double toIntegerIfIntegral(Object v) {
        double n = Ops.toNumber(v);
        if (Double.isNaN(n) || Double.isInfinite(n) || n != Math.floor(n)) {
            throw tRangeErr("value must be an integer");
        }
        return n + 0.0;
    }
}
