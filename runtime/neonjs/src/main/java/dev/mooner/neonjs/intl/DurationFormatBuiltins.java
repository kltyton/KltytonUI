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
import dev.mooner.neonjs.runtime.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Intl.DurationFormat (ECMA-402 §13). */
final class DurationFormatBuiltins {
    private DurationFormatBuiltins() {
    }

    static final String[] UNITS = {"years", "months", "weeks", "days", "hours", "minutes", "seconds",
            "milliseconds", "microseconds", "nanoseconds"};
    static final String[] NF_UNITS = {"year", "month", "week", "day", "hour", "minute", "second",
            "millisecond", "microsecond", "nanosecond"};
    private static final String[] DATE_STYLES = {"long", "short", "narrow"};
    private static final String[] HMS_STYLES = {"long", "short", "narrow", "numeric", "2-digit"};
    private static final String[] SUBSECOND_STYLES = {"long", "short", "narrow", "numeric"};
    private static final String[] STYLES = {"long", "short", "narrow", "digital"};
    private static final String[] DISPLAYS = {"auto", "always"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.DurationFormat.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "DurationFormat", 0, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw Dsl.typeErr("Constructor Intl.DurationFormat requires 'new'");
            }
            return create(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
        });
        realm.getIntrinsics().put("%Intl.DurationFormat%", constructor);
        intl.defineOwn("DurationFormat", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.DurationFormat", Attr.CONFIGURABLE);
        Dsl.method(proto, realm, "format", 1, (function, thisArg, args, newTarget) -> {
            JSIntlDurationFormat df = thisDF(thisArg, "format");
            DurationRecord record = DurationRecord.from(Values.arg(args, 0));
            StringBuilder builder = new StringBuilder();
            for (DurationPart part : DurationFormatPattern.partition(function.realm, df, record)) {
                builder.append(part.value);
                function.realm.agent.checkStringLength(builder.length());
            }
            return builder.toString();
        });
        Dsl.method(proto, realm, "formatToParts", 1, (function, thisArg, args, newTarget) -> {
            JSIntlDurationFormat df = thisDF(thisArg, "formatToParts");
            DurationRecord record = DurationRecord.from(Values.arg(args, 0));
            List<Object> out = new ArrayList<>();
            for (DurationPart part : DurationFormatPattern.partition(function.realm, df, record)) {
                JSObject object = Dsl.partObject(function.realm, part.type, part.value);
                if (part.unit != null) {
                    object.createDataPropertyOrThrow("unit", part.unit);
                }
                out.add(object);
            }
            return Dsl.jsArray(function.realm, out);
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) ->
                resolvedOptions(function.realm, thisDF(thisArg, "resolvedOptions")));
    }

    /** {@code new Intl.DurationFormat(locales, options).format(duration)} (Temporal.Duration.prototype.toLocaleString). */
    static String format(Realm realm, Object locales, Object options, Object duration) {
        JSIntlDurationFormat df = create(realm, realm.intrinsic("%Intl.DurationFormat%"), locales, options);
        DurationRecord record = DurationRecord.from(duration);
        StringBuilder builder = new StringBuilder();
        for (DurationPart part : DurationFormatPattern.partition(realm, df, record)) {
            builder.append(part.value);
            realm.agent.checkStringLength(builder.length());
        }
        return builder.toString();
    }

    private static JSIntlDurationFormat thisDF(Object thisArg, String method) {
        if (thisArg instanceof JSIntlDurationFormat) {
            return (JSIntlDurationFormat) thisArg;
        }
        throw Dsl.typeErr("Method Intl.DurationFormat.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlDurationFormat create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlDurationFormat df = new JSIntlDurationFormat(
                Dsl.protoFromCtor(newTarget, "%Intl.DurationFormat.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.getOptionsObject(optionsArg);
        Opt.localeMatcher(options);
        String nu = (String) Opt.string(options, "numberingSystem", null, null);
        if (nu != null && !Opt.isUnicodeType(nu)) {
            throw Dsl.rangeErr("Invalid numberingSystem: " + nu);
        }
        Map<String, String> optionValues = new HashMap<>();
        optionValues.put("nu", nu);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of("nu"), optionValues,
                (locale, key) -> NumberFormatBuiltins.numberingSystemData(locale));
        df.locale = resolved.locale;
        df.dataLocale = resolved.dataLocale;
        df.numberingSystem = resolved.values.get("nu") == null ? "latn" : resolved.values.get("nu");
        String style = Opt.str(options, "style", STYLES, "short");
        df.style = style;
        String previousStyle = "";
        for (int i = 0; i < 10; i++) {
            String[] values;
            if (i < 4) {
                values = DATE_STYLES;
            } else if (i < 7) {
                values = HMS_STYLES;
            } else {
                values = SUBSECOND_STYLES;
            }
            String digitalBase = i < 4 ? "short" : "numeric";
            String[] unitOptions = unitOptions(df, i, options, style, values, digitalBase, previousStyle);
            df.styles[i] = unitOptions[0];
            df.displays[i] = unitOptions[1];
            if (i >= 4) {
                previousStyle = unitOptions[0];
            }
        }
        df.fractionalDigits = Opt.number(options, "fractionalDigits", 0, 9, null);
        return df;
    }

    /** GetDurationUnitOptions */
    private static String[] unitOptions(JSIntlDurationFormat df, int index, JSObject options, String baseStyle,
                                        String[] values, String digitalBase, String previousStyle) {
        String unit = UNITS[index];
        String style = (String) Opt.string(options, unit, values, null);
        String displayDefault = "always";
        if (style == null) {
            if ("digital".equals(baseStyle)) {
                style = digitalBase;
                if (!"hours".equals(unit) && !"minutes".equals(unit) && !"seconds".equals(unit)) {
                    displayDefault = "auto";
                }
            } else if ("fractional".equals(previousStyle) || "numeric".equals(previousStyle)
                    || "2-digit".equals(previousStyle)) {
                style = "numeric";
                if (!"minutes".equals(unit) && !"seconds".equals(unit)) {
                    displayDefault = "auto";
                }
            } else {
                style = baseStyle;
                displayDefault = "auto";
            }
        }
        if ("numeric".equals(style) && index >= 7) {
            style = "fractional";
            displayDefault = "auto";
        }
        String display = Opt.str(options, unit + "Display", DISPLAYS, displayDefault);
        // ValidateDurationUnitStyle
        if ("always".equals(display) && "fractional".equals(style)) {
            throw Dsl.rangeErr(unit + ": fractional style cannot be displayed always");
        }
        if ("fractional".equals(previousStyle) && !"fractional".equals(style)) {
            throw Dsl.rangeErr(unit + ": invalid style " + style + " after a fractional unit");
        }
        if (("numeric".equals(previousStyle) || "2-digit".equals(previousStyle))
                && !"fractional".equals(style) && !"numeric".equals(style) && !"2-digit".equals(style)) {
            throw Dsl.rangeErr(unit + ": invalid style " + style + " after a numeric unit");
        }
        if ("numeric".equals(style)) {
            if ("hours".equals(unit) && DurationFormatPattern.twoDigitHours(df)) {
                style = "2-digit";
            }
            if (("minutes".equals(unit) || "seconds".equals(unit))
                    && ("numeric".equals(previousStyle) || "2-digit".equals(previousStyle))) {
                style = "2-digit";
            }
        }
        return new String[]{style, display};
    }

    private static JSObject resolvedOptions(Realm realm, JSIntlDurationFormat df) {
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("locale", df.locale);
        object.createDataPropertyOrThrow("numberingSystem", df.numberingSystem);
        object.createDataPropertyOrThrow("style", df.style);
        for (int i = 0; i < 10; i++) {
            String style = df.styles[i];
            object.createDataPropertyOrThrow(UNITS[i], "fractional".equals(style) ? "numeric" : style);
            object.createDataPropertyOrThrow(UNITS[i] + "Display", df.displays[i]);
        }
        if (df.fractionalDigits != null) {
            object.createDataPropertyOrThrow("fractionalDigits", (double) df.fractionalDigits);
        }
        return object;
    }
}
