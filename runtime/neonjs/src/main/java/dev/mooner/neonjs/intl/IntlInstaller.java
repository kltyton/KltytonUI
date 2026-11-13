/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.util.function.LongFunction;

/** Installs the ECMA-402 Intl object and the locale-sensitive core methods into a realm. */
final class IntlInstaller {
    private IntlInstaller() {
    }

    static void install(Realm realm) {
        JSObject intl = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl%", intl);
        intl.defineOwn(JSSymbol.toStringTag, "Intl", Attr.CONFIGURABLE);
        Dsl.method(intl, realm, "getCanonicalLocales", 1, (function, thisArg, args, newTarget) ->
                Dsl.jsArray(function.realm, new java.util.ArrayList<Object>(
                        Locales.canonicalizeLocaleList(function.realm, Values.arg(args, 0)))));
        Dsl.method(intl, realm, "supportedValuesOf", 1, (function, thisArg, args, newTarget) ->
                SupportedValues.supportedValuesOf(function.realm, Ops.toString(Values.arg(args, 0))));
        CollatorBuiltins.install(realm, intl);
        DateTimeFormatBuiltins.install(realm, intl);
        DisplayNamesBuiltins.install(realm, intl);
        DurationFormatBuiltins.install(realm, intl);
        ListFormatBuiltins.install(realm, intl);
        LocaleBuiltins.install(realm, intl);
        NumberFormatBuiltins.install(realm, intl);
        PluralRulesBuiltins.install(realm, intl);
        RelativeTimeFormatBuiltins.install(realm, intl);
        SegmenterBuiltins.install(realm, intl);
        TemporalLocale.install(realm);
        installArrayMethods(realm);
        realm.getGlobalObject().defineOwn("Intl", intl, Attr.WC);
    }

    /** Array.prototype.toLocaleString and %TypedArray%.prototype.toLocaleString (ECMA-402 §19.5.1, §19.6.1). */
    private static void installArrayMethods(Realm realm) {
        Dsl.method(realm.getArrayPrototype(), realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
            JSObject object = Ops.toObject(thisArg);
            long length = Ops.lengthOfArrayLike(object);
            return joinLocale(function.realm, length, args, k -> object.get(PK.fromIndex(k), object));
        });
        JSObject typedArrayPrototype = realm.getIntrinsics().get("%TypedArray.prototype%");
        if (typedArrayPrototype == null) {
            return;
        }
        Dsl.method(typedArrayPrototype, realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSTypedArray)) {
                throw Dsl.typeErr("%TypedArray%.prototype.toLocaleString: receiver is not a typed array");
            }
            JSTypedArray typedArray = (JSTypedArray) thisArg;
            int length = typedArray.lengthOrOOB();
            if (length < 0) {
                throw Dsl.typeErr("%TypedArray%.prototype.toLocaleString: typed array is detached or out of bounds");
            }
            return joinLocale(function.realm, length, args, k -> typedArray.get(PK.fromIndex(k), typedArray));
        });
    }

    private static String joinLocale(Realm realm, long length, Object[] args, LongFunction<Object> element) {
        StringBuilder builder = new StringBuilder();
        Object[] invokeArgs = {Values.arg(args, 0), Values.arg(args, 1)};
        long k = 0;
        while (k < length) {
            Dsl.tick(realm, k);
            if (k > 0) {
                builder.append(',');
            }
            Object value = element.apply(k);
            if (value != Undefined.INSTANCE && value != Null.INSTANCE) {
                builder.append(Ops.toString(Ops.invoke(value, "toLocaleString", invokeArgs)));
                realm.agent.checkStringLength(builder.length());
            }
            k++;
        }
        return builder.toString();
    }
}
