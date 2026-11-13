/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CreateDateTimeFormat and the time zone / Temporal format-record operations it relies on. */
final class DtfCreate {
    private DtfCreate() {
    }

    /** Java-style three-letter ids that ICU knows but that are not IANA names. */
    private static final List<String> NON_IANA = List.of(
            "ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST", "CTT", "EAT", "ECT", "IET", "IST",
            "JST", "MIT", "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST");

    private static final List<String> YEAR_MONTH = List.of("year", "month");
    private static final List<String> MONTH_DAY = List.of("month", "day");
    private static final List<String> ALL_FIELDS;
    private static final List<String> ANY_PRESENT = List.of("weekday", "year", "month", "day", "dayPeriod", "hour",
            "minute", "second", "fractionalSecondDigits");

    static {
        List<String> all = new ArrayList<>(DtfTables.DATE_FIELDS);
        all.addAll(DtfTables.TIME_FIELDS);
        ALL_FIELDS = List.copyOf(all);
    }

    /** CreateDateTimeFormat. */
    static JSIntlDateTimeFormat create(Realm realm, JSObject newTarget, Object locales, Object optionsArg,
                                       String required, String defaults) {
        return create(realm, newTarget, locales, optionsArg, required, defaults, null);
    }

    /** CreateDateTimeFormat; {@code toLocaleStringTimeZone} is the time zone of a ZonedDateTime being formatted. */
    static JSIntlDateTimeFormat create(Realm realm, JSObject newTarget, Object locales, Object optionsArg,
                                       String required, String defaults, String toLocaleStringTimeZone) {
        JSIntlDateTimeFormat dtf = new JSIntlDateTimeFormat(
                Dsl.protoFromCtor(newTarget, "%Intl.DateTimeFormat.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.coerceToObject(optionsArg);
        Opt.localeMatcher(options);
        String calendar = typeOption(options, "calendar");
        String nu = typeOption(options, "numberingSystem");
        Boolean hour12 = (Boolean) Opt.bool(options, "hour12", null);
        String hourCycle = (String) Opt.string(options, "hourCycle", DtfTables.HOUR_CYCLES, null);
        if (hour12 != null) {
            hourCycle = null;
        }
        try {
            resolveLocale(realm, dtf, requested, calendar, nu, hourCycle, hour12);
        } catch (JSException e) {
            throw e;
        } catch (RuntimeException invalid) {
            if (invalid instanceof TerminationException) {
                throw invalid;
            }
            throw Dsl.rangeErr("Incorrect locale information provided");
        }
        dtf.timeZone = readTimeZone(options, toLocaleStringTimeZone);
        readComponents(options, dtf);
        String matcher = Opt.str(options, "formatMatcher", DtfTables.MATCHERS, "best fit");
        dtf.formatMatcher = matcher;
        String dateStyle = (String) Opt.string(options, "dateStyle", DtfTables.STYLES, null);
        dtf.dateStyle = dateStyle;
        String timeStyle = (String) Opt.string(options, "timeStyle", DtfTables.STYLES, null);
        dtf.timeStyle = timeStyle;
        if (dateStyle != null || timeStyle != null) {
            if (hasExplicitComponents(dtf)) {
                throw Dsl.typeErr("Can't set option " + explicitName(dtf)
                        + " when dateStyle or timeStyle is used");
            }
            if ("date".equals(required) && timeStyle != null) {
                throw Dsl.typeErr("Invalid option : timeStyle");
            }
            if ("time".equals(required) && dateStyle != null) {
                throw Dsl.typeErr("Invalid option : dateStyle");
            }
        }
        // ZonedDateTime.prototype.toLocaleString without any field or style option also shows the time zone
        if (toLocaleStringTimeZone != null && dateStyle == null && timeStyle == null
                && dtf.formatOptions.isEmpty()) {
            Map<String, String> withZone = new LinkedHashMap<>();
            withZone.put("timeZoneName", "short");
            dtf.formatOptions = withZone;
        }
        try {
            DtfIcu icu = DtfIcu.of(dtf);
            if (dateStyle != null || timeStyle != null) {
                dtf.format = DtfData.styleFormat(icu.gen, icu.uloc, dateStyle, timeStyle, dtf.hc);
            } else {
                dtf.format = DtfData.formatFor(icu.gen,
                        getDateTimeFormat(dtf.formatOptions, required, defaults, "all"), dtf.hc);
            }
        } catch (JSException e) {
            throw e;
        } catch (RuntimeException invalid) {
            if (invalid instanceof TerminationException) {
                throw invalid;
            }
            throw Dsl.rangeErr("Unable to create the date-time format");
        }
        dtf.hourCycle = dtf.format.hasHour() ? dtf.hc : null;
        return dtf;
    }

    private static String typeOption(JSObject options, String name) {
        Object value = Opt.string(options, name, null, null);
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        if (!Opt.isUnicodeType(text)) {
            throw Dsl.rangeErr("Invalid " + name + " : " + text);
        }
        return text;
    }

    private static void resolveLocale(Realm realm, JSIntlDateTimeFormat dtf, List<String> requested,
                                      String calendar, String nu, String hourCycle, Boolean hour12) {
        Map<String, String> opt = new HashMap<>();
        opt.put("ca", calendar);
        opt.put("hc", hourCycle);
        opt.put("nu", nu);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of("ca", "hc", "nu"), opt,
                (locale, key) -> keyData(locale, key));
        String locale = resolved.locale;
        String resolvedHourCycle = resolved.values.get("hc");
        if (hour12 != null) {
            // options.[[hc]] is null: ResolveLocale drops a requested "hc" keyword
            resolvedHourCycle = null;
            if (LanguageTag.unicodeKeyword(locale, "hc") != null) {
                LanguageTag.Components components = LanguageTag.unicodeExtensionOf(locale);
                List<LanguageTag.Keyword> keywords = new ArrayList<>();
                for (LanguageTag.Keyword keyword : components.keywords) {
                    if (!keyword.key.equals("hc")) {
                        keywords.add(keyword);
                    }
                }
                locale = LanguageTag.insertUnicodeExtensionAndCanonicalize(
                        LanguageTag.removeUnicodeExtension(locale), components.attributes, keywords);
            }
        }
        dtf.locale = locale;
        dtf.dataLocale = resolved.dataLocale;
        dtf.calendar = resolved.values.get("ca") == null ? "gregory" : resolved.values.get("ca");
        dtf.numberingSystem = resolved.values.get("nu") == null ? "latn" : resolved.values.get("nu");
        String[] cycles = DtfData.hourCycles(resolved.dataLocale);
        if (hour12 == null) {
            dtf.hc = resolvedHourCycle != null ? resolvedHourCycle : cycles[0];
        } else if (hour12) {
            dtf.hc = cycles[1];
        } else {
            dtf.hc = cycles[2];
        }
    }

    private static List<String> keyData(String locale, String key) {
        List<String> values = new ArrayList<>();
        switch (key) {
            case "ca":
                values.add(DtfData.defaultCalendar(locale));
                values.addAll(SupportedValues.CALENDARS);
                return values;
            case "nu":
                values.add(DtfData.defaultNumberingSystem(locale));
                values.addAll(SupportedValues.NUMBERING_SYSTEMS.get());
                return values;
            case "hc":
                values.add(null);
                values.add("h11");
                values.add("h12");
                values.add("h23");
                values.add("h24");
                return values;
            default:
                values.add(null);
                return values;
        }
    }

    private static void readComponents(JSObject options, JSIntlDateTimeFormat dtf) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (DtfTables.Component component : DtfTables.COMPONENTS) {
            String value;
            if (component.values == null) {
                Integer number = Opt.number(options, component.name, 1, 3, null);
                value = number == null ? null : number.toString();
            } else {
                value = (String) Opt.string(options, component.name, component.values, null);
            }
            if (value != null) {
                out.put(component.name, value);
            }
        }
        dtf.formatOptions = out;
    }

    private static boolean hasExplicitComponents(JSIntlDateTimeFormat dtf) {
        return !dtf.formatOptions.isEmpty();
    }

    private static String explicitName(JSIntlDateTimeFormat dtf) {
        return dtf.formatOptions.keySet().iterator().next();
    }

    // ------------------------------------------------------------------ time zones

    static String readTimeZone(JSObject options) {
        return readTimeZone(options, null);
    }

    static String readTimeZone(JSObject options, String toLocaleStringTimeZone) {
        Object value = options.get("timeZone", options);
        String text;
        if (value == Undefined.INSTANCE) {
            if (toLocaleStringTimeZone != null) {
                text = toLocaleStringTimeZone;
            } else {
                return systemTimeZone();
            }
        } else {
            if (toLocaleStringTimeZone != null) {
                throw Dsl.typeErr("The timeZone option cannot be used with ZonedDateTime.prototype.toLocaleString");
            }
            text = Ops.toString(value);
        }
        if (!text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-')) {
            String offset = parseOffset(text);
            if (offset == null) {
                throw Dsl.rangeErr("Invalid time zone specified: " + text);
            }
            return offset;
        }
        if (NON_IANA.contains(text.toUpperCase(Locale.ROOT))) {
            throw Dsl.rangeErr("Invalid time zone specified: " + text);
        }
        TimeZones.Record record = TimeZones.find(text);
        if (record == null) {
            throw Dsl.rangeErr("Invalid time zone specified: " + text);
        }
        return record.identifier;
    }

    /** UTCOffset with at most hours and minutes ("+HH", "+HHMM", "+HH:MM") -> FormatOffsetTimeZoneIdentifier. */
    static String parseOffset(String value) {
        int sign = value.charAt(0) == '-' ? -1 : 1;
        int hours = twoDigits(value, 1);
        if (hours < 0 || hours > 23) {
            return null;
        }
        int minutes;
        switch (value.length()) {
            case 3:
                minutes = 0;
                break;
            case 5:
                minutes = twoDigits(value, 3);
                break;
            case 6:
                minutes = value.charAt(3) == ':' ? twoDigits(value, 4) : -1;
                break;
            default:
                minutes = -1;
                break;
        }
        if (minutes < 0 || minutes > 59) {
            return null;
        }
        return formatOffset(sign * (hours * 60 + minutes));
    }

    private static int twoDigits(String value, int index) {
        if (index + 1 < value.length() && LanguageTag.isDigit(value.charAt(index))
                && LanguageTag.isDigit(value.charAt(index + 1))) {
            return (value.charAt(index) - '0') * 10 + (value.charAt(index + 1) - '0');
        }
        return -1;
    }

    static String formatOffset(int minutes) {
        char sign = minutes < 0 ? '-' : '+';
        int absolute = Math.abs(minutes);
        String hours = Integer.toString(absolute / 60);
        String mins = Integer.toString(absolute % 60);
        if (hours.length() < 2) {
            hours = "0" + hours;
        }
        if (mins.length() < 2) {
            mins = "0" + mins;
        }
        return sign + hours + ":" + mins;
    }

    /** SystemTimeZoneIdentifier: the configured (or host) zone as a primary identifier or offset identifier. */
    static String systemTimeZone() {
        Agent agent = Agent.current.get();
        ZoneId zone = agent != null ? agent.config.timeZone : null;
        if (zone == null) {
            zone = hostZone();
        }
        if (zone instanceof ZoneOffset) {
            return offsetId((ZoneOffset) zone);
        }
        TimeZones.Record found = TimeZones.find(zone.getId());
        if (found != null) {
            return found.primary;
        }
        ZoneId normalized = null;
        try {
            normalized = zone.normalized();
        } catch (Exception invalid) {
            normalized = null;
        }
        if (normalized instanceof ZoneOffset) {
            return offsetId((ZoneOffset) normalized);
        }
        return "UTC";
    }

    private static ZoneId hostZone() {
        String configured = System.getProperty("neonjs.timezone");
        if (configured != null) {
            try {
                return ZoneId.of(configured);
            } catch (Exception invalid) {
                return ZoneId.systemDefault();
            }
        }
        return ZoneId.systemDefault();
    }

    private static String offsetId(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "UTC" : formatOffset(offset.getTotalSeconds() / 60);
    }

    // ------------------------------------------------------------------ Temporal format records

    /** GetDateTimeFormat: the component record for {@code required}/{@code defaults}, or null (no relevant fields). */
    static Map<String, String> getDateTimeFormat(Map<String, String> opts, String required, String defaults,
                                                 String inherit) {
        List<String> requiredOptions;
        switch (required) {
            case "date":
                requiredOptions = DtfTables.DATE_FIELDS;
                break;
            case "time":
                requiredOptions = DtfTables.TIME_FIELDS;
                break;
            case "year-month":
                requiredOptions = YEAR_MONTH;
                break;
            case "month-day":
                requiredOptions = MONTH_DAY;
                break;
            default:
                requiredOptions = ALL_FIELDS;
                break;
        }
        List<String> defaultOptions;
        switch (defaults) {
            case "date":
                defaultOptions = List.of("year", "month", "day");
                break;
            case "time":
                defaultOptions = List.of("hour", "minute", "second");
                break;
            case "year-month":
                defaultOptions = YEAR_MONTH;
                break;
            case "month-day":
                defaultOptions = MONTH_DAY;
                break;
            default:
                defaultOptions = List.of("year", "month", "day", "hour", "minute", "second");
                break;
        }
        LinkedHashMap<String, String> formatOptions = new LinkedHashMap<>();
        if ("all".equals(inherit)) {
            formatOptions.putAll(opts);
        } else if ("date".equals(required) || "year-month".equals(required) || "any".equals(required)) {
            String era = opts.get("era");
            if (era != null) {
                formatOptions.put("era", era);
            }
        }
        boolean anyPresent = false;
        for (String property : ANY_PRESENT) {
            if (opts.get(property) != null) {
                anyPresent = true;
                break;
            }
        }
        boolean needDefaults = true;
        for (String property : requiredOptions) {
            String value = opts.get(property);
            if (value == null) {
                continue;
            }
            formatOptions.put(property, value);
            needDefaults = false;
        }
        if (needDefaults) {
            if (anyPresent && "relevant".equals(inherit)) {
                return null;
            }
            for (String property : defaultOptions) {
                formatOptions.put(property, "numeric");
            }
        }
        return formatOptions;
    }

    /** The format record used for a Temporal value of {@code kind} (null: no overlapping fields, a TypeError). */
    static DtfFormat temporalFormat(JSIntlDateTimeFormat dtf, String kind) {
        if (dtf.temporalFormats.containsKey(kind)) {
            return dtf.temporalFormats.get(kind);
        }
        DtfFormat result;
        try {
            result = computeTemporalFormat(dtf, kind);
        } catch (JSException e) {
            throw e;
        } catch (RuntimeException invalid) {
            if (invalid instanceof TerminationException) {
                throw invalid;
            }
            throw Dsl.rangeErr("Unable to create the date-time format");
        }
        dtf.temporalFormats.put(kind, result);
        return result;
    }

    private static DtfFormat computeTemporalFormat(JSIntlDateTimeFormat dtf, String kind) {
        DtfIcu icu = DtfIcu.of(dtf);
        if (dtf.dateStyle != null || dtf.timeStyle != null) {
            if ("instant".equals(kind)) {
                return dtf.format;
            }
            List<String> allowed;
            switch (kind) {
                case "date":
                    allowed = List.of("weekday", "era", "year", "month", "day");
                    break;
                case "year-month":
                    allowed = List.of("era", "year", "month");
                    break;
                case "month-day":
                    allowed = MONTH_DAY;
                    break;
                case "time":
                    allowed = DtfTables.TIME_FIELDS;
                    break;
                default: {
                    List<String> all = new ArrayList<>(List.of("weekday", "era", "year", "month", "day"));
                    all.addAll(DtfTables.TIME_FIELDS);
                    allowed = all;
                    break;
                }
            }
            LinkedHashMap<String, String> adjusted = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : dtf.format.fields.entrySet()) {
                if (allowed.contains(entry.getKey())) {
                    adjusted.put(entry.getKey(), entry.getValue());
                }
            }
            if (adjusted.isEmpty()) {
                return null;
            }
            if (adjusted.size() == dtf.format.fields.size()) {
                return dtf.format;
            }
            return DtfData.formatFor(icu.gen, adjusted, dtf.hc);
        }
        Map<String, String> comps;
        switch (kind) {
            case "date":
                comps = getDateTimeFormat(dtf.formatOptions, "date", "date", "relevant");
                break;
            case "year-month":
                comps = getDateTimeFormat(dtf.formatOptions, "year-month", "year-month", "relevant");
                break;
            case "month-day":
                comps = getDateTimeFormat(dtf.formatOptions, "month-day", "month-day", "relevant");
                break;
            case "time":
                comps = getDateTimeFormat(dtf.formatOptions, "time", "time", "relevant");
                break;
            case "datetime":
                comps = getDateTimeFormat(dtf.formatOptions, "any", "all", "relevant");
                break;
            default:
                comps = getDateTimeFormat(dtf.formatOptions, "any", "all", "all");
                break;
        }
        return comps == null ? null : DtfData.formatFor(icu.gen, comps, dtf.hc);
    }
}
