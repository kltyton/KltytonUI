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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Intl.RelativeTimeFormat (ECMA-402 §18). */
final class RelativeTimeFormatBuiltins {
    private RelativeTimeFormatBuiltins() {
    }

    private static final String[] STYLES = {"long", "short", "narrow"};
    private static final String[] NUMERICS = {"always", "auto"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.RelativeTimeFormat.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "RelativeTimeFormat", 0, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        throw Dsl.typeErr("Constructor Intl.RelativeTimeFormat requires 'new'");
                    }
                    return create(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
                });
        realm.getIntrinsics().put("%Intl.RelativeTimeFormat%", constructor);
        intl.defineOwn("RelativeTimeFormat", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.RelativeTimeFormat", Attr.CONFIGURABLE);
        Dsl.method(proto, realm, "format", 2, (function, thisArg, args, newTarget) -> {
            JSIntlRelativeTimeFormat rtf = thisRTF(thisArg, "format");
            double value = Ops.toNumber(Values.arg(args, 0));
            String unit = Ops.toString(Values.arg(args, 1));
            StringBuilder builder = new StringBuilder();
            for (FmtPart part : RelativeTimeFormatIcu.partition(function.realm, rtf, value, unit)) {
                builder.append(part.value);
            }
            return Dsl.checkedString(function.realm, builder);
        });
        Dsl.method(proto, realm, "formatToParts", 2, (function, thisArg, args, newTarget) -> {
            JSIntlRelativeTimeFormat rtf = thisRTF(thisArg, "formatToParts");
            double value = Ops.toNumber(Values.arg(args, 0));
            String unit = Ops.toString(Values.arg(args, 1));
            return RelativeTimeFormatIcu.formatToParts(function.realm, rtf, value, unit);
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) -> {
            JSIntlRelativeTimeFormat rtf = thisRTF(thisArg, "resolvedOptions");
            JSObject object = Dsl.plainObject(function.realm);
            object.createDataPropertyOrThrow("locale", rtf.locale);
            object.createDataPropertyOrThrow("style", rtf.style);
            object.createDataPropertyOrThrow("numeric", rtf.numeric);
            object.createDataPropertyOrThrow("numberingSystem", rtf.numberingSystem);
            return object;
        });
    }

    private static JSIntlRelativeTimeFormat thisRTF(Object thisArg, String method) {
        if (thisArg instanceof JSIntlRelativeTimeFormat) {
            return (JSIntlRelativeTimeFormat) thisArg;
        }
        throw Dsl.typeErr("Method Intl.RelativeTimeFormat.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlRelativeTimeFormat create(Realm realm, JSObject newTarget, Object locales,
                                                   Object optionsArg) {
        JSIntlRelativeTimeFormat rtf = new JSIntlRelativeTimeFormat(
                Dsl.protoFromCtor(newTarget, "%Intl.RelativeTimeFormat.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.coerceToObject(optionsArg);
        Opt.localeMatcher(options);
        String nu = (String) Opt.string(options, "numberingSystem", null, null);
        if (nu != null && !Opt.isUnicodeType(nu)) {
            throw Dsl.rangeErr("Invalid numberingSystem: " + nu);
        }
        Map<String, String> optionValues = new HashMap<>();
        optionValues.put("nu", nu);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of("nu"), optionValues,
                (locale, key) -> NumberFormatBuiltins.numberingSystemData(locale));
        rtf.locale = resolved.locale;
        rtf.dataLocale = resolved.dataLocale;
        rtf.numberingSystem = resolved.values.get("nu") == null ? "latn" : resolved.values.get("nu");
        rtf.style = Opt.str(options, "style", STYLES, "long");
        rtf.numeric = Opt.str(options, "numeric", NUMERICS, "always");
        return rtf;
    }
}
