/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;

/** Option reading. */
final class TOpt {
    public static final TOpt INSTANCE = new TOpt();

    public static final int UNIT_UNSET = -1;
    public static final int UNIT_AUTO = -2;

    private static final String[] OVERFLOW = {"constrain", "reject"};
    private static final String[] DISAMBIGUATION = {"compatible", "earlier", "later", "reject"};
    private static final String[] OFFSET = {"prefer", "use", "ignore", "reject"};
    private static final String[] CALENDAR_NAME = {"auto", "always", "never", "critical"};
    private static final String[] TZ_NAME = {"auto", "never", "critical"};
    private static final String[] SHOW_OFFSET = {"auto", "never"};
    private static final String[] DIRECTION = {"next", "previous"};
    private static final String[] ROUNDING_MODES;
    private static final String[] UNIT_STRINGS;

    static {
        ROUNDING_MODES = new String[RMode.ALL.length];
        for (int i = 0; i < RMode.ALL.length; i++) ROUNDING_MODES[i] = RMode.ALL[i].id;
        int units = TUnit.ALL.length;
        UNIT_STRINGS = new String[units * 2 + 1];
        int at = 0;
        for (TUnit unit : TUnit.ALL) UNIT_STRINGS[at++] = unit.singular;
        for (TUnit unit : TUnit.ALL) UNIT_STRINGS[at++] = unit.plural;
        UNIT_STRINGS[at] = "auto";
    }

    private TOpt() {
    }

    /** GetOptionsObject. */
    public static JSObject optionsObject(Object v) {
        if (v == Undefined.INSTANCE) return new JSObject(null);
        if (v instanceof JSObject) return (JSObject) v;
        throw TemporalAbstractOps.tTypeErr("options must be an object or undefined");
    }

    /** GetOption(options, key, string, values, default) with default {@code null} meaning undefined. */
    public static String getString(JSObject o, String key, String[] allowed, String defaultValue) {
        Object v = o.get(key, o);
        if (v == Undefined.INSTANCE) return defaultValue;
        String s = Ops.toString(v);
        if (allowed != null && !contains(allowed, s)) {
            throw TemporalAbstractOps.tRangeErr(
                    s.substring(0, Math.min(50, s.length())) + " is not a valid value for " + key);
        }
        return s;
    }

    private static boolean contains(String[] values, String value) {
        for (String candidate : values) if (candidate.equals(value)) return true;
        return false;
    }

    public static Overflow overflow(JSObject o) {
        return "constrain".equals(getString(o, "overflow", OVERFLOW, "constrain"))
                ? Overflow.CONSTRAIN : Overflow.REJECT;
    }

    public static Disambiguation disambiguation(JSObject o) {
        String value = getString(o, "disambiguation", DISAMBIGUATION, "compatible");
        switch (value) {
            case "compatible":
                return Disambiguation.COMPATIBLE;
            case "earlier":
                return Disambiguation.EARLIER;
            case "later":
                return Disambiguation.LATER;
            default:
                return Disambiguation.REJECT;
        }
    }

    public static OffsetOption offset(JSObject o, OffsetOption fallback) {
        String fb;
        switch (fallback) {
            case PREFER:
                fb = "prefer";
                break;
            case USE:
                fb = "use";
                break;
            case IGNORE:
                fb = "ignore";
                break;
            default:
                fb = "reject";
                break;
        }
        switch (getString(o, "offset", OFFSET, fb)) {
            case "prefer":
                return OffsetOption.PREFER;
            case "use":
                return OffsetOption.USE;
            case "ignore":
                return OffsetOption.IGNORE;
            default:
                return OffsetOption.REJECT;
        }
    }

    public static ShowCalendar showCalendar(JSObject o) {
        switch (getString(o, "calendarName", CALENDAR_NAME, "auto")) {
            case "always":
                return ShowCalendar.ALWAYS;
            case "never":
                return ShowCalendar.NEVER;
            case "critical":
                return ShowCalendar.CRITICAL;
            default:
                return ShowCalendar.AUTO;
        }
    }

    /** GetTemporalShowTimeZoneNameOption: 0 auto, 1 never, 2 critical. */
    public static int showTimeZone(JSObject o) {
        switch (getString(o, "timeZoneName", TZ_NAME, "auto")) {
            case "never":
                return 1;
            case "critical":
                return 2;
            default:
                return 0;
        }
    }

    /** GetTemporalShowOffsetOption: true when the offset is shown. */
    public static boolean showOffset(JSObject o) {
        return !"never".equals(getString(o, "offset", SHOW_OFFSET, "auto"));
    }

    /** GetDirectionOption: true for next. */
    public static boolean directionNext(JSObject o) {
        String s = getString(o, "direction", DIRECTION, null);
        if (s == null) throw TemporalAbstractOps.tRangeErr("direction is required");
        return "next".equals(s);
    }

    public static RMode roundingMode(JSObject o, RMode fallback) {
        String s = getString(o, "roundingMode", ROUNDING_MODES, fallback.id);
        for (RMode mode : RMode.ALL) if (mode.id.equals(s)) return mode;
        return fallback;
    }

    /** GetRoundingIncrementOption. */
    public static long roundingIncrement(JSObject o) {
        Object v = o.get("roundingIncrement", o);
        if (v == Undefined.INSTANCE) return 1;
        double i = TemporalAbstractOps.toIntegerWithTruncation(v);
        if (i < 1 || i > 1e9) throw TemporalAbstractOps.tRangeErr("roundingIncrement out of range");
        return (long) i;
    }

    /** GetTemporalFractionalSecondDigitsOption: -1 for auto. */
    public static int fractionalSecondDigits(JSObject o) {
        Object v = o.get("fractionalSecondDigits", o);
        if (v == Undefined.INSTANCE) return -1;
        if (!(v instanceof Double)) {
            if (!"auto".equals(Ops.toString(v))) {
                throw TemporalAbstractOps.tRangeErr("fractionalSecondDigits must be 'auto' or 0 through 9");
            }
            return -1;
        }
        double d = (Double) v;
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw TemporalAbstractOps.tRangeErr("fractionalSecondDigits must be 'auto' or 0 through 9");
        }
        double c = Math.floor(d);
        if (c < 0 || c > 9) {
            throw TemporalAbstractOps.tRangeErr("fractionalSecondDigits must be 'auto' or 0 through 9");
        }
        return (int) c;
    }

    /** GetTemporalUnitValuedOption: returns a TUnit ordinal, UNIT_UNSET or UNIT_AUTO. */
    public static int unit(JSObject o, String key, boolean required) {
        String s = getString(o, key, UNIT_STRINGS, null);
        if (s == null) {
            if (required) throw TemporalAbstractOps.tRangeErr(key + " is required");
            return UNIT_UNSET;
        }
        if ("auto".equals(s)) return UNIT_AUTO;
        for (TUnit unit : TUnit.ALL) {
            if (unit.singular.equals(s) || unit.plural.equals(s)) return unit.ordinal();
        }
        return UNIT_UNSET;
    }

    /** ValidateTemporalUnitValue; {@code group}: 0 date, 1 time, 2 datetime. */
    public static void validateUnit(int value, int group) {
        validateUnit(value, group, null);
    }

    /** ValidateTemporalUnitValue; {@code group}: 0 date, 1 time, 2 datetime. */
    public static void validateUnit(int value, int group, int[] extra) {
        if (value == UNIT_UNSET) return;
        if (extra != null) {
            for (int candidate : extra) if (candidate == value) return;
        }
        if (value == UNIT_AUTO) throw TemporalAbstractOps.tRangeErr("auto is not allowed here");
        TUnit u = TUnit.ALL[value];
        if (u.isDate && (group == 0 || group == 2)) return;
        if (!u.isDate && (group == 1 || group == 2)) return;
        throw TemporalAbstractOps.tRangeErr(u.singular + " is not allowed here");
    }

    /** ValidateTemporalRoundingIncrement. */
    public static void validateIncrement(long increment, long dividend, boolean inclusive) {
        long maximum = inclusive ? dividend : dividend - 1;
        if (increment > maximum) throw TemporalAbstractOps.tRangeErr("roundingIncrement out of range");
        if (dividend % increment != 0L) {
            throw TemporalAbstractOps.tRangeErr("roundingIncrement must evenly divide " + dividend);
        }
    }
}
