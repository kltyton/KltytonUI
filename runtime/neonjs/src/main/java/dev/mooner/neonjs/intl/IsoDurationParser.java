/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import java.math.BigDecimal;
import java.math.BigInteger;

/** ISO 8601 duration strings as accepted by Temporal (sign, P, Y/M/W/D, T, H/M/S, one trailing fraction). */
final class IsoDurationParser {
    private static final String INVALID = "!";
    private static final long[] SUB_SECOND_SIZES = {60_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L, 1L};

    private final String source;
    private int index;

    IsoDurationParser(String source) {
        this.source = source;
    }

    private String digits() {
        int start = index;
        while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
            index++;
        }
        return index > start ? source.substring(start, index) : null;
    }

    private String fraction() {
        if (index < source.length() && (source.charAt(index) == '.' || source.charAt(index) == ',')) {
            index++;
            int start = index;
            while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
                index++;
            }
            int length = index - start;
            if (length < 1 || length > 9) {
                return INVALID;
            }
            return source.substring(start, index);
        }
        return null;
    }

    private Character designator(String set) {
        if (index < source.length() && set.indexOf(Character.toUpperCase(source.charAt(index))) >= 0) {
            return Character.toUpperCase(source.charAt(index++));
        }
        return null;
    }

    double[] parse() {
        boolean negative = false;
        if (index < source.length()) {
            char c = source.charAt(index);
            if (c == '+' || c == '-' || c == '\u2212') {
                negative = c != '+';
                index++;
            }
        }
        if (designator("P") == null) {
            return null;
        }
        BigDecimal[] out = new BigDecimal[10];
        boolean any = false;
        int lastDate = -1;
        // date part
        while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
            String digits = digits();
            Character unit = designator("YMWD");
            if (unit == null) {
                return null;
            }
            int target;
            switch (unit) {
                case 'Y':
                    target = 0;
                    break;
                case 'M':
                    target = 1;
                    break;
                case 'W':
                    target = 2;
                    break;
                case 'D':
                    target = 3;
                    break;
                default:
                    return null;
            }
            if (target <= lastDate) {
                return null;
            }
            lastDate = target;
            out[target] = new BigDecimal(digits);
            any = true;
        }
        int fractionUnit = -1;
        String fraction = null;
        if (designator("T") != null) {
            int lastTime = -1;
            boolean anyTime = false;
            while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
                if (fraction != null) {
                    return null;
                }
                String digits = digits();
                String part = fraction();
                if (INVALID.equals(part)) {
                    return null;
                }
                Character unit = designator("HMS");
                if (unit == null) {
                    return null;
                }
                int target;
                switch (unit) {
                    case 'H':
                        target = 4;
                        break;
                    case 'M':
                        target = 5;
                        break;
                    case 'S':
                        target = 6;
                        break;
                    default:
                        return null;
                }
                if (target <= lastTime) {
                    return null;
                }
                lastTime = target;
                out[target] = new BigDecimal(digits);
                if (part != null) {
                    fraction = part;
                    fractionUnit = target;
                }
                anyTime = true;
            }
            if (!anyTime) {
                return null;
            }
            any = true;
        }
        if (!any || index != source.length()) {
            return null;
        }
        return toRecord(out, fraction, fractionUnit, negative);
    }

    private static double[] toRecord(BigDecimal[] out, String fraction, int fractionUnit, boolean negative) {
        // spread the fraction of the last time unit over the smaller units (in nanoseconds)
        BigDecimal unitNanoseconds;
        if (fractionUnit == 4) {
            unitNanoseconds = new BigDecimal("3600000000000");
        } else if (fractionUnit == 5) {
            unitNanoseconds = new BigDecimal("60000000000");
        } else {
            unitNanoseconds = new BigDecimal("1000000000");
        }
        BigDecimal[] values = new BigDecimal[10];
        for (int i = 0; i < 10; i++) {
            values[i] = out[i] != null ? out[i] : BigDecimal.ZERO;
        }
        if (fraction != null) {
            BigInteger nanoseconds = new BigDecimal("0." + fraction).multiply(unitNanoseconds).toBigInteger();
            for (int k = 0; k < SUB_SECOND_SIZES.length; k++) {
                int unit = 5 + k;
                if (unit <= fractionUnit) {
                    continue;
                }
                BigInteger divisor = BigInteger.valueOf(SUB_SECOND_SIZES[k]);
                BigInteger quotient = nanoseconds.divide(divisor);
                values[unit] = values[unit].add(new BigDecimal(quotient));
                nanoseconds = nanoseconds.subtract(quotient.multiply(divisor));
            }
        }
        double[] result = new double[10];
        for (int i = 0; i < 10; i++) {
            double d = values[i].doubleValue();
            result[i] = negative && d != 0.0 ? -d : d;
        }
        return result;
    }
}
