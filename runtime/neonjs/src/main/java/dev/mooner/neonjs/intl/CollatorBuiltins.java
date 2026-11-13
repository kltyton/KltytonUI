/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Intl.Collator (ECMA-402 §10) and the Collator-based String.prototype methods. */
final class CollatorBuiltins {
    private CollatorBuiltins() {
    }

    private static final String[] USAGES = {"sort", "search"};
    private static final String[] CASE_FIRST = {"upper", "lower", "false"};
    private static final String[] SENSITIVITIES = {"base", "accent", "case", "variant"};
    private static final List<String> KEYS = List.of("co", "kf", "kn");

    /** Languages with language-sensitive case mappings in the Unicode Character Database (SpecialCasing / ICU). */
    private static final Set<String> CASE_LANGUAGES = Set.of("az", "el", "hy", "lt", "nl", "tr");

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.Collator.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "Collator", 0, proto, (function, thisArg, args, newTarget) ->
                create(function.realm, newTarget != null ? newTarget : function,
                        Values.arg(args, 0), Values.arg(args, 1)));
        realm.getIntrinsics().put("%Intl.Collator%", constructor);
        intl.defineOwn("Collator", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.Collator", Attr.CONFIGURABLE);
        Dsl.getter(proto, realm, "compare", (function, thisArg, args, newTarget) -> {
            JSIntlCollator collator = thisCollator(thisArg, "compare");
            if (collator.boundCompare == null) {
                NativeFunction bound = new NativeFunction(function.realm, "", 2,
                        (f, t, a, nt) -> compareStrings(collator, Ops.toString(Values.arg(a, 0)),
                                Ops.toString(Values.arg(a, 1))),
                        false, function.realm.getFunctionPrototype(), null);
                collator.boundCompare = bound;
            }
            return collator.boundCompare;
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) ->
                resolvedOptions(function.realm, thisCollator(thisArg, "resolvedOptions")));
        installStringMethods(realm);
    }

    private static JSIntlCollator thisCollator(Object thisArg, String method) {
        if (thisArg instanceof JSIntlCollator) {
            return (JSIntlCollator) thisArg;
        }
        throw Dsl.typeErr("Method Intl.Collator.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSObject resolvedOptions(Realm realm, JSIntlCollator collator) {
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("locale", collator.locale);
        object.createDataPropertyOrThrow("usage", collator.usage);
        object.createDataPropertyOrThrow("sensitivity", collator.sensitivity);
        object.createDataPropertyOrThrow("ignorePunctuation", collator.ignorePunctuation);
        object.createDataPropertyOrThrow("collation", collator.collation);
        object.createDataPropertyOrThrow("numeric", collator.numeric);
        object.createDataPropertyOrThrow("caseFirst", collator.caseFirst);
        return object;
    }

    /** OrdinaryCreateFromConstructor + InitializeCollator */
    static JSIntlCollator create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlCollator collator = new JSIntlCollator(
                Dsl.protoFromCtor(newTarget, "%Intl.Collator.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.coerceToObject(optionsArg);
        String usage = Opt.str(options, "usage", USAGES, "sort");
        collator.usage = usage;
        Opt.localeMatcher(options);
        String collation = (String) Opt.string(options, "collation", null, null);
        if (collation != null && !Opt.isUnicodeType(collation)) {
            throw Dsl.rangeErr("Invalid collation : " + collation);
        }
        Boolean numeric = (Boolean) Opt.bool(options, "numeric", null);
        String caseFirst = (String) Opt.string(options, "caseFirst", CASE_FIRST, null);
        Map<String, String> opt = new HashMap<>();
        opt.put("co", collation);
        opt.put("kn", numeric == null ? null : numeric.toString());
        opt.put("kf", caseFirst);
        Locales.Resolved resolved = Locales.resolve(realm, requested, KEYS, opt,
                (locale, key) -> keyData(locale, key, usage));
        collator.locale = resolved.locale;
        collator.dataLocale = resolved.dataLocale;
        collator.collation = resolved.values.get("co") == null ? "default" : resolved.values.get("co");
        collator.numeric = "true".equals(resolved.values.get("kn"));
        collator.caseFirst = resolved.values.get("kf") == null ? "false" : resolved.values.get("kf");
        collator.sensitivity = Opt.str(options, "sensitivity", SENSITIVITIES, "variant");
        boolean defaultIgnorePunctuation = localeDefaults(resolved.dataLocale).ignorePunctuation;
        collator.ignorePunctuation = (Boolean) Opt.bool(options, "ignorePunctuation", defaultIgnorePunctuation);
        return collator;
    }

    /** {@code [[SortLocaleData]]} / {@code [[SearchLocaleData]]} for the relevant extension keys. */
    private static List<String> keyData(String locale, String key, String usage) {
        List<String> values = new ArrayList<>();
        switch (key) {
            case "co":
                if ("search".equals(usage)) {
                    values.add(null);
                } else {
                    values.add(null);
                    values.addAll(collationsFor(locale));
                }
                return values;
            case "kn":
                values.add("false");
                values.add("true");
                return values;
            case "kf": {
                String defaultValue = localeDefaults(locale).caseFirst;
                values.add(defaultValue);
                for (String value : CASE_FIRST) {
                    if (!value.equals(defaultValue)) {
                        values.add(value);
                    }
                }
                return values;
            }
            default:
                values.add(null);
                return values;
        }
    }

    private static List<String> collationsFor(String locale) {
        return icuCall(() -> {
            List<String> result = new ArrayList<>();
            for (String value : Collator.getKeywordValuesForLocale("collation",
                    LocaleInfo.dataLocale(locale), true)) {
                String converted = LocaleInfo.collationToBcp47(value);
                if (!"standard".equals(converted) && !"search".equals(converted) && !result.contains(converted)) {
                    result.add(converted);
                }
            }
            return result;
        });
    }

    /** Locale-dependent defaults of the root collation of a locale (immutable values). */
    private static final class Defaults {
        final String caseFirst;
        final boolean ignorePunctuation;

        Defaults(String caseFirst, boolean ignorePunctuation) {
            this.caseFirst = caseFirst;
            this.ignorePunctuation = ignorePunctuation;
        }
    }

    /** Bounded, thread-safe cache of {@link Defaults} by data locale (holds only immutable values). */
    private static final ConcurrentHashMap<String, Defaults> DEFAULTS_CACHE = new ConcurrentHashMap<>();

    private static Defaults localeDefaults(String locale) {
        Defaults cached = DEFAULTS_CACHE.get(locale);
        if (cached != null) {
            return cached;
        }
        Defaults defaults = icuCall(() -> {
            RuleBasedCollator collator = (RuleBasedCollator) Collator.getInstance(LocaleInfo.dataLocale(locale));
            return new Defaults(collator.isUpperCaseFirst() ? "upper"
                    : collator.isLowerCaseFirst() ? "lower" : "false",
                    collator.isAlternateHandlingShifted());
        });
        if (DEFAULTS_CACHE.size() >= 256) {
            DEFAULTS_CACHE.clear();
        }
        DEFAULTS_CACHE.put(locale, defaults);
        return defaults;
    }

    private static <T> T icuCall(Supplier<T> block) {
        try {
            return block.get();
        } catch (dev.mooner.neonjs.runtime.JSException e) {
            throw e;
        } catch (TerminationException e) {
            throw e;
        } catch (RuntimeException invalid) {
            throw Dsl.rangeErr("Internal error in collation data");
        }
    }

    private static Collator icuCollator(JSIntlCollator collator) {
        if (collator.icu != null) {
            return collator.icu;
        }
        Collator coll = icuCall(() -> {
            ULocale locale = LocaleInfo.dataLocale(collator.dataLocale);
            String icuCollation = "search".equals(collator.usage) ? "search"
                    : !"default".equals(collator.collation)
                    ? ULocale.toLegacyType("collation", collator.collation) : null;
            if (icuCollation != null) {
                locale = locale.setKeywordValue("collation", icuCollation);
            }
            RuleBasedCollator ruleBased = (RuleBasedCollator) Collator.getInstance(locale);
            ruleBased.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
            switch (collator.sensitivity) {
                case "base":
                    ruleBased.setStrength(Collator.PRIMARY);
                    break;
                case "accent":
                    ruleBased.setStrength(Collator.SECONDARY);
                    break;
                case "case":
                    ruleBased.setStrength(Collator.PRIMARY);
                    ruleBased.setCaseLevel(true);
                    break;
                default:
                    ruleBased.setStrength(Collator.TERTIARY);
                    break;
            }
            ruleBased.setNumericCollation(collator.numeric);
            switch (collator.caseFirst) {
                case "upper":
                    ruleBased.setUpperCaseFirst(true);
                    break;
                case "lower":
                    ruleBased.setLowerCaseFirst(true);
                    break;
                default:
                    ruleBased.setUpperCaseFirst(false);
                    ruleBased.setLowerCaseFirst(false);
                    break;
            }
            ruleBased.setAlternateHandlingShifted(collator.ignorePunctuation);
            ruleBased.freeze();
            return ruleBased;
        });
        collator.icu = coll;
        return coll;
    }

    /** CompareStrings */
    static Double compareStrings(JSIntlCollator collator, String x, String y) {
        Collator coll = icuCollator(collator);
        if (x.length() + y.length() > (1 << 16)) {
            Agent agent = Agent.current.get();
            if (agent != null) {
                agent.checkInterrupt();
            }
        }
        int result = coll.compare(x, y);
        return result < 0 ? -1.0 : result > 0 ? 1.0 : 0.0;
    }

    // ------------------------------------------------------------------ String.prototype

    private static String thisString(Object thisArg) {
        return Ops.toString(Ops.requireObjectCoercible(thisArg));
    }

    private static void installStringMethods(Realm realm) {
        JSObject stringPrototype = realm.getStringPrototype();
        Dsl.method(stringPrototype, realm, "localeCompare", 1, (function, thisArg, args, newTarget) -> {
            String string = thisString(thisArg);
            String that = Ops.toString(Values.arg(args, 0));
            Object locales = Values.arg(args, 1);
            Object options = Values.arg(args, 2);
            JSObject constructor = function.realm.intrinsic("%Intl.Collator%");
            // a collator built from a primitive locale string and no options is unobservable to construct: reuse it
            JSIntlCollator collator;
            if (options == Undefined.INSTANCE && (locales == Undefined.INSTANCE || locales instanceof CharSequence)) {
                String key = locales == Undefined.INSTANCE ? "Collator:" : "Collator:" + locales;
                collator = IntlState.of(function.realm).cached(key,
                        () -> create(function.realm, constructor, locales, Undefined.INSTANCE));
            } else {
                collator = create(function.realm, constructor, locales, options);
            }
            return compareStrings(collator, string, that);
        });
        Dsl.method(stringPrototype, realm, "toLocaleLowerCase", 0, (function, thisArg, args, newTarget) ->
                transformCase(function.realm, thisString(thisArg), Values.arg(args, 0), false));
        Dsl.method(stringPrototype, realm, "toLocaleUpperCase", 0, (function, thisArg, args, newTarget) ->
                transformCase(function.realm, thisString(thisArg), Values.arg(args, 0), true));
    }

    /** TransformCase(S, locales, targetCase) */
    private static String transformCase(Realm realm, String string, Object locales, boolean upper) {
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        String requestedLocale = !requested.isEmpty() ? requested.get(0) : Locales.defaultLocale(realm);
        String withoutExtension = LanguageTag.removeUnicodeExtension(requestedLocale);
        // LookupMatchingLocaleByPrefix over the case-mapping languages: only the language subtag can match
        LanguageTag.Parsed parsed = LanguageTag.parse(withoutExtension);
        String language = parsed == null ? null : parsed.language;
        ULocale locale = language != null && CASE_LANGUAGES.contains(language)
                ? new ULocale(language) : ULocale.ROOT;
        String out = upper ? UCharacter.toUpperCase(locale, string) : UCharacter.toLowerCase(locale, string);
        return Dsl.checkedString(realm, out);
    }
}
