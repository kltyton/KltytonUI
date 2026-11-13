/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.IslamicCalendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** "islamic-umalqura": ICU's KACST table for 1300–1600 AH, "islamic-civil" outside that range. */
final class UmalquraSource implements TemporalCalendarSource {
    static final int FIRST = 1300;
    static final int LAST = 1600;

    private final TabularIslamicSource civil = new TabularIslamicSource(TabularIslamicSource.CIVIL_EPOCH);

    private static long icuStart(IslamicCalendar calendar, int year, int month) {
        calendar.clear();
        calendar.set(Calendar.EXTENDED_YEAR, year);
        calendar.set(Calendar.MONTH, month);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return calendar.get(Calendar.JULIAN_DAY) - Civil.JD_EPOCH;
    }

    private static IslamicCalendar newCalendar() {
        IslamicCalendar calendar = new IslamicCalendar(TimeZone.GMT_ZONE, ULocale.ROOT);
        calendar.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        return calendar;
    }

    @Override
    public CalendarYearInfo yearInfo(int year) {
        if (year < FIRST - 1 || year > LAST) {
            return civil.yearInfo(year);
        }
        IslamicCalendar calendar = newCalendar();
        if (year == FIRST - 1) {
            // the civil year before the table ends where the table's first year starts
            CalendarYearInfo info = civil.yearInfo(year);
            long[] starts = info.monthStarts.clone();
            starts[12] = icuStart(calendar, FIRST, 0);
            return Civil.info(starts, Civil.CODES_12, starts[12] - starts[0] > 354);
        }
        long[] starts = new long[13];
        for (int month = 0; month < 12; month++) {
            starts[month] = icuStart(calendar, year, month);
        }
        starts[12] = year == LAST ? civil.startOfYear(LAST + 1L) : icuStart(calendar, year + 1, 0);
        return Civil.info(starts, Civil.CODES_12, starts[12] - starts[0] > 354);
    }

    @Override
    public long monthsBeforeYear(int year) {
        return 12L * year;
    }

    @Override
    public int estimateYear(long epochDay) {
        return civil.estimateYear(epochDay);
    }
}
