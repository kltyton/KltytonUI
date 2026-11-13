/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;

import java.util.Arrays;

/** Option reading abstract operations of ECMA-402 §9.2 (GetOption, GetNumberOption, ...). */
final class Opt {
    private Opt() {
    }

    /** Marker for GetOption's ~required~ default. */
    static final Object REQUIRED = new Object();

    private static final String[] MATCHERS = {"lookup", "best fit"};

    /** CoerceOptionsToObject: undefined becomes an empty null-prototype object. */
    static JSObject coerceToObject(Object options) {
        return options == Undefined.INSTANCE ? new JSObject(null) : Ops.toObject(options);
    }

    /** GetOptionsObject: undefined becomes an empty null-prototype object; other non-objects throw a TypeError. */
    static JSObject getOptionsObject(Object options) {
        if (options == Undefined.INSTANCE) {
            return new JSObject(null);
        }
        if (options instanceof JSObject) {
            return (JSObject) options;
        }
        throw Dsl.typeErr("Options must be an object");
    }

    /** GetOption(options, property, string, values, default): returns a String or {@code defaultValue}. */
    static Object string(JSObject options, String property, String[] values, Object defaultValue) {
        Object value = options.get(property, options);
        if (value == Undefined.INSTANCE) {
            if (defaultValue == REQUIRED) {
                throw Dsl.rangeErr("Option " + property + " is required");
            }
            return defaultValue;
        }
        String text = Ops.toString(value);
        if (values != null && !Arrays.asList(values).contains(text)) {
            throw Dsl.rangeErr("Value " + text + " out of range for option " + property);
        }
        return text;
    }

    /** GetOption returning a non-null String (the default is used for undefined). */
    static String str(JSObject options, String property, String[] values, String defaultValue) {
        return (String) string(options, property, values, defaultValue);
    }

    /** GetOption(options, property, boolean, empty, default): returns a Boolean or {@code defaultValue}. */
    static Object bool(JSObject options, String property, Object defaultValue) {
        Object value = options.get(property, options);
        if (value == Undefined.INSTANCE) {
            return defaultValue;
        }
        return Ops.toBoolean(value);
    }

    /** DefaultNumberOption(value, minimum, maximum, fallback) */
    static Integer defaultNumber(Object value, int minimum, int maximum, Integer fallback) {
        if (value == Undefined.INSTANCE) {
            return fallback;
        }
        double number = Ops.toNumber(value);
        if (Double.isNaN(number) || number < minimum || number > maximum) {
            throw Dsl.rangeErr("Value " + Ops.toDisplayString(value) + " out of range");
        }
        return (int) Math.floor(number);
    }

    /** GetNumberOption(options, property, minimum, maximum, fallback) */
    static Integer number(JSObject options, String property, int minimum, int maximum, Integer fallback) {
        return defaultNumber(options.get(property, options), minimum, maximum, fallback);
    }

    /** GetBooleanOrStringNumberFormatOption(options, property, stringValues, fallback) */
    static Object booleanOrString(JSObject options, String property, String[] stringValues, Object fallback) {
        Object value = options.get(property, options);
        if (value == Undefined.INSTANCE) {
            return fallback;
        }
        if (Boolean.TRUE.equals(value)) {
            return Boolean.TRUE;
        }
        if (!Ops.toBoolean(value)) {
            return Boolean.FALSE;
        }
        String text = Ops.toString(value);
        if (!Arrays.asList(stringValues).contains(text)) {
            throw Dsl.rangeErr("Value " + text + " out of range for option " + property);
        }
        return text;
    }

    /** Reads {@code localeMatcher} (shared by every constructor and supportedLocalesOf). */
    static String localeMatcher(JSObject options) {
        return str(options, "localeMatcher", MATCHERS, "best fit");
    }

    /** Validates the type sequence of a Unicode extension value given as an option ({@code (3*8alphanum) *("-" (3*8alphanum))}). */
    static boolean isUnicodeType(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int start = 0;
        while (true) {
            int end = value.indexOf('-', start);
            if (end < 0) {
                end = value.length();
            }
            int length = end - start;
            if (length < 3 || length > 8) {
                return false;
            }
            for (int i = start; i < end; i++) {
                if (!LanguageTag.isAlnum(value.charAt(i))) {
                    return false;
                }
            }
            if (end == value.length()) {
                return true;
            }
            start = end + 1;
        }
    }
}
