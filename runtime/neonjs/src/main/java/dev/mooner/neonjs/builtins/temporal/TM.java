/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import java.math.BigInteger;

/** ISO calendar math. */
final class TM {
    public static final TM INSTANCE = new TM();

    public static final long NS_PER_DAY = 86_400_000_000_000L;
    public static final BigInteger BI_NS_PER_DAY = BigInteger.valueOf(NS_PER_DAY);
    public static final BigInteger NS_MAX_INSTANT = BI_NS_PER_DAY.multiply(BigInteger.valueOf(100_000_000L));
    public static final BigInteger NS_MIN_INSTANT = NS_MAX_INSTANT.negate();
    public static final BigInteger MAX_TIME_DURATION =
            BigInteger.ONE.shiftLeft(53).multiply(TNum.BI_1E9).subtract(BigInteger.ONE);
    public static final BigInteger TWO_POW_53_NS = BigInteger.ONE.shiftLeft(53).multiply(TNum.BI_1E9);
    public static final long MAX_EPOCH_DAYS = 100_000_000L;

    private TM() {
    }

    public static boolean isLeap(long y) {
        return y % 4 == 0L && (y % 100 != 0L || y % 400 == 0L);
    }

    public static boolean isLeapD(double y) {
        return y % 4.0 == 0.0 && (y % 100.0 != 0.0 || y % 400.0 == 0.0);
    }

    public static int daysInMonth(long y, int m) {
        switch (m) {
            case 2:
                return isLeap(y) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    public static int daysInMonthD(double y, int m) {
        switch (m) {
            case 2:
                return isLeapD(y) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    public static int daysInYear(long y) {
        return isLeap(y) ? 366 : 365;
    }

    /** ISODateToEpochDays(y, m - 1, d) for 1 &lt;= m &lt;= 12. */
    public static long epochDays(long y, int m, int d) {
        long yy = m <= 2 ? y - 1 : y;
        long era = Math.floorDiv(yy, 400L);
        long yoe = yy - era * 400;
        int mp = (m + 9) % 12;
        int doy = (153 * mp + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    public static long epochDays(IsoDate d) {
        return epochDays(d.year, d.month, d.day);
    }

    /** The ISO date of epoch day {@code days}. */
    public static IsoDate dateFromEpochDays(long days) {
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097L);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        long y = yoe + era * 400 + (m <= 2 ? 1 : 0);
        if (y > Integer.MAX_VALUE || y < Integer.MIN_VALUE) {
            throw TemporalAbstractOps.tRangeErr("date outside of supported range");
        }
        return new IsoDate((int) y, (int) m, (int) d);
    }

    /** AddDaysToISODate. */
    public static IsoDate addDays(IsoDate d, long days) {
        return days == 0L ? d : dateFromEpochDays(epochDays(d) + days);
    }

    /** BalanceISOYearMonth: returns the year; the month is obtained with {@link #balanceMonth(long)}. */
    public static long balanceYear(long y, long m) {
        return y + Math.floorDiv(m - 1, 12L);
    }

    public static int balanceMonth(long m) {
        return (int) (Math.floorMod(m - 1, 12L) + 1);
    }

    public static boolean isValidIsoDate(double y, double m, double d) {
        if (m < 1 || m > 12) return false;
        if (d < 1 || d > daysInMonthD(y, (int) m)) return false;
        return true;
    }

    /** Builds an IsoDate from a year that must lie well outside the Temporal range to be rejected. */
    public static IsoDate makeDate(double y, int m, int d) {
        if (Math.abs(y) > 1.0e9) throw TemporalAbstractOps.tRangeErr("date outside of supported range");
        return new IsoDate((int) y, m, d);
    }

    /** RegulateISODate; years far outside the representable range are rejected with a RangeError. */
    public static IsoDate regulateIsoDate(double y, double m, double d, Overflow overflow) {
        if (overflow == Overflow.CONSTRAIN) {
            int mm = (int) coerceIn(m, 1.0, 12.0);
            int dim = daysInMonthD(y, mm);
            int dd = (int) coerceIn(d, 1.0, (double) dim);
            return makeDate(y, mm, dd);
        }
        if (!isValidIsoDate(y, m, d)) throw TemporalAbstractOps.tRangeErr("date is out of range");
        return makeDate(y, (int) m, (int) d);
    }

    private static double coerceIn(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static int compareDate(IsoDate a, IsoDate b) {
        if (a.year != b.year) return a.year > b.year ? 1 : -1;
        if (a.month != b.month) return a.month > b.month ? 1 : -1;
        if (a.day != b.day) return a.day > b.day ? 1 : -1;
        return 0;
    }

    public static int compareTime(TimeRec a, TimeRec b) {
        long c = Long.compare(a.nanosOfDay(), b.nanosOfDay());
        return c > 0 ? 1 : c < 0 ? -1 : 0;
    }

    public static int compareDateTime(IsoDateTime a, IsoDateTime b) {
        int c = compareDate(a.date, b.date);
        return c != 0 ? c : compareTime(a.time, b.time);
    }

    public static boolean isoDateWithinLimits(IsoDate d) {
        if (Math.abs(d.year) > 300000) return false;
        long days = epochDays(d);
        return days >= -MAX_EPOCH_DAYS - 1 && days <= MAX_EPOCH_DAYS;
    }

    public static boolean isoDateTimeWithinLimits(IsoDateTime dt) {
        if (Math.abs(dt.date.year) > 300000) return false;
        long days = epochDays(dt.date);
        if (days < -MAX_EPOCH_DAYS - 1 || days > MAX_EPOCH_DAYS) return false;
        if (days == -MAX_EPOCH_DAYS - 1 && dt.time.nanosOfDay() == 0L) return false;
        return true;
    }

    public static boolean isoYearMonthWithinLimits(int y, int m) {
        if (y < -271821 || y > 275760) return false;
        if (y == -271821 && m < 4) return false;
        if (y == 275760 && m > 9) return false;
        return true;
    }

    /** CheckISODaysRange. */
    public static void checkIsoDaysRange(IsoDate d) {
        if (Math.abs(d.year) > 300000 || Math.abs(epochDays(d)) > MAX_EPOCH_DAYS) {
            throw TemporalAbstractOps.tRangeErr("date outside of supported range");
        }
    }

    public static boolean isValidEpochNs(BigInteger ns) {
        return ns.compareTo(NS_MIN_INSTANT) >= 0 && ns.compareTo(NS_MAX_INSTANT) <= 0;
    }

    /** GetUTCEpochNanoseconds. */
    public static BigInteger utcEpochNs(IsoDateTime dt) {
        return BigInteger.valueOf(epochDays(dt.date)).multiply(BI_NS_PER_DAY)
                .add(BigInteger.valueOf(dt.time.nanosOfDay()));
    }

    public static BigInteger utcEpochNs(IsoDate d, TimeRec t) {
        return BigInteger.valueOf(epochDays(d)).multiply(BI_NS_PER_DAY)
                .add(BigInteger.valueOf(t.nanosOfDay()));
    }

    /** GetISOPartsFromEpoch (also usable for epoch values shifted by an offset). */
    public static IsoDateTime isoPartsFromEpoch(BigInteger ns) {
        BigInteger[] qr = ns.divideAndRemainder(BI_NS_PER_DAY);
        long days = qr[0].longValue();
        long rem = qr[1].longValue();
        if (rem < 0) {
            rem += NS_PER_DAY;
            days -= 1;
        }
        return new IsoDateTime(dateFromEpochDays(days), TimeRec.fromNanosOfDay(rem));
    }

    /** BalanceTime with arbitrary Long components. */
    public static TimeRec balanceTime(long h, long mi, long s, long ms, long us, long ns) {
        long microsecond = us + Math.floorDiv(ns, 1000L);
        long nanosecond = Math.floorMod(ns, 1000L);
        long millisecond = ms + Math.floorDiv(microsecond, 1000L);
        microsecond = Math.floorMod(microsecond, 1000L);
        long second = s + Math.floorDiv(millisecond, 1000L);
        millisecond = Math.floorMod(millisecond, 1000L);
        long minute = mi + Math.floorDiv(second, 60L);
        second = Math.floorMod(second, 60L);
        long hour = h + Math.floorDiv(minute, 60L);
        minute = Math.floorMod(minute, 60L);
        long days = Math.floorDiv(hour, 24L);
        hour = Math.floorMod(hour, 24L);
        return new TimeRec((int) hour, (int) minute, (int) second, (int) millisecond, (int) microsecond,
                (int) nanosecond, days);
    }

    /** BalanceISODateTime where only the nanosecond component may be out of range (|extraNs| small). */
    public static IsoDateTime balanceDateTime(IsoDate d, TimeRec t, long extraNs) {
        long total = t.nanosOfDay() + extraNs;
        long days = Math.floorDiv(total, NS_PER_DAY);
        long rem = Math.floorMod(total, NS_PER_DAY);
        return new IsoDateTime(addDays(d, days), TimeRec.fromNanosOfDay(rem));
    }

    /** AddTime. */
    public static TimeRec addTime(TimeRec t, BigInteger d) {
        if (d.signum() == 0) {
            return new TimeRec(t.hour, t.minute, t.second, t.millisecond, t.microsecond, t.nanosecond, 0L);
        }
        BigInteger total = d.add(BigInteger.valueOf(t.nanosOfDay()));
        BigInteger[] qr = total.divideAndRemainder(BI_NS_PER_DAY);
        long days = qr[0].longValue();
        long rem = qr[1].longValue();
        if (rem < 0) {
            rem += NS_PER_DAY;
            days -= 1;
        }
        return TimeRec.fromNanosOfDay(rem, days);
    }

    /** DifferenceTime as nanoseconds. */
    public static long differenceTime(TimeRec a, TimeRec b) {
        return b.nanosOfDay() - a.nanosOfDay();
    }

    /** RoundTime. */
    public static TimeRec roundTime(TimeRec t, long increment, TUnit unit, RMode mode) {
        long quantity;
        switch (unit) {
            case DAY:
            case HOUR:
                quantity = t.nanosOfDay();
                break;
            case MINUTE:
                quantity = (((t.minute * 60L + t.second) * 1000L + t.millisecond) * 1000L + t.microsecond)
                        * 1000L + t.nanosecond;
                break;
            case SECOND:
                quantity = ((t.second * 1000L + t.millisecond) * 1000L + t.microsecond) * 1000L
                        + t.nanosecond;
                break;
            case MILLISECOND:
                quantity = (t.millisecond * 1000L + t.microsecond) * 1000L + t.nanosecond;
                break;
            case MICROSECOND:
                quantity = t.microsecond * 1000L + t.nanosecond;
                break;
            default:
                quantity = t.nanosecond;
                break;
        }
        long unitLength = unit.nanos;
        long result = TNum.roundToIncrement(quantity, increment * unitLength, mode) / unitLength;
        switch (unit) {
            case DAY:
                return new TimeRec(0, 0, 0, 0, 0, 0, result);
            case HOUR:
                return balanceTime(result, 0, 0, 0, 0, 0);
            case MINUTE:
                return balanceTime(t.hour, result, 0, 0, 0, 0);
            case SECOND:
                return balanceTime(t.hour, t.minute, result, 0, 0, 0);
            case MILLISECOND:
                return balanceTime(t.hour, t.minute, t.second, result, 0, 0);
            case MICROSECOND:
                return balanceTime(t.hour, t.minute, t.second, t.millisecond, result, 0);
            default:
                return balanceTime(t.hour, t.minute, t.second, t.millisecond, t.microsecond, result);
        }
    }

    /** RoundISODateTime. */
    public static IsoDateTime roundDateTime(IsoDateTime dt, long increment, TUnit unit, RMode mode) {
        TimeRec rt = roundTime(dt.time, increment, unit, mode);
        return new IsoDateTime(addDays(dt.date, rt.days), rt);
    }

    public static int dayOfWeek(IsoDate d) {
        int wd = (int) Math.floorMod(epochDays(d) + 4, 7L);
        return wd == 0 ? 7 : wd;
    }

    public static int dayOfYear(IsoDate d) {
        return (int) (epochDays(d) - epochDays(d.year, 1, 1) + 1);
    }

    /** ISOWeekOfYear: returns {week, year}. */
    public static int[] weekOfYear(IsoDate d) {
        int year = d.year;
        int dayOfYear = dayOfYear(d);
        int dayOfWeek = dayOfWeek(d);
        int week = Math.floorDiv(dayOfYear + 7 - dayOfWeek + 3, 7);
        if (week < 1) {
            int dayOfJan1st = dayOfWeek(new IsoDate(year, 1, 1));
            if (dayOfJan1st == 5) return new int[]{53, year - 1};
            if (dayOfJan1st == 6 && isLeap(year - 1L)) return new int[]{53, year - 1};
            return new int[]{52, year - 1};
        }
        if (week == 53) {
            int daysLater = daysInYear(year) - dayOfYear;
            if (daysLater < 4 - dayOfWeek) return new int[]{1, year + 1};
        }
        return new int[]{week, year};
    }
}
