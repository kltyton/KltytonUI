package dev.mooner.neonjs.runtime;

/** Canonical JavaScript property keys; derived from NeonJS and adapted to Java. */
public final class PK {
    private PK() {
    }

    public static Object fromString(String value) {
        int length = value.length();
        if (length == 0 || length > 10) return value;
        char first = value.charAt(0);
        if (first < '0' || first > '9') return value;
        if (first == '0') return length == 1 ? 0 : value;
        long number = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return value;
            number = number * 10 + c - '0';
        }
        return number <= Integer.MAX_VALUE ? (int) number : value;
    }

    public static Object fromIndex(long index) {
        return index >= 0 && index <= Integer.MAX_VALUE ? (int) index : Long.toString(index);
    }

    public static Object fromDouble(double value) {
        int integer = (int) value;
        if ((double) integer == value && integer >= 0 && !(value == 0 && 1.0 / value < 0)) return integer;
        return fromString(NumberConv.toString(value));
    }

    public static Object toValue(Object key) {
        return key instanceof Integer ? key.toString() : key;
    }

    public static String toStringKey(Object key) {
        return key.toString();
    }

    public static long arrayIndex(Object key) {
        if (key instanceof Integer integer) return integer.longValue();
        if (key instanceof String value) {
            if (value.length() != 10 || value.charAt(0) == '0') return -1;
            long number = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9') return -1;
                number = number * 10 + c - '0';
            }
            return number < 4294967295L ? number : -1;
        }
        return -1;
    }

    public static String functionName(Object key) {
        return key instanceof JSSymbol symbol ? symbol.functionName() : toStringKey(key);
    }
}
