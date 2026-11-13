/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.temporal.JSTemporalDuration;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A Duration Record: the ten fields in {@link DurationFormatBuiltins#UNITS} order (integral Numbers, never -0). */
final class DurationRecord {
    final double[] values;

    /** Get order of ToDurationRecord (alphabetical), as indexes into the UNITS order. */
    private static final int[] READ_ORDER = {3, 4, 8, 7, 5, 1, 9, 6, 2, 0};
    private static final BigDecimal TWO_POW_32 = new BigDecimal(BigInteger.ONE.shiftLeft(32));
    private static final BigDecimal TWO_POW_53 = new BigDecimal(BigInteger.ONE.shiftLeft(53));

    DurationRecord(double[] values) {
        this.values = values;
    }

    /** DurationSign */
    int sign() {
        for (double value : values) {
            if (value < 0) {
                return -1;
            }
            if (value > 0) {
                return 1;
            }
        }
        return 0;
    }

    /** ToDurationRecord(input) */
    static DurationRecord from(Object input) {
        if (input instanceof JSTemporalDuration) {
            JSTemporalDuration duration = (JSTemporalDuration) input;
            double[] values = {duration.years, duration.months, duration.weeks, duration.days, duration.hours,
                    duration.minutes, duration.seconds, duration.milliseconds, duration.microseconds,
                    duration.nanoseconds};
            for (int i = 0; i < values.length; i++) {
                values[i] += 0.0;
            }
            return new DurationRecord(values);
        }
        if (!(input instanceof JSObject)) {
            if (input instanceof CharSequence) {
                return parseIso(input.toString());
            }
            throw Dsl.typeErr("Invalid duration: " + Ops.toDisplayString(input));
        }
        JSObject object = (JSObject) input;
        double[] values = new double[10];
        boolean any = false;
        for (int i : READ_ORDER) {
            Object value = object.get(DurationFormatBuiltins.UNITS[i], object);
            if (value != Undefined.INSTANCE) {
                any = true;
                values[i] = toIntegerIfIntegral(value);
            }
        }
        if (!any) {
            throw Dsl.typeErr("Invalid duration: no duration fields");
        }
        if (!isValid(values)) {
            throw Dsl.rangeErr("Invalid duration");
        }
        return new DurationRecord(values);
    }

    private static double toIntegerIfIntegral(Object value) {
        double d = Ops.toNumber(value);
        if (!Ops.isIntegral(d)) {
            throw Dsl.rangeErr("Duration field is not an integral number: " + Ops.toDisplayString(value));
        }
        return d + 0.0;
    }

    /** IsValidDuration */
    static boolean isValid(double[] values) {
        int sign = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
            if (value < 0) {
                if (sign > 0) {
                    return false;
                }
                sign = -1;
            }
            if (value > 0) {
                if (sign < 0) {
                    return false;
                }
                sign = 1;
            }
        }
        for (int i = 0; i <= 2; i++) {
            if (new BigDecimal(values[i]).abs().compareTo(TWO_POW_32) >= 0) {
                return false;
            }
        }
        BigDecimal seconds = new BigDecimal(values[3]).multiply(BigDecimal.valueOf(86400))
                .add(new BigDecimal(values[4]).multiply(BigDecimal.valueOf(3600)))
                .add(new BigDecimal(values[5]).multiply(BigDecimal.valueOf(60)))
                .add(new BigDecimal(values[6]))
                .add(new BigDecimal(values[7]).movePointLeft(3))
                .add(new BigDecimal(values[8]).movePointLeft(6))
                .add(new BigDecimal(values[9]).movePointLeft(9));
        return seconds.abs().compareTo(TWO_POW_53) < 0;
    }

    /** ParseTemporalDurationString + CreateDurationRecord (RangeError for anything else). */
    static DurationRecord parseIso(String source) {
        if (source.length() > 256) {
            throw Dsl.rangeErr("Invalid duration string");
        }
        double[] values = new IsoDurationParser(source).parse();
        if (values == null) {
            throw Dsl.rangeErr("Invalid duration string: " + source);
        }
        if (!isValid(values)) {
            throw Dsl.rangeErr("Invalid duration");
        }
        return new DurationRecord(values);
    }
}
