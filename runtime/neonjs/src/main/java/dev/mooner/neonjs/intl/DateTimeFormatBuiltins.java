/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.builtins.JSDate;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

/** Intl.DateTimeFormat (ECMA-402 section 11) and Date.prototype.toLocale{,Date,Time}String (section 21.4). */
final class DateTimeFormatBuiltins {
    private DateTimeFormatBuiltins() {
    }

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.DateTimeFormat.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "DateTimeFormat", 0, proto,
                (function, thisArg, args, newTarget) -> {
                    JSIntlDateTimeFormat dtf = DtfCreate.create(function.realm,
                            newTarget != null ? newTarget : function,
                            Values.arg(args, 0), Values.arg(args, 1), "any", "date");
                    // ChainDateTimeFormat (normative optional legacy constructor behaviour)
                    if (newTarget == null && thisArg instanceof JSObject
                            && Ops.ordinaryHasInstance(function, thisArg)) {
                        ((JSObject) thisArg).definePropertyOrThrow(IntlState.of(function.realm).fallbackSymbol,
                                Dsl.data(dtf, Attr.NONE));
                        return thisArg;
                    }
                    return dtf;
                });
        realm.getIntrinsics().put("%Intl.DateTimeFormat%", constructor);
        intl.defineOwn("DateTimeFormat", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.DateTimeFormat", Attr.CONFIGURABLE);

        Dsl.getter(proto, realm, "format", (function, thisArg, args, newTarget) -> {
            JSIntlDateTimeFormat dtf = unwrap(function.realm, thisArg, "format");
            if (dtf.boundFormat == null) {
                NativeFunction bound = new NativeFunction(function.realm, "", 1, (bf, t, a, nt) -> {
                    Object date = Values.arg(a, 0);
                    Object value = date == Undefined.INSTANCE
                            ? (Object) bf.realm.agent.currentTimeMillis()
                            : DtfFormatting.toFormattable(date);
                    return DtfFormatting.format(bf.realm, dtf, value);
                }, false, function.realm.getFunctionPrototype(), null);
                dtf.boundFormat = bound;
            }
            return dtf.boundFormat;
        });
        Dsl.method(proto, realm, "formatToParts", 1, (function, thisArg, args, newTarget) -> {
            JSIntlDateTimeFormat dtf = thisDtf(thisArg, "formatToParts");
            Object date = Values.arg(args, 0);
            Object value = date == Undefined.INSTANCE
                    ? (Object) function.realm.agent.currentTimeMillis()
                    : DtfFormatting.toFormattable(date);
            DtfValue formatted = DtfFormatting.handle(dtf, value);
            return DtfFormatting.partsArray(function.realm, DtfFormatting.partsOf(dtf, formatted), false);
        });
        Dsl.method(proto, realm, "formatRange", 2, (function, thisArg, args, newTarget) -> {
            JSIntlDateTimeFormat dtf = thisDtf(thisArg, "formatRange");
            Object[] range = rangeArgs(args);
            StringBuilder builder = new StringBuilder();
            for (DtfFormatting.Part part : DtfFormatting.rangeParts(dtf, range[0], range[1])) {
                builder.append(part.value);
            }
            return Dsl.checkedString(function.realm, builder);
        });
        Dsl.method(proto, realm, "formatRangeToParts", 2, (function, thisArg, args, newTarget) -> {
            JSIntlDateTimeFormat dtf = thisDtf(thisArg, "formatRangeToParts");
            Object[] range = rangeArgs(args);
            return DtfFormatting.partsArray(function.realm,
                    DtfFormatting.rangeParts(dtf, range[0], range[1]), true);
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) ->
                resolvedOptions(function.realm, unwrap(function.realm, thisArg, "resolvedOptions")));

        installDateMethods(realm);
    }

    private static Object[] rangeArgs(Object[] args) {
        Object start = Values.arg(args, 0);
        Object end = Values.arg(args, 1);
        if (start == Undefined.INSTANCE || end == Undefined.INSTANCE) {
            throw Dsl.typeErr("startDate and endDate are required");
        }
        return new Object[]{DtfFormatting.toFormattable(start), DtfFormatting.toFormattable(end)};
    }

    private static JSIntlDateTimeFormat thisDtf(Object thisArg, String method) {
        if (thisArg instanceof JSIntlDateTimeFormat) {
            return (JSIntlDateTimeFormat) thisArg;
        }
        throw Dsl.typeErr("Method Intl.DateTimeFormat.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    /** UnwrapDateTimeFormat followed by the {@code [[InitializedDateTimeFormat]]} check. */
    private static JSIntlDateTimeFormat unwrap(Realm realm, Object thisArg, String method) {
        if (!(thisArg instanceof JSObject)) {
            throw Dsl.typeErr("Method Intl.DateTimeFormat.prototype." + method
                    + " called on incompatible receiver " + Ops.describe(thisArg));
        }
        if (thisArg instanceof JSIntlDateTimeFormat) {
            return (JSIntlDateTimeFormat) thisArg;
        }
        JSObject object = (JSObject) thisArg;
        JSObject constructor = realm.intrinsic("%Intl.DateTimeFormat%");
        if (Ops.ordinaryHasInstance(constructor, object)) {
            Object value = object.get(IntlState.of(realm).fallbackSymbol, object);
            if (value instanceof JSIntlDateTimeFormat) {
                return (JSIntlDateTimeFormat) value;
            }
        }
        throw Dsl.typeErr("Method Intl.DateTimeFormat.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSObject resolvedOptions(Realm realm, JSIntlDateTimeFormat dtf) {
        JSObject object = Dsl.plainObject(realm);
        object.createDataPropertyOrThrow("locale", dtf.locale);
        object.createDataPropertyOrThrow("calendar", dtf.calendar);
        object.createDataPropertyOrThrow("numberingSystem", dtf.numberingSystem);
        object.createDataPropertyOrThrow("timeZone", dtf.timeZone);
        String hourCycle = dtf.hourCycle;
        if (hourCycle != null) {
            object.createDataPropertyOrThrow("hourCycle", hourCycle);
            object.createDataPropertyOrThrow("hour12", "h11".equals(hourCycle) || "h12".equals(hourCycle));
        }
        if (dtf.dateStyle == null && dtf.timeStyle == null) {
            for (var entry : dtf.format.fields.entrySet()) {
                object.createDataPropertyOrThrow(entry.getKey(), "fractionalSecondDigits".equals(entry.getKey())
                        ? (Object) Double.parseDouble(entry.getValue()) : entry.getValue());
            }
        }
        if (dtf.dateStyle != null) {
            object.createDataPropertyOrThrow("dateStyle", dtf.dateStyle);
        }
        if (dtf.timeStyle != null) {
            object.createDataPropertyOrThrow("timeStyle", dtf.timeStyle);
        }
        return object;
    }

    // ------------------------------------------------------------------ Date.prototype

    private static void installDateMethods(Realm realm) {
        JSObject datePrototype = realm.getIntrinsics().get("%Date.prototype%");
        if (datePrototype == null) {
            return;
        }
        Dsl.method(datePrototype, realm, "toLocaleString", 0, (function, thisArg, args, newTarget) ->
                dateToLocale(function.realm, thisArg, args, "any", "all", "toLocaleString"));
        Dsl.method(datePrototype, realm, "toLocaleDateString", 0, (function, thisArg, args, newTarget) ->
                dateToLocale(function.realm, thisArg, args, "date", "date", "toLocaleDateString"));
        Dsl.method(datePrototype, realm, "toLocaleTimeString", 0, (function, thisArg, args, newTarget) ->
                dateToLocale(function.realm, thisArg, args, "time", "time", "toLocaleTimeString"));
    }

    private static String dateToLocale(Realm realm, Object thisArg, Object[] args, String required,
                                       String defaults, String method) {
        if (!(thisArg instanceof JSDate)) {
            throw Dsl.typeErr("Date.prototype." + method + " called on incompatible receiver "
                    + Ops.toDisplayString(thisArg));
        }
        double time = ((JSDate) thisArg).timeValue;
        if (Double.isNaN(time)) {
            return "Invalid Date";
        }
        Object locales = Values.arg(args, 0);
        Object options = Values.arg(args, 1);
        JSObject constructor = realm.intrinsic("%Intl.DateTimeFormat%");
        JSIntlDateTimeFormat dtf;
        if (locales == Undefined.INSTANCE && options == Undefined.INSTANCE) {
            dtf = IntlState.of(realm).cached("dtf:" + required + ":" + defaults,
                    () -> DtfCreate.create(realm, constructor, Undefined.INSTANCE, Undefined.INSTANCE,
                            required, defaults));
        } else {
            dtf = DtfCreate.create(realm, constructor, locales, options, required, defaults);
        }
        return DtfFormatting.format(realm, dtf, time);
    }
}

