/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.TimeZone;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarProvider;
import dev.mooner.neonjs.builtins.temporal.TemporalCalendarSource;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporal's non-ISO calendars (ECMA-402 / Intl era and monthCode proposal) and IANA time zone link resolution.
 * Registered through META-INF/services/dev.mooner.neonjs.builtins.temporal.TemporalCalendarProvider.
 *
 * <p>Arithmetic calendars are computed in closed form here; ICU4J supplies the data-driven ones: "persian" (Iranian
 * authority leap years), "islamic-umalqura" (KACST table, 1300–1600 AH) and "chinese" / "dangi" (astronomical
 * computation, used for related ISO years [ChineseSource.ICU_FIRST, ChineseSource.ICU_LAST]). ICU calendars are
 * created per computation and never shared between threads. ICU's ChineseCalendar and HebrewCalendar caches
 * (com.ibm.icu.impl.CalendarCache) can loop forever on negative keys, so neither is ever asked about a year &lt;= 0.
 */
public final class IcuTemporalCalendarProvider implements TemporalCalendarProvider {
    private static final List<String> IDS = List.of(
            "buddhist", "chinese", "coptic", "dangi", "ethioaa", "ethiopic", "gregory", "hebrew", "indian",
            "islamic-civil", "islamic-tbla", "islamic-umalqura", "japanese", "persian", "roc");

    private static final Map<String, TemporalCalendarSource> SOURCES;

    static {
        Map<String, TemporalCalendarSource> sources = new HashMap<>();
        sources.put("gregory", new GregorianSource(0));
        sources.put("japanese", new GregorianSource(0));
        sources.put("buddhist", new GregorianSource(543));
        sources.put("roc", new GregorianSource(-1911));
        sources.put("coptic", new CopticSource(CopticSource.COPTIC_EPOCH, 0));
        sources.put("ethiopic", new CopticSource(CopticSource.ETHIOPIC_EPOCH, 0));
        sources.put("ethioaa", new CopticSource(CopticSource.ETHIOPIC_EPOCH, 5500));
        sources.put("indian", new IndianSource());
        sources.put("islamic-civil", new TabularIslamicSource(TabularIslamicSource.CIVIL_EPOCH));
        sources.put("islamic-tbla", new TabularIslamicSource(TabularIslamicSource.CIVIL_EPOCH - 1));
        sources.put("islamic-umalqura", new UmalquraSource());
        sources.put("hebrew", new HebrewSource());
        sources.put("persian", new PersianSource());
        sources.put("chinese", new ChineseSource(false));
        sources.put("dangi", new ChineseSource(true));
        SOURCES = Map.copyOf(sources);
    }

    @Override
    public Collection<String> calendarIds() {
        return IDS;
    }

    @Override
    public TemporalCalendarSource calendarSource(String calendarId) {
        return SOURCES.get(calendarId);
    }

    @Override
    public String primaryTimeZoneId(String id) {
        String iana;
        try {
            iana = IcuCompatibility.primaryTimeZoneId(id);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (TimeZone.UNKNOWN_ZONE_ID.equals(iana)) {
            return null;
        }
        return iana;
    }
}
