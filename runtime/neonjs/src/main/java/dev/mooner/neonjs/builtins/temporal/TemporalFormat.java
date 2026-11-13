/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.math.BigDecimal;
import java.math.BigInteger;

/** String serialization of Temporal values. */
final class TFormat {
    private TFormat() {
    }

    private static void pad2(StringBuilder sb, int v) {
        if (v < 10) sb.append('0');
        sb.append(v);
    }

    /** PadISOYear */
    static void padIsoYear(StringBuilder sb, int y) {
        if (y >= 0 && y <= 9999) {
            String s = Integer.toString(y);
            sb.append("0".repeat(4 - s.length()));
            sb.append(s);
            return;
        }
        sb.append(y > 0 ? '+' : '-');
        String s = Long.toString(Math.abs((long) y));
        sb.append("0".repeat(6 - s.length()));
        sb.append(s);
    }

    /** FormatFractionalSeconds; precision -1 = auto. */
    static void fractionalSeconds(StringBuilder sb, int subSecondNs, int precision) {
        if (precision == Precision.AUTO) {
            if (subSecondNs == 0) return;
            String s = Integer.toString(subSecondNs);
            s = "0".repeat(9 - s.length()) + s;
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            sb.append('.').append(s, 0, end);
            return;
        }
        if (precision == 0) return;
        String s = Integer.toString(subSecondNs);
        s = "0".repeat(9 - s.length()) + s;
        sb.append('.').append(s, 0, precision);
    }

    /** FormatTimeString; precision: digits, -1 auto, -2 minute. */
    static void timeString(StringBuilder sb, int hour, int minute, int second, int subSecondNs, int precision) {
        timeString(sb, hour, minute, second, subSecondNs, precision, true);
    }

    static void timeString(StringBuilder sb, int hour, int minute, int second, int subSecondNs, int precision,
                           boolean separated) {
        pad2(sb, hour);
        if (separated) sb.append(':');
        pad2(sb, minute);
        if (precision == Precision.MINUTE) return;
        if (separated) sb.append(':');
        pad2(sb, second);
        fractionalSeconds(sb, subSecondNs, precision);
    }

    static String timeRecordToString(TimeRec t, int precision) {
        StringBuilder sb = new StringBuilder(18);
        timeString(sb, t.hour, t.minute, t.second, t.subSecondNanos(), precision);
        return sb.toString();
    }

    /** FormatOffsetTimeZoneIdentifier */
    static String formatOffsetMinutes(int offsetMinutes, boolean separated) {
        StringBuilder sb = new StringBuilder(6);
        sb.append(offsetMinutes >= 0 ? '+' : '-');
        int minutes = Math.abs(offsetMinutes);
        timeString(sb, minutes / 60, minutes % 60, 0, 0, Precision.MINUTE, separated);
        return sb.toString();
    }

    /** FormatUTCOffsetNanoseconds */
    static String formatUtcOffsetNs(long offsetNs) {
        StringBuilder sb = new StringBuilder(20);
        sb.append(offsetNs >= 0 ? '+' : '-');
        long ns = Math.abs(offsetNs);
        int hour = (int) (ns / 3_600_000_000_000L);
        int minute = (int) ((ns / 60_000_000_000L) % 60);
        int second = (int) ((ns / 1_000_000_000L) % 60);
        int sub = (int) (ns % 1_000_000_000L);
        int precision = second == 0 && sub == 0 ? Precision.MINUTE : Precision.AUTO;
        timeString(sb, hour, minute, second, sub, precision);
        return sb.toString();
    }

    /** FormatDateTimeUTCOffsetRounded */
    static String formatOffsetRounded(long offsetNs) {
        long rounded = TNum.roundToIncrement(offsetNs, 60_000_000_000L, RMode.HALF_EXPAND);
        return formatOffsetMinutes((int) (rounded / 60_000_000_000L), true);
    }

    /** FormatCalendarAnnotation */
    static void calendarAnnotation(StringBuilder sb, String id, ShowCalendar show) {
        if (show == ShowCalendar.NEVER) return;
        if (show == ShowCalendar.AUTO && TCal.ISO.equals(id)) return;
        sb.append('[');
        if (show == ShowCalendar.CRITICAL) sb.append('!');
        sb.append("u-ca=").append(id).append(']');
    }

    private static void dateParts(StringBuilder sb, IsoDate d) {
        padIsoYear(sb, d.year);
        sb.append('-');
        pad2(sb, d.month);
        sb.append('-');
        pad2(sb, d.day);
    }

    /** TemporalDateToString */
    static String dateToString(IsoDate d, String cal, ShowCalendar show) {
        StringBuilder sb = new StringBuilder(24);
        dateParts(sb, d);
        calendarAnnotation(sb, cal, show);
        return sb.toString();
    }

    /** ISODateTimeToString */
    static void dateTimeToString(StringBuilder sb, IsoDateTime dt, String cal, int precision, ShowCalendar show) {
        dateParts(sb, dt.date);
        sb.append('T');
        TimeRec t = dt.time;
        timeString(sb, t.hour, t.minute, t.second, t.subSecondNanos(), precision);
        calendarAnnotation(sb, cal, show);
    }

    static String dateTimeToString(IsoDateTime dt, String cal, int precision, ShowCalendar show) {
        StringBuilder sb = new StringBuilder(40);
        dateTimeToString(sb, dt, cal, precision, show);
        return sb.toString();
    }

    /** TemporalYearMonthToString */
    static String yearMonthToString(IsoDate d, String cal, ShowCalendar show) {
        StringBuilder sb = new StringBuilder(24);
        padIsoYear(sb, d.year);
        sb.append('-');
        pad2(sb, d.month);
        if (show == ShowCalendar.ALWAYS || show == ShowCalendar.CRITICAL || !TCal.ISO.equals(cal)) {
            sb.append('-');
            pad2(sb, d.day);
        }
        calendarAnnotation(sb, cal, show);
        return sb.toString();
    }

    /** TemporalMonthDayToString */
    static String monthDayToString(IsoDate d, String cal, ShowCalendar show) {
        StringBuilder sb = new StringBuilder(24);
        if (show == ShowCalendar.ALWAYS || show == ShowCalendar.CRITICAL || !TCal.ISO.equals(cal)) {
            padIsoYear(sb, d.year);
            sb.append('-');
        }
        pad2(sb, d.month);
        sb.append('-');
        pad2(sb, d.day);
        calendarAnnotation(sb, cal, show);
        return sb.toString();
    }

    /** TemporalInstantToString */
    static String instantToString(BigInteger ns, TimeZone tz, int precision) {
        TimeZone out = tz != null ? tz : TimeZone.UTC;
        IsoDateTime dt = TZ.isoDateTimeFor(out, ns);
        StringBuilder sb = new StringBuilder(40);
        dateTimeToString(sb, dt, TCal.ISO, precision, ShowCalendar.NEVER);
        if (tz == null) sb.append('Z');
        else sb.append(formatOffsetRounded(TZ.offsetNs(out, ns)));
        return sb.toString();
    }

    /** TemporalZonedDateTimeToString */
    static String zonedToString(JSTemporalZonedDateTime z, int precision, ShowCalendar showCalendar, int showTimeZone,
                                boolean showOffset) {
        return zonedToString(z, precision, showCalendar, showTimeZone, showOffset, 1L, TUnit.NANOSECOND, RMode.TRUNC);
    }

    static String zonedToString(JSTemporalZonedDateTime z, int precision, ShowCalendar showCalendar, int showTimeZone,
                                boolean showOffset, long increment) {
        return zonedToString(z, precision, showCalendar, showTimeZone, showOffset, increment, TUnit.NANOSECOND, RMode.TRUNC);
    }

    static String zonedToString(JSTemporalZonedDateTime z, int precision, ShowCalendar showCalendar, int showTimeZone,
                                boolean showOffset, long increment, TUnit unit) {
        return zonedToString(z, precision, showCalendar, showTimeZone, showOffset, increment, unit, RMode.TRUNC);
    }

    static String zonedToString(JSTemporalZonedDateTime z, int precision, ShowCalendar showCalendar, int showTimeZone,
                                boolean showOffset, long increment, TUnit unit, RMode mode) {
        BigInteger ns = z.epochNs;
        if (!(increment == 1L && unit == TUnit.NANOSECOND)) {
            ns = TNum.roundAsIfPositive(ns, BigInteger.valueOf(increment * unit.nanos), mode);
        }
        TimeZone tz = z.timeZone;
        long offsetNs = TZ.offsetNs(tz, ns);
        IsoDateTime dt = TZ.isoDateTimeFor(tz, ns);
        StringBuilder sb = new StringBuilder(48);
        dateTimeToString(sb, dt, TCal.ISO, precision, ShowCalendar.NEVER);
        if (showOffset) sb.append(formatOffsetRounded(offsetNs));
        if (showTimeZone != 1) {
            sb.append('[');
            if (showTimeZone == 2) sb.append('!');
            sb.append(tz.id).append(']');
        }
        calendarAnnotation(sb, z.calendar, showCalendar);
        return sb.toString();
    }

    private static String decimal(double d) {
        double a = Math.abs(d);
        if (a < 9.0e15) return Long.toString((long) a);
        return new BigDecimal(a).toBigInteger().toString();
    }

    /** TemporalDurationToString */
    static String durationToString(JSTemporalDuration d, int precision) {
        int sign = TDur.sign(d);
        StringBuilder sb = new StringBuilder(32);
        if (sign < 0) sb.append('-');
        sb.append('P');
        if (d.years != 0.0) sb.append(decimal(d.years)).append('Y');
        if (d.months != 0.0) sb.append(decimal(d.months)).append('M');
        if (d.weeks != 0.0) sb.append(decimal(d.weeks)).append('W');
        if (d.days != 0.0) sb.append(decimal(d.days)).append('D');
        StringBuilder time = new StringBuilder();
        if (d.hours != 0.0) time.append(decimal(d.hours)).append('H');
        if (d.minutes != 0.0) time.append(decimal(d.minutes)).append('M');
        TUnit largest = TDur.defaultLargestUnit(d);
        boolean zeroMinutesAndHigher = largest.ordinal() >= TUnit.SECOND.ordinal();
        BigInteger secondsDuration = TDur.timeFromComponents(
                0.0, 0.0, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds);
        if (secondsDuration.signum() != 0 || zeroMinutesAndHigher || precision != Precision.AUTO) {
            BigInteger[] qr = secondsDuration.abs().divideAndRemainder(TNum.BI_1E9);
            time.append(qr[0].toString());
            fractionalSeconds(time, qr[1].intValue(), precision);
            time.append('S');
        }
        if (time.length() > 0) sb.append('T').append(time);
        return sb.toString();
    }
}
