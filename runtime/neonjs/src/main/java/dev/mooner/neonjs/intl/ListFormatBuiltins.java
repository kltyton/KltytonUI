/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.ConstrainedFieldPosition;
import com.ibm.icu.text.FormattedValue;
import com.ibm.icu.text.ListFormatter;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Intl.ListFormat (ECMA-402 §13). */
final class ListFormatBuiltins {
    private ListFormatBuiltins() {
    }

    private static final String[] TYPES = {"conjunction", "disjunction", "unit"};
    private static final String[] STYLES = {"long", "short", "narrow"};

    static void install(Realm realm, JSObject intl) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%Intl.ListFormat.prototype%", proto);
        var constructor = Dsl.makeCtor(realm, "ListFormat", 0, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) {
                throw Dsl.typeErr("Constructor Intl.ListFormat requires 'new'");
            }
            return create(function.realm, newTarget, Values.arg(args, 0), Values.arg(args, 1));
        });
        realm.getIntrinsics().put("%Intl.ListFormat%", constructor);
        intl.defineOwn("ListFormat", constructor, Attr.WC);
        Locales.installSupportedLocalesOf(realm, constructor);
        proto.defineOwn(JSSymbol.toStringTag, "Intl.ListFormat", Attr.CONFIGURABLE);
        Dsl.method(proto, realm, "format", 1, (function, thisArg, args, newTarget) -> {
            JSIntlListFormat lf = thisListFormat(thisArg, "format");
            List<String> list = stringListFromIterable(function.realm, Values.arg(args, 0));
            return Dsl.checkedString(function.realm, formatter(lf).format(list));
        });
        Dsl.method(proto, realm, "formatToParts", 1, (function, thisArg, args, newTarget) -> {
            JSIntlListFormat lf = thisListFormat(thisArg, "formatToParts");
            List<String> list = stringListFromIterable(function.realm, Values.arg(args, 0));
            return formatToParts(function.realm, lf, list);
        });
        Dsl.method(proto, realm, "resolvedOptions", 0, (function, thisArg, args, newTarget) -> {
            JSIntlListFormat lf = thisListFormat(thisArg, "resolvedOptions");
            JSObject object = Dsl.plainObject(function.realm);
            object.createDataPropertyOrThrow("locale", lf.locale);
            object.createDataPropertyOrThrow("type", lf.type);
            object.createDataPropertyOrThrow("style", lf.style);
            return object;
        });
    }

    private static JSIntlListFormat thisListFormat(Object thisArg, String method) {
        if (thisArg instanceof JSIntlListFormat) {
            return (JSIntlListFormat) thisArg;
        }
        throw Dsl.typeErr("Method Intl.ListFormat.prototype." + method
                + " called on incompatible receiver " + Ops.describe(thisArg));
    }

    private static JSIntlListFormat create(Realm realm, JSObject newTarget, Object locales, Object optionsArg) {
        JSIntlListFormat lf = new JSIntlListFormat(
                Dsl.protoFromCtor(newTarget, "%Intl.ListFormat.prototype%", realm));
        List<String> requested = Locales.canonicalizeLocaleList(realm, locales);
        JSObject options = Opt.getOptionsObject(optionsArg);
        Opt.localeMatcher(options);
        Locales.Resolved resolved = Locales.resolve(realm, requested, List.of(), Map.of(),
                (locale, key) -> List.of());
        lf.locale = resolved.locale;
        lf.dataLocale = resolved.dataLocale;
        lf.type = Opt.str(options, "type", TYPES, "conjunction");
        lf.style = Opt.str(options, "style", STYLES, "long");
        return lf;
    }

    private static ListFormatter formatter(JSIntlListFormat lf) {
        if (lf.icu != null) {
            return lf.icu;
        }
        ListFormatter.Type type;
        switch (lf.type) {
            case "disjunction":
                type = ListFormatter.Type.OR;
                break;
            case "unit":
                type = ListFormatter.Type.UNITS;
                break;
            default:
                type = ListFormatter.Type.AND;
                break;
        }
        ListFormatter.Width width;
        switch (lf.style) {
            case "short":
                width = ListFormatter.Width.SHORT;
                break;
            case "narrow":
                width = ListFormatter.Width.NARROW;
                break;
            default:
                width = ListFormatter.Width.WIDE;
                break;
        }
        ListFormatter created;
        try {
            created = ListFormatter.getInstance(LocaleInfo.dataLocale(lf.dataLocale), type, width);
        } catch (RuntimeException invalid) {
            throw Dsl.rangeErr("Internal error in list format data");
        }
        lf.icu = created;
        return created;
    }

    /** StringListFromIterable */
    private static List<String> stringListFromIterable(Realm realm, Object iterable) {
        if (iterable == Undefined.INSTANCE) {
            return List.of();
        }
        IteratorRecord record = Iteration.getIterator(realm, iterable, false);
        List<String> list = new ArrayList<>();
        long total = 0;
        while (true) {
            Object next = Iteration.stepValue(record);
            if (next == NotFound.INSTANCE) {
                return list;
            }
            if (!(next instanceof CharSequence)) {
                JSException error = JSException.typeError("Iterable yielded "
                        + Ops.toDisplayString(next) + " which is not a string");
                Iteration.closeAndRethrow(record, error);
                throw error;
            }
            String string = next.toString();
            total += string.length();
            realm.agent.checkStringLength(total);
            list.add(string);
            Dsl.tick(realm, list.size());
        }
    }

    private static JSArray formatToParts(Realm realm, JSIntlListFormat lf, List<String> list) {
        FormattedValue formatted = formatter(lf).formatToValue(list);
        String text = formatted.toString();
        realm.agent.checkStringLength(text.length());
        List<Object> parts = new ArrayList<>();
        ConstrainedFieldPosition position = new ConstrainedFieldPosition();
        position.constrainField(ListFormatter.Field.ELEMENT);
        int pos = 0;
        long index = 0;
        while (formatted.nextPosition(position)) {
            Dsl.tick(realm, index++);
            if (position.getStart() > pos) {
                parts.add(Dsl.partObject(realm, "literal", text.substring(pos, position.getStart())));
            }
            parts.add(Dsl.partObject(realm, "element", text.substring(position.getStart(), position.getLimit())));
            pos = position.getLimit();
        }
        if (pos < text.length()) {
            parts.add(Dsl.partObject(realm, "literal", text.substring(pos)));
        }
        return Dsl.jsArray(realm, parts);
    }
}
