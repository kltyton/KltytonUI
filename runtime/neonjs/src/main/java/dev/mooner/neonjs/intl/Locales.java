/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.runtime.concurrent.LazyValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Locale negotiation of ECMA-402 §9.2: CanonicalizeLocaleList, ResolveLocale, SupportedLocales, DefaultLocale. */
final class Locales {
    private Locales() {
    }

    /** Maximum number of locales taken from a requested-locales list (keeps adversarial lists bounded). */
    static final int MAX_REQUESTED = 1000;

    /** Canonical tags of all locales ICU has data for (computed on first use; immutable afterwards). */
    static final LazyValue<Set<String>> AVAILABLE = new LazyValue<>(Locales::computeAvailable);

    private static Set<String> computeAvailable() {
        Set<String> out = new HashSet<>();
        Iterable<ULocale> all = ULocale.getAvailableLocalesByType(ULocale.AvailableType.WITH_LEGACY_ALIASES);
        for (ULocale locale : all) {
            String tag = locale.toLanguageTag();
            if (tag.contains("-u-") || tag.contains("-x-") || tag.contains("-t-")) {
                continue;
            }
            if (!LanguageTag.isStructurallyValid(tag)) {
                continue;
            }
            String canonical;
            try {
                canonical = LanguageTag.canonicalize(tag);
            } catch (Exception invalid) {
                continue;
            }
            out.add(canonical);
        }
        // language-region aliases of language-script-region locales whose script is the likely one (e.g. "zh-TW")
        for (String tag : new ArrayList<>(out)) {
            LanguageTag.Parsed parsed = LanguageTag.parse(tag);
            if (parsed == null || parsed.script == null || parsed.region == null || !parsed.variants.isEmpty()) {
                continue;
            }
            String languageRegion = parsed.language + "-" + parsed.region.toUpperCase(Locale.ROOT);
            if (out.contains(languageRegion)) {
                continue;
            }
            ULocale maximal = ULocale.addLikelySubtags(ULocale.forLanguageTag(languageRegion));
            if (maximal.getScript().equalsIgnoreCase(parsed.script)) {
                out.add(languageRegion);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ DefaultLocale

    /** DefaultLocale(): the configured (or host) locale, canonicalized, without extensions, and available. */
    static String defaultLocale(Realm realm) {
        IntlState state = IntlState.of(realm);
        if (state.defaultLocale != null) {
            return state.defaultLocale;
        }
        String configured = realm.agent.config.defaultLocale;
        String raw = configured != null ? configured : Locale.getDefault().toLanguageTag();
        String tag;
        try {
            tag = LanguageTag.baseName(LanguageTag.canonicalize(raw));
        } catch (Exception invalid) {
            tag = "en-US";
        }
        String resolved = bestAvailable(tag);
        if (resolved == null) {
            resolved = "en-US";
        }
        state.defaultLocale = resolved;
        return resolved;
    }

    /** BestAvailableLocale over {@link #AVAILABLE} by prefix truncation. */
    static String bestAvailable(String locale) {
        Set<String> available = AVAILABLE.get();
        String prefix = locale;
        while (!prefix.isEmpty()) {
            if (available.contains(prefix)) {
                return prefix;
            }
            int pos = prefix.lastIndexOf('-');
            if (pos < 0) {
                pos = 0;
            }
            while (pos >= 2 && prefix.charAt(pos - 2) == '-') {
                pos -= 2;
            }
            prefix = prefix.substring(0, pos);
        }
        return null;
    }

    // ------------------------------------------------------------------ CanonicalizeLocaleList

    static List<String> canonicalizeLocaleList(Realm realm, Object locales) {
        if (locales == Undefined.INSTANCE) {
            return List.of();
        }
        List<String> seen = new ArrayList<>();
        JSObject object;
        if (locales instanceof CharSequence || locales instanceof JSIntlLocale) {
            object = Dsl.jsArray(realm, List.of(locales));
        } else {
            object = Ops.toObject(locales);
        }
        long length = Ops.toLength(object.get("length", object));
        long k = 0;
        while (k < length) {
            Dsl.tick(realm, k);
            Object key = PK.fromIndex(k);
            if (object.hasProperty(key)) {
                Object value = object.get(key, object);
                if (!(value instanceof CharSequence) && !(value instanceof JSObject)) {
                    throw Dsl.typeErr("Language ID should be string or object.");
                }
                String tag = value instanceof JSIntlLocale
                        ? ((JSIntlLocale) value).locale
                        : Ops.toString(value);
                String canonical = LanguageTag.canonicalize(tag);
                if (!seen.contains(canonical)) {
                    if (seen.size() >= MAX_REQUESTED) {
                        throw Dsl.rangeErr("Too many locales requested");
                    }
                    seen.add(canonical);
                }
            }
            k++;
        }
        return seen;
    }

    // ------------------------------------------------------------------ matching

    static final class Match {
        final String locale;
        final String extension;

        Match(String locale, String extension) {
            this.locale = locale;
            this.extension = extension;
        }
    }

    /** LookupMatchingLocaleByPrefix (also used for best fit). */
    static Match lookupMatch(List<String> requested) {
        for (String locale : requested) {
            LanguageTag.Parsed parsed = LanguageTag.parse(locale);
            if (parsed == null) {
                continue;
            }
            String unicode = null;
            for (String extension : parsed.extensions) {
                if (!extension.isEmpty() && extension.charAt(0) == 'u') {
                    unicode = extension;
                    break;
                }
            }
            String withoutExtension = unicode == null ? locale : LanguageTag.removeUnicodeExtension(locale);
            String found = bestAvailable(withoutExtension);
            if (found != null) {
                return new Match(found, unicode);
            }
        }
        return null;
    }

    /** Result of ResolveLocale: the locale and the resolved value of each relevant key (null when none). */
    static final class Resolved {
        final String locale;
        final String dataLocale;
        final Map<String, String> values;

        Resolved(String locale, String dataLocale, Map<String, String> values) {
            this.locale = locale;
            this.dataLocale = dataLocale;
            this.values = values;
        }
    }

    /**
     * ResolveLocale. {@code keyData} gives, for the found (data) locale and a key, the supported values with the
     * default first (null for "no default"). {@code optionValues} are the option values for the keys (null when absent).
     */
    static Resolved resolve(Realm realm, List<String> requested, List<String> relevantKeys,
                            Map<String, String> optionValues,
                            BiFunction<String, String, List<String>> keyData) {
        Match match = lookupMatch(requested);
        if (match == null) {
            match = new Match(defaultLocale(realm), null);
        }
        String foundLocale = match.locale;
        List<LanguageTag.Keyword> keywords = List.of();
        if (match.extension != null) {
            keywords = LanguageTag.unicodeComponents(List.of(match.extension.split("-", -1)), 1).keywords;
        }
        List<LanguageTag.Keyword> supported = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        for (String key : relevantKeys) {
            List<String> keyLocaleData = keyData.apply(foundLocale, key);
            String value = keyLocaleData.isEmpty() ? null : keyLocaleData.get(0);
            LanguageTag.Keyword supportedKeyword = null;
            LanguageTag.Keyword entry = null;
            for (LanguageTag.Keyword keyword : keywords) {
                if (keyword.key.equals(key)) {
                    entry = keyword;
                    break;
                }
            }
            if (entry != null) {
                String requestedValue = entry.value;
                if (!requestedValue.isEmpty()) {
                    if (keyLocaleData.contains(requestedValue)) {
                        value = requestedValue;
                        supportedKeyword = new LanguageTag.Keyword(key, value);
                    }
                } else if (keyLocaleData.contains("true")) {
                    value = "true";
                    supportedKeyword = new LanguageTag.Keyword(key, "");
                }
            }
            String optionsValue = optionValues.get(key);
            if (optionsValue != null) {
                optionsValue = LanguageTag.canonicalizeUValue(key, optionsValue.toLowerCase(Locale.ROOT));
                if (optionsValue.isEmpty()) {
                    optionsValue = "true";
                }
            }
            if (optionsValue != null && !optionsValue.equals(value) && keyLocaleData.contains(optionsValue)) {
                value = optionsValue;
                supportedKeyword = null;
            }
            if (supportedKeyword != null) {
                supported.add(supportedKeyword);
            }
            values.put(key, value);
        }
        String dataLocale = foundLocale;
        if (!supported.isEmpty()) {
            foundLocale = LanguageTag.insertUnicodeExtensionAndCanonicalize(foundLocale, List.of(), supported);
        }
        return new Resolved(foundLocale, dataLocale, values);
    }

    /** SupportedLocales(availableLocales, requestedLocales, options) */
    static JSArray supportedLocales(Realm realm, List<String> requested, Object options) {
        JSObject opts = Opt.coerceToObject(options);
        Opt.localeMatcher(opts);
        List<Object> out = new ArrayList<>();
        for (String locale : requested) {
            if (lookupMatch(List.of(locale)) != null) {
                out.add(locale);
            }
        }
        return Dsl.jsArray(realm, out);
    }

    /** Installs {@code supportedLocalesOf} on a service constructor. */
    static void installSupportedLocalesOf(Realm realm, JSObject ctor) {
        Dsl.method(ctor, realm, "supportedLocalesOf", 1, (function, thisArg, args, newTarget) -> {
            List<String> requested = canonicalizeLocaleList(function.realm, Values.arg(args, 0));
            return supportedLocales(function.realm, requested, Values.arg(args, 1));
        });
    }
}
