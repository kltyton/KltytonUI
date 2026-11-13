/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.CurrencyDisplayNames;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.DisplayContext;
import com.ibm.icu.text.LocaleDisplayNames;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Intl.DisplayNames (ECMA-402 §12). */
final class DisplayNamesBuiltins {
    private DisplayNamesBuiltins() {
    }

    private static final String[] STYLES = {"narrow", "short", "long"};
    private static final String[] TYPES = {"language", "region", "script", "currency", "calendar", "dateTimeField"};
    private static final String[] FALLBACKS = {"code", "none"};
    private static final String[] LANGUAGE_DISPLAYS = {"dialect", "standard"};
    private static final Map<String, Integer> DATE_TIME_FIELDS = Map.ofEntries(
            Map.entry("era", DateTimePatternGenerator.ERA),
            Map.entry("year", DateTimePatternGenerator.YEAR),
            Map.entry("quarter", DateTimePatternGenerator.QUARTER),
            Map.entry("month", DateTimePatternGenerator.MONTH),
            Map.entry("weekOfYear", DateTimePatternGenerator.WEEK_OF_YEAR),
            Map.entry("weekday", DateTimePatternGenerator.WEEKDAY),
            Map.entry("day", DateTimePatternGenerator.DAY),
            Map.entry("dayPeriod", DateTimePatternGenerator.DAYPERIOD),
            Map.entry("hour", DateTimePatternGenerator.HOUR),
            Map.entry("minute", DateTimePatternGenerator.MINUTE),
            Map.entry("second", DateTimePatternGenerator.SECOND),
            Map.entry("timeZoneName", DateTimePatternGenerator.ZONE));

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.DisplayNames.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "DisplayNames", 2, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw Dsl.typeErr("Constructor Intl.DisplayNames requires 'new'");
            }
            return create(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
        });
        realm.getIntrinsics().put("%Intl.DisplayNames%", constructor);
        intl.defineOwn("DisplayNames", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.DisplayNames", Attr.CONFIGURABLE);
        Dsl.method(proto, realm, "of", 1, (function, thisArg, args, newTarget) -> {
            JSIntlDisplayNames dn = thisDisplayNames(thisArg, "of");
            String code = canonicalCode(dn.type, Ops.toString(Values.arg(args, 0)));
            String name = displayName(dn, code);
            return name != null ? name : ("code".equals(dn.fallback) ? code : Undefined.INSTANCE);
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) -> {
            JSIntlDisplayNames dn = thisDisplayNames(thisArg, "resolvedOptions");
            JSObject object = Dsl.plainObject(function.realm);
            object.createDataPropertyOrThrow("locale", dn.locale);
            object.createDataPropertyOrThrow("style", dn.style);
            object.createDataPropertyOrThrow("type", dn.type);
            object.createDataPropertyOrThrow("fallback", dn.fallback);
            if (dn.languageDisplay != null) {
                object.createDataPropertyOrThrow("languageDisplay", dn.languageDisplay);
            }
            return object;
        });
    }

    private static JSIntlDisplayNames thisDisplayNames(Object thisArg, String method) {
        if (thisArg instanceof JSIntlDisplayNames) {
            return (JSIntlDisplayNames) thisArg;
        }
        throw Dsl.typeErr("Method Intl.DisplayNames.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlDisplayNames create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlDisplayNames dn = new JSIntlDisplayNames(
                Dsl.protoFromCtor(newTarget, "%Intl.DisplayNames.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        if (optionsArg == Undefined.INSTANCE) {
            throw Dsl.typeErr("Intl.DisplayNames requires an options object with a type");
        }
        JSObject options = Opt.getOptionsObject(optionsArg);
        Opt.localeMatcher(options);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of(), Map.of(),
                (locale, key) -> List.of());
        dn.style = Opt.str(options, "style", STYLES, "long");
        String type = (String) Opt.string(options, "type", TYPES, null);
        if (type == null) {
            throw Dsl.typeErr("Intl.DisplayNames requires a type option");
        }
        dn.type = type;
        dn.fallback = Opt.str(options, "fallback", FALLBACKS, "code");
        dn.locale = resolved.locale;
        dn.dataLocale = resolved.dataLocale;
        String languageDisplay = Opt.str(options, "languageDisplay", LANGUAGE_DISPLAYS, "dialect");
        if ("language".equals(type)) {
            dn.languageDisplay = languageDisplay;
        }
        return dn;
    }

    /** CanonicalCodeForDisplayNames */
    private static String canonicalCode(String type, String code) {
        switch (type) {
            case "language": {
                LanguageTag.Parsed parsed = LanguageTag.parse(code);
                if (parsed == null || !parsed.extensions.isEmpty() || parsed.privateUse != null) {
                    throw Dsl.rangeErr("Invalid language code: " + code);
                }
                return LanguageTag.canonicalize(code);
            }
            case "region":
                if (!LanguageTag.isRegionSubtag(code)) {
                    throw Dsl.rangeErr("Invalid region code: " + code);
                }
                return code.toUpperCase(Locale.ROOT);
            case "script":
                if (!LanguageTag.isScriptSubtag(code)) {
                    throw Dsl.rangeErr("Invalid script code: " + code);
                }
                return Character.toUpperCase(code.charAt(0)) + code.substring(1).toLowerCase(Locale.ROOT);
            case "calendar":
                if (!Opt.isUnicodeType(code)) {
                    throw Dsl.rangeErr("Invalid calendar code: " + code);
                }
                return code.toLowerCase(Locale.ROOT);
            case "dateTimeField":
                if (!DATE_TIME_FIELDS.containsKey(code)) {
                    throw Dsl.rangeErr("Invalid dateTimeField code: " + code);
                }
                return code;
            default:
                if (code.length() != 3) {
                    throw Dsl.rangeErr("Invalid currency code: " + code);
                }
                for (int i = 0; i < code.length(); i++) {
                    char c = code.charAt(i);
                    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                        throw Dsl.rangeErr("Invalid currency code: " + code);
                    }
                }
                return code.toUpperCase(Locale.ROOT);
        }
    }

    private static ULocale ulocale(JSIntlDisplayNames dn) {
        return LocaleInfo.dataLocale(dn.dataLocale);
    }

    private static LocaleDisplayNames localeDisplayNames(JSIntlDisplayNames dn) {
        if (dn.icu instanceof LocaleDisplayNames) {
            return (LocaleDisplayNames) dn.icu;
        }
        DisplayContext dialect = "standard".equals(dn.languageDisplay)
                ? DisplayContext.STANDARD_NAMES : DisplayContext.DIALECT_NAMES;
        DisplayContext length = "long".equals(dn.style) ? DisplayContext.LENGTH_FULL : DisplayContext.LENGTH_SHORT;
        LocaleDisplayNames names = LocaleDisplayNames.getInstance(ulocale(dn), dialect, length,
                DisplayContext.NO_SUBSTITUTE);
        dn.icu = names;
        return names;
    }

    /** The display name of an already canonical {@code code}, or null when the locale data has none. */
    private static String displayName(JSIntlDisplayNames dn, String code) {
        try {
            switch (dn.type) {
                case "language":
                    return localeDisplayNames(dn).localeDisplayName(ULocale.forLanguageTag(code));
                case "region":
                    return localeDisplayNames(dn).regionDisplayName(code);
                case "script":
                    return localeDisplayNames(dn).scriptDisplayName(code);
                case "calendar": {
                    String legacy = ULocale.toLegacyType("calendar", code);
                    return localeDisplayNames(dn).keyValueDisplayName("calendar", legacy != null ? legacy : code);
                }
                case "dateTimeField": {
                    DateTimePatternGenerator generator;
                    if (dn.icu instanceof DateTimePatternGenerator) {
                        generator = (DateTimePatternGenerator) dn.icu;
                    } else {
                        generator = DateTimePatternGenerator.getInstance(ulocale(dn));
                        dn.icu = generator;
                    }
                    DateTimePatternGenerator.DisplayWidth width;
                    switch (dn.style) {
                        case "short":
                            width = DateTimePatternGenerator.DisplayWidth.ABBREVIATED;
                            break;
                        case "narrow":
                            width = DateTimePatternGenerator.DisplayWidth.NARROW;
                            break;
                        default:
                            width = DateTimePatternGenerator.DisplayWidth.WIDE;
                            break;
                    }
                    return generator.getFieldDisplayName(DATE_TIME_FIELDS.get(code), width);
                }
                default: {
                    CurrencyDisplayNames currency;
                    if (dn.icu instanceof CurrencyDisplayNames) {
                        currency = (CurrencyDisplayNames) dn.icu;
                    } else {
                        currency = CurrencyDisplayNames.getInstance(ulocale(dn), true);
                        dn.icu = currency;
                    }
                    switch (dn.style) {
                        case "short":
                            return currency.getSymbol(code);
                        case "narrow":
                            return currency.getNarrowSymbol(code);
                        default:
                            return currency.getName(code);
                    }
                }
            }
        } catch (JSException e) {
            throw e;
        } catch (TerminationException e) {
            throw e;
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
