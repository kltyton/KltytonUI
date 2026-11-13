package dev.mooner.neonjs.intl;

import com.ibm.icu.util.TimeZone;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class IcuCompatibility {
    // CLDR 48 timezone identifiers. Copyright © 1991-2017 Unicode, Inc.; Unicode License V3.
    private static final Map<String, String> IANA_NAMES = new LinkedHashMap<>();

    static {
        IANA_NAMES.put("America/Buenos_Aires", "America/Argentina/Buenos_Aires");
        IANA_NAMES.put("America/Cordoba", "America/Argentina/Cordoba");
        IANA_NAMES.put("America/Catamarca", "America/Argentina/Catamarca");
        IANA_NAMES.put("America/Jujuy", "America/Argentina/Jujuy");
        IANA_NAMES.put("America/Mendoza", "America/Argentina/Mendoza");
        IANA_NAMES.put("America/Coral_Harbour", "America/Atikokan");
        IANA_NAMES.put("Africa/Asmera", "Africa/Asmara");
        IANA_NAMES.put("Pacific/Ponape", "Pacific/Pohnpei");
        IANA_NAMES.put("Pacific/Truk", "Pacific/Chuuk");
        IANA_NAMES.put("Atlantic/Faeroe", "Atlantic/Faroe");
        IANA_NAMES.put("America/Godthab", "America/Nuuk");
        IANA_NAMES.put("Asia/Calcutta", "Asia/Kolkata");
        IANA_NAMES.put("Pacific/Enderbury", "Pacific/Kanton");
        IANA_NAMES.put("Asia/Rangoon", "Asia/Yangon");
        IANA_NAMES.put("Asia/Katmandu", "Asia/Kathmandu");
        IANA_NAMES.put("Europe/Kiev", "Europe/Kyiv");
        IANA_NAMES.put("America/Indianapolis", "America/Indiana/Indianapolis");
        IANA_NAMES.put("America/Louisville", "America/Kentucky/Louisville");
        IANA_NAMES.put("Asia/Saigon", "Asia/Ho_Chi_Minh");
    }

    private static final Map<String, String> ICU_NAMES = new LinkedHashMap<>();

    static {
        for (Map.Entry<String, String> entry : IANA_NAMES.entrySet()) {
            ICU_NAMES.put(entry.getValue().toLowerCase(Locale.ROOT), entry.getKey());
        }
    }

    private IcuCompatibility() {
    }

    public static void addAvailablePrimaryNames(Map<String, String> ids) {
        for (Map.Entry<String, String> entry : IANA_NAMES.entrySet()) {
            String legacy = entry.getKey();
            String primary = entry.getValue();
            if (ids.containsKey(legacy.toLowerCase(Locale.ROOT))) {
                ids.putIfAbsent(primary.toLowerCase(Locale.ROOT), primary);
            }
        }
    }

    public static String icuTimeZoneId(String id) {
        String legacy = ICU_NAMES.get(id.toLowerCase(Locale.ROOT));
        return legacy != null ? legacy : id;
    }

    public static String primaryTimeZoneId(String id) {
        boolean[] isSystem = new boolean[] {false};
        String canonical = TimeZone.getCanonicalID(id, isSystem);
        if (!isSystem[0] || canonical == null) {
            String legacy = ICU_NAMES.get(id.toLowerCase(Locale.ROOT));
            if (legacy == null) return TimeZone.UNKNOWN_ZONE_ID;
            canonical = TimeZone.getCanonicalID(legacy, isSystem);
        }
        if (!isSystem[0] || canonical == null) return TimeZone.UNKNOWN_ZONE_ID;
        String primary = IANA_NAMES.get(canonical);
        return primary != null ? primary : canonical;
    }
}
