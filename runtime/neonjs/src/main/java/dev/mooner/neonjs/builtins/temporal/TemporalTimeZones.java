/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

import dev.mooner.neonjs.builtins.DateTime;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.concurrent.LazyValue;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Time zone lookup, offset/disambiguation and transition helpers (Kotlin {@code TZ} object). */
final class TZ {
    private static final long NS_PER_S = 1_000_000_000L;
    private static final BigInteger BI_NS_PER_S = BigInteger.valueOf(NS_PER_S);

    /** IANA names the JDK's tz database lacks, with the zone whose rules they use (and primary identifier). */
    private static final Map<String, String> EXTRA_LINKS;

    static {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("EST", "America/Panama");
        links.put("MST", "America/Phoenix");
        links.put("HST", "Pacific/Honolulu");
        links.put("ROC", "Asia/Taipei");
        links.put("GMT+0", "Etc/GMT");
        links.put("GMT-0", "Etc/GMT");
        EXTRA_LINKS = Collections.unmodifiableMap(links);
    }

    /** Links whose primary identifier is "UTC" (ECMA-402: the targets Etc/UTC, Etc/GMT and GMT become "UTC"). */
    private static final Set<String> UTC_FAMILY = Set.of(
            "Etc/UTC", "Etc/GMT", "GMT", "Etc/UCT", "Etc/Universal", "Etc/Zulu", "UCT", "Universal", "Zulu", "Etc/GMT+0",
            "Etc/GMT-0", "Etc/GMT0", "Etc/Greenwich", "GMT+0", "GMT-0", "GMT0", "Greenwich");

    /** ASCII-lowercase name -> canonical spelling, for every available named time zone identifier. */
    private static final LazyValue<Map<String, String>> NAMES = new LazyValue<>(() -> {
        Map<String, String> m = new HashMap<>(1024);
        for (String id : ZoneId.getAvailableZoneIds()) {
            // SystemV/* are JDK-only legacy names, not IANA identifiers
            if (id.startsWith("SystemV/")) {
                continue;
            }
            m.put(asciiLower(id), id);
        }
        for (String id : EXTRA_LINKS.keySet()) {
            m.put(asciiLower(id), id);
        }
        m.put("utc", "UTC");
        return m;
    });

    private static final ConcurrentHashMap<String, TimeZone> zoneCache = new ConcurrentHashMap<>();

    static final TZ INSTANCE = new TZ();

    private TZ() {
    }

    private static String asciiLower(String s) {
        int i = 0;
        while (i < s.length() && !(s.charAt(i) >= 'A' && s.charAt(i) <= 'Z')) {
            i++;
        }
        if (i == s.length()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c >= 'A' && c <= 'Z') {
                sb.setCharAt(k, (char) (c + 32));
            }
        }
        return sb.toString();
    }

    /** ASCII-case-insensitive match; {@code lower} must already be ASCII-lowercase. */
    private static boolean asciiEqualsIgnoreCase(String s, String lower) {
        if (s.length() != lower.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            if (c != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String take(String s, int count) {
        return s.length() <= count ? s : s.substring(0, count);
    }

    private static JSException tTypeErr(String message) {
        return JSException.typeError(message);
    }

    private static JSException tRangeErr(String message) {
        return JSException.rangeError(message);
    }

    /** GetAvailableNamedTimeZoneIdentifier: the zone for an ASCII-case-insensitive match of {@code name}, or null. */
    static TimeZone namedZone(String name) {
        if (asciiEqualsIgnoreCase(name, "utc")) {
            return TimeZone.UTC;
        }
        if (name.length() > 64) {
            return null;
        }
        String canonical = NAMES.get().get(asciiLower(name));
        if (canonical == null) {
            return null;
        }
        return zoneCache.computeIfAbsent(canonical, id -> {
            String zoneId = EXTRA_LINKS.getOrDefault(id, id);
            ZoneRules rules = ZoneId.of(zoneId).getRules();
            return new TimeZone(id, false, 0, rules);
        });
    }

    /** ParseTemporalTimeZoneString: returns a name (String) or offset minutes (Integer). */
    static Object parseTimeZoneString(String s) {
        Object identifier = TemporalParser.parseTimeZoneIdentifier(s);
        if (identifier != null) {
            return identifier;
        }
        IsoParse r = TemporalParser.parseIsoDateTime(s, TemporalParser.ALL_GOALS);
        String ann = r.tzAnnotation;
        if (ann != null) {
            Object parsed = TemporalParser.parseTimeZoneIdentifier(ann);
            if (parsed == null) {
                throw new NullPointerException();
            }
            return parsed;
        }
        if (r.z) {
            return "UTC";
        }
        String off = r.offset;
        if (off != null) {
            Object parsed = TemporalParser.parseTimeZoneIdentifier(off);
            if (parsed != null) {
                return parsed;
            }
            throw tRangeErr("invalid time zone offset " + off);
        }
        throw tRangeErr("string does not contain a time zone: " + take(s, 60));
    }

    /** ToTemporalTimeZoneIdentifier */
    static TimeZone toTimeZone(Object v) {
        if (v instanceof JSTemporalZonedDateTime) {
            return ((JSTemporalZonedDateTime) v).timeZone;
        }
        if (!(v instanceof CharSequence)) {
            throw tTypeErr("time zone must be a string");
        }
        Object parsed = parseTimeZoneString(v.toString());
        if (parsed instanceof Integer) {
            return TimeZone.ofOffsetMinutes((Integer) parsed);
        }
        String name = (String) parsed;
        TimeZone zone = namedZone(name);
        if (zone == null) {
            throw tRangeErr("unknown time zone " + take(name, 60));
        }
        return zone;
    }

    /** Time zone from a (constructor) identifier string via ParseTimeZoneIdentifier. */
    static TimeZone fromIdentifier(String s) {
        Object parsed = TemporalParser.parseTimeZoneIdentifier(s);
        if (parsed == null) {
            throw tRangeErr("invalid time zone identifier " + take(s, 60));
        }
        if (parsed instanceof Integer) {
            return TimeZone.ofOffsetMinutes((Integer) parsed);
        }
        String name = (String) parsed;
        TimeZone zone = namedZone(name);
        if (zone == null) {
            throw tRangeErr("unknown time zone " + take(name, 60));
        }
        return zone;
    }

    private static Instant instantOf(BigInteger epochNs) {
        BigInteger[] qr = epochNs.divideAndRemainder(BI_NS_PER_S);
        long sec = qr[0].longValue();
        long nano = qr[1].longValue();
        if (nano < 0) {
            nano += NS_PER_S;
            sec -= 1;
        }
        return Instant.ofEpochSecond(sec, nano);
    }

    /** GetOffsetNanosecondsFor */
    static long offsetNs(TimeZone tz, BigInteger epochNs) {
        if (tz.fixedOffsetNs != null) {
            return tz.fixedOffsetNs;
        }
        return tz.rules.getOffset(instantOf(epochNs)).getTotalSeconds() * NS_PER_S;
    }

    /** GetISODateTimeFor */
    static IsoDateTime isoDateTimeFor(TimeZone tz, BigInteger epochNs) {
        long offset = offsetNs(tz, epochNs);
        return TM.isoPartsFromEpoch(offset == 0 ? epochNs : epochNs.add(BigInteger.valueOf(offset)));
    }

    private static LocalDateTime localDateTime(IsoDateTime dt) {
        IsoDate d = dt.date;
        // dates this far out are outside the representable range of epoch nanoseconds in any case
        if (Math.abs(d.year) > 400_000) {
            throw tRangeErr("date-time outside of supported range");
        }
        TimeRec t = dt.time;
        return LocalDateTime.of(d.year, d.month, d.day, t.hour, t.minute, t.second, t.subSecondNanos());
    }

    /** GetPossibleEpochNanoseconds */
    static List<BigInteger> possibleEpochNs(TimeZone tz, IsoDateTime dt) {
        Long fixed = tz.fixedOffsetNs;
        List<BigInteger> result;
        if (tz.isOffset) {
            IsoDateTime balanced = TM.balanceDateTime(dt.date, dt.time, -tz.getOffsetNs());
            TM.checkIsoDaysRange(balanced.date);
            result = List.of(TM.utcEpochNs(balanced));
        } else if (fixed != null) {
            BigInteger utc = TM.utcEpochNs(dt);
            result = List.of(fixed == 0 ? utc : utc.subtract(BigInteger.valueOf(fixed)));
        } else {
            BigInteger utc = TM.utcEpochNs(dt);
            List<ZoneOffset> offsets = tz.rules.getValidOffsets(localDateTime(dt));
            switch (offsets.size()) {
                case 0:
                    result = List.of();
                    break;
                case 1:
                    result = List.of(utc.subtract(BigInteger.valueOf(offsets.get(0).getTotalSeconds() * NS_PER_S)));
                    break;
                default:
                    List<BigInteger> sorted = new ArrayList<>(offsets.size());
                    for (ZoneOffset offset : offsets) {
                        sorted.add(utc.subtract(BigInteger.valueOf(offset.getTotalSeconds() * NS_PER_S)));
                    }
                    Collections.sort(sorted);
                    result = sorted;
                    break;
            }
        }
        for (BigInteger ns : result) {
            if (!TM.isValidEpochNs(ns)) {
                throw tRangeErr("date-time outside of supported range");
            }
        }
        return result;
    }

    /** GetEpochNanosecondsFor */
    static BigInteger epochNsFor(TimeZone tz, IsoDateTime dt, Disambiguation disambiguation) {
        List<BigInteger> possible = possibleEpochNs(tz, dt);
        return disambiguate(possible, tz, dt, disambiguation);
    }

    /** DisambiguatePossibleEpochNanoseconds */
    static BigInteger disambiguate(List<BigInteger> possible, TimeZone tz, IsoDateTime dt,
                                   Disambiguation disambiguation) {
        int n = possible.size();
        if (n == 1) {
            return possible.get(0);
        }
        if (n != 0) {
            switch (disambiguation) {
                case EARLIER:
                case COMPATIBLE:
                    return possible.get(0);
                case LATER:
                    return possible.get(n - 1);
                case REJECT:
                default:
                    throw tRangeErr("ambiguous date-time");
            }
        }
        if (disambiguation == Disambiguation.REJECT) {
            throw tRangeErr("nonexistent date-time");
        }
        // n = 0: a wall-clock time skipped by a positive offset transition
        ZoneOffsetTransition transition = tz.rules != null ? tz.rules.getTransition(localDateTime(dt)) : null;
        if (transition == null) {
            throw tRangeErr("nonexistent date-time");
        }
        long nanoseconds = (transition.getOffsetAfter().getTotalSeconds()
                - transition.getOffsetBefore().getTotalSeconds()) * NS_PER_S;
        if (disambiguation == Disambiguation.EARLIER) {
            TimeRec t = TM.addTime(dt.time, BigInteger.valueOf(-nanoseconds));
            IsoDateTime earlier = new IsoDateTime(TM.addDays(dt.date, t.days), t);
            List<BigInteger> p = possibleEpochNs(tz, earlier);
            if (p.isEmpty()) {
                throw tRangeErr("nonexistent date-time");
            }
            return p.get(0);
        }
        TimeRec t = TM.addTime(dt.time, BigInteger.valueOf(nanoseconds));
        IsoDateTime later = new IsoDateTime(TM.addDays(dt.date, t.days), t);
        List<BigInteger> p = possibleEpochNs(tz, later);
        if (p.isEmpty()) {
            throw tRangeErr("nonexistent date-time");
        }
        return p.get(p.size() - 1);
    }

    /** GetStartOfDay */
    static BigInteger startOfDay(TimeZone tz, IsoDate d) {
        IsoDateTime dt = new IsoDateTime(d, TimeRec.MIDNIGHT);
        List<BigInteger> possible = possibleEpochNs(tz, dt);
        if (!possible.isEmpty()) {
            return possible.get(0);
        }
        // midnight is skipped: the day starts at the first instant after the transition
        ZoneOffsetTransition transition = tz.rules != null ? tz.rules.getTransition(localDateTime(dt)) : null;
        if (transition == null) {
            throw tRangeErr("nonexistent date-time");
        }
        BigInteger ns = BigInteger.valueOf(transition.toEpochSecond()).multiply(BI_NS_PER_S);
        if (!TM.isValidEpochNs(ns)) {
            throw tRangeErr("date-time outside of supported range");
        }
        return ns;
    }

    /** GetNamedTimeZoneNextTransition / GetNamedTimeZonePreviousTransition; null when there is none. */
    static BigInteger transition(TimeZone tz, BigInteger epochNs, boolean next) {
        if (tz.isOffset || tz.fixedOffsetNs != null) {
            return null;
        }
        ZoneRules rules = tz.rules;
        Instant instant = instantOf(epochNs);
        ZoneOffsetTransition t = next ? rules.nextTransition(instant) : rules.previousTransition(instant);
        if (t == null) {
            return null;
        }
        BigInteger ns = BigInteger.valueOf(t.toEpochSecond()).multiply(BI_NS_PER_S);
        return TM.isValidEpochNs(ns) ? ns : null;
    }

    /** The primary identifier of a named zone (offset zones are compared by offset). */
    static String primaryId(TimeZone tz) {
        String cached = tz.primary;
        if (cached != null) {
            return cached;
        }
        String p;
        if (tz.isOffset || tz == TimeZone.UTC) {
            p = tz.id;
        } else {
            String fromProvider;
            try {
                TemporalCalendarProvider provider = TemporalProviders.provider;
                fromProvider = provider == null ? null : provider.primaryTimeZoneId(tz.id);
            } catch (RuntimeException ignored) {
                fromProvider = null;
            }
            p = fromProvider != null ? fromProvider : EXTRA_LINKS.getOrDefault(tz.id, tz.id);
        }
        if (UTC_FAMILY.contains(p) || asciiEqualsIgnoreCase(p, "utc")) {
            p = "UTC";
        }
        tz.primary = p;
        return p;
    }

    /** TimeZoneEquals */
    static boolean equals(TimeZone a, TimeZone b) {
        if (a.id.equals(b.id)) {
            return true;
        }
        if (a.isOffset || b.isOffset) {
            return a.isOffset && b.isOffset && a.offsetMinutes == b.offsetMinutes;
        }
        return primaryId(a).equals(primaryId(b));
    }

    /** The available time zone (with its primary identifier) for a host ZoneId; "UTC" when not representable. */
    static TimeZone fromZoneId(ZoneId z) {
        try {
            if (!(z instanceof ZoneOffset)) {
                TimeZone named = namedZone(z.getId());
                if (named != null) {
                    String p = primaryId(named);
                    if (p.equals(named.id)) {
                        return named;
                    }
                    TimeZone primaryZone = namedZone(p);
                    return primaryZone != null ? primaryZone : named;
                }
            }
            ZoneId n = z.normalized();
            if (n instanceof ZoneOffset) {
                int seconds = ((ZoneOffset) n).getTotalSeconds();
                return seconds == 0 || seconds % 60 != 0 ? TimeZone.UTC : TimeZone.ofOffsetMinutes(seconds / 60);
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return TimeZone.UTC;
    }

    /** SystemTimeZoneIdentifier: the agent's configured zone (sandbox policy), else the host zone used by Date. */
    static TimeZone system(Realm realm) {
        ZoneId configured = realm.agent.config.timeZone;
        return fromZoneId(configured != null ? configured : DateTime.hostZone());
    }
}
