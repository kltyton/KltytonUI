/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.impl.ICUData;
import com.ibm.icu.impl.ICUResourceBundle;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.NumberingSystem;
import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import com.ibm.icu.util.UResourceBundle;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.concurrent.LazyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ICU-backed locale information used by Intl.Locale (likely subtags, calendars, hour cycles, week data, ...). */
final class LocaleInfo {
    private LocaleInfo() {
    }

    /** Region preference: (region, regionOverride). Kotlin Pair replaced by this domain type. */
    static final class RegionPreference {
        final String region;
        final String regionOverride;

        RegionPreference(String region, String regionOverride) {
            this.region = region;
            this.regionOverride = regionOverride;
        }
    }

    /** The ICU locale for {@code locale}'s data: its best available prefix, or root (never ICU's JVM-default fallback). */
    static ULocale dataLocale(String locale) {
        String found = Locales.bestAvailable(LanguageTag.baseName(locale));
        if (found == null) {
            return ULocale.ROOT;
        }
        return ULocale.forLanguageTag(found);
    }

    static String likelySubtags(String base, boolean maximize) {
        try {
            ULocale locale = ULocale.forLanguageTag(base);
            ULocale result = maximize ? ULocale.addLikelySubtags(locale) : ULocale.minimizeSubtags(locale);
            String tag = result.toLanguageTag();
            return LanguageTag.isStructurallyValid(tag) ? LanguageTag.canonicalize(tag) : base;
        } catch (Exception invalid) {
            return base;
        }
    }

    /** Converts an ICU calendar type ("gregorian") to its BCP 47 form ("gregory"). */
    static String calendarToBcp47(String icu) {
        String converted = ULocale.toUnicodeLocaleType("ca", icu);
        return converted != null ? converted : icu;
    }

    static String collationToBcp47(String icu) {
        String converted = ULocale.toUnicodeLocaleType("co", icu);
        return converted != null ? converted : icu;
    }

    /** RegionPreference: (region, regionOverride). */
    static RegionPreference regionPreference(String locale) {
        LanguageTag.Parsed parsed = LanguageTag.split(locale);
        String region = parsed.region == null ? null : parsed.region.toUpperCase(Locale.ROOT);
        if (region == null) {
            region = subdivisionRegion(locale, "sd");
            if (region == null) {
                LanguageTag.Parsed maximal = LanguageTag.split(
                        likelySubtags(LanguageTag.baseName(locale), true));
                region = maximal.region == null ? "001" : maximal.region.toUpperCase(Locale.ROOT);
            }
        }
        return new RegionPreference(region, subdivisionRegion(locale, "rg"));
    }

    /** CanonicalUnicodeSubdivision: the region part of a valid "rg"/"sd" subdivision keyword. */
    private static String subdivisionRegion(String locale, String key) {
        String value = LanguageTag.unicodeKeyword(locale, key);
        if (value == null) {
            return null;
        }
        if (value.length() < 3 || value.length() > 7) {
            return null;
        }
        String region = LanguageTag.isDigit(value.charAt(0))
                ? value.substring(0, 3)
                : value.substring(0, 2);
        if (!LanguageTag.isRegionSubtag(region)) {
            return null;
        }
        return region.toUpperCase(Locale.ROOT);
    }

    private static String lookupRegion(String locale) {
        RegionPreference preference = regionPreference(locale);
        return preference.regionOverride != null ? preference.regionOverride : preference.region;
    }

    static List<Object> calendars(JSIntlLocale locale) {
        if (locale.calendar != null) {
            return List.of(locale.calendar);
        }
        String region = lookupRegion(locale.locale);
        String[] list = Calendar.getKeywordValuesForLocale("calendar", new ULocale("und_" + region), true);
        Set<String> result = new LinkedHashSet<>();
        for (String value : list) {
            result.add(calendarToBcp47(value));
        }
        return new ArrayList<>(result);
    }

    static List<Object> collations(JSIntlLocale locale) {
        if (locale.collation != null) {
            return List.of(locale.collation);
        }
        String[] values = Collator.getKeywordValuesForLocale("collation", dataLocale(locale.locale), true);
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String converted = collationToBcp47(value);
            if (!"standard".equals(converted) && !"search".equals(converted)) {
                result.add(converted);
            }
        }
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return new ArrayList<>(sorted);
    }

    private static final LazyValue<UResourceBundle> TIME_DATA = new LazyValue<>(
            () -> UResourceBundle.getBundleInstance(ICUData.ICU_BASE_NAME, "supplementalData",
                    ICUResourceBundle.ICU_DATA_CLASS_LOADER).get("timeData"));

    private static List<String> hourCyclesFor(String key) {
        UResourceBundle entry;
        try {
            entry = TIME_DATA.get().get(key);
        } catch (Exception missing) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        try {
            addHourCycle(out, entry.get("preferred").getString());
        } catch (Exception ignored) {
            // optional preferred skeleton
        }
        try {
            for (String skeleton : entry.get("allowed").getStringArray()) {
                addHourCycle(out, skeleton);
            }
        } catch (Exception ignored) {
            // optional allowed skeletons
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    private static void addHourCycle(Set<String> out, String skeleton) {
        String value = hourCycleOf(skeleton);
        if (value != null) {
            out.add(value);
        }
    }

    private static String hourCycleOf(String skeleton) {
        if (skeleton.isEmpty()) {
            return null;
        }
        switch (skeleton.charAt(0)) {
            case 'h':
                return "h12";
            case 'H':
                return "h23";
            case 'K':
                return "h11";
            case 'k':
                return "h24";
            default:
                return null;
        }
    }

    static List<Object> hourCycles(JSIntlLocale locale) {
        if (locale.hourCycle != null) {
            return List.of(locale.hourCycle);
        }
        RegionPreference preference = regionPreference(locale.locale);
        String language = LanguageTag.split(locale.locale).language;
        List<String> regions = new ArrayList<>();
        if (preference.regionOverride != null) {
            regions.add(preference.regionOverride);
        }
        regions.add(preference.region);
        for (String region : regions) {
            List<String> found = hourCyclesFor(language + "_" + region);
            if (found != null) {
                return new ArrayList<>(found);
            }
            found = hourCyclesFor(region);
            if (found != null) {
                return new ArrayList<>(found);
            }
        }
        List<String> fallback = hourCyclesFor("001");
        return fallback != null ? new ArrayList<>(fallback) : List.of("h23");
    }

    static List<Object> numberingSystems(JSIntlLocale locale) {
        if (locale.numberingSystem != null) {
            return List.of(locale.numberingSystem);
        }
        NumberingSystem system = NumberingSystem.getInstance(dataLocale(locale.locale));
        return List.of(system.getName());
    }

    static List<Object> timeZones(JSIntlLocale locale) {
        LanguageTag.Parsed parsed = LanguageTag.split(locale.locale);
        if (parsed.region == null) {
            return null;
        }
        Set<String> ids = TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION,
                parsed.region.toUpperCase(Locale.ROOT), null);
        Set<String> result = new LinkedHashSet<>();
        for (String id : ids) {
            result.add(IcuCompatibility.primaryTimeZoneId(id));
        }
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return new ArrayList<>(sorted);
    }

    static String direction(JSIntlLocale locale) {
        LanguageTag.Parsed maximal = LanguageTag.split(
                likelySubtags(LanguageTag.baseName(locale.locale), true));
        String script = maximal.script;
        if (script == null) {
            return "ltr";
        }
        int code = com.ibm.icu.lang.UScript.getCodeFromName(script);
        return code >= 0 && com.ibm.icu.lang.UScript.isRightToLeft(code) ? "rtl" : "ltr";
    }

    /** ISO weekday (1 = Monday ... 7 = Sunday) of an ICU Calendar day constant (1 = Sunday ... 7 = Saturday). */
    private static int isoDay(int icu) {
        return icu == Calendar.SUNDAY ? 7 : icu - 1;
    }

    static JSObject weekInfo(Realm realm, JSIntlLocale locale) {
        RegionPreference preference = regionPreference(locale.locale);
        Calendar.WeekData week = Calendar.getWeekDataForRegion(
                preference.regionOverride != null ? preference.regionOverride : preference.region);
        int firstDay = isoDay(week.firstDayOfWeek);
        if (locale.firstDayOfWeek != null) {
            switch (locale.firstDayOfWeek) {
                case "mon": firstDay = 1; break;
                case "tue": firstDay = 2; break;
                case "wed": firstDay = 3; break;
                case "thu": firstDay = 4; break;
                case "fri": firstDay = 5; break;
                case "sat": firstDay = 6; break;
                case "sun": firstDay = 7; break;
                default: break;
            }
        }
        List<Double> weekend = new ArrayList<>();
        int day = isoDay(week.weekendOnset);
        int end = isoDay(week.weekendCease);
        for (int i = 0; i < 7; i++) {
            weekend.add((double) day);
            if (day == end) {
                break;
            }
            day = day % 7 + 1;
        }
        Collections.sort(weekend);
        List<Object> values = new ArrayList<>(weekend.size());
        values.addAll(weekend);
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("firstDay", (double) firstDay);
        object.createDataPropertyOrThrow("weekend", Dsl.jsArray(realm, values));
        return object;
    }
}
