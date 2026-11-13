/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.PersianCalendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.builtins.temporal.CalendarYearInfo;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

/** "persian": ICU4J (33-year rule corrected by the Iranian calendar authority's table). */
final class PersianSource implements TemporalCalendarSource {
    private static long startOfYear(Calendar calendar, int year) {
        calendar.clear();
        calendar.set(Calendar.EXTENDED_YEAR, year);
        calendar.set(Calendar.MONTH, 0);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return calendar.get(Calendar.JULIAN_DAY) - Civil.JD_EPOCH;
    }

    @Override
    public CalendarYearInfo yearInfo(int year) {
        PersianCalendar calendar = new PersianCalendar(TimeZone.GMT_ZONE, ULocale.ROOT);
        long first = startOfYear(calendar, year);
        long end = startOfYear(calendar, year + 1);
        long[] starts = new long[13];
        long day = first;
        for (int month = 1; month <= 12; month++) {
            starts[month - 1] = day;
            day += month <= 6 ? 31 : 30;
        }
        starts[12] = end;
        return Civil.info(starts, Civil.CODES_12, end - first > 365);
    }

    @Override
    public long monthsBeforeYear(int year) {
        return 12L * year;
    }

    @Override
    public int estimateYear(long epochDay) {
        return (int) (Civil.yearOf(epochDay) - 621);
    }
}
