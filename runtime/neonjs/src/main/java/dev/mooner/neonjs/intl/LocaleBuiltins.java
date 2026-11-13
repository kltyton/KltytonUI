/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Intl.Locale (ECMA-402 §14). */
final class LocaleBuiltins {
    private LocaleBuiltins() {
    }

    private static final List<String> KEYS = List.of("ca", "co", "fw", "hc", "kf", "kn", "nu");
    private static final String[] HOUR_CYCLES = {"h11", "h12", "h23", "h24"};
    private static final String[] CASE_FIRST = {"upper", "lower", "false"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.Locale.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "Locale", 1, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw Dsl.typeErr("Constructor Intl.Locale requires 'new'");
            }
            return construct(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
        });
        realm.getIntrinsics().put("%Intl.Locale%", constructor);
        intl.defineOwn("Locale", constructor, Attr.WC);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.Locale", Attr.CONFIGURABLE);

        Dsl.method(proto, realm, "maximize", 0, (function, thisArg, args, newTarget) ->
                derived(function.realm, thisLocale(thisArg, "maximize"), true));
        Dsl.method(proto, realm, "minimize", 0, (function, thisArg, args, newTarget) ->
                derived(function.realm, thisLocale(thisArg, "minimize"), false));
        Dsl.method(proto, realm, "toString", 0, (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "toString").locale);
        Dsl.getter(proto, realm, "baseName", (function, thisArg, args, newTarget) ->
                LanguageTag.baseName(thisLocale(thisArg, "baseName").locale));
        Dsl.getter(proto, realm, "calendar", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "calendar").calendar == null
                        ? Undefined.INSTANCE : thisLocale(thisArg, "calendar").calendar);
        Dsl.getter(proto, realm, "caseFirst", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "caseFirst").caseFirst == null
                        ? Undefined.INSTANCE : thisLocale(thisArg, "caseFirst").caseFirst);
        Dsl.getter(proto, realm, "collation", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "collation").collation == null
                        ? Undefined.INSTANCE : thisLocale(thisArg, "collation").collation);
        Dsl.getter(proto, realm, "firstDayOfWeek", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "firstDayOfWeek").firstDayOfWeek == null
                        ? Undefined.INSTANCE : thisLocale(thisArg, "firstDayOfWeek").firstDayOfWeek);
        Dsl.getter(proto, realm, "hourCycle", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "hourCycle").hourCycle == null
                        ? Undefined.INSTANCE : thisLocale(thisArg, "hourCycle").hourCycle);
        Dsl.getter(proto, realm, "numeric", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "numeric").numeric);
        Dsl.getter(proto, realm, "numberingSystem", (function, thisArg, args, newTarget) ->
                thisLocale(thisArg, "numberingSystem").numberingSystem == null
                        ? Undefined.INSTANCE : thisLocale(thisArg, "numberingSystem").numberingSystem);
        Dsl.getter(proto, realm, "language", (function, thisArg, args, newTarget) ->
                LanguageTag.split(thisLocale(thisArg, "language").locale).language);
        Dsl.getter(proto, realm, "script", (function, thisArg, args, newTarget) -> {
            String script = LanguageTag.split(thisLocale(thisArg, "script").locale).script;
            if (script == null) {
                return Undefined.INSTANCE;
            }
            return Character.toUpperCase(script.charAt(0)) + script.substring(1);
        });
        Dsl.getter(proto, realm, "region", (function, thisArg, args, newTarget) -> {
            String region = LanguageTag.split(thisLocale(thisArg, "region").locale).region;
            return region == null ? Undefined.INSTANCE : region.toUpperCase(Locale.ROOT);
        });
        Dsl.getter(proto, realm, "variants", (function, thisArg, args, newTarget) -> {
            List<String> variants = LanguageTag.split(thisLocale(thisArg, "variants").locale).variants;
            return variants.isEmpty() ? Undefined.INSTANCE : String.join("-", variants);
        });
        Dsl.method(proto, realm, "getCalendars", 0, (function, thisArg, args, newTarget) ->
                Dsl.jsArray(function.realm, LocaleInfo.calendars(thisLocale(thisArg, "getCalendars"))));
        Dsl.method(proto, realm, "getCollations", 0, (function, thisArg, args, newTarget) ->
                Dsl.jsArray(function.realm, LocaleInfo.collations(thisLocale(thisArg, "getCollations"))));
        Dsl.method(proto, realm, "getHourCycles", 0, (function, thisArg, args, newTarget) ->
                Dsl.jsArray(function.realm, LocaleInfo.hourCycles(thisLocale(thisArg, "getHourCycles"))));
        Dsl.method(proto, realm, "getNumberingSystems", 0, (function, thisArg, args, newTarget) ->
                Dsl.jsArray(function.realm, LocaleInfo.numberingSystems(thisLocale(thisArg, "getNumberingSystems"))));
        Dsl.method(proto, realm, "getTimeZones", 0, (function, thisArg, args, newTarget) -> {
            List<Object> zones = LocaleInfo.timeZones(thisLocale(thisArg, "getTimeZones"));
            return zones == null ? Undefined.INSTANCE : Dsl.jsArray(function.realm, zones);
        });
        Dsl.method(proto, realm, "getTextInfo", 0, (function, thisArg, args, newTarget) -> {
            JSObject object = Dsl.plainObject(function.realm);
            object.createDataPropertyOrThrow("direction", LocaleInfo.direction(thisLocale(thisArg, "getTextInfo")));
            return object;
        });
        Dsl.method(proto, realm, "getWeekInfo", 0, (function, thisArg, args, newTarget) ->
                LocaleInfo.weekInfo(function.realm, thisLocale(thisArg, "getWeekInfo")));
    }

    private static JSIntlLocale thisLocale(Object thisArg, String method) {
        if (thisArg instanceof JSIntlLocale) {
            return (JSIntlLocale) thisArg;
        }
        throw Dsl.typeErr("Method Intl.Locale.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlLocale construct(Realm realm, JSObject newTarget, Object tagArg, Object optionsArg) {
        JSIntlLocale locale = new JSIntlLocale(Dsl.protoFromCtor(newTarget, "%Intl.Locale.prototype%", realm));
        if (!(tagArg instanceof CharSequence) && !(tagArg instanceof JSObject)) {
            throw Dsl.typeErr("First argument to Intl.Locale constructor can't be empty or missing");
        }
        String tag0 = tagArg instanceof JSIntlLocale
                ? ((JSIntlLocale) tagArg).locale
                : Ops.toString(tagArg);
        JSObject options = Opt.coerceToObject(optionsArg);
        if (!LanguageTag.isStructurallyValid(tag0)) {
            throw Dsl.rangeErr("Incorrect locale information provided");
        }
        String tag = LanguageTag.canonicalize(tag0);
        tag = updateLanguageId(tag, options);
        Map<String, String> opt = new HashMap<>();
        opt.put("ca", typeOption(options, "calendar"));
        opt.put("co", typeOption(options, "collation"));
        String fw = (String) Opt.string(options, "firstDayOfWeek", null, null);
        if (fw != null) {
            fw = weekdayToString(fw);
            if (!Opt.isUnicodeType(fw)) {
                throw Dsl.rangeErr("Incorrect firstDayOfWeek: " + fw);
            }
        }
        opt.put("fw", fw);
        opt.put("hc", (String) Opt.string(options, "hourCycle", HOUR_CYCLES, null));
        opt.put("kf", (String) Opt.string(options, "caseFirst", CASE_FIRST, null));
        Object kn = Opt.bool(options, "numeric", null);
        opt.put("kn", kn == null ? null : kn.toString());
        opt.put("nu", typeOption(options, "numberingSystem"));
        Map<String, String> record = makeLocaleRecord(tag, opt);
        locale.locale = record.get("locale");
        locale.calendar = record.get("ca");
        locale.collation = record.get("co");
        locale.firstDayOfWeek = record.get("fw");
        locale.hourCycle = record.get("hc");
        locale.caseFirst = record.get("kf");
        String rkn = record.get("kn");
        locale.numeric = "true".equals(rkn) || "".equals(rkn);
        locale.numberingSystem = record.get("nu");
        return locale;
    }

    private static String typeOption(JSObject options, String name) {
        Object value = Opt.string(options, name, null, null);
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        if (!Opt.isUnicodeType(text)) {
            throw Dsl.rangeErr("Incorrect " + name + ": " + text);
        }
        return text;
    }

    /** WeekdayToString */
    private static String weekdayToString(String firstDayOfWeek) {
        switch (firstDayOfWeek) {
            case "0":
            case "7":
                return "sun";
            case "1":
                return "mon";
            case "2":
                return "tue";
            case "3":
                return "wed";
            case "4":
                return "thu";
            case "5":
                return "fri";
            case "6":
                return "sat";
            default:
                return firstDayOfWeek;
        }
    }

    /** UpdateLanguageId */
    private static String updateLanguageId(String tag, JSObject options) {
        LanguageTag.Parsed parsed = LanguageTag.split(tag);
        String language = Opt.str(options, "language", null, parsed.language);
        if (!LanguageTag.isLanguageSubtag(language)) {
            throw Dsl.rangeErr("Incorrect language: " + language);
        }
        String script = (String) Opt.string(options, "script", null, parsed.script);
        if (script != null && !LanguageTag.isScriptSubtag(script)) {
            throw Dsl.rangeErr("Incorrect script: " + script);
        }
        String region = (String) Opt.string(options, "region", null, parsed.region);
        if (region != null && !LanguageTag.isRegionSubtag(region)) {
            throw Dsl.rangeErr("Incorrect region: " + region);
        }
        String variantsDefault = parsed.variants.isEmpty() ? null : String.join("-", parsed.variants);
        String variants = (String) Opt.string(options, "variants", null, variantsDefault);
        List<String> variantList = parsed.variants;
        if (variants != null) {
            if (variants.isEmpty()) {
                throw Dsl.rangeErr("Incorrect variants");
            }
            String[] subtags = variants.toLowerCase(Locale.ROOT).split("-", -1);
            for (String subtag : subtags) {
                if (!LanguageTag.isVariantSubtag(subtag)) {
                    throw Dsl.rangeErr("Incorrect variants: " + variants);
                }
            }
            Set<String> unique = new HashSet<>(List.of(subtags));
            if (unique.size() != subtags.length) {
                throw Dsl.rangeErr("Duplicate variants: " + variants);
            }
            variantList = List.of(subtags);
        }
        String newTag = LanguageTag.build(language.toLowerCase(Locale.ROOT), script, region, variantList,
                parsed.extensions, parsed.privateUse);
        if (!LanguageTag.isStructurallyValid(newTag)) {
            throw Dsl.rangeErr("Incorrect locale information provided");
        }
        return newTag;
    }

    /** MakeLocaleRecord: returns the slots plus "locale". */
    private static Map<String, String> makeLocaleRecord(String tag, Map<String, String> opt) {
        LanguageTag.Components components = LanguageTag.unicodeExtensionOf(tag);
        List<String> attrs = components.attributes;
        List<LanguageTag.Keyword> keywords = new ArrayList<>(components.keywords);
        Map<String, String> result = new HashMap<>();
        for (String key : KEYS) {
            int index = -1;
            for (int i = 0; i < keywords.size(); i++) {
                if (keywords.get(i).key.equals(key)) {
                    index = i;
                    break;
                }
            }
            String value = index >= 0 ? keywords.get(index).value : null;
            String override = opt.get(key);
            if (override != null) {
                value = LanguageTag.canonicalizeUValue(key, override.toLowerCase(Locale.ROOT));
                if (index >= 0) {
                    keywords.set(index, new LanguageTag.Keyword(key, value));
                } else {
                    keywords.add(new LanguageTag.Keyword(key, value));
                }
            }
            result.put(key, value);
        }
        String base = LanguageTag.removeUnicodeExtension(tag);
        result.put("locale", !attrs.isEmpty() || !keywords.isEmpty()
                ? LanguageTag.insertUnicodeExtensionAndCanonicalize(base, attrs, keywords)
                : LanguageTag.canonicalize(base));
        // slots hold the canonical keyword values (e.g. "kn-true" -> "")
        LanguageTag.Components finalComponents = LanguageTag.unicodeExtensionOf(result.get("locale"));
        for (String key : KEYS) {
            String value = null;
            for (LanguageTag.Keyword keyword : finalComponents.keywords) {
                if (keyword.key.equals(key)) {
                    value = keyword.value;
                    break;
                }
            }
            result.put(key, value);
        }
        return result;
    }

    /** maximize() / minimize() */
    private static JSIntlLocale derived(Realm realm, JSIntlLocale locale, boolean maximize) {
        String base = LanguageTag.baseName(locale.locale);
        String newBase = LocaleInfo.likelySubtags(base, maximize);
        LanguageTag.Parsed parsed = LanguageTag.split(locale.locale);
        LanguageTag.Parsed next = LanguageTag.split(newBase);
        String tag = LanguageTag.canonicalize(LanguageTag.build(next.language, next.script, next.region,
                next.variants, parsed.extensions, parsed.privateUse));
        JSIntlLocale result = new JSIntlLocale(realm.intrinsic("%Intl.Locale.prototype%"));
        result.locale = tag;
        result.calendar = locale.calendar;
        result.collation = locale.collation;
        result.firstDayOfWeek = locale.firstDayOfWeek;
        result.hourCycle = locale.hourCycle;
        result.caseFirst = locale.caseFirst;
        result.numeric = locale.numeric;
        result.numberingSystem = locale.numberingSystem;
        return result;
    }
}
