/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.DecimalFormatSymbols;
import com.ibm.icu.text.FormattedValue;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Intl.NumberFormat (ECMA-402 section 16) and Number/BigInt.prototype.toLocaleString. */
final class NumberFormatBuiltins {
    private NumberFormatBuiltins() {
    }

    private static final String[] STYLES = {"decimal", "percent", "currency", "unit"};
    private static final String[] CURRENCY_DISPLAYS = {"code", "symbol", "narrowSymbol", "name"};
    private static final String[] CURRENCY_SIGNS = {"standard", "accounting"};
    private static final String[] UNIT_DISPLAYS = {"short", "narrow", "long"};
    private static final String[] NOTATIONS = {"standard", "scientific", "engineering", "compact"};
    private static final String[] COMPACT_DISPLAYS = {"short", "long"};
    private static final String[] USE_GROUPING = {"min2", "auto", "always", "true", "false"};
    private static final String[] SIGN_DISPLAYS = {"auto", "never", "always", "exceptZero", "negative"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.NumberFormat.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "NumberFormat", 0, proto, (function, thisArg, args, newTarget) -> {
            JSIntlNumberFormat nf = create(function.realm, newTarget != null ? newTarget : function,
                    Values.arg(args, 0), Values.arg(args, 1));
            return newTarget == null ? chain(function.realm, function, thisArg, nf) : nf;
        });
        realm.getIntrinsics().put("%Intl.NumberFormat%", constructor);
        intl.defineOwn("NumberFormat", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.NumberFormat", Attr.CONFIGURABLE);
        Dsl.getter(proto, realm, "format", (function, thisArg, args, newTarget) -> {
            JSIntlNumberFormat nf = unwrap(function.realm, thisArg, "format");
            if (nf.boundFormat == null) {
                nf.boundFormat = new NativeFunction(function.realm, "", 1,
                        (f, t, a, nt) -> formatToString(f.realm, nf, IntlMV.from(Values.arg(a, 0))),
                        false, function.realm.getFunctionPrototype(), null);
            }
            return nf.boundFormat;
        });
        Dsl.method(proto, realm, "formatToParts", 1, (function, thisArg, args, newTarget) ->
                formatToParts(function.realm, thisNF(thisArg, "formatToParts"),
                        IntlMV.from(Values.arg(args, 0))));
        Dsl.method(proto, realm, "formatRange", 2, (function, thisArg, args, newTarget) -> {
            JSIntlNumberFormat nf = thisNF(thisArg, "formatRange");
            IntlMV[] range = rangeArgs(args);
            return Dsl.checkedString(function.realm,
                    NumberFormatIcu.formatRange(nf, range[0], range[1]).toString());
        });
        Dsl.method(proto, realm, "formatRangeToParts", 2, (function, thisArg, args, newTarget) -> {
            JSIntlNumberFormat nf = thisNF(thisArg, "formatRangeToParts");
            IntlMV[] range = rangeArgs(args);
            FormattedValue value = NumberFormatIcu.formatRange(nf, range[0], range[1]);
            String approximation = DecimalFormatSymbols.getInstance(
                    NumberFormatIcu.icuLocale(nf.dataLocale, nf.numberingSystem)).getApproximatelySignString();
            return NumberFormatIcu.partsArray(function.realm, NumberFormatIcu.parts(function.realm, value,
                    new IntlMV[]{range[0], range[1]}, true, "unit".equals(nf.style), approximation));
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) ->
                resolvedOptions(function.realm, unwrap(function.realm, thisArg, "resolvedOptions")));

        Dsl.method(realm.getNumberPrototype(), realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
            double x;
            if (thisArg instanceof Double) {
                x = (Double) thisArg;
            } else if (thisArg instanceof JSPrimitiveWrapper
                    && ((JSPrimitiveWrapper) thisArg).primitive instanceof Double) {
                x = (Double) ((JSPrimitiveWrapper) thisArg).primitive;
            } else {
                throw Dsl.typeErr("Number.prototype.toLocaleString requires that 'this' be a Number");
            }
            return formatToString(function.realm,
                    forLocaleMethod(function.realm, Values.arg(args, 0), Values.arg(args, 1)), IntlMV.of(x));
        });
        Dsl.method(realm.getBigintPrototype(), realm, "toLocaleString", 0, (function, thisArg, args, newTarget) -> {
            BigInteger x;
            if (thisArg instanceof BigInteger) {
                x = (BigInteger) thisArg;
            } else if (thisArg instanceof JSPrimitiveWrapper
                    && ((JSPrimitiveWrapper) thisArg).primitive instanceof BigInteger) {
                x = (BigInteger) ((JSPrimitiveWrapper) thisArg).primitive;
            } else {
                throw Dsl.typeErr("BigInt.prototype.toLocaleString requires that 'this' be a BigInt");
            }
            return formatToString(function.realm,
                    forLocaleMethod(function.realm, Values.arg(args, 0), Values.arg(args, 1)), IntlMV.of(x));
        });
    }

    /** The NumberFormat for toLocaleString (cached per realm when locales and options are undefined). */
    static JSIntlNumberFormat forLocaleMethod(Realm realm, Object locales, Object options) {
        if (locales == Undefined.INSTANCE && options == Undefined.INSTANCE) {
            return IntlState.of(realm).cached("NumberFormat:default",
                    () -> create(realm, null, Undefined.INSTANCE, Undefined.INSTANCE));
        }
        return create(realm, null, locales, options);
    }

    private static IntlMV[] rangeArgs(Object[] args) {
        Object start = Values.arg(args, 0);
        Object end = Values.arg(args, 1);
        if (start == Undefined.INSTANCE || end == Undefined.INSTANCE) {
            throw Dsl.typeErr("start or end is undefined");
        }
        IntlMV x = IntlMV.from(start);
        IntlMV y = IntlMV.from(end);
        if (x.isNaN() || y.isNaN()) {
            throw Dsl.rangeErr("start or end is NaN");
        }
        return new IntlMV[]{x, y};
    }

    private static JSIntlNumberFormat thisNF(Object thisArg, String method) {
        if (thisArg instanceof JSIntlNumberFormat) {
            return (JSIntlNumberFormat) thisArg;
        }
        throw Dsl.typeErr("Method Intl.NumberFormat.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    /** UnwrapNumberFormat + RequireInternalSlot */
    private static JSIntlNumberFormat unwrap(Realm realm, Object thisArg, String method) {
        if (!(thisArg instanceof JSObject)) {
            throw Dsl.typeErr("Method Intl.NumberFormat.prototype." + method
                    + " called on incompatible receiver " + Ops.describe(thisArg));
        }
        if (thisArg instanceof JSIntlNumberFormat) {
            return (JSIntlNumberFormat) thisArg;
        }
        JSObject object = (JSObject) thisArg;
        if (Ops.ordinaryHasInstance(realm.intrinsic("%Intl.NumberFormat%"), object)) {
            return thisNF(object.get(IntlState.of(realm).fallbackSymbol, object), method);
        }
        return thisNF(object, method);
    }

    /** ChainNumberFormat (legacy constructor semantics). */
    private static Object chain(Realm realm, JSObject constructor, Object thisValue, JSIntlNumberFormat nf) {
        if (thisValue instanceof JSObject && Ops.ordinaryHasInstance(constructor, thisValue)) {
            ((JSObject) thisValue).definePropertyOrThrow(IntlState.of(realm).fallbackSymbol,
                    Dsl.data(nf, Attr.NONE));
            return thisValue;
        }
        return nf;
    }

    /** The Intl.NumberFormat constructor steps (CreateNumberFormat). */
    static JSIntlNumberFormat create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlNumberFormat nf = new JSIntlNumberFormat(
                Dsl.protoFromCtor(newTarget, "%Intl.NumberFormat.prototype%", realm));
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
                (locale, key) -> numberingSystemData(locale));
        nf.locale = resolved.locale;
        nf.dataLocale = resolved.dataLocale;
        nf.numberingSystem = resolved.values.get("nu") == null ? "latn" : resolved.values.get("nu");
        setUnitOptions(nf, options);
        String notation = Opt.str(options, "notation", NOTATIONS, "standard");
        nf.notation = notation;
        int mnfdDefault;
        int mxfdDefault;
        if ("currency".equals(nf.style) && "standard".equals(notation)) {
            int digits = NumberFormatIcu.currencyDigits(nf.currency);
            mnfdDefault = digits;
            mxfdDefault = digits;
        } else {
            mnfdDefault = 0;
            mxfdDefault = "percent".equals(nf.style) ? 0 : 3;
        }
        nf.digits.read(options, mnfdDefault, mxfdDefault, notation);
        String compactDisplay = Opt.str(options, "compactDisplay", COMPACT_DISPLAYS, "short");
        String defaultUseGrouping = "auto";
        if ("compact".equals(notation)) {
            nf.compactDisplay = compactDisplay;
            defaultUseGrouping = "min2";
        }
        Object useGrouping = Opt.booleanOrString(options, "useGrouping", USE_GROUPING, defaultUseGrouping);
        if ("true".equals(useGrouping) || "false".equals(useGrouping)) {
            useGrouping = defaultUseGrouping;
        }
        if (Boolean.TRUE.equals(useGrouping)) {
            useGrouping = "always";
        }
        nf.useGrouping = useGrouping;
        nf.signDisplay = Opt.str(options, "signDisplay", SIGN_DISPLAYS, "auto");
        return nf;
    }

    /** Locale data for "nu": the locale's default numbering system first, then every supported one. */
    static List<String> numberingSystemData(String dataLocale) {
        String defaultValue = NumberFormatIcu.defaultNumberingSystem(dataLocale);
        List<String> all = SupportedValues.NUMBERING_SYSTEMS.get();
        List<String> out = new ArrayList<>(all.size() + 1);
        out.add(defaultValue);
        for (String name : all) {
            if (!name.equals(defaultValue)) {
                out.add(name);
            }
        }
        return out;
    }

    /** SetNumberFormatUnitOptions */
    private static void setUnitOptions(JSIntlNumberFormat nf, JSObject options) {
        String style = Opt.str(options, "style", STYLES, "decimal");
        nf.style = style;
        String currency = (String) Opt.string(options, "currency", null, null);
        if (currency == null) {
            if ("currency".equals(style)) {
                throw Dsl.typeErr("Currency code is required with currency style.");
            }
        } else if (!isWellFormedCurrencyCode(currency)) {
            throw Dsl.rangeErr("Invalid currency code : " + currency);
        }
        String currencyDisplay = Opt.str(options, "currencyDisplay", CURRENCY_DISPLAYS, "symbol");
        String currencySign = Opt.str(options, "currencySign", CURRENCY_SIGNS, "standard");
        String unit = (String) Opt.string(options, "unit", null, null);
        if (unit == null) {
            if ("unit".equals(style)) {
                throw Dsl.typeErr("Unit is required with unit style.");
            }
        } else if (!isWellFormedUnitIdentifier(unit)) {
            throw Dsl.rangeErr("Invalid unit argument for Intl.NumberFormat() '" + unit + "'");
        }
        String unitDisplay = Opt.str(options, "unitDisplay", UNIT_DISPLAYS, "short");
        if ("currency".equals(style)) {
            nf.currency = currency.toUpperCase(Locale.ROOT);
            nf.currencyDisplay = currencyDisplay;
            nf.currencySign = currencySign;
        }
        if ("unit".equals(style)) {
            nf.unit = unit;
            nf.unitDisplay = unitDisplay;
        }
    }

    static boolean isWellFormedCurrencyCode(String code) {
        if (code.length() != 3) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }

    static boolean isWellFormedUnitIdentifier(String unit) {
        if (SupportedValues.UNIT_SET.contains(unit)) {
            return true;
        }
        int index = unit.indexOf("-per-");
        return index >= 0 && unit.indexOf("-per-", index + 1) < 0
                && SupportedValues.UNIT_SET.contains(unit.substring(0, index))
                && SupportedValues.UNIT_SET.contains(unit.substring(index + 5));
    }

    static String formatToString(Realm realm, JSIntlNumberFormat nf, IntlMV value) {
        return Dsl.checkedString(realm, NumberFormatIcu.format(nf, value).toString());
    }

    static JSArray formatToParts(Realm realm, JSIntlNumberFormat nf, IntlMV value) {
        FormattedValue formatted = NumberFormatIcu.format(nf, value);
        return NumberFormatIcu.partsArray(realm,
                NumberFormatIcu.parts(realm, formatted, new IntlMV[]{value}, false, "unit".equals(nf.style)));
    }

    private static JSObject resolvedOptions(Realm realm, JSIntlNumberFormat nf) {
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("locale", nf.locale);
        object.createDataPropertyOrThrow("numberingSystem", nf.numberingSystem);
        object.createDataPropertyOrThrow("style", nf.style);
        if (nf.currency != null) {
            object.createDataPropertyOrThrow("currency", nf.currency);
        }
        if (nf.currencyDisplay != null) {
            object.createDataPropertyOrThrow("currencyDisplay", nf.currencyDisplay);
        }
        if (nf.currencySign != null) {
            object.createDataPropertyOrThrow("currencySign", nf.currencySign);
        }
        if (nf.unit != null) {
            object.createDataPropertyOrThrow("unit", nf.unit);
        }
        if (nf.unitDisplay != null) {
            object.createDataPropertyOrThrow("unitDisplay", nf.unitDisplay);
        }
        nf.digits.addDigitsTo(object);
        object.createDataPropertyOrThrow("useGrouping", nf.useGrouping);
        object.createDataPropertyOrThrow("notation", nf.notation);
        if (nf.compactDisplay != null) {
            object.createDataPropertyOrThrow("compactDisplay", nf.compactDisplay);
        }
        object.createDataPropertyOrThrow("signDisplay", nf.signDisplay);
        object.createDataPropertyOrThrow("roundingIncrement", (double) nf.digits.roundingIncrement);
        object.createDataPropertyOrThrow("roundingMode", nf.digits.roundingMode);
        object.createDataPropertyOrThrow("roundingPriority", nf.digits.computedRoundingPriority);
        object.createDataPropertyOrThrow("trailingZeroDisplay", nf.digits.trailingZeroDisplay);
        return object;
    }
}

