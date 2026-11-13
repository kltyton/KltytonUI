/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.ChineseCalendar;
import com.ibm.icu.util.DangiCalendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "chinese" and "dangi". ICU4J's astronomical calendar for related ISO years [ICU_FIRST, ICU_LAST] (which include the
 * 1900-2100 range of published data); outside, an implementation-defined approximation using mean lunations and mean
 * principal solar terms with the modern leap-month rule. The month index of a year is the number of the mean lunation
 * its new year falls in, which is exact for ICU's true new moons too (they stay within two days of the mean), so month
 * arithmetic is consistent across the boundary.
 */
final class ChineseSource implements TemporalCalendarSource {
    static final double SYN = 29.530588861;
    static final double TROPICAL = 365.242189;
    /** Mean new moon of 2000-01-06 (JD 2451550.09766), in epoch days, UTC. */
    static final double NM0 = 2451550.09766 - 2440587.5;
    /** Winter solstice of 2000 (2000-12-21 13:37 UTC), in epoch days. */
    static final double WS0 = 2451900.0674 - 2440587.5;
    static final int ICU_FIRST = 1700;
    static final int ICU_LAST = 2300;
    static final int OFFICIAL_FIRST = 1901;
    static final int OFFICIAL_LAST = 2099;

    /** UTC offset of the reference meridian, in days (Beijing +08:00, Seoul +09:00). */
    private final double tz;
    private final boolean korean;
    private final ConcurrentHashMap<Integer, Long> icuNewYears = new ConcurrentHashMap<>();

    ChineseSource(boolean korean) {
        this.korean = korean;
        this.tz = korean ? 9.0 / 24 : 8.0 / 24;
    }

    // ---------------------------------------------------------------- mean model

    /** Local epoch-day time of mean new moon {@code k} (k = 0: 2000-01-06 14:20 UTC). */
    private double meanNewMoon(long k) {
        return NM0 + tz + k * SYN;
    }

    private long newMoonDay(long k) {
        return (long) Math.floor(meanNewMoon(k));
    }

    /** The lunation containing local day {@code day}. */
    private long lunationOf(long day) {
        long k = (long) Math.floor((day + 0.5 - (NM0 + tz)) / SYN);
        while (newMoonDay(k + 1) <= day) {
            k++;
        }
        while (newMoonDay(k) > day) {
            k--;
        }
        return k;
    }

    /** Local epoch-day time of the mean winter solstice of Gregorian year {@code y}. */
    private double solstice(int year) {
        return WS0 + tz + (year - 2000) * TROPICAL;
    }

    /** Whether lunation {@code k} contains a mean principal solar term. */
    private boolean hasPrincipalTerm(long k) {
        double a = newMoonDay(k);
        double b = newMoonDay(k + 1);
        double step = TROPICAL / 12;
        return Math.ceil((b - (WS0 + tz)) / step) - Math.ceil((a - (WS0 + tz)) / step) > 0;
    }

    /** Lunation of the 11th month (containing the winter solstice) of Gregorian year {@code y}. */
    private long month11(int year) {
        return lunationOf((long) Math.floor(solstice(year)));
    }

    /** Month codes of the sui from the 11th month of {@code y} - 1 (inclusive) to that of {@code y} (exclusive). */
    private int[] suiCodes(int year) {
        long k0 = month11(year - 1);
        int n = (int) (month11(year) - k0);
        int leap = -1;
        if (n == 13) {
            for (int i = 1; i < 13; i++) {
                if (!hasPrincipalTerm(k0 + i)) {
                    leap = i;
                    break;
                }
            }
        }
        int[] codes = new int[n];
        int num = 11;
        codes[0] = num << 1;
        for (int i = 1; i < n; i++) {
            if (i == leap) {
                codes[i] = (num << 1) | 1;
            } else {
                num = num % 12 + 1;
                codes[i] = num << 1;
            }
        }
        return codes;
    }

    /** Lunation of the model's new year of {@code y}. */
    private long modelNewYear(int year) {
        int[] codes = suiCodes(year);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == 2) {
                return month11(year - 1) + i;
            }
        }
        throw new IllegalStateException("no first month");
    }

    private CalendarYearInfo modelYearInfo(int year) {
        long kStart = modelNewYear(year);
        long kEnd = modelNewYear(year + 1);
        int n = (int) (kEnd - kStart);
        long split = month11(year);
        int[] sA = suiCodes(year);
        int[] sB = suiCodes(year + 1);
        long kA = month11(year - 1);
        int[] codes = new int[n];
        long[] starts = new long[n + 1];
        for (int i = 0; i < n; i++) {
            long k = kStart + i;
            codes[i] = k < split ? sA[(int) (k - kA)] : sB[(int) (k - split)];
            starts[i] = newMoonDay(k);
        }
        starts[n] = newMoonDay(kEnd);
        // stitch to ICU's new year at the boundaries of the ICU range
        if (year == ICU_FIRST - 1) {
            starts[n] = icuNewYear(ICU_FIRST);
        }
        if (year == ICU_LAST + 1) {
            starts[0] = icuNewYear(ICU_LAST + 1);
        }
        return new CalendarYearInfo(starts, codes, n == 13);
    }

    // ---------------------------------------------------------------- ICU

    private Calendar newCalendar(boolean korean) {
        return korean
                ? new DangiCalendar(TimeZone.GMT_ZONE, ULocale.ROOT)
                : new ChineseCalendar(TimeZone.GMT_ZONE, ULocale.ROOT);
    }

    private long icuMonthStart(Calendar calendar, int year, int month) {
        calendar.clear();
        calendar.set(Calendar.EXTENDED_YEAR, year);
        calendar.set(Calendar.MONTH, month);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return calendar.get(Calendar.JULIAN_DAY) - Civil.JD_EPOCH;
    }

    private long icuNewYear(int year) {
        if (!korean && year >= OFFICIAL_FIRST && year <= OFFICIAL_LAST + 1) {
            return Official.newYear(year);
        }
        Long cached = icuNewYears.get(year);
        if (cached != null) {
            return cached;
        }
        if (year < 1) {
            throw new IllegalArgumentException("ICU Chinese calendar must not be used for year " + year);
        }
        long value = icuMonthStart(newCalendar(korean), year, 0);
        if (icuNewYears.size() > 4096) {
            icuNewYears.clear();
        }
        icuNewYears.put(year, value);
        return value;
    }

    /**
     * Walks the months of year {@code y} day-wise (julian day → fields), which is more robust than ICU's field → julian
     * day direction (that one mislabels or merges months in some years, e.g. around the 2033 leap month).
     */
    private CalendarYearInfo icuYearInfo(int year) {
        Calendar calendar = newCalendar(korean);
        long end = icuNewYear(year + 1);
        long[] starts = new long[14];
        int[] codes = new int[13];
        int n = 0;
        long start = icuNewYear(year);
        while (start < end && n < 13) {
            calendar.clear();
            calendar.set(Calendar.JULIAN_DAY, (int) (start + Civil.JD_EPOCH));
            starts[n] = start;
            codes[n] = ((calendar.get(Calendar.MONTH) + 1) << 1) | calendar.get(Calendar.IS_LEAP_MONTH);
            n++;
            // months have 29 or 30 days: the 30th day either is day 30 of this month or day 1 of the next
            calendar.clear();
            calendar.set(Calendar.JULIAN_DAY, (int) (start + 29 + Civil.JD_EPOCH));
            start += calendar.get(Calendar.DAY_OF_MONTH) == 1 ? 29 : 30;
        }
        starts[n] = end;
        return new CalendarYearInfo(Arrays.copyOf(starts, n + 1), Arrays.copyOf(codes, n), n == 13);
    }

    // ---------------------------------------------------------------- source

    @Override
    public CalendarYearInfo yearInfo(int year) {
        if (!korean && year >= OFFICIAL_FIRST && year <= OFFICIAL_LAST) {
            return Official.yearInfo(year);
        }
        if (year >= ICU_FIRST && year <= ICU_LAST) {
            return icuYearInfo(year);
        }
        return modelYearInfo(year);
    }

    @Override
    public long monthsBeforeYear(int year) {
        if (year < ICU_FIRST || year > ICU_LAST + 1) {
            return modelNewYear(year);
        }
        // true new moons stay within a couple of days of the mean ones: the nearest mean lunation is exact
        return Math.round((icuNewYear(year) + 0.5 - (NM0 + tz)) / SYN);
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) Civil.yearOf(epochDay);
    }

    /**
     * The official "chinese" calendar for 1901-2099 (Hong Kong Observatory conversion tables, which follow the
     * Purple Mountain Observatory data required by the specification). ICU's astronomical computation differs in
     * 15 of these years, where a new moon or principal term falls within minutes of midnight (e.g. 1987, 2027, 2030).
     * Each year is 5 hex digits: bits 0-12 mark the 30-day months, bits 13+ the 0-based position of the leap month.
     */
    private static final class Official {
        private static final long FIRST_NEW_YEAR = -25153L; // 1901-02-19
        private static final String DATA =
                "0075200ea50b64a0064b00a9b095560056a00b5905752007520db2500b2500a4b0b4ab002ad0056b04b6900da90fd9200e9200d25" +
                "0ba4d00a56002b6095b5006d400ea905e9200e920cd260052b00a570b2b600b5a006d406ec9007490f69300a930052b0ca5b00a" +
                "ad0056a09b5500ba400b4905a9300a950f52d0053600aad0b5aa005b200da507d4a00d4a10a9500a97005560cab500ad5006d20" +
                "8ea500ea50064a06c9700a9b0f55a0056a00b690b75200b5200b250964b00a4b114ab002ad0056d0cb6900da900d9209d2500d2" +
                "515a4d00a56002b60c5b5006d500ea90be9200e9200d2606a5600a57114d60035a006d50b6c900749006930952b0052b00a5b05" +
                "55a0056a0fb5500ba400b490ba9300a950052d08aad00ab5135aa005d200da50dd4a00d4a00c950952e0055600ab5055b2006d2" +
                "0cea5007250064b0ac9700cab0055a06ad600b691775200b5200b250da4b00a4b004ab0a55b005ad00b6a05b5200d920fd2500d" +
                "2500a550b4ad004b6005b506daa00ec911e9200e9200d260ca5600a5700556086d5007550074906e93006930f52b0052b00a5b0" +
                "b55a0056a00b650974a00b4a11a9500a950052d0caad00ab5005aa08ba500da500d4a07c9500c960f94e0055600ab50b5b2006d" +
                "200ea508e4a0068b10c97004ab0055b0cad600b6a007520972500b4500a8b0549b";

        private static final CalendarYearInfo[] YEARS;
        private static final long[] NEW_YEARS;

        static {
            int n = OFFICIAL_LAST - OFFICIAL_FIRST + 1;
            if (DATA.length() != n * 5) {
                throw new IllegalStateException("malformed Chinese calendar table");
            }
            NEW_YEARS = new long[n + 1];
            long newYear = FIRST_NEW_YEAR;
            YEARS = new CalendarYearInfo[n];
            for (int i = 0; i < n; i++) {
                int value = Integer.parseInt(DATA.substring(i * 5, i * 5 + 5), 16);
                int leap = value >> 13;
                int count = leap != 0 ? 13 : 12;
                long[] starts = new long[count + 1];
                int[] codes = new int[count];
                int num = 0;
                for (int month = 0; month < count; month++) {
                    starts[month] = newYear;
                    newYear += (value & (1 << month)) != 0 ? 30 : 29;
                    if (month == leap && leap != 0) {
                        codes[month] = (num << 1) | 1;
                    } else {
                        codes[month] = (++num) << 1;
                    }
                }
                starts[count] = newYear;
                NEW_YEARS[i] = starts[0];
                YEARS[i] = new CalendarYearInfo(starts, codes, leap != 0);
            }
            NEW_YEARS[n] = newYear;
        }

        static CalendarYearInfo yearInfo(int year) {
            return YEARS[year - OFFICIAL_FIRST];
        }

        /** New year of {@code y} in [OFFICIAL_FIRST], [OFFICIAL_LAST] + 1. */
        static long newYear(int year) {
            return NEW_YEARS[year - OFFICIAL_FIRST];
        }
    }
}

