/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.CurrencyDisplayNames;
import com.ibm.icu.text.NumberingSystem;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.concurrent.LazyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Intl.supportedValuesOf and the identifier lists behind it (computed lazily, immutable afterwards). */
final class SupportedValues {
    private SupportedValues() {
    }

    /** AvailableCalendars (canonical BCP 47 calendar types), as required by Intl.Era-monthcode. */
    static final List<String> CALENDARS = List.of(
            "buddhist", "chinese", "coptic", "dangi", "ethioaa", "ethiopic", "gregory", "hebrew", "indian",
            "islamic-civil", "islamic-tbla", "islamic-umalqura", "iso8601", "japanese", "persian", "roc");

    /** Calendar types accepted as input (canonical types plus aliases that canonicalize to them). */
    static boolean isSupportedCalendar(String ca) {
        return CALENDARS.contains(ca);
    }

    static final LazyValue<List<String>> COLLATIONS = new LazyValue<>(() -> {
        Set<String> result = new LinkedHashSet<>();
        for (String value : Collator.getKeywordValues("collation")) {
            String converted = LocaleInfo.collationToBcp47(value);
            if (!"standard".equals(converted) && !"search".equals(converted)) {
                result.add(converted);
            }
        }
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return sorted;
    });

    static final LazyValue<List<String>> CURRENCIES = new LazyValue<>(() -> {
        // only codes with display names, so that Intl.DisplayNames supports exactly this list
        CurrencyDisplayNames names = CurrencyDisplayNames.getInstance(ULocale.ENGLISH, true);
        Set<String> all = new HashSet<>();
        for (Currency currency : Currency.getAvailableCurrencies()) {
            String code = currency.getCurrencyCode();
            if (code.length() == 3 && allUpper(code) && names.getName(code) != null) {
                all.add(code);
            }
        }
        List<String> sorted = new ArrayList<>(all);
        Collections.sort(sorted);
        return sorted;
    });

    /** Numbering systems with a simple digit mapping (the only ones usable by NumberFormat/DateTimeFormat). */
    static final LazyValue<List<String>> NUMBERING_SYSTEMS = new LazyValue<>(() -> {
        Set<String> result = new LinkedHashSet<>();
        for (String name : NumberingSystem.getAvailableNames()) {
            NumberingSystem system = NumberingSystem.getInstanceByName(name);
            if (system != null && !system.isAlgorithmic() && system.getRadix() == 10
                    && name.length() >= 3 && name.length() <= 8 && allLowerDigit(name)) {
                result.add(name);
            }
        }
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return sorted;
    });

    private static final LazyValue<Set<String>> NUMBERING_SYSTEM_SET =
            new LazyValue<>(() -> new HashSet<>(NUMBERING_SYSTEMS.get()));

    static boolean isSupportedNumberingSystem(String nu) {
        return NUMBERING_SYSTEM_SET.get().contains(nu);
    }

    private static boolean allUpper(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    private static boolean allLowerDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** Simple units sanctioned for use in ECMAScript (Table "IsSanctionedSingleUnitIdentifier"). */
    static final List<String> UNITS = List.of(
            "acre", "bit", "byte", "celsius", "centimeter", "day", "degree", "fahrenheit", "fluid-ounce", "foot",
            "gallon", "gigabit", "gigabyte", "gram", "hectare", "hour", "inch", "kilobit", "kilobyte", "kilogram",
            "kilometer", "liter", "megabit", "megabyte", "meter", "microsecond", "mile", "mile-scandinavian",
            "milliliter", "millimeter", "millisecond", "minute", "month", "nanosecond", "ounce", "percent", "petabyte",
            "pound", "second", "stone", "terabit", "terabyte", "week", "yard", "year");

    static final Set<String> UNIT_SET = new HashSet<>(UNITS);

    static JSArray supportedValuesOf(Realm realm, String key) {
        List<String> values;
        switch (key) {
            case "calendar":
                values = CALENDARS;
                break;
            case "collation":
                values = COLLATIONS.get();
                break;
            case "currency":
                values = CURRENCIES.get();
                break;
            case "numberingSystem":
                values = NUMBERING_SYSTEMS.get();
                break;
            case "timeZone":
                values = TimeZones.PRIMARY_IDENTIFIERS.get();
                break;
            case "unit":
                values = UNITS;
                break;
            default:
                throw Dsl.rangeErr("Invalid key : " + key);
        }
        return Dsl.jsArray(realm, new ArrayList<Object>(values));
    }
}
