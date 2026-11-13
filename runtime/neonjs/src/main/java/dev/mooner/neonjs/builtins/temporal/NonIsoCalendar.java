/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.runtime.JSException;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Calendar Date Record; [eraYear] is meaningful only when [era] is not null. */
final class CalDate {
    public final int year;
    public final int month;
    public final int monthCode;
    public final int day;
    public final int dayOfYear;
    public final int daysInMonth;
    public final int daysInYear;
    public final int monthsInYear;
    public final boolean inLeapYear;
    public final String era;
    public final int eraYear;

    CalDate(int year, int month, int monthCode, int day, int dayOfYear, int daysInMonth, int daysInYear,
            int monthsInYear, boolean inLeapYear, String era, int eraYear) {
        this.year = year;
        this.month = month;
        this.monthCode = monthCode;
        this.day = day;
        this.dayOfYear = dayOfYear;
        this.daysInMonth = daysInMonth;
        this.daysInYear = daysInYear;
        this.monthsInYear = monthsInYear;
        this.inLeapYear = inLeapYear;
        this.era = era;
        this.eraYear = eraYear;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public int getMonthCode() {
        return monthCode;
    }

    public int getDay() {
        return day;
    }

    public int getDayOfYear() {
        return dayOfYear;
    }

    public int getDaysInMonth() {
        return daysInMonth;
    }

    public int getDaysInYear() {
        return daysInYear;
    }

    public int getMonthsInYear() {
        return monthsInYear;
    }

    public boolean isInLeapYear() {
        return inLeapYear;
    }

    public boolean getInLeapYear() {
        return inLeapYear;
    }

    public String getEra() {
        return era;
    }

    public int getEraYear() {
        return eraYear;
    }
}

/** Month codes encoded as {@code number * 2 + (1 if leap)}, the encoding of TemporalParser.parseMonthCodeString. */
final class MonthCodes {
    private MonthCodes() {
    }

    static int of(int number, boolean leap) {
        return (number << 1) | (leap ? 1 : 0);
    }

    static int number(int code) {
        return code >> 1;
    }

    static String toString(int code) {
        int n = code >> 1;
        StringBuilder sb = new StringBuilder(4).append('M');
        if (n < 10) sb.append('0');
        sb.append(n);
        if ((code & 1) != 0) sb.append('L');
        return sb.toString();
    }
}

/**
 * A non-ISO calendar: the calendar-independent algorithms of the Intl era and monthCode proposal (eras, month codes,
 * NonISODateAdd / NonISODateUntil / NonISOResolveFields / NonISOMonthDayToISOReferenceDate) on top of the year
 * structure supplied by a {@link TemporalCalendarSource}.
 *
 * <p>DoS discipline: every operation touches a bounded number of calendar years. Month and day balancing use absolute
 * month indices ({@link TemporalCalendarSource#monthsBeforeYear}) and epoch days instead of the spec's unit-by-unit
 * loops, and NonISODateUntil computes its years / months / weeks / days in closed form (the spec's candidate loops are
 * monotone, so the results are identical). All remaining loops have small constant bounds.
 *
 * <p>Thread safety: instances are shared JVM-wide; the only mutable state is the {@link #years} cache of immutable
 * records.
 */
final class NonIsoCalendar {
    /** Arithmetic years beyond this are never within the Temporal date range in any supported calendar. */
    static final int MAX_YEAR = 300_000;
    private static final int CACHE_LIMIT = 4096;
    private static final int NONE = 0;
    private static final int SKIP_BACKWARD = 1;
    private static final int SKIP_FORWARD = 2;
    static final int EPOCH = 0;
    static final int NEGATIVE = 1;
    static final int OFFSET = 2;

    private final String id;
    private final TemporalCalendarSource source;
    private final ConcurrentHashMap<Integer, CalendarYearInfo> years = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> monthsBefore = new ConcurrentHashMap<>();
    private final Era[] eras;
    private final boolean chineseLike;
    private final int leapToCommon;
    private final boolean hasMonth13;

    private volatile double meanMonthsPerYear = 0.0;
    private volatile long monthsBeforeOrigin = 0L;
    private volatile int[] maxDaysByCode = null;

    private NonIsoCalendar(String id, TemporalCalendarSource source) {
        this.id = id;
        this.source = source;
        this.eras = ERAS.get(id);
        this.chineseLike = "chinese".equals(id) || "dangi".equals(id);
        switch (id) {
            case "chinese":
            case "dangi":
                this.leapToCommon = SKIP_BACKWARD;
                break;
            case "hebrew":
                this.leapToCommon = SKIP_FORWARD;
                break;
            default:
                this.leapToCommon = NONE;
                break;
        }
        this.hasMonth13 = "coptic".equals(id) || "ethiopic".equals(id) || "ethioaa".equals(id);
    }

    public String getId() {
        return id;
    }

    boolean supportsEra() {
        return eras != null;
    }

    public boolean getSupportsEra() {
        return supportsEra();
    }

    /** CalendarHasMidYearEras */
    boolean hasMidYearEras() {
        return "japanese".equals(id);
    }

    public boolean getHasMidYearEras() {
        return hasMidYearEras();
    }

    // ------------------------------------------------------------------ year structure

    CalendarYearInfo info(int y) {
        if (y > MAX_YEAR || y < -MAX_YEAR) throw rangeErr("date outside of supported range");
        CalendarYearInfo cached = years.get(y);
        if (cached != null) return cached;
        CalendarYearInfo inf = guarded(() -> source.yearInfo(y));
        int monthCount = inf.monthCodes.length;
        if (monthCount < 12 || monthCount > 13) throw rangeErr("invalid calendar data");
        if (years.size() >= CACHE_LIMIT) years.clear();
        years.put(y, inf);
        return inf;
    }

    private long mby(int y) {
        if (y > MAX_YEAR + 1 || y < -MAX_YEAR) throw rangeErr("date outside of supported range");
        Long cached = monthsBefore.get(y);
        if (cached != null) return cached;
        long v = guarded(() -> source.monthsBeforeYear(y));
        if (monthsBefore.size() >= CACHE_LIMIT) monthsBefore.clear();
        monthsBefore.put(y, v);
        return v;
    }

    /** Provider failures (bad data, ICU errors) surface as RangeErrors, not as engine-internal errors. */
    private <T> T guarded(Supplier<T> f) {
        try {
            return f.get();
        } catch (JSException e) {
            throw e;
        } catch (RuntimeException e) {
            throw rangeErr("calendar data unavailable for the " + id + " calendar");
        }
    }

    private static JSException rangeErr(String msg) {
        return JSException.rangeError(msg);
    }

    private static JSException typeErr(String msg) {
        return JSException.typeError(msg);
    }

    private static String take(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static int daysInMonth(CalendarYearInfo inf, int m) {
        return (int) (inf.monthStarts[m] - inf.monthStarts[m - 1]);
    }

    private static long startOf(CalendarYearInfo inf) {
        return inf.monthStarts[0];
    }

    private static long endOf(CalendarYearInfo inf) {
        return inf.monthStarts[inf.monthCodes.length];
    }

    private static int monthCountOf(CalendarYearInfo inf) {
        return inf.monthCodes.length;
    }

    /** The arithmetic year containing [epochDay]; the estimate is corrected in jumps that never overshoot. */
    int yearOf(long epochDay) {
        int estimate = guarded(() -> source.estimateYear(epochDay));
        int y = Math.max(-MAX_YEAR, Math.min(MAX_YEAR, estimate));
        for (int i = 0; i < 64; i++) {
            CalendarYearInfo inf = info(y);
            long start = startOf(inf);
            long end = endOf(inf);
            if (epochDay < start) {
                // no calendar year is longer than 390 days, so these jumps stay on the near side of the target
                y = y - (int) Math.max(1L, (start - epochDay) / 390);
            } else if (epochDay >= end) {
                y = y + (int) Math.max(1L, (epochDay - end) / 390);
            } else {
                return y;
            }
        }
        throw rangeErr("calendar computation did not converge");
    }

    /** Absolute index of month [m] of year [y]. */
    long monthIndex(int y, int m) {
        return mby(y) + (m - 1);
    }

    /** The arithmetic year containing absolute month index [idx]. */
    int yearOfMonthIndex(long idx) {
        if (meanMonthsPerYear == 0.0) {
            long origin = mby(0);
            monthsBeforeOrigin = origin;
            meanMonthsPerYear = (mby(1000) - origin) / 1000.0;
        }
        double est = Math.floor((idx - monthsBeforeOrigin) / meanMonthsPerYear);
        int y = (int) Math.max(-(double) MAX_YEAR, Math.min((double) MAX_YEAR, est));
        for (int i = 0; i < 64; i++) {
            long a = mby(y);
            if (idx < a) {
                y -= (int) Math.max(1L, (a - idx) / 14);
                continue;
            }
            long b = mby(y + 1);
            if (idx >= b) {
                y += (int) Math.max(1L, (idx - b) / 14);
                continue;
            }
            return y;
        }
        throw rangeErr("calendar computation did not converge");
    }

    // ------------------------------------------------------------------ conversions

    /** NonISOCalendarISOToDate */
    CalDate isoToDate(IsoDate iso) {
        return fromEpochDay(TM.epochDays(iso));
    }

    CalDate fromEpochDay(long day) {
        int y = yearOf(day);
        CalendarYearInfo inf = info(y);
        int monthCount = monthCountOf(inf);
        int m = 1;
        while (m < monthCount && day >= inf.monthStarts[m]) m++;
        int d = (int) (day - inf.monthStarts[m - 1] + 1);
        String era = null;
        int eraYear = 0;
        if (eras != null) {
            Era e = eraOf(y, day);
            era = e.code;
            eraYear = eraYearOf(e, y);
        }
        return new CalDate(
                y, m, inf.monthCodes[m - 1], d, (int) (day - startOf(inf) + 1), daysInMonth(inf, m),
                (int) (endOf(inf) - startOf(inf)), monthCount, inf.inLeapYear, era, eraYear);
    }

    /** CalendarIntegersToISO */
    IsoDate integersToIso(int y, int m, int d) {
        CalendarYearInfo inf = info(y);
        int monthCount = monthCountOf(inf);
        if (m < 1 || m > monthCount || d < 1 || d > daysInMonth(inf, m)) {
            throw rangeErr("invalid date in the " + id + " calendar");
        }
        // callers check ISODateWithinLimits / ISOYearMonthWithinLimits (the first of a month may precede the limit)
        return TM.dateFromEpochDays(inf.monthStarts[m - 1] + d - 1);
    }

    private IsoDate isoFromEpochDay(long day) {
        if (day < -TM.MAX_EPOCH_DAYS - 1 || day > TM.MAX_EPOCH_DAYS) throw rangeErr("date outside of supported range");
        return TM.dateFromEpochDays(day);
    }

    // ------------------------------------------------------------------ month codes

    /** IsValidMonthCodeForCalendar */
    boolean isValidMonthCode(int code) {
        int n = code >> 1;
        if ((code & 1) == 0) return (n >= 1 && n <= 12) || (n == 13 && hasMonth13);
        switch (leapToCommon) {
            case SKIP_BACKWARD:
                return n >= 1 && n <= 12;
            case SKIP_FORWARD:
                return n == 5;
            default:
                return false;
        }
    }

    /** YearContainsMonthCode */
    boolean yearContainsMonthCode(int y, int code) {
        if ((code & 1) == 0) return true;
        for (int c : info(y).monthCodes) {
            if (c == code) return true;
        }
        return false;
    }

    /** ConstrainMonthCode */
    int constrainMonthCode(int y, int code, Overflow overflow) {
        if (yearContainsMonthCode(y, code)) return code;
        if (overflow == Overflow.REJECT) throw rangeErr("month code " + MonthCodes.toString(code) + " does not exist in year " + y);
        return leapToCommon == SKIP_FORWARD ? MonthCodes.of(6, false) : code & ~1;
    }

    /** MonthCodeToOrdinal */
    int monthCodeToOrdinal(int y, int code) {
        int[] codes = info(y).monthCodes;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == code) return i + 1;
        }
        throw rangeErr("month code " + MonthCodes.toString(code) + " does not exist in year " + y);
    }

    // ------------------------------------------------------------------ eras

    private Era eraOf(int y, long epochDay) {
        Era[] es = eras;
        if ("japanese".equals(id)) {
            for (int k = 0; k < JAPANESE_ERA_STARTS.length; k++) {
                if (epochDay >= JAPANESE_ERA_STARTS[k]) return es[k];
            }
        }
        if (es.length == 1) return es[0];
        // the era containing the epoch year comes first, the era counting backwards second
        return y >= es[es.length - 2].minArithmeticYear() ? es[es.length - 2] : es[es.length - 1];
    }

    private int eraYearOf(Era e, int y) {
        switch (e.kind) {
            case EPOCH:
                return y;
            case NEGATIVE:
                return 1 - y;
            default:
                return y - e.offset + 1;
        }
    }

    /** CanonicalizeEraInCalendar; null when [era] is not an era of this calendar. */
    Era canonicalizeEra(String era) {
        if (eras == null) return null;
        for (Era e : eras) {
            if (e.code.equals(era) || (e.alias != null && e.alias.equals(era))) return e;
        }
        return null;
    }

    /** CalendarDateArithmeticYearForEraYear */
    double arithmeticYearForEraYear(Era e, double eraYear) {
        switch (e.kind) {
            case EPOCH:
                return eraYear;
            case NEGATIVE:
                return 1 - eraYear;
            default:
                return e.offset + eraYear - 1;
        }
    }

    // ------------------------------------------------------------------ fields

    /** NonISOFieldKeysToIgnore on a field mask. */
    int fieldKeysToIgnore(int keys) {
        int ignored = keys;
        if ((keys & TCal.F_MONTH) != 0) ignored = ignored | TCal.F_MONTHCODE;
        if ((keys & TCal.F_MONTHCODE) != 0) ignored = ignored | TCal.F_MONTH;
        int eraKeys = TCal.F_ERA | TCal.F_ERAYEAR | TCal.F_YEAR;
        if ((keys & eraKeys) != 0 && supportsEra()) ignored = ignored | eraKeys;
        if ((keys & (TCal.F_DAY | TCal.F_MONTH | TCal.F_MONTHCODE)) != 0 && hasMidYearEras()) {
            ignored = ignored | TCal.F_ERA | TCal.F_ERAYEAR;
        }
        return ignored;
    }

    /** NonISOResolveFields */
    void resolveFields(CalFields f, int type) {
        boolean needsYear = type == TCal.TYPE_DATE || type == TCal.TYPE_YEAR_MONTH;
        if (f.monthCode == null) needsYear = true;
        if (!Double.isNaN(f.month)) needsYear = true;
        boolean needsDay = type == TCal.TYPE_DATE || type == TCal.TYPE_MONTH_DAY;
        boolean hasEras = supportsEra();
        if (needsYear && Double.isNaN(f.year)) {
            if (!hasEras) throw typeErr("year is required");
            if (f.era == null || Double.isNaN(f.eraYear)) throw typeErr("year, or era and eraYear, are required");
        }
        if (hasEras) {
            if (f.era != null && Double.isNaN(f.eraYear)) throw typeErr("eraYear is required when era is present");
            if (!Double.isNaN(f.eraYear) && f.era == null) throw typeErr("era is required when eraYear is present");
        }
        if (needsDay && Double.isNaN(f.day)) throw typeErr("day is required");
        if (Double.isNaN(f.month) && f.monthCode == null) throw typeErr("month or monthCode is required");
        if (hasEras && !Double.isNaN(f.eraYear)) {
            Era e = canonicalizeEra(f.era);
            if (e == null) throw rangeErr("invalid era " + take(f.era, 30) + " for the " + id + " calendar");
            double y = arithmeticYearForEraYear(e, f.eraYear);
            if (!Double.isNaN(f.year) && f.year != y) throw rangeErr("year and era/eraYear do not agree");
            f.year = y;
            f.era = null;
            f.eraYear = Double.NaN;
        }
        String mc = f.monthCode;
        if (mc != null) {
            int code = TemporalParser.parseMonthCodeString(mc);
            if (!isValidMonthCode(code)) throw rangeErr("invalid monthCode " + mc + " for the " + id + " calendar");
            if (!Double.isNaN(f.year)) {
                int y = checkYear(f.year);
                int constrained = yearContainsMonthCode(y, code) ? code : constrainMonthCode(y, code, Overflow.CONSTRAIN);
                int month = monthCodeToOrdinal(y, constrained);
                if (!Double.isNaN(f.month) && f.month != (double) month) throw rangeErr("month and monthCode do not agree");
                f.month = (double) month;
            }
        }
    }

    private int checkYear(double y) {
        if (y > MAX_YEAR || y < -MAX_YEAR) throw rangeErr("date outside of supported range");
        return (int) y;
    }

    /** NonISOCalendarDateToISO (after resolving the fields). */
    IsoDate dateToIso(CalFields f, Overflow overflow) {
        int y = checkYear(f.year);
        if (f.monthCode != null) constrainMonthCode(y, TemporalParser.parseMonthCodeString(f.monthCode), overflow);
        CalendarYearInfo inf = info(y);
        int n = monthCountOf(inf);
        int month;
        if (f.month > n) {
            if (overflow == Overflow.REJECT) throw rangeErr("month out of range");
            month = n;
        } else {
            month = (int) f.month;
        }
        int dim = daysInMonth(inf, month);
        int day;
        if (f.day > dim) {
            if (overflow == Overflow.REJECT) throw rangeErr("day out of range");
            day = dim;
        } else {
            day = (int) f.day;
        }
        return integersToIso(y, month, day);
    }

    /** NonISOMonthDayToISOReferenceDate (after resolving the fields). */
    IsoDate monthDayToIso(CalFields f, Overflow overflow) {
        int code;
        int maxDays;
        if (!Double.isNaN(f.year)) {
            int y = checkYear(f.year);
            CalendarYearInfo inf = info(y);
            if (startOf(inf) > TM.MAX_EPOCH_DAYS || endOf(inf) <= -TM.MAX_EPOCH_DAYS - 1) {
                throw rangeErr("date outside of supported range");
            }
            int n = monthCountOf(inf);
            int month;
            if (f.month > n) {
                if (overflow == Overflow.REJECT) throw rangeErr("month out of range");
                month = n;
            } else {
                month = (int) f.month;
            }
            String mc = f.monthCode;
            code = mc == null ? inf.monthCodes[month - 1]
                    : constrainMonthCode(y, TemporalParser.parseMonthCodeString(mc), overflow);
            maxDays = daysInMonth(inf, month);
        } else {
            code = TemporalParser.parseMonthCodeString(f.monthCode);
            maxDays = chineseLike ? 30 : maxDaysInMonthCode(code);
        }
        int day;
        if (f.day > maxDays) {
            if (overflow == Overflow.REJECT) throw rangeErr("day out of range");
            day = maxDays;
        } else {
            day = (int) f.day;
        }
        if (chineseLike) {
            if (chineseReferenceYear(code, day) == 0) {
                if (overflow == Overflow.REJECT) {
                    throw rangeErr("month-day " + MonthCodes.toString(code) + " " + day + " does not exist");
                }
                code = code & ~1;
            }
            int ref = chineseReferenceYear(code, day);
            IsoDate found = findInIsoYear(code, day, ref);
            if (found != null) return found;
        }
        IsoDate reference = referenceDate(code, day);
        if (reference == null) throw rangeErr("month-day " + MonthCodes.toString(code) + " " + day + " does not exist");
        return reference;
    }

    /** Table 6 (ISO reference years for "chinese" and "dangi"); 0 for "—". */
    private int chineseReferenceYear(int code, int day) {
        int n = code >> 1;
        if ((code & 1) == 0) {
            if (day < 30) return 1972;
            switch (n) {
                case 1:
                case 4:
                case 11:
                    return 1970;
                case 3:
                    return "dangi".equals(id) ? 1968 : 1966;
                case 6:
                case 8:
                    return 1971;
                default:
                    return 1972;
            }
        }
        if (day < 30) {
            switch (n) {
                case 2:
                    return 1947;
                case 3:
                    return 1966;
                case 4:
                    return 1963;
                case 5:
                    return 1971;
                case 6:
                    return 1960;
                case 7:
                    return 1968;
                case 8:
                    return 1957;
                case 9:
                    return 2014;
                case 10:
                    return 1984;
                case 11:
                    return day <= 10 ? 2033 : 2034;
                default:
                    return 0;
            }
        }
        switch (n) {
            case 3:
                return 1955;
            case 4:
                return 1944;
            case 5:
                return 1952;
            case 6:
                return 1941;
            case 7:
                return 1938;
            default:
                return 0;
        }
    }

    /** The latest date in ISO year [isoYear] with [code] and [day], or null. */
    private IsoDate findInIsoYear(int code, int day, int isoYear) {
        long first = TM.epochDays((long) isoYear, 1, 1);
        long last = TM.epochDays((long) isoYear, 12, 31);
        long best = Long.MIN_VALUE;
        int y1 = yearOf(last);
        for (int y = y1; y >= y1 - 2; y--) {
            Long d = dayInYear(y, code, day);
            if (d == null) continue;
            if (d >= first && d <= last && d > best) best = d;
        }
        return best == Long.MIN_VALUE ? null : TM.dateFromEpochDays(best);
    }

    /** Epoch day of [code] / [day] in year [y], or null when that year lacks it. */
    private Long dayInYear(int y, int code, int day) {
        CalendarYearInfo inf = info(y);
        for (int i = 0; i < inf.monthCodes.length; i++) {
            if (inf.monthCodes[i] == code) {
                return day <= daysInMonth(inf, i + 1) ? inf.monthStarts[i] + day - 1 : null;
            }
        }
        return null;
    }

    /** The latest date in 1900–1972 with [code] / [day], else the earliest in 1973–2035 (bounded scans). */
    private IsoDate referenceDate(int code, int day) {
        long lo = TM.epochDays(1900, 1, 1);
        long mid = TM.epochDays(1972, 12, 31);
        long hi = TM.epochDays(2035, 12, 31);
        int yMid = yearOf(mid);
        int yLo = yearOf(lo);
        for (int y = yMid; y >= yLo; y--) {
            Long d = dayInYear(y, code, day);
            if (d == null) continue;
            if (d >= lo && d <= mid) return TM.dateFromEpochDays(d);
        }
        int yHi = yearOf(hi);
        for (int y = yearOf(mid + 1); y <= yHi; y++) {
            Long d = dayInYear(y, code, day);
            if (d == null) continue;
            if (d >= mid + 1 && d <= hi) return TM.dateFromEpochDays(d);
        }
        return null;
    }

    /** The largest number of days the month [code] has in any year (sampled over the reference-date window). */
    private int maxDaysInMonthCode(int code) {
        int[] table = maxDaysByCode;
        if (table == null) {
            table = new int[28];
            int yLo = yearOf(TM.epochDays(1900, 1, 1));
            int yHi = yearOf(TM.epochDays(2035, 12, 31));
            for (int y = yLo; y <= yHi; y++) {
                CalendarYearInfo inf = info(y);
                for (int i = 0; i < inf.monthCodes.length; i++) {
                    int c = inf.monthCodes[i];
                    if (c < table.length) table[c] = Math.max(table[c], daysInMonth(inf, i + 1));
                }
            }
            maxDaysByCode = table;
        }
        return code < table.length && table[code] > 0 ? table[code] : 30;
    }

    // ------------------------------------------------------------------ arithmetic

    /** Packs (year, month) of absolute month index [idx]. */
    private long yearMonthOfIndex(long idx) {
        int y = yearOfMonthIndex(idx);
        int m = (int) (idx - mby(y)) + 1;
        return ((long) y << 8) | (long) m;
    }

    /** NonISODateAdd */
    IsoDate dateAdd(IsoDate iso, DateDuration dur, Overflow overflow) {
        CalDate p = isoToDate(iso);
        long y0l = (long) p.year + dur.years;
        if (y0l > MAX_YEAR || y0l < -MAX_YEAR) throw rangeErr("date outside of supported range");
        int y0 = (int) y0l;
        int m0 = monthCodeToOrdinal(y0, constrainMonthCode(y0, p.monthCode, overflow));
        long ym = yearMonthOfIndex(monthIndex(y0, m0) + dur.months);
        CalendarYearInfo inf = info((int) (ym >> 8));
        int m = (int) (ym & 0xff);
        int dim = daysInMonth(inf, m);
        int regulated;
        if (p.day <= dim) {
            regulated = p.day;
        } else {
            if (overflow == Overflow.REJECT) throw rangeErr("day out of range");
            regulated = dim;
        }
        long days = dur.days + 7 * dur.weeks;
        if (Math.abs(days) > 4 * TM.MAX_EPOCH_DAYS) throw rangeErr("date outside of supported range");
        return isoFromEpochDay(inf.monthStarts[m - 1] + (regulated - 1) + days);
    }

    /** CompareSurpasses with a month code. */
    private static boolean surpassesCode(int sign, long year, int code, int day, CalDate t) {
        if (year != (long) t.year) return sign * (year - t.year) > 0;
        if (code != t.monthCode) return sign * (code - t.monthCode) > 0;
        if (day != t.day) return sign * (day - t.day) > 0;
        return false;
    }

    /** CompareSurpasses with an ordinal month. */
    private static boolean surpassesMonth(int sign, long year, int month, int day, CalDate t) {
        if (year != (long) t.year) return sign * (year - t.year) > 0;
        if (month != t.month) return sign * (month - t.month) > 0;
        if (day != t.day) return sign * (day - t.day) > 0;
        return false;
    }

    /** NonISODateSurpasses with only years. */
    private boolean surpassesYears(int sign, CalDate p1, CalDate p2, long years) {
        long y0l = (long) p1.year + years;
        if (surpassesCode(sign, y0l, p1.monthCode, p1.day, p2)) return true;
        if (y0l > MAX_YEAR || y0l < -MAX_YEAR) return true;
        int y0 = (int) y0l;
        int m0 = monthCodeToOrdinal(y0, constrainMonthCode(y0, p1.monthCode, Overflow.CONSTRAIN));
        return surpassesMonth(sign, y0l, m0, p1.day, p2);
    }

    /** NonISODateUntil, in closed form. */
    DateDuration dateUntil(IsoDate one, IsoDate two, TUnit largest) {
        int sign = -TM.compareDate(one, two);
        if (sign == 0) return DateDuration.ZERO;
        CalDate p1 = isoToDate(one);
        CalDate p2 = isoToDate(two);
        long years = 0L;
        if (largest == TUnit.YEAR) {
            // start just short of the target year: that candidate never surpasses, and the answer is within 2 steps
            long candidate = (long) (p2.year - p1.year);
            if (candidate != 0L) candidate -= sign;
            int guard = 0;
            while (!surpassesYears(sign, p1, p2, candidate)) {
                years = candidate;
                candidate += sign;
                if (++guard > 8) throw rangeErr("calendar computation did not converge");
            }
        }
        int y0 = (int) ((long) p1.year + years);
        int m0 = monthCodeToOrdinal(y0, constrainMonthCode(y0, p1.monthCode, Overflow.CONSTRAIN));
        long idx0 = monthIndex(y0, m0);
        long months = 0L;
        if (largest == TUnit.YEAR || largest == TUnit.MONTH) {
            // the month index where (year, month) meets the target's; one less if the day would pass it
            long k = monthIndex(p2.year, p2.month) - idx0;
            if (sign * (p1.day - p2.day) > 0) k -= sign;
            if (k * sign < 0) k = 0;
            months = k;
        }
        long ym = yearMonthOfIndex(idx0 + months);
        CalendarYearInfo inf = info((int) (ym >> 8));
        int m = (int) (ym & 0xff);
        int regulated = Math.min(p1.day, daysInMonth(inf, m));
        long days = TM.epochDays(two) - (inf.monthStarts[m - 1] + regulated - 1);
        long weeks = 0L;
        if (largest == TUnit.WEEK) {
            weeks = days / 7;
            days %= 7;
        }
        return new DateDuration(years, months, weeks, days);
    }

    // ------------------------------------------------------------------ registry and tables

    static final class Era {
        public final String code;
        public final String alias;
        public final int kind;
        public final int offset;

        Era(String code, String alias, int kind, int offset) {
            this.code = code;
            this.alias = alias;
            this.kind = kind;
            this.offset = offset;
        }

        /** The smallest arithmetic year counted in this era when it counts forward. */
        int minArithmeticYear() {
            return kind == OFFSET ? offset : 1;
        }

        public String getCode() {
            return code;
        }

        public String getAlias() {
            return alias;
        }

        public int getKind() {
            return kind;
        }

        public int getOffset() {
            return offset;
        }

        public int getMinArithmeticYear() {
            return minArithmeticYear();
        }
    }

    private static Era[] single(String code) {
        return new Era[]{new Era(code, null, EPOCH, 0)};
    }

    private static Era[] twoWay(String pos, String posAlias, String neg, String negAlias) {
        return new Era[]{new Era(pos, posAlias, EPOCH, 0), new Era(neg, negAlias, NEGATIVE, 0)};
    }

    /** Table 2. Order: (japanese regnal eras newest first), the forward era, then the backward era. */
    private static final Map<String, Era[]> ERAS;

    /** First days of reiwa, heisei, showa, taisho and (as counted in Temporal) meiji. */
    private static final long[] JAPANESE_ERA_STARTS = {
            TM.epochDays(2019, 5, 1), TM.epochDays(1989, 1, 8), TM.epochDays(1926, 12, 25), TM.epochDays(1912, 7, 30),
            TM.epochDays(1873, 1, 1),
    };

    static {
        Map<String, Era[]> eras = new HashMap<>();
        eras.put("buddhist", single("be"));
        eras.put("coptic", single("am"));
        eras.put("ethioaa", single("aa"));
        eras.put("ethiopic", new Era[]{new Era("am", null, EPOCH, 0), new Era("aa", null, OFFSET, -5499)});
        eras.put("gregory", twoWay("ce", "ad", "bce", "bc"));
        eras.put("hebrew", single("am"));
        eras.put("indian", single("shaka"));
        eras.put("islamic-civil", twoWay("ah", null, "bh", null));
        eras.put("islamic-tbla", twoWay("ah", null, "bh", null));
        eras.put("islamic-umalqura", twoWay("ah", null, "bh", null));
        eras.put("japanese", new Era[]{
                new Era("reiwa", null, OFFSET, 2019), new Era("heisei", null, OFFSET, 1989),
                new Era("showa", null, OFFSET, 1926), new Era("taisho", null, OFFSET, 1912),
                new Era("meiji", null, OFFSET, 1868), new Era("ce", "ad", EPOCH, 0),
                new Era("bce", "bc", NEGATIVE, 0),
        });
        eras.put("persian", single("ap"));
        eras.put("roc", twoWay("roc", null, "broc", null));
        ERAS = eras;
    }

    private static final ConcurrentHashMap<String, NonIsoCalendar> instances = new ConcurrentHashMap<>();

    /** The calendar for canonical id [id], or null when no provider implements it. */
    static NonIsoCalendar of(String id) {
        NonIsoCalendar existing = instances.get(id);
        if (existing != null) return existing;
        TemporalCalendarProvider provider = TemporalProviders.getProvider();
        if (provider == null) return null;
        if (!supportedIds().contains(id)) return null;
        TemporalCalendarSource source = provider.calendarSource(id);
        if (source == null) return null;
        return instances.computeIfAbsent(id, key -> new NonIsoCalendar(key, source));
    }

    private static volatile Set<String> ids = null;

    /** Canonical ids of the available non-ISO calendars. */
    static Set<String> supportedIds() {
        Set<String> cached = ids;
        if (cached != null) return cached;
        TemporalCalendarProvider provider = TemporalProviders.getProvider();
        Collection<String> calendarIds = provider == null ? null : provider.calendarIds();
        Set<String> s = calendarIds == null ? Collections.emptySet() : new HashSet<>(calendarIds);
        ids = s;
        return s;
    }
}
