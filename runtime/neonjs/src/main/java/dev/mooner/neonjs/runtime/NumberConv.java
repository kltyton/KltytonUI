package dev.mooner.neonjs.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** ECMA-262 numeric string conversions; derived from NeonJS and adapted to Java. */
public final class NumberConv {
    private static final String[] SMALL_INTS = new String[1024];
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";

    static {
        for (int i = 0; i < SMALL_INTS.length; i++) SMALL_INTS[i] = Integer.toString(i);
    }

    private NumberConv() {
    }

    public static Decimal shortest(double value) {
        String text = Double.toString(value);
        int exponentAt = text.indexOf('E');
        String mantissa = exponentAt >= 0 ? text.substring(0, exponentAt) : text;
        int exponent = exponentAt >= 0 ? Integer.parseInt(text.substring(exponentAt + 1)) : 0;
        int dot = mantissa.indexOf('.');
        String integer = dot >= 0 ? mantissa.substring(0, dot) : mantissa;
        String fraction = dot >= 0 ? mantissa.substring(dot + 1) : "";
        String digits = integer + fraction;
        int point = integer.length() + exponent;
        int leading = 0;
        while (leading < digits.length() - 1 && digits.charAt(leading) == '0') leading++;
        digits = digits.substring(leading);
        point -= leading;
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        digits = digits.substring(0, end);
        if (digits.length() == 2) {
            Decimal single = oneDigit(value, digits, point);
            if (single != null) return single;
        }
        return new Decimal(digits, point);
    }

    private static Decimal oneDigit(double value, String digits, int point) {
        BigDecimal exact = new BigDecimal(value);
        Decimal best = null;
        BigDecimal distance = null;
        int first = digits.charAt(0) - '0';
        for (int candidate : new int[] {first, first + 1}) {
            String digit = candidate == 10 ? "1" : Integer.toString(candidate);
            int candidatePoint = candidate == 10 ? point + 1 : point;
            if (digit.equals("0")) continue;
            BigDecimal number = new BigDecimal(new BigInteger(digit), -(candidatePoint - 1));
            if (number.doubleValue() != value) continue;
            BigDecimal candidateDistance = number.subtract(exact).abs();
            if (distance == null || candidateDistance.compareTo(distance) < 0) {
                best = new Decimal(digit, candidatePoint);
                distance = candidateDistance;
            }
        }
        return best;
    }

    public static String toString(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == 0) return "0";
        if (value >= 0 && value < 1024) {
            int integer = (int) value;
            if ((double) integer == value) return SMALL_INTS[integer];
        }
        if (value < 0) return "-" + toString(-value);
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value < 2147483647.0) {
            int integer = (int) value;
            if ((double) integer == value) return Integer.toString(integer);
        }
        Decimal decimal = shortest(value);
        int count = decimal.digits.length();
        int point = decimal.n;
        StringBuilder out = new StringBuilder();
        if (point >= count && point <= 21) {
            out.append(decimal.digits);
            for (int i = 0; i < point - count; i++) out.append('0');
        } else if (point >= 1 && point <= 21) {
            out.append(decimal.digits, 0, point).append('.').append(decimal.digits, point, count);
        } else if (point > -6 && point <= 0) {
            out.append("0.");
            for (int i = 0; i < -point; i++) out.append('0');
            out.append(decimal.digits);
        } else {
            int exponent = point - 1;
            out.append(decimal.digits.charAt(0));
            if (count > 1) out.append('.').append(decimal.digits, 1, count);
            out.append('e').append(exponent >= 0 ? '+' : '-').append(Math.abs(exponent));
        }
        return out.toString();
    }

    public static String toStringRadix(double original, int radix) {
        if (radix == 10) return toString(original);
        if (Double.isNaN(original)) return "NaN";
        if (original == Double.POSITIVE_INFINITY) return "Infinity";
        if (original == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (original == 0) return "0";
        if (original == Math.rint(original) && Math.abs(original) < 9.007199254740992E15) {
            if (radix < 2 || radix > 36) throw new IllegalArgumentException("radix must be between 2 and 36");
            return Long.toString((long) original, radix);
        }
        int size = 2200;
        char[] buffer = new char[size];
        int integerCursor = size / 2;
        int fractionCursor = integerCursor;
        boolean negative = original < 0;
        double value = negative ? -original : original;
        double integer = Math.floor(value);
        double fraction = value - integer;
        double delta = Math.max(Math.nextUp(0.0), 0.5 * (Math.nextUp(value) - value));
        if (fraction >= delta) {
            buffer[fractionCursor++] = '.';
            do {
                fraction *= radix;
                delta *= radix;
                int digit = (int) fraction;
                buffer[fractionCursor++] = CHARS.charAt(digit);
                fraction -= digit;
                if (fraction > 0.5 || (fraction == 0.5 && (digit & 1) != 0)) {
                    if (fraction + delta > 1) {
                        while (true) {
                            fractionCursor--;
                            if (fractionCursor == size / 2) {
                                integer++;
                                break;
                            }
                            char c = buffer[fractionCursor];
                            int previous = c > '9' ? c - 'a' + 10 : c - '0';
                            if (previous + 1 < radix) {
                                buffer[fractionCursor++] = CHARS.charAt(previous + 1);
                                break;
                            }
                        }
                        break;
                    }
                }
            } while (fraction >= delta);
        }
        while (Math.getExponent(integer / radix) > 52) {
            integer /= radix;
            buffer[--integerCursor] = '0';
        }
        do {
            double remainder = integer % radix;
            buffer[--integerCursor] = CHARS.charAt((int) remainder);
            integer = (integer - remainder) / radix;
        } while (integer > 0);
        if (negative) buffer[--integerCursor] = '-';
        return new String(buffer, integerCursor, fractionCursor - integerCursor);
    }

    public static boolean isJSWhitespace(char value) {
        return switch (value) {
            case 0x0009, 0x000B, 0x000C, 0x0020, 0x00A0, 0xFEFF, 0x000A, 0x000D, 0x2028, 0x2029 -> true;
            default -> value > 0x7F && value != 0x180E && Character.getType(value) == Character.SPACE_SEPARATOR;
        };
    }

    public static String trim(CharSequence value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJSWhitespace(value.charAt(start))) start++;
        while (end > start && isJSWhitespace(value.charAt(end - 1))) end--;
        return value.subSequence(start, end).toString();
    }

    public static int digitVal(char value, int radix) {
        int digit;
        if (value >= '0' && value <= '9') digit = value - '0';
        else if (value >= 'a' && value <= 'z') digit = value - 'a' + 10;
        else if (value >= 'A' && value <= 'Z') digit = value - 'A' + 10;
        else return -1;
        return digit < radix ? digit : -1;
    }

    public static double stringToNumber(CharSequence original) {
        String value = trim(original);
        int size = value.length();
        if (size == 0) return 0;
        char first = value.charAt(0);
        if (size > 2 && first == '0') {
            int radix = switch (value.charAt(1)) {
                case 'x', 'X' -> 16;
                case 'o', 'O' -> 8;
                case 'b', 'B' -> 2;
                default -> 0;
            };
            if (radix != 0) return parseRadixDigits(value, radix);
        }
        int index = first == '+' || first == '-' ? 1 : 0;
        if (value.regionMatches(index, "Infinity", 0, 8) && size == index + 8) {
            return first == '-' ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        int digits = 0;
        while (index < size && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
            index++;
            digits++;
        }
        if (index < size && value.charAt(index) == '.') {
            index++;
            while (index < size && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
                index++;
                digits++;
            }
        }
        if (digits == 0) return Double.NaN;
        if (index < size && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            if (index < size && (value.charAt(index) == '+' || value.charAt(index) == '-')) index++;
            int exponentDigits = 0;
            while (index < size && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
                index++;
                exponentDigits++;
            }
            if (exponentDigits == 0) return Double.NaN;
        }
        if (index != size) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private static double parseRadixDigits(String value, int radix) {
        if (value.length() <= 2) return Double.NaN;
        for (int i = 2; i < value.length(); i++) if (digitVal(value.charAt(i), radix) < 0) return Double.NaN;
        int count = value.length() - 2;
        if ((radix == 16 && count <= 13) || (radix == 8 && count <= 17) || (radix == 2 && count <= 53)) {
            return (double) Long.parseLong(value.substring(2), radix);
        }
        return new BigInteger(value.substring(2), radix).doubleValue();
    }

    public static String toFixed(double value, int fractionDigits) {
        if (Double.isNaN(value)) return "NaN";
        if (Math.abs(value) >= 1e21) return toString(value);
        BigDecimal rounded = new BigDecimal(value).setScale(fractionDigits, RoundingMode.HALF_UP);
        String text = rounded.abs().toPlainString();
        if (rounded.signum() == 0 && value < 0) return "-" + text;
        return value < 0 ? "-" + text : text;
    }

    public static String toExponential(double original, Integer fractionDigits) {
        if (Double.isNaN(original)) return "NaN";
        boolean negative = original < 0;
        double value = Math.abs(original);
        if (value == Double.POSITIVE_INFINITY) return negative ? "-Infinity" : "Infinity";
        String digits;
        int exponent;
        if (value == 0) {
            digits = "0".repeat((fractionDigits == null ? 0 : fractionDigits) + 1);
            exponent = 0;
        } else if (fractionDigits == null) {
            Decimal decimal = shortest(value);
            digits = decimal.digits;
            exponent = decimal.n - 1;
        } else {
            BigDecimal rounded = new BigDecimal(value).round(new MathContext(fractionDigits + 1, RoundingMode.HALF_UP));
            String unscaled = rounded.unscaledValue().toString();
            exponent = unscaled.length() - 1 - rounded.scale();
            if (unscaled.length() < fractionDigits + 1) unscaled += "0".repeat(fractionDigits + 1 - unscaled.length());
            digits = unscaled.substring(0, fractionDigits + 1);
        }
        StringBuilder out = new StringBuilder();
        if (negative) out.append('-');
        out.append(digits.charAt(0));
        if (digits.length() > 1) out.append('.').append(digits, 1, digits.length());
        out.append('e').append(exponent >= 0 ? '+' : '-').append(Math.abs(exponent));
        return out.toString();
    }

    public static String toPrecision(double original, int precision) {
        if (Double.isNaN(original)) return "NaN";
        boolean negative = original < 0;
        double value = Math.abs(original);
        if (value == Double.POSITIVE_INFINITY) return negative ? "-Infinity" : "Infinity";
        String digits;
        int exponent;
        if (value == 0) {
            digits = "0".repeat(precision);
            exponent = 0;
        } else {
            BigDecimal rounded = new BigDecimal(value).round(new MathContext(precision, RoundingMode.HALF_UP));
            String unscaled = rounded.unscaledValue().toString();
            exponent = unscaled.length() - 1 - rounded.scale();
            if (unscaled.length() < precision) unscaled += "0".repeat(precision - unscaled.length());
            digits = unscaled.substring(0, precision);
        }
        StringBuilder out = new StringBuilder();
        if (negative) out.append('-');
        if (exponent < -6 || exponent >= precision) {
            out.append(digits.charAt(0));
            if (precision > 1) out.append('.').append(digits, 1, precision);
            out.append('e').append(exponent >= 0 ? '+' : '-').append(Math.abs(exponent));
            return out.toString();
        }
        if (exponent == precision - 1) return out.append(digits).toString();
        if (exponent >= 0) return out.append(digits, 0, exponent + 1).append('.')
                .append(digits, exponent + 1, precision).toString();
        out.append("0.");
        for (int i = 0; i < -(exponent + 1); i++) out.append('0');
        return out.append(digits).toString();
    }

    public static final class Decimal {
        public final String digits;
        public final int n;

        public Decimal(String digits, int n) {
            this.digits = digits;
            this.n = n;
        }

        public String getDigits() { return digits; }
        public int getN() { return n; }
    }
}
