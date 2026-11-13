/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.NumberingSystem;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.GregorianCalendar;
import com.ibm.icu.util.ULocale;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Locale data and pattern generation for DateTimeFormat (ICU; runs only once a DateTimeFormat is created). */
final class DtfData {
    private DtfData() {
    }

    interface PatternLetterSink {
        void accept(int index, char c);
    }

    static ULocale icuLocale(String dataLocale, String calendar, String nu) {
        return ULocale.forLanguageTag(dataLocale + "-u-ca-" + calendar + "-nu-" + nu);
    }

    /** Immutable per-locale defaults (default calendar, numbering system, hour cycles); bounded, thread-safe. */
    private static final class LocaleDefaults {
        final String calendar;
        final String nu;
        final String[] hourCycles;

        LocaleDefaults(String calendar, String nu, String[] hourCycles) {
            this.calendar = calendar;
            this.nu = nu;
            this.hourCycles = hourCycles;
        }
    }

    private static final ConcurrentHashMap<String, LocaleDefaults> DEFAULTS_CACHE = new ConcurrentHashMap<>();

    private static LocaleDefaults defaults(String dataLocale) {
        LocaleDefaults cached = DEFAULTS_CACHE.get(dataLocale);
        if (cached != null) {
            return cached;
        }
        LocaleDefaults computed = new LocaleDefaults(computeDefaultCalendar(dataLocale),
                computeDefaultNumberingSystem(dataLocale), computeHourCycles(dataLocale));
        if (DEFAULTS_CACHE.size() >= 512) {
            DEFAULTS_CACHE.clear();
        }
        DEFAULTS_CACHE.put(dataLocale, computed);
        return computed;
    }

    static String defaultCalendar(String dataLocale) {
        return defaults(dataLocale).calendar;
    }

    static String defaultNumberingSystem(String dataLocale) {
        return defaults(dataLocale).nu;
    }

    /** (default hc, hc for hour12 = true, hc for hour12 = false) of a data locale. */
    static String[] hourCycles(String dataLocale) {
        return defaults(dataLocale).hourCycles;
    }

    private static String computeDefaultCalendar(String dataLocale) {
        String[] values;
        try {
            values = Calendar.getKeywordValuesForLocale("calendar", ULocale.forLanguageTag(dataLocale), true);
        } catch (Exception invalid) {
            return "gregory";
        }
        String ca = values.length == 0 ? null : LocaleInfo.calendarToBcp47(values[0]);
        if (ca == null) {
            ca = "gregory";
        }
        return SupportedValues.isSupportedCalendar(ca) ? ca : "gregory";
    }

    private static String computeDefaultNumberingSystem(String dataLocale) {
        try {
            NumberingSystem system = NumberingSystem.getInstance(ULocale.forLanguageTag(dataLocale));
            return !system.isAlgorithmic() && SupportedValues.isSupportedNumberingSystem(system.getName())
                    ? system.getName() : "latn";
        } catch (Exception invalid) {
            return "latn";
        }
    }

    private static String[] computeHourCycles(String dataLocale) {
        DateTimePatternGenerator generator = DateTimePatternGenerator.getInstance(
                ULocale.forLanguageTag(dataLocale));
        String def;
        switch (generator.getDefaultHourCycle()) {
            case HOUR_CYCLE_11:
                def = "h11";
                break;
            case HOUR_CYCLE_12:
                def = "h12";
                break;
            case HOUR_CYCLE_24:
                def = "h24";
                break;
            default:
                def = "h23";
                break;
        }
        String hc12;
        if ("h11".equals(def) || "h12".equals(def)) {
            hc12 = def;
        } else {
            Character hourChar = hourCharOf(generator.getBestPattern("h"));
            hc12 = hourChar != null && hourChar == 'K' ? "h11" : "h12";
        }
        String hc24 = "h24".equals(def) ? "h24" : "h23";
        return new String[]{def, hc12, hc24};
    }

    // ------------------------------------------------------------------ patterns

    /** Iterates the pattern letters outside quoted literals: (index, char). */
    private static void forEachPatternLetter(String pattern, PatternLetterSink sink) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                sink.accept(i, c);
            }
        }
    }

    static Character hourCharOf(String pattern) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && (c == 'h' || c == 'H' || c == 'k' || c == 'K')) {
                return c;
            }
        }
        return null;
    }

    private static char hcChar(String hc) {
        switch (hc) {
            case "h11":
                return 'K';
            case "h12":
                return 'h';
            case "h24":
                return 'k';
            default:
                return 'H';
        }
    }

    /** Replaces the hour letters of {@code pattern} by the letter of {@code hc} (same 12/24-hour family assumed). */
    static String replaceHourChars(String pattern, String hc) {
        char target = hcChar(hc);
        StringBuilder builder = new StringBuilder(pattern);
        forEachPatternLetter(pattern, (i, c) -> {
            if (c == 'h' || c == 'H' || c == 'k' || c == 'K') {
                builder.setCharAt(i, target);
            }
        });
        return builder.toString();
    }

    private static boolean is12(char c) {
        return c == 'h' || c == 'K';
    }

    /** Makes a pattern use hour cycle {@code hc}: replaces hour letters, regenerating the pattern if the family changes. */
    static String applyHourCycle(DateTimePatternGenerator generator, String pattern, String hc) {
        if (hc == null) {
            return pattern;
        }
        Character current = hourCharOf(pattern);
        if (current == null) {
            return pattern;
        }
        char want = hcChar(hc);
        if (is12(current) == is12(want)) {
            return replaceHourChars(pattern, hc);
        }
        StringBuilder skeleton = new StringBuilder();
        for (char c : generator.getSkeleton(pattern).toCharArray()) {
            switch (c) {
                case 'a':
                case 'b':
                case 'B':
                    break;
                case 'h':
                case 'H':
                case 'k':
                case 'K':
                    skeleton.append(is12(want) ? 'h' : 'H');
                    break;
                default:
                    skeleton.append(c);
                    break;
            }
        }
        return replaceHourChars(generator.getBestPattern(skeleton.toString(),
                DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH), hc);
    }

    /** Skeleton for a component record (hour letter chosen from {@code hc}). */
    static String skeletonOf(Map<String, String> comps, String hc) {
        StringBuilder builder = new StringBuilder();
        String era = comps.get("era");
        if (era != null) {
            switch (era) {
                case "narrow":
                    repeat(builder, 'G', 5);
                    break;
                case "short":
                    repeat(builder, 'G', 1);
                    break;
                case "long":
                    repeat(builder, 'G', 4);
                    break;
                default:
                    break;
            }
        }
        String year = comps.get("year");
        if (year != null) {
            if ("2-digit".equals(year)) {
                repeat(builder, 'y', 2);
            } else if ("numeric".equals(year)) {
                repeat(builder, 'y', 1);
            }
        }
        String month = comps.get("month");
        if (month != null) {
            switch (month) {
                case "numeric":
                    repeat(builder, 'M', 1);
                    break;
                case "2-digit":
                    repeat(builder, 'M', 2);
                    break;
                case "short":
                    repeat(builder, 'M', 3);
                    break;
                case "long":
                    repeat(builder, 'M', 4);
                    break;
                case "narrow":
                    repeat(builder, 'M', 5);
                    break;
                default:
                    break;
            }
        }
        String weekday = comps.get("weekday");
        if (weekday != null) {
            switch (weekday) {
                case "narrow":
                    repeat(builder, 'E', 5);
                    break;
                case "short":
                    repeat(builder, 'E', 3);
                    break;
                case "long":
                    repeat(builder, 'E', 4);
                    break;
                default:
                    break;
            }
        }
        String day = comps.get("day");
        if (day != null) {
            if ("2-digit".equals(day)) {
                repeat(builder, 'd', 2);
            } else if ("numeric".equals(day)) {
                repeat(builder, 'd', 1);
            }
        }
        String dayPeriod = comps.get("dayPeriod");
        if (dayPeriod != null) {
            switch (dayPeriod) {
                case "narrow":
                    repeat(builder, 'B', 5);
                    break;
                case "short":
                    repeat(builder, 'B', 1);
                    break;
                case "long":
                    repeat(builder, 'B', 4);
                    break;
                default:
                    break;
            }
        }
        char hourChar = hc == null ? 'j' : ("h11".equals(hc) || "h12".equals(hc)) ? 'h' : 'H';
        String hour = comps.get("hour");
        if (hour != null) {
            if ("2-digit".equals(hour)) {
                repeat(builder, hourChar, 2);
            } else if ("numeric".equals(hour)) {
                repeat(builder, hourChar, 1);
            }
        }
        String minute = comps.get("minute");
        if (minute != null) {
            if ("2-digit".equals(minute)) {
                repeat(builder, 'm', 2);
            } else if ("numeric".equals(minute)) {
                repeat(builder, 'm', 1);
            }
        }
        String second = comps.get("second");
        if (second != null) {
            if ("2-digit".equals(second)) {
                repeat(builder, 's', 2);
            } else if ("numeric".equals(second)) {
                repeat(builder, 's', 1);
            }
        }
        String fractional = comps.get("fractionalSecondDigits");
        if (fractional != null) {
            repeat(builder, 'S', Integer.parseInt(fractional));
        }
        String timeZoneName = comps.get("timeZoneName");
        if (timeZoneName != null) {
            switch (timeZoneName) {
                case "short":
                    repeat(builder, 'z', 1);
                    break;
                case "long":
                    repeat(builder, 'z', 4);
                    break;
                case "shortOffset":
                    repeat(builder, 'O', 1);
                    break;
                case "longOffset":
                    repeat(builder, 'O', 4);
                    break;
                case "shortGeneric":
                    repeat(builder, 'v', 1);
                    break;
                case "longGeneric":
                    repeat(builder, 'v', 4);
                    break;
                default:
                    break;
            }
        }
        return builder.toString();
    }

    private static void repeat(StringBuilder builder, char c, int count) {
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
    }

    /** The component values a pattern displays (Table 15 order). */
    static LinkedHashMap<String, String> fieldsOfPattern(String pattern) {
        Map<String, String> found = new HashMap<>();
        int i = 0;
        boolean quoted = false;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                i++;
                continue;
            }
            if (quoted || !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                i++;
                continue;
            }
            int n = 1;
            while (i + n < pattern.length() && pattern.charAt(i + n) == c) {
                n++;
            }
            i += n;
            String[] field = fieldOf(c, n);
            if (field != null) {
                found.putIfAbsent(field[0], field[1]);
            }
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String key : DtfTables.ORDER) {
            String value = found.get(key);
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String textWidth(int n) {
        if (n == 4) {
            return "long";
        }
        if (n == 5) {
            return "narrow";
        }
        return "short";
    }

    private static String[] fieldOf(char c, int n) {
        switch (c) {
            case 'G':
                return new String[]{"era", textWidth(n)};
            case 'y':
            case 'Y':
            case 'u':
            case 'U':
            case 'r':
                return new String[]{"year", n == 2 && c == 'y' ? "2-digit" : "numeric"};
            case 'M':
            case 'L': {
                String value;
                switch (n) {
                    case 1:
                        value = "numeric";
                        break;
                    case 2:
                        value = "2-digit";
                        break;
                    case 3:
                        value = "short";
                        break;
                    case 4:
                        value = "long";
                        break;
                    default:
                        value = "narrow";
                        break;
                }
                return new String[]{"month", value};
            }
            case 'd':
                return new String[]{"day", n == 2 ? "2-digit" : "numeric"};
            case 'E':
                return new String[]{"weekday", n == 6 ? "short" : textWidth(n)};
            case 'c':
            case 'e':
                return n >= 3 ? new String[]{"weekday", n == 6 ? "short" : textWidth(n)} : null;
            case 'B':
                return new String[]{"dayPeriod", textWidth(n)};
            case 'h':
            case 'H':
            case 'k':
            case 'K':
                return new String[]{"hour", n == 2 ? "2-digit" : "numeric"};
            case 'm':
                return new String[]{"minute", n == 2 ? "2-digit" : "numeric"};
            case 's':
                return new String[]{"second", n == 2 ? "2-digit" : "numeric"};
            case 'S':
                return new String[]{"fractionalSecondDigits", Integer.toString(Math.min(n, 3))};
            case 'z':
                return new String[]{"timeZoneName", n == 4 ? "long" : "short"};
            case 'O':
                return new String[]{"timeZoneName", n == 4 ? "longOffset" : "shortOffset"};
            case 'v':
                return new String[]{"timeZoneName", n == 4 ? "longGeneric" : "shortGeneric"};
            case 'V':
            case 'Z':
            case 'X':
            case 'x':
                return new String[]{"timeZoneName", "short"};
            default:
                return null;
        }
    }

    /** BasicFormatMatcher / BestFitFormatMatcher (both via ICU's pattern generator) for a component record. */
    static DtfFormat formatFor(DateTimePatternGenerator generator, Map<String, String> comps, String hc) {
        String skeleton = skeletonOf(comps, hc);
        String pattern = generator.getBestPattern(skeleton, DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH);
        if (hc != null) {
            pattern = replaceHourChars(pattern, hc);
        }
        LinkedHashMap<String, String> fields = fieldsOfPattern(pattern);
        if (!comps.containsKey("dayPeriod")) {
            fields.remove("dayPeriod");
        }
        return new DtfFormat(fields, pattern, skeleton);
    }

    private static int icuStyle(String style) {
        switch (style) {
            case "full":
                return DateFormat.FULL;
            case "long":
                return DateFormat.LONG;
            case "medium":
                return DateFormat.MEDIUM;
            default:
                return DateFormat.SHORT;
        }
    }

    /** DateTimeStyleFormat */
    static DtfFormat styleFormat(DateTimePatternGenerator generator, ULocale locale, String dateStyle,
                                 String timeStyle, String hc) {
        String datePattern = dateStyle != null
                ? ((SimpleDateFormat) DateFormat.getDateInstance(icuStyle(dateStyle), locale)).toPattern()
                : null;
        String timePattern = timeStyle != null
                ? applyHourCycle(generator,
                        ((SimpleDateFormat) DateFormat.getTimeInstance(icuStyle(timeStyle), locale)).toPattern(), hc)
                : null;
        String pattern;
        if (datePattern != null && timePattern != null) {
            String glue = Calendar.getDateTimePattern(Calendar.getInstance(locale), locale, icuStyle(dateStyle));
            pattern = glue.replace("{1}", datePattern).replace("{0}", timePattern);
        } else if (datePattern != null) {
            pattern = datePattern;
        } else {
            pattern = timePattern;
        }
        String skeleton = generator.getSkeleton(pattern);
        return new DtfFormat(fieldsOfPattern(pattern), pattern, skeleton);
    }

    /** A calendar for formatting: the locale's calendar type, proleptic Gregorian where applicable. */
    static Calendar newCalendar(ULocale locale, com.ibm.icu.util.TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, locale);
        if (calendar instanceof GregorianCalendar) {
            ((GregorianCalendar) calendar).setGregorianChange(new java.util.Date(Long.MIN_VALUE));
        }
        calendar.setLenient(true);
        return calendar;
    }
}
