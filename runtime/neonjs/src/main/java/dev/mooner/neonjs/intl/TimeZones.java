/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.TimeZone;
import dev.mooner.neonjs.runtime.concurrent.LazyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** IANA time zone identifiers (via ICU's tz data). */
final class TimeZones {
    private TimeZones() {
    }

    /** GetAvailableNamedTimeZoneIdentifier: (identifier as listed, primary identifier). Kotlin Pair replaced. */
    static final class Record {
        final String identifier;
        final String primary;

        Record(String identifier, String primary) {
            this.identifier = identifier;
            this.primary = primary;
        }
    }

    /** Three-letter IDs that ICU (like java.util.TimeZone) accepts but that are not IANA Zone or Link names. */
    private static final Set<String> JAVA_ONLY = Set.of(
            "ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST", "CTT", "EAT", "ECT", "IET", "IST",
            "JST", "MIT", "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST");

    /** Every available Zone and Link name, keyed by its ASCII-lowercase form. */
    static final LazyValue<Map<String, String>> BY_LOWER_CASE = new LazyValue<>(() -> {
        Map<String, String> map = new HashMap<>();
        for (String id : TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.ANY, null, null)) {
            if (id.startsWith("SystemV/") || JAVA_ONLY.contains(id)) {
                continue;
            }
            map.put(id.toLowerCase(Locale.ROOT), id);
        }
        IcuCompatibility.addAvailablePrimaryNames(map);
        for (String id : List.of("UTC", "Etc/UTC", "Etc/GMT", "GMT")) {
            map.putIfAbsent(id.toLowerCase(Locale.ROOT), id);
        }
        return map;
    });

    /** AvailablePrimaryTimeZoneIdentifiers, sorted. */
    static final LazyValue<List<String>> PRIMARY_IDENTIFIERS = new LazyValue<>(() -> {
        Set<String> out = new HashSet<>();
        for (String id : TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL, null, null)) {
            if (id.startsWith("SystemV/") || JAVA_ONLY.contains(id)) {
                continue;
            }
            out.add(primaryOf(id));
        }
        out.remove("Etc/UTC");
        out.remove("Etc/GMT");
        out.add("UTC");
        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    });

    /** The primary identifier of an available identifier ("UTC" for the UTC aliases). */
    static String primaryOf(String id) {
        String canonical = TimeZone.getCanonicalID(id);
        String iana = IcuCompatibility.primaryTimeZoneId(canonical != null ? canonical : id);
        switch (iana) {
            case "Etc/UTC":
            case "Etc/GMT":
            case "GMT":
            case "Etc/UCT":
            case "UCT":
            case "Etc/Universal":
            case "Universal":
            case "Etc/Zulu":
            case "Zulu":
            case "Etc/GMT0":
            case "Etc/GMT+0":
            case "Etc/GMT-0":
            case "GMT0":
            case "GMT+0":
            case "GMT-0":
            case "Etc/Greenwich":
            case "Greenwich":
                return "UTC";
            default:
                return iana;
        }
    }

    /** GetAvailableNamedTimeZoneIdentifier: (identifier as listed, primary identifier) or null. */
    static Record find(String name) {
        if (name.length() > 64) {
            return null;
        }
        String id = BY_LOWER_CASE.get().get(name.toLowerCase(Locale.ROOT));
        if (id == null) {
            return null;
        }
        return new Record(id, primaryOf(id));
    }
}
