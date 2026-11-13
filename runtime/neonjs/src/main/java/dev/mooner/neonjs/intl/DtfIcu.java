/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DateIntervalFormat;
import com.ibm.icu.text.DateIntervalInfo;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;

import java.util.IdentityHashMap;

/** ICU objects of one DateTimeFormat. Not thread-safe; owned by a single Intl.DateTimeFormat instance. */
final class DtfIcu {
    final ULocale uloc;
    final DateTimePatternGenerator gen;
    final TimeZone zone;

    private final IdentityHashMap<DtfFormat, SimpleDateFormat> formatters = new IdentityHashMap<>();
    private final IdentityHashMap<DtfFormat, SimpleDateFormat> utcFormatters = new IdentityHashMap<>();
    private final IdentityHashMap<DtfFormat, DateIntervalFormat> intervals = new IdentityHashMap<>();
    private final IdentityHashMap<DtfFormat, DateIntervalFormat> utcIntervals = new IdentityHashMap<>();

    DtfIcu(ULocale uloc, DateTimePatternGenerator gen, TimeZone zone) {
        this.uloc = uloc;
        this.gen = gen;
        this.zone = zone;
    }

    private TimeZone zoneFor(boolean utc) {
        return utc ? TimeZone.getFrozenTimeZone("UTC") : zone;
    }

    SimpleDateFormat formatter(DtfFormat format, boolean utc) {
        IdentityHashMap<DtfFormat, SimpleDateFormat> cache = utc ? utcFormatters : formatters;
        SimpleDateFormat existing = cache.get(format);
        if (existing != null) {
            return existing;
        }
        TimeZone zone = zoneFor(utc);
        SimpleDateFormat formatter = new SimpleDateFormat(format.pattern.replace(DtfFormatting.NNBSP, ' '), uloc);
        formatter.setCalendar(DtfData.newCalendar(uloc, zone));
        formatter.setTimeZone(zone);
        cache.put(format, formatter);
        return formatter;
    }

    DateIntervalFormat interval(DtfFormat format, boolean utc) {
        IdentityHashMap<DtfFormat, DateIntervalFormat> cache = utc ? utcIntervals : intervals;
        DateIntervalFormat existing = cache.get(format);
        if (existing != null) {
            return existing;
        }
        // an explicit DateIntervalInfo bypasses ICU4J's static pattern cache, whose cached entries lose the
        // date/time sub-patterns (same-day ranges with seconds then repeat the date)
        DateIntervalFormat interval = DateIntervalFormat.getInstance(format.skeleton, uloc,
                new DateIntervalInfo(uloc));
        interval.setTimeZone(zoneFor(utc));
        cache.put(format, interval);
        return interval;
    }

    com.ibm.icu.util.Calendar calendarAt(long milliseconds, boolean utc) {
        com.ibm.icu.util.Calendar calendar = DtfData.newCalendar(uloc, zoneFor(utc));
        calendar.setTimeInMillis(milliseconds);
        return calendar;
    }

    static DtfIcu of(JSIntlDateTimeFormat dtf) {
        if (dtf.icu != null) {
            return dtf.icu;
        }
        ULocale uloc = DtfData.icuLocale(dtf.dataLocale, dtf.calendar, dtf.numberingSystem);
        DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(uloc);
        String tz = dtf.timeZone;
        String icuId = tz.startsWith("+") || tz.startsWith("-") ? "GMT" + tz : tz;
        TimeZone zone = TimeZone.getFrozenTimeZone(icuId);
        if (TimeZone.UNKNOWN_ZONE_ID.equals(zone.getID())) {
            TimeZones.Record found = TimeZones.find(tz);
            zone = TimeZone.getFrozenTimeZone(IcuCompatibility.icuTimeZoneId(
                    found != null ? found.primary : "UTC"));
        }
        DtfIcu icu = new DtfIcu(uloc, generator, zone);
        dtf.icu = icu;
        return icu;
    }
}
