/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;

import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/** ECMAScript abstract operations; derived from NeonJS and adapted to Java. */
public final class Ops {
    private static final Double[] DOUBLE_CACHE = new Double[1024];

    static {
        for (int i = 0; i < DOUBLE_CACHE.length; i++) DOUBLE_CACHE[i] = (double) i;
    }

    public static final int HINT_DEFAULT = 0;
    public static final int HINT_NUMBER = 1;
    public static final int HINT_STRING = 2;

    public static final int MAX_BIGINT_BITS = 1 << 30;

    private Ops() {
    }

    /** Boxes a double, reusing cached boxes for small integers. */
    public static Object num(double value) {
        int integer = (int) value;
        if (integer >= 0 && integer < 1024 && (double) integer == value
                && !(value == 0.0 && 1.0 / value < 0)) {
            return DOUBLE_CACHE[integer];
        }
        return value;
    }

    public static Object num(int value) {
        if (value >= 0 && value < 1024) return DOUBLE_CACHE[value];
        return (double) value;
    }

    public static Object num(long value) {
        if (value >= 0 && value < 1024) return DOUBLE_CACHE[(int) value];
        return (double) value;
    }

    // ------------------------------------------------------------------ type tests

    public static String typeOf(Object value) {
        if (value == Undefined.INSTANCE || value == null) return "undefined";
        if (value == Null.INSTANCE) return "object";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Double) return "number";
        if (value instanceof CharSequence) return "string";
        if (value instanceof JSSymbol) return "symbol";
        if (value instanceof BigInteger) return "bigint";
        if (value instanceof JSObject object) {
            if ((object.special & JSObject.HTMLDDA) != 0) return "undefined";
            return object.isCallable() ? "function" : "object";
        }
        return "object";
    }

    public static boolean isCallable(Object value) {
        return value instanceof JSObject object && (object.special & JSObject.CALLABLE) != 0;
    }

    public static boolean isConstructor(Object value) {
        return value instanceof JSObject object && (object.special & JSObject.CONSTRUCTOR) != 0;
    }

    // ------------------------------------------------------------------ conversions

    public static boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof Double doubleValue) return !(doubleValue == 0.0 || doubleValue.isNaN());
        if (value instanceof CharSequence text) return text.length() != 0;
        if (value == Undefined.INSTANCE || value == Null.INSTANCE || value == null) return false;
        if (value instanceof BigInteger integer) return integer.signum() != 0;
        if (value instanceof JSObject object) return (object.special & JSObject.HTMLDDA) == 0;
        return true;
    }

    public static Object toPrimitive(Object value) {
        return toPrimitive(value, HINT_DEFAULT);
    }

    public static Object toPrimitive(Object value, int hint) {
        if (!(value instanceof JSObject object)) return value;
        Object exotic = getMethod(object, JSSymbol.toPrimitive);
        if (exotic != Undefined.INSTANCE) {
            String hintString = hint == HINT_NUMBER ? "number" : hint == HINT_STRING ? "string" : "default";
            Object result = ((JSObject) exotic).call(object, new Object[]{hintString});
            if (result instanceof JSObject) {
                throw JSException.typeError("Cannot convert object to primitive value");
            }
            return result;
        }
        return ordinaryToPrimitive(object, hint == HINT_STRING ? HINT_STRING : HINT_NUMBER);
    }

    public static Object ordinaryToPrimitive(JSObject object, int hint) {
        String first = hint == HINT_STRING ? "toString" : "valueOf";
        String second = hint == HINT_STRING ? "valueOf" : "toString";
        Object method = object.get(first, object);
        if (isCallable(method)) {
            Object result = ((JSObject) method).call(object, EMPTY_ARGS);
            if (!(result instanceof JSObject)) return result;
        }
        method = object.get(second, object);
        if (isCallable(method)) {
            Object result = ((JSObject) method).call(object, EMPTY_ARGS);
            if (!(result instanceof JSObject)) return result;
        }
        throw JSException.typeError("Cannot convert object to primitive value");
    }

    public static double toNumber(Object value) {
        if (value instanceof Double doubleValue) return doubleValue;
        if (value instanceof Boolean booleanValue) return booleanValue ? 1.0 : 0.0;
        if (value == Undefined.INSTANCE) return Double.NaN;
        if (value == Null.INSTANCE) return 0.0;
        if (value instanceof CharSequence text) return NumberConv.stringToNumber(text);
        if (value instanceof JSSymbol) {
            throw JSException.typeError("Cannot convert a Symbol value to a number");
        }
        if (value instanceof BigInteger) {
            throw JSException.typeError("Cannot convert a BigInt value to a number");
        }
        if (value instanceof JSObject) return toNumber(toPrimitive(value, HINT_NUMBER));
        if (value == null) return Double.NaN;
        if (value instanceof Number number) return number.doubleValue();
        return Double.NaN;
    }

    /** ToNumeric: returns Double or BigInteger. */
    public static Object toNumeric(Object value) {
        if (value instanceof Double) return value;
        if (value instanceof BigInteger) return value;
        Object primitive = toPrimitive(value, HINT_NUMBER);
        if (primitive instanceof BigInteger) return primitive;
        return toNumber(primitive);
    }

    public static double toIntegerOrInfinity(Object value) {
        return integerPart(toNumber(value));
    }

    public static double integerPart(double value) {
        if (value != value) return 0.0;
        if (value == Double.POSITIVE_INFINITY || value == Double.NEGATIVE_INFINITY) return value;
        double truncated = value < 0 ? Math.ceil(value) : Math.floor(value);
        return truncated == 0.0 ? 0.0 : truncated;
    }

    public static int toInt32(double value) {
        // below 2^63 in magnitude, truncating to a long is exact (NaN gives 0) and its low 32 bits are the result; the
        // saturated longs (2^63 and beyond, infinities) take the slow path.
        long truncated = (long) value;
        if (truncated != Long.MAX_VALUE && truncated != Long.MIN_VALUE) return (int) truncated;
        if (Double.isNaN(value) || value == Double.POSITIVE_INFINITY
                || value == Double.NEGATIVE_INFINITY) return 0;
        double integer = value < 0 ? Math.ceil(value) : Math.floor(value);
        double modulo = integer % 4294967296.0;
        return (int) (long) modulo;
    }

    public static int toInt32(Object value) {
        return value instanceof Double doubleValue ? toInt32(doubleValue.doubleValue()) : toInt32(toNumber(value));
    }

    public static long toUint32(Object value) {
        return ((long) toInt32(value)) & 0xFFFFFFFFL;
    }

    public static long toUint32(double value) {
        return ((long) toInt32(value)) & 0xFFFFFFFFL;
    }

    public static int toUint16(Object value) {
        return toInt32(value) & 0xFFFF;
    }

    public static long toLength(Object value) {
        double length = toIntegerOrInfinity(value);
        if (length <= 0) return 0;
        return length >= 9007199254740991.0 ? 9007199254740991L : (long) length;
    }

    public static long toIndex(Object value) {
        if (value == Undefined.INSTANCE) return 0;
        double index = toIntegerOrInfinity(value);
        if (index < 0 || index > 9007199254740991.0) {
            throw JSException.rangeError("Invalid index");
        }
        return (long) index;
    }

    public static String toString(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof CharSequence text) return text.toString();
        if (value instanceof Double doubleValue) return NumberConv.toString(doubleValue);
        if (value instanceof Boolean booleanValue) return booleanValue ? "true" : "false";
        if (value == Undefined.INSTANCE) return "undefined";
        if (value == Null.INSTANCE) return "null";
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof JSSymbol) {
            throw JSException.typeError("Cannot convert a Symbol value to a string");
        }
        if (value instanceof JSObject) return toString(toPrimitive(value, HINT_STRING));
        if (value == null) return "undefined";
        return value.toString();
    }

    /** ToString keeping ropes (for concatenation). */
    public static CharSequence toJSString(Object value) {
        return value instanceof CharSequence text ? text : toString(value);
    }

    public static Object toPropertyKey(Object value) {
        // an Int is never a JS value, only an already canonical array-index key (TO_KEY_FOR_BASE output)
        if (value instanceof Integer) return value;
        if (value instanceof String text) return PK.fromString(text);
        if (value instanceof Double doubleValue) return PK.fromDouble(doubleValue);
        if (value instanceof JSSymbol) return value;
        if (value instanceof CharSequence text) return PK.fromString(text.toString());
        Object primitive = toPrimitive(value, HINT_STRING);
        if (primitive instanceof JSSymbol) return primitive;
        return PK.fromString(toString(primitive));
    }

    public static JSObject toObject(Realm realm, Object value) {
        if (value instanceof JSObject object) return object;
        if (value == Undefined.INSTANCE || value == Null.INSTANCE || value == null) {
            throw JSException.typeError("Cannot convert undefined or null to object");
        }
        if (value instanceof Boolean) return new JSPrimitiveWrapper(realm.getBooleanPrototype(), value);
        if (value instanceof Double) return new JSPrimitiveWrapper(realm.getNumberPrototype(), value);
        if (value instanceof CharSequence text) {
            return new JSStringObject(realm.getStringPrototype(), text.toString());
        }
        if (value instanceof JSSymbol) return new JSPrimitiveWrapper(realm.getSymbolPrototype(), value);
        if (value instanceof BigInteger) return new JSPrimitiveWrapper(realm.getBigintPrototype(), value);
        throw JSException.typeError("Cannot convert to object");
    }

    public static JSObject toObject(Object value) {
        return value instanceof JSObject object ? object : toObject(Agent.currentRealm(), value);
    }

    public static Object requireObjectCoercible(Object value) {
        if (value == Undefined.INSTANCE || value == Null.INSTANCE || value == null) {
            throw JSException.typeError("Cannot convert undefined or null to object");
        }
        return value;
    }

    public static Double canonicalNumericIndexString(String value) {
        if (value.equals("-0")) return -0.0;
        double number = NumberConv.stringToNumber(value);
        if (value.equals("NaN") && Double.isNaN(number)) return number;
        if (Double.isNaN(number)) return null;
        if (!NumberConv.toString(number).equals(value)) return null;
        return number;
    }

    // ------------------------------------------------------------------ BigInt

    public static BigInteger checkBigInt(BigInteger value) {
        if (value.bitLength() > MAX_BIGINT_BITS) {
            throw JSException.rangeError("Maximum BigInt size exceeded");
        }
        return value;
    }

    public static BigInteger stringToBigInt(CharSequence text) {
        String value = NumberConv.trim(text);
        if (value.isEmpty()) return BigInteger.ZERO;
        try {
            if (value.length() > 2 && value.charAt(0) == '0') {
                int radix;
                switch (value.charAt(1)) {
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
                    for (int i = 2; i < value.length(); i++) {
                        if (NumberConv.digitVal(value.charAt(i), radix) < 0) return null;
                    }
                    return new BigInteger(value.substring(2), radix);
                }
            }
            int index = 0;
            if (value.charAt(0) == '+' || value.charAt(0) == '-') index++;
            if (index == value.length()) return null;
            for (int i = index; i < value.length(); i++) {
                if (value.charAt(i) < '0' || value.charAt(i) > '9') return null;
            }
            return new BigInteger(value.charAt(0) == '+' ? value.substring(1) : value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public static BigInteger toBigInt(Object value) {
        Object primitive = toPrimitive(value, HINT_NUMBER);
        if (primitive instanceof BigInteger integer) return integer;
        if (primitive instanceof Boolean booleanValue) return booleanValue ? BigInteger.ONE : BigInteger.ZERO;
        if (primitive instanceof CharSequence text) {
            BigInteger converted = stringToBigInt(text);
            if (converted == null) {
                throw JSException.syntaxError("Cannot convert " + text + " to a BigInt");
            }
            return converted;
        }
        if (primitive == Undefined.INSTANCE || primitive == Null.INSTANCE) {
            throw JSException.typeError("Cannot convert " + toString(primitive) + " to a BigInt");
        }
        if (primitive instanceof Double doubleValue) {
            throw JSException.typeError("Cannot convert " + NumberConv.toString(doubleValue) + " to a BigInt");
        }
        if (primitive instanceof JSSymbol) {
            throw JSException.typeError("Cannot convert a Symbol value to a BigInt");
        }
        throw JSException.typeError("Cannot convert to BigInt");
    }

    public static BigInteger bigIntFromDouble(double value) {
        return new BigDecimal(value).toBigIntegerExact();
    }

    public static boolean isIntegral(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value);
    }

    // ------------------------------------------------------------------ equality

    public static boolean strictEquals(Object a, Object b) {
        if (a instanceof Double left) {
            return b instanceof Double right && left.doubleValue() == right.doubleValue();
        }
        if (a instanceof CharSequence left) {
            return b instanceof CharSequence right && (left == right || contentEquals(left, right));
        }
        if (a instanceof BigInteger left) return b instanceof BigInteger right && left.equals(right);
        if (a instanceof Boolean left) return b instanceof Boolean right && left.equals(right);
        return a == b;
    }

    public static boolean contentEquals(CharSequence a, CharSequence b) {
        return a.length() == b.length() && a.toString().equals(b.toString());
    }

    public static boolean sameValue(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            if (left.isNaN()) return right.isNaN();
            if (left == 0.0 && right == 0.0) return 1.0 / left == 1.0 / right;
            return left.doubleValue() == right.doubleValue();
        }
        return strictEquals(a, b);
    }

    public static boolean sameValueZero(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            if (left.isNaN()) return right.isNaN();
            return left.doubleValue() == right.doubleValue();
        }
        return strictEquals(a, b);
    }

    public static boolean looseEquals(Object a, Object b) {
        Object x = a;
        Object y = b;
        while (true) {
            if (x instanceof Double left && y instanceof Double right) {
                return left.doubleValue() == right.doubleValue();
            }
            if (x instanceof CharSequence left && y instanceof CharSequence right) {
                return contentEquals(left, right);
            }
            if (sameType(x, y)) return strictEquals(x, y);
            boolean xNullish = isNullishOrHtmlDda(x);
            boolean yNullish = isNullishOrHtmlDda(y);
            if ((x == Undefined.INSTANCE || x == Null.INSTANCE) && yNullish) return true;
            if ((y == Undefined.INSTANCE || y == Null.INSTANCE) && xNullish) return true;
            if (x == Undefined.INSTANCE || x == Null.INSTANCE
                    || y == Undefined.INSTANCE || y == Null.INSTANCE) return false;
            if (x instanceof Double left && y instanceof CharSequence right) {
                return left.doubleValue() == NumberConv.stringToNumber(right);
            }
            if (x instanceof CharSequence left && y instanceof Double right) {
                return NumberConv.stringToNumber(left) == right.doubleValue();
            }
            if (x instanceof BigInteger left && y instanceof CharSequence right) {
                BigInteger number = stringToBigInt(right);
                return number != null && left.equals(number);
            }
            if (x instanceof CharSequence left && y instanceof BigInteger right) {
                BigInteger number = stringToBigInt(left);
                return number != null && number.equals(right);
            }
            if (x instanceof Boolean booleanValue) {
                x = booleanValue ? 1.0 : 0.0;
                continue;
            }
            if (y instanceof Boolean booleanY) {
                y = booleanY ? 1.0 : 0.0;
                continue;
            }
            if ((x instanceof Double || x instanceof CharSequence || x instanceof BigInteger || x instanceof JSSymbol)
                    && y instanceof JSObject) {
                y = toPrimitive(y);
                continue;
            }
            if (x instanceof JSObject
                    && (y instanceof Double || y instanceof CharSequence || y instanceof BigInteger
                    || y instanceof JSSymbol)) {
                x = toPrimitive(x);
                continue;
            }
            if (x instanceof BigInteger left && y instanceof Double right) {
                return bigIntEqualsNumber(left, right.doubleValue());
            }
            if (x instanceof Double left && y instanceof BigInteger right) {
                return bigIntEqualsNumber(right, left.doubleValue());
            }
            return false;
        }
    }

    private static boolean isNullishOrHtmlDda(Object value) {
        return value == Undefined.INSTANCE || value == Null.INSTANCE
                || (value instanceof JSObject object && (object.special & JSObject.HTMLDDA) != 0);
    }

    private static boolean bigIntEqualsNumber(BigInteger integer, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return false;
        if (value != Math.floor(value)) return false;
        return integer.equals(bigIntFromDouble(value));
    }

    private static boolean sameType(Object x, Object y) {
        if (x == Undefined.INSTANCE) return y == Undefined.INSTANCE;
        if (x == Null.INSTANCE) return y == Null.INSTANCE;
        if (x instanceof Boolean) return y instanceof Boolean;
        if (x instanceof Double) return y instanceof Double;
        if (x instanceof CharSequence) return y instanceof CharSequence;
        if (x instanceof JSSymbol) return y instanceof JSSymbol;
        if (x instanceof BigInteger) return y instanceof BigInteger;
        if (x instanceof JSObject) return y instanceof JSObject;
        return false;
    }

    // ------------------------------------------------------------------ relational

    /** IsLessThan(x, y); returns null for undefined (NaN involved). Operands must already be primitives. */
    public static Boolean lessThanPrim(Object x, Object y) {
        if (x instanceof CharSequence left && y instanceof CharSequence right) {
            return left.toString().compareTo(right.toString()) < 0;
        }
        if (x instanceof BigInteger left && y instanceof CharSequence right) {
            BigInteger number = stringToBigInt(right);
            if (number == null) return null;
            return left.compareTo(number) < 0;
        }
        if (x instanceof CharSequence left && y instanceof BigInteger right) {
            BigInteger number = stringToBigInt(left);
            if (number == null) return null;
            return number.compareTo(right) < 0;
        }
        Object nx;
        if (x instanceof BigInteger) nx = x;
        else nx = toNumber(x);
        Object ny;
        if (y instanceof BigInteger) ny = y;
        else ny = toNumber(y);
        if (nx instanceof Double left && ny instanceof Double right) {
            if (left.isNaN() || right.isNaN()) return null;
            return left.doubleValue() < right.doubleValue();
        }
        if (nx instanceof BigInteger left && ny instanceof BigInteger right) {
            return left.compareTo(right) < 0;
        }
        if (nx instanceof BigInteger integer) {
            double right = (Double) ny;
            if (Double.isNaN(right)) return null;
            if (right == Double.POSITIVE_INFINITY) return true;
            if (right == Double.NEGATIVE_INFINITY) return false;
            return new BigDecimal(integer).compareTo(new BigDecimal(right)) < 0;
        }
        double left = (Double) nx;
        if (Double.isNaN(left)) return null;
        if (left == Double.POSITIVE_INFINITY) return false;
        if (left == Double.NEGATIVE_INFINITY) return true;
        return new BigDecimal(left).compareTo(new BigDecimal((BigInteger) ny)) < 0;
    }

    public static boolean lt(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() < right.doubleValue();
        }
        Object pa = toPrimitive(a, HINT_NUMBER);
        Object pb = toPrimitive(b, HINT_NUMBER);
        return Boolean.TRUE.equals(lessThanPrim(pa, pb));
    }

    public static boolean gt(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() > right.doubleValue();
        }
        Object pa = toPrimitive(a, HINT_NUMBER);
        Object pb = toPrimitive(b, HINT_NUMBER);
        return Boolean.TRUE.equals(lessThanPrim(pb, pa));
    }

    public static boolean le(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() <= right.doubleValue();
        }
        Object pa = toPrimitive(a, HINT_NUMBER);
        Object pb = toPrimitive(b, HINT_NUMBER);
        return Boolean.FALSE.equals(lessThanPrim(pb, pa));
    }

    public static boolean ge(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() >= right.doubleValue();
        }
        Object pa = toPrimitive(a, HINT_NUMBER);
        Object pb = toPrimitive(b, HINT_NUMBER);
        return Boolean.FALSE.equals(lessThanPrim(pa, pb));
    }

    // ------------------------------------------------------------------ arithmetic

    public static Object add(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() + right.doubleValue();
        }
        if (a instanceof CharSequence left && b instanceof CharSequence right) {
            return Rope.concat(left, right);
        }
        Object pa = toPrimitive(a);
        Object pb = toPrimitive(b);
        if (pa instanceof CharSequence || pb instanceof CharSequence) {
            return Rope.concat(toJSString(pa), toJSString(pb));
        }
        Object na = toNumericPrim(pa);
        Object nb = toNumericPrim(pb);
        if (na instanceof Double left && nb instanceof Double right) {
            return left.doubleValue() + right.doubleValue();
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            return checkBigInt(left.add(right));
        }
        throw mixError();
    }

    private static Object toNumericPrim(Object value) {
        if (value instanceof BigInteger) return value;
        return toNumber(value);
    }

    private static JSException mixError() {
        return JSException.typeError("Cannot mix BigInt and other types, use explicit conversions");
    }

    public static Object sub(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() - right.doubleValue();
        }
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return left.doubleValue() - right.doubleValue();
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            return checkBigInt(left.subtract(right));
        }
        throw mixError();
    }

    public static Object mul(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() * right.doubleValue();
        }
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return left.doubleValue() * right.doubleValue();
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            if ((long) left.bitLength() + right.bitLength() > MAX_BIGINT_BITS) {
                throw JSException.rangeError("Maximum BigInt size exceeded");
            }
            return left.multiply(right);
        }
        throw mixError();
    }

    public static Object div(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() / right.doubleValue();
        }
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return left.doubleValue() / right.doubleValue();
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            if (right.signum() == 0) throw JSException.rangeError("Division by zero");
            return left.divide(right);
        }
        throw mixError();
    }

    public static Object mod(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return left.doubleValue() % right.doubleValue();
        }
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return left.doubleValue() % right.doubleValue();
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            if (right.signum() == 0) throw JSException.rangeError("Division by zero");
            return left.remainder(right);
        }
        throw mixError();
    }

    public static Object exp(Object a, Object b) {
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return pow(left.doubleValue(), right.doubleValue());
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            return bigPow(left, right);
        }
        throw mixError();
    }

    public static double pow(double x, double y) {
        if (Double.isNaN(y)) return Double.NaN;
        if (y == 0.0) return 1.0;
        if ((x == 1.0 || x == -1.0) && Double.isInfinite(y)) return Double.NaN;
        return Math.pow(x, y);
    }

    public static BigInteger bigPow(BigInteger base, BigInteger exponent) {
        if (exponent.signum() < 0) throw JSException.rangeError("Exponent must be non-negative");
        if (exponent.signum() == 0) return BigInteger.ONE;
        if (base.signum() == 0 || base.equals(BigInteger.ONE)) return base;
        if (base.equals(BigInteger.ONE.negate())) {
            return exponent.testBit(0) ? base : BigInteger.ONE;
        }
        if (exponent.bitLength() > 31) {
            throw JSException.rangeError("Maximum BigInt size exceeded");
        }
        int power = exponent.intValue();
        if ((long) base.bitLength() * power > MAX_BIGINT_BITS) {
            throw JSException.rangeError("Maximum BigInt size exceeded");
        }
        return base.pow(power);
    }

    public static Object neg(Object value) {
        if (value instanceof Double doubleValue) return -doubleValue;
        Object numeric = toNumeric(value);
        if (numeric instanceof Double doubleValue) return -doubleValue;
        return ((BigInteger) numeric).negate();
    }

    public static Object bitNot(Object value) {
        Object numeric = toNumeric(value);
        if (numeric instanceof Double doubleValue) {
            return (double) (~toInt32(doubleValue.doubleValue()));
        }
        return ((BigInteger) numeric).not();
    }

    public static Object inc(Object value) {
        if (value instanceof Double doubleValue) return doubleValue + 1.0;
        if (value instanceof BigInteger integer) return integer.add(BigInteger.ONE);
        throw new IllegalStateException("inc on non-numeric");
    }

    public static Object dec(Object value) {
        if (value instanceof Double doubleValue) return doubleValue - 1.0;
        if (value instanceof BigInteger integer) return integer.subtract(BigInteger.ONE);
        throw new IllegalStateException("dec on non-numeric");
    }

    private static Object bitwise(Object a, Object b, IntBinaryOperator intOp, BinaryOperator<BigInteger> bigOp) {
        if (a instanceof Double left && b instanceof Double right) {
            return (double) intOp.applyAsInt(toInt32(left.doubleValue()), toInt32(right.doubleValue()));
        }
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return (double) intOp.applyAsInt(toInt32(left.doubleValue()), toInt32(right.doubleValue()));
        }
        if (na instanceof BigInteger left && nb instanceof BigInteger right) {
            return bigOp.apply(left, right);
        }
        throw mixError();
    }

    public static Object bitAnd(Object a, Object b) {
        return bitwise(a, b, (x, y) -> x & y, BigInteger::and);
    }

    public static Object bitOr(Object a, Object b) {
        return bitwise(a, b, (x, y) -> x | y, BigInteger::or);
    }

    public static Object bitXor(Object a, Object b) {
        return bitwise(a, b, (x, y) -> x ^ y, BigInteger::xor);
    }

    public static Object shl(Object a, Object b) {
        return bitwise(a, b, (x, y) -> x << (y & 31), Ops::bigShift);
    }

    public static Object sar(Object a, Object b) {
        return bitwise(a, b, (x, y) -> x >> (y & 31), (x, y) -> bigShift(x, y.negate()));
    }

    public static Object shr(Object a, Object b) {
        if (a instanceof Double left && b instanceof Double right) {
            return (double) ((((long) toInt32(left.doubleValue())) & 0xFFFFFFFFL)
                    >>> (toInt32(right.doubleValue()) & 31));
        }
        Object na = toNumeric(a);
        Object nb = toNumeric(b);
        if (na instanceof Double left && nb instanceof Double right) {
            return (double) ((((long) toInt32(left.doubleValue())) & 0xFFFFFFFFL)
                    >>> (toInt32(right.doubleValue()) & 31));
        }
        if (na instanceof BigInteger && nb instanceof BigInteger) {
            throw JSException.typeError("BigInts have no unsigned right shift, use >> instead");
        }
        throw mixError();
    }

    private static BigInteger bigShift(BigInteger value, BigInteger shift) {
        if (value.signum() == 0) return value;
        if (shift.bitLength() > 31) {
            if (shift.signum() < 0) {
                return value.signum() < 0 ? BigInteger.ONE.negate() : BigInteger.ZERO;
            }
            throw JSException.rangeError("Maximum BigInt size exceeded");
        }
        int amount = shift.intValue();
        if (amount > 0) {
            if ((long) value.bitLength() + amount > MAX_BIGINT_BITS) {
                throw JSException.rangeError("Maximum BigInt size exceeded");
            }
            return value.shiftLeft(amount);
        }
        return value.shiftRight(-amount);
    }

    // ------------------------------------------------------------------ objects & properties

    /** GetV: property get on any value (primitives use their prototype). */
    public static Object getV(Realm realm, Object value, Object key) {
        if (value instanceof JSObject object) return object.get(key, value);
        if (value instanceof CharSequence text) {
            if ("length".equals(key)) return (double) text.length();
            if (key instanceof Integer index && index < text.length()) {
                return String.valueOf(text.charAt(index));
            }
            return realm.getStringPrototype().get(key, value);
        }
        if (value instanceof Double) return realm.getNumberPrototype().get(key, value);
        if (value instanceof Boolean) return realm.getBooleanPrototype().get(key, value);
        if (value instanceof JSSymbol) return realm.getSymbolPrototype().get(key, value);
        if (value instanceof BigInteger) return realm.getBigintPrototype().get(key, value);
        if (value == Undefined.INSTANCE || value == Null.INSTANCE || value == null) {
            throw JSException.typeError("Cannot read properties of " + toString(value)
                    + " (reading '" + PK.toStringKey(key) + "')");
        }
        throw JSException.typeError("Cannot read property of host value");
    }

    public static Object getV(Object value, Object key) {
        return value instanceof JSObject object ? object.get(key, value)
                : getV(Agent.currentRealm(), value, key);
    }

    /** PutValue for property references; returns false on failure (caller decides on strict-mode error). */
    public static boolean putV(Realm realm, Object base, Object key, Object value) {
        if (base instanceof JSObject object) return object.set(key, value, base);
        JSObject prototype;
        if (base instanceof CharSequence text) {
            if ("length".equals(key) || (key instanceof Integer index && index < text.length())) return false;
            prototype = realm.getStringPrototype();
        } else if (base instanceof Double) {
            prototype = realm.getNumberPrototype();
        } else if (base instanceof Boolean) {
            prototype = realm.getBooleanPrototype();
        } else if (base instanceof JSSymbol) {
            prototype = realm.getSymbolPrototype();
        } else if (base instanceof BigInteger) {
            prototype = realm.getBigintPrototype();
        } else {
            throw JSException.typeError("Cannot set properties of " + toString(base)
                    + " (setting '" + PK.toStringKey(key) + "')");
        }
        return prototype.set(key, value, base);
    }

    public static Object getMethod(Object value, Object key) {
        Object function = getV(value, key);
        if (function == Undefined.INSTANCE || function == Null.INSTANCE) return Undefined.INSTANCE;
        if (!isCallable(function)) {
            throw JSException.typeError(describeKey(key) + " is not a function");
        }
        return function;
    }

    public static Object call(Object function, Object thisArg, Object[] arguments) {
        if (function instanceof JSObject object && (object.special & JSObject.CALLABLE) != 0) {
            return object.call(thisArg, arguments);
        }
        throw JSException.typeError(describe(function) + " is not a function");
    }

    public static Object call(Object function, Object thisArg) {
        return call(function, thisArg, EMPTY_ARGS);
    }

    public static Object construct(Object function, Object[] arguments, Object newTarget) {
        if (function instanceof JSObject object && (object.special & JSObject.CONSTRUCTOR) != 0) {
            JSObject target = (JSObject) newTarget;
            if (target == null) {
                throw new NullPointerException(
                        "null cannot be cast to non-null type dev.mooner.neonjs.runtime.JSObject");
            }
            return object.construct(arguments, target);
        }
        throw JSException.typeError(describe(function) + " is not a constructor");
    }

    public static Object construct(Object function, Object[] arguments) {
        return construct(function, arguments, function);
    }

    public static Object invoke(Object value, Object key, Object[] arguments) {
        Object function = getV(value, key);
        return call(function, value, arguments);
    }

    public static long lengthOfArrayLike(JSObject object) {
        if (object instanceof JSArray array) return array.length;
        return toLength(object.get("length", object));
    }

    public static Object[] createListFromArrayLike(Object value) {
        return createListFromArrayLike(value, false);
    }

    public static Object[] createListFromArrayLike(Object value, boolean onlyPropertyKeys) {
        if (!(value instanceof JSObject object)) {
            throw JSException.typeError("CreateListFromArrayLike called on non-object");
        }
        long length = lengthOfArrayLike(object);
        if (length > 10_000_000) throw JSException.rangeError("Too many arguments");
        Agent agent = Agent.current.get();
        if (length > 8192 && agent != null) agent.reserveAllocation(length * 8);
        Object[] out = new Object[(int) length];
        for (int i = 0; i < (int) length; i++) {
            if ((i & 1023) == 1023 && agent != null) agent.checkInterrupt();
            Object element = object.get(i, object);
            if (onlyPropertyKeys && !(element instanceof CharSequence) && !(element instanceof JSSymbol)) {
                throw JSException.typeError("Invalid property key");
            }
            out[i] = element instanceof CharSequence text ? text.toString() : element;
        }
        return out;
    }

    public static boolean isArray(Object value) {
        if (value instanceof JSArray) return true;
        if (value instanceof ProxyObject proxy) {
            JSObject target = proxy.target;
            if (target == null) {
                throw JSException.typeError("Cannot perform 'IsArray' on a proxy that has been revoked");
            }
            return isArray(target);
        }
        return false;
    }

    public static boolean instanceOf(Object value, Object target) {
        if (!(target instanceof JSObject object)) {
            throw JSException.typeError("Right-hand side of 'instanceof' is not an object");
        }
        Object handler = getMethod(object, JSSymbol.hasInstance);
        if (handler != Undefined.INSTANCE) {
            return toBoolean(((JSObject) handler).call(object, new Object[]{value}));
        }
        if (!object.isCallable()) {
            throw JSException.typeError("Right-hand side of 'instanceof' is not callable");
        }
        return ordinaryHasInstance(object, value);
    }

    public static boolean ordinaryHasInstance(Object constructor, Object value) {
        if (!isCallable(constructor)) return false;
        if (constructor instanceof BoundFunction function) return instanceOf(value, function.target);
        if (!(value instanceof JSObject object)) return false;
        Object prototype = ((JSObject) constructor).get("prototype", constructor);
        if (!(prototype instanceof JSObject expected)) {
            throw JSException.typeError("Function has non-object prototype in instanceof check");
        }
        JSObject current = object;
        while (true) {
            current = current.getPrototypeOf();
            if (current == null) return false;
            if (current == expected) return true;
        }
    }

    public static boolean hasPropertyOp(Object key, Object target) {
        if (!(target instanceof JSObject object)) {
            String keyText = key instanceof JSSymbol symbol ? symbol.toString() : toDisplayString(key);
            throw JSException.typeError("Cannot use 'in' operator to search for '" + keyText + "' in "
                    + toDisplayString(target));
        }
        return object.hasProperty(toPropertyKey(key));
    }

    public static Realm getFunctionRealm(JSObject object) {
        if (object instanceof JSFunction function) return function.realm;
        if (object instanceof BoundFunction bound) return getFunctionRealm(bound.target);
        if (object instanceof ProxyObject proxy) {
            JSObject target = proxy.target;
            if (target == null) {
                throw JSException.typeError("Cannot perform operation on a revoked proxy");
            }
            return getFunctionRealm(target);
        }
        return Agent.currentRealm();
    }

    public static JSObject getPrototypeFromConstructor(JSObject constructor,
                                                       Function<Realm, JSObject> defaultPrototype) {
        Object prototype = constructor.get("prototype", constructor);
        if (prototype instanceof JSObject object) return object;
        return defaultPrototype.apply(getFunctionRealm(constructor));
    }

    public static JSObject speciesConstructor(JSObject object, JSObject defaultConstructor) {
        Object constructor = object.get("constructor", object);
        if (constructor == Undefined.INSTANCE) return defaultConstructor;
        if (!(constructor instanceof JSObject speciesHolder)) {
            throw JSException.typeError("object.constructor is not an object");
        }
        Object species = speciesHolder.get(JSSymbol.species, speciesHolder);
        if (species == Undefined.INSTANCE || species == Null.INSTANCE) return defaultConstructor;
        if (isConstructor(species)) return (JSObject) species;
        throw JSException.typeError("object.constructor[Symbol.species] is not a constructor");
    }

    // ------------------------------------------------------------------ property descriptors

    public static PropertyDescriptor toPropertyDescriptor(Object value) {
        if (!(value instanceof JSObject object)) {
            throw JSException.typeError("Property description must be an object: " + toDisplayString(value));
        }
        PropertyDescriptor descriptor = new PropertyDescriptor();
        if (object.hasProperty("enumerable")) {
            descriptor.enumerable(toBoolean(object.get("enumerable", object)));
        }
        if (object.hasProperty("configurable")) {
            descriptor.configurable(toBoolean(object.get("configurable", object)));
        }
        if (object.hasProperty("value")) descriptor.value(object.get("value", object));
        if (object.hasProperty("writable")) {
            descriptor.writable(toBoolean(object.get("writable", object)));
        }
        if (object.hasProperty("get")) {
            Object getter = object.get("get", object);
            if (getter != Undefined.INSTANCE && !isCallable(getter)) {
                throw JSException.typeError("Getter must be a function: " + toDisplayString(getter));
            }
            descriptor.getter(getter);
        }
        if (object.hasProperty("set")) {
            Object setter = object.get("set", object);
            if (setter != Undefined.INSTANCE && !isCallable(setter)) {
                throw JSException.typeError("Setter must be a function: " + toDisplayString(setter));
            }
            descriptor.setter(setter);
        }
        if (descriptor.isAccessor() && descriptor.isData()) {
            throw JSException.typeError("Invalid property descriptor. Cannot both specify accessors "
                    + "and a value or writable attribute");
        }
        return descriptor;
    }

    public static Object fromPropertyDescriptor(Realm realm, PropertyDescriptor descriptor) {
        if (descriptor == null) return Undefined.INSTANCE;
        JSObject object = new JSObject(realm.getObjectPrototype());
        if (descriptor.getHasValue()) object.createDataProperty("value", descriptor.value);
        if (descriptor.getHasWritable()) object.createDataProperty("writable", descriptor.writable);
        if (descriptor.getHasGet()) object.createDataProperty("get", descriptor.getter);
        if (descriptor.getHasSet()) object.createDataProperty("set", descriptor.setter);
        if (descriptor.getHasEnumerable()) object.createDataProperty("enumerable", descriptor.enumerable);
        if (descriptor.getHasConfigurable()) {
            object.createDataProperty("configurable", descriptor.configurable);
        }
        return object;
    }

    public static PropertyDescriptor completePropertyDescriptor(PropertyDescriptor descriptor) {
        if (descriptor.isGeneric() || descriptor.isData()) {
            if (!descriptor.getHasValue()) descriptor.value(Undefined.INSTANCE);
            if (!descriptor.getHasWritable()) descriptor.writable(false);
        } else {
            if (!descriptor.getHasGet()) descriptor.getter(Undefined.INSTANCE);
            if (!descriptor.getHasSet()) descriptor.setter(Undefined.INSTANCE);
        }
        if (!descriptor.getHasEnumerable()) descriptor.enumerable(false);
        if (!descriptor.getHasConfigurable()) descriptor.configurable(false);
        return descriptor;
    }

    /** ValidateAndApplyPropertyDescriptor with O = undefined. */
    public static boolean isCompatiblePropertyDescriptor(boolean extensible, PropertyDescriptor descriptor,
                                                         PropertyDescriptor current) {
        if (current == null) return extensible;
        if (descriptor.present == 0) return true;
        if (!current.configurable) {
            if (descriptor.getHasConfigurable() && descriptor.configurable) return false;
            if (descriptor.getHasEnumerable() && descriptor.enumerable != current.enumerable) return false;
            if (!descriptor.isGeneric() && descriptor.isAccessor() != current.isAccessor()) return false;
            if (current.isAccessor()) {
                if (descriptor.getHasGet() && !sameValue(descriptor.getter, current.getter)) return false;
                if (descriptor.getHasSet() && !sameValue(descriptor.setter, current.setter)) return false;
            } else if (!current.writable) {
                if (descriptor.getHasWritable() && descriptor.writable) return false;
                if (descriptor.getHasValue() && !sameValue(descriptor.value, current.value)) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ descriptions for messages

    public static String describe(Object value) {
        if (value instanceof CharSequence text) {
            String content = text.length() > 40
                    ? text.subSequence(0, 40).toString() + "..."
                    : text.toString();
            return "\"" + content + "\"";
        }
        if (value instanceof JSFunction function) {
            return trimEnd("function " + function.debugName());
        }
        if (value instanceof JSArray) return "[object Array]";
        if (value instanceof JSObject object) {
            return object.isCallable() ? "function" : "#<" + constructorNameOf(object) + ">";
        }
        if (value instanceof JSSymbol symbol) return symbol.toString();
        if (value instanceof BigInteger integer) return integer + "n";
        return toDisplayString(value);
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && (Character.isWhitespace(value.charAt(end - 1))
                || Character.isSpaceChar(value.charAt(end - 1)))) end--;
        return value.substring(0, end);
    }

    private static String constructorNameOf(JSObject object) {
        try {
            JSObject current = object;
            int depth = 0;
            while (current != null && depth < 32) {
                PropertyMap map = current.props;
                if (map != null) {
                    int entry = map.find("constructor");
                    if (entry >= 0 && (map.flags[entry] & Attr.ACCESSOR) == 0) {
                        Object constructor = map.values[entry];
                        if (constructor instanceof JSFunction function) {
                            String name = function.debugName();
                            if (!name.isEmpty()) return name;
                        }
                    }
                }
                current = current.proto;
                depth++;
            }
            return object.getClassName();
        } catch (RuntimeException error) {
            // diagnostic fallback only; critical JVM errors are not swallowed
            return object.getClassName();
        }
    }

    public static String describeKey(Object key) {
        return key instanceof JSSymbol symbol ? symbol.toString() : PK.toStringKey(key);
    }

    /** String conversion for display/messages that never invokes user code. */
    public static String toDisplayString(Object value) {
        if (value instanceof CharSequence text) return text.toString();
        if (value instanceof Double doubleValue) return NumberConv.toString(doubleValue);
        if (value instanceof Boolean booleanValue) return booleanValue.toString();
        if (value == Undefined.INSTANCE || value == null) return "undefined";
        if (value == Null.INSTANCE) return "null";
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof JSSymbol symbol) return symbol.toString();
        if (value instanceof JSObject object) {
            return object.isCallable() ? "function" : "[object " + object.getClassName() + "]";
        }
        return value.toString();
    }
}
