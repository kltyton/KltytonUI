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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Intl.PluralRules (ECMA-402 §17). */
final class PluralRulesBuiltins {
    private PluralRulesBuiltins() {
    }

    private static final String[] TYPES = {"cardinal", "ordinal"};
    private static final String[] NOTATIONS = {"standard", "scientific", "engineering", "compact"};
    private static final String[] COMPACT_DISPLAYS = {"short", "long"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.PluralRules.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "PluralRules", 0, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw Dsl.typeErr("Constructor Intl.PluralRules requires 'new'");
            }
            return create(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
        });
        realm.getIntrinsics().put("%Intl.PluralRules%", constructor);
        intl.defineOwn("PluralRules", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.PluralRules", Attr.CONFIGURABLE);
        Dsl.method(proto, realm, "select", 1, (function, thisArg, args, newTarget) ->
                PluralRulesIcu.select(thisPR(thisArg, "select"), Ops.toNumber(Values.arg(args, 0))));
        Dsl.method(proto, realm, "selectRange", 2, (function, thisArg, args, newTarget) -> {
            JSIntlPluralRules pr = thisPR(thisArg, "selectRange");
            Object start = Values.arg(args, 0);
            Object end = Values.arg(args, 1);
            if (start == Undefined.INSTANCE || end == Undefined.INSTANCE) {
                throw Dsl.typeErr("start or end is undefined");
            }
            double x = Ops.toNumber(start);
            double y = Ops.toNumber(end);
            if (Double.isNaN(x) || Double.isNaN(y)) {
                throw Dsl.rangeErr("start or end is NaN");
            }
            return PluralRulesIcu.selectRange(pr, x, y);
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) ->
                resolvedOptions(function.realm, thisPR(thisArg, "resolvedOptions")));
    }

    private static JSIntlPluralRules thisPR(Object thisArg, String method) {
        if (thisArg instanceof JSIntlPluralRules) {
            return (JSIntlPluralRules) thisArg;
        }
        throw Dsl.typeErr("Method Intl.PluralRules.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlPluralRules create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlPluralRules pr = new JSIntlPluralRules(
                Dsl.protoFromCtor(newTarget, "%Intl.PluralRules.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.coerceToObject(optionsArg);
        Opt.localeMatcher(options);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of(), Map.of(),
                (locale, key) -> List.of());
        pr.locale = resolved.locale;
        pr.dataLocale = resolved.dataLocale;
        pr.type = Opt.str(options, "type", TYPES, "cardinal");
        String notation = Opt.str(options, "notation", NOTATIONS, "standard");
        pr.notation = notation;
        String compactDisplay = Opt.str(options, "compactDisplay", COMPACT_DISPLAYS, "short");
        if ("compact".equals(notation)) {
            pr.compactDisplay = compactDisplay;
        }
        pr.digits.read(options, 0, 3, notation);
        return pr;
    }

    private static JSObject resolvedOptions(Realm realm, JSIntlPluralRules pr) {
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("locale", pr.locale);
        object.createDataPropertyOrThrow("type", pr.type);
        object.createDataPropertyOrThrow("notation", pr.notation);
        if (pr.compactDisplay != null) {
            object.createDataPropertyOrThrow("compactDisplay", pr.compactDisplay);
        }
        pr.digits.addDigitsTo(object);
        object.createDataPropertyOrThrow("pluralCategories", Dsl.jsArray(realm, PluralRulesIcu.categories(pr)));
        object.createDataPropertyOrThrow("roundingIncrement", (double) pr.digits.roundingIncrement);
        object.createDataPropertyOrThrow("roundingMode", pr.digits.roundingMode);
        object.createDataPropertyOrThrow("roundingPriority", pr.digits.computedRoundingPriority);
        object.createDataPropertyOrThrow("trailingZeroDisplay", pr.digits.trailingZeroDisplay);
        return object;
    }
}
