/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Intl mathematical value: a finite decimal ({@code value}) or one of the special values. Numbers keep their Double
 * (ICU formats doubles by their shortest round-trip decimal, as the spec's tests expect).
 */
final class IntlMV {
    static final int FINITE = 0;
    static final int NEG_ZERO = 1;
    static final int POS_INF = 2;
    static final int NEG_INF = 3;
    static final int NAN = 4;

    static final IntlMV NaN = new IntlMV(NAN, null);
    static final IntlMV PosInf = new IntlMV(POS_INF, null);
    static final IntlMV NegInf = new IntlMV(NEG_INF, null);
    static final IntlMV NegZero = new IntlMV(NEG_ZERO, null);

    final int kind;
    final Number value;

    private IntlMV(int kind, Number value) {
        this.kind = kind;
        this.value = value;
    }

    boolean isNaN() {
        return kind == NAN;
    }

    boolean isFinite() {
        return kind == FINITE || kind == NEG_ZERO;
    }

    /** -1, 0 or 1 (negative zero and negative infinity count as negative). */
    int signum() {
        switch (kind) {
            case NEG_ZERO:
            case NEG_INF:
                return -1;
            case POS_INF:
                return 1;
            case NAN:
                return 0;
            default: {
                Number number = value;
                if (number instanceof Double) {
                    double d = (Double) number;
                    return d < 0 ? -1 : d > 0 ? 1 : 0;
                }
                if (number instanceof BigInteger) {
                    return ((BigInteger) number).signum();
                }
                return ((BigDecimal) number).signum();
            }
        }
    }

    /** The value handed to ICU. */
    Number toIcu() {
        switch (kind) {
            case NAN:
                return Double.NaN;
            case POS_INF:
                return Double.POSITIVE_INFINITY;
            case NEG_INF:
                return Double.NEGATIVE_INFINITY;
            case NEG_ZERO:
                return -0.0;
            default:
                return value;
        }
    }

    static IntlMV of(double d) {
        if (Double.isNaN(d)) {
            return NaN;
        }
        if (d == Double.POSITIVE_INFINITY) {
            return PosInf;
        }
        if (d == Double.NEGATIVE_INFINITY) {
            return NegInf;
        }
        if (d == 0.0 && 1.0 / d < 0) {
            return NegZero;
        }
        return new IntlMV(FINITE, d);
    }

    static IntlMV of(BigInteger value) {
        return new IntlMV(FINITE, value);
    }

    static IntlMV of(BigDecimal value) {
        return new IntlMV(FINITE, value);
    }

    /** ToIntlMathematicalValue(value) */
    static IntlMV from(Object value) {
        if (value instanceof Double) {
            return of((Double) value);
        }
        Object prim = Ops.toPrimitive(value, Ops.HINT_NUMBER);
        if (prim instanceof BigInteger) {
            return of((BigInteger) prim);
        }
        if (prim instanceof CharSequence) {
            return parse(prim.toString());
        }
        return of(Ops.toNumber(prim));
    }

    private static boolean isJsWhitespace(char c) {
        switch (c) {
            case '\t':
            case '\n':
            case '\u000B':
            case '\u000C':
            case '\r':
            case ' ':
            case '\u00A0':
            case '\u1680':
            case '\u2028':
            case '\u2029':
            case '\u202F':
            case '\u205F':
            case '\u3000':
            case '\uFEFF':
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A';
        }
    }

    /** StringIntlMV of a StringNumericLiteral, followed by RoundMVResult range checks. */
    static IntlMV parse(String source) {
        int a = 0;
        int b = source.length();
        while (a < b && isJsWhitespace(source.charAt(a))) {
            a++;
        }
        while (b > a && isJsWhitespace(source.charAt(b - 1))) {
            b--;
        }
        String s = source.substring(a, b);
        if (s.isEmpty()) {
            return of(BigDecimal.ZERO);
        }
        if (s.length() > 2 && s.charAt(0) == '0') {
            int radix;
            switch (s.charAt(1)) {
                case 'x':
                case 'X':
                    radix = 16;
                    break;
                case 'o':
                case 'O':
                    radix = 8;
                    break;
                case 'b':
                case 'B':
                    radix = 2;
                    break;
                default:
                    radix = 0;
                    break;
            }
            if (radix != 0) {
                String digits = s.substring(2);
                for (int i = 0; i < digits.length(); i++) {
                    char c = digits.charAt(i);
                    if (Character.digit(c, radix) < 0 || c > 127) {
                        return NaN;
                    }
                }
                return checkRange(new BigDecimal(new BigInteger(digits, radix)), false);
            }
        }
        int i = 0;
        boolean negative = false;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            negative = s.charAt(0) == '-';
            i = 1;
        }
        String body = s.substring(i);
        if ("Infinity".equals(body)) {
            return negative ? NegInf : PosInf;
        }
        if (!isDecimalLiteral(body)) {
            return NaN;
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(body);
        } catch (RuntimeException outOfRange) {
            // the literal is valid, so its exponent is out of BigDecimal's range: the value is 0 or infinite
            boolean zerosOnly = true;
            for (int k = 0; k < body.length(); k++) {
                char c = body.charAt(k);
                if (!(c == '0' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'
                        || (c >= '0' && c <= '9'))) {
                    zerosOnly = false;
                    break;
                }
            }
            int exponent = body.indexOf('e');
            if (exponent < 0) {
                exponent = body.indexOf('E');
            }
            String mantissa = exponent < 0 ? body : body.substring(0, exponent);
            boolean mantissaZeros = true;
            for (int k = 0; k < mantissa.length(); k++) {
                char c = mantissa.charAt(k);
                if (!(c == '0' || c == '.')) {
                    mantissaZeros = false;
                    break;
                }
            }
            if (zerosOnly && mantissaZeros) {
                return negative ? NegZero : of(BigDecimal.ZERO);
            }
            if (body.contains("e-") || body.contains("E-")) {
                return negative ? NegZero : of(BigDecimal.ZERO);
            }
            return negative ? NegInf : PosInf;
        }
        return checkRange(negative ? decimal.negate() : decimal, negative);
    }

    /** StrUnsignedDecimalLiteral (digits with optional '.' and exponent; no separators). */
    private static boolean isDecimalLiteral(String s) {
        int i = 0;
        int n = s.length();
        int digits = 0;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
            digits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return false;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int start = i;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == start) {
                return false;
            }
        }
        return i == n;
    }

    /** RoundMVResult(abs(mv)): values rounding to 0 or Infinity as a Number become the special values. */
    private static IntlMV checkRange(BigDecimal value, boolean negative) {
        if (value.signum() == 0) {
            return negative ? NegZero : of(BigDecimal.ZERO);
        }
        BigDecimal abs = value.abs();
        // precision - scale - 1 is the decimal exponent
        long exponent = (long) abs.precision() - (long) abs.scale() - 1;
        if (exponent > 400 || (exponent > 300 && Double.isInfinite(abs.doubleValue()))) {
            return value.signum() < 0 ? NegInf : PosInf;
        }
        if (exponent < -400 || (exponent < -300 && abs.doubleValue() == 0.0)) {
            return value.signum() < 0 ? NegZero : of(BigDecimal.ZERO);
        }
        return of(value);
    }
}
