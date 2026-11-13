/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.util.Map;

/** Symbol constructor and prototype; derived from NeonJS and adapted to Java. */
final class SymbolBuiltins {
    private SymbolBuiltins() {
    }

    static void install(Realm realm) {
        JSObject proto = new JSObject(realm.objectPrototype);
        realm.symbolPrototype = proto;
        NativeFunction constructor = BuiltinsKt.makeCtor(realm, "Symbol", 0, proto,
                (function, thisValue, args, newTarget) -> {
                    if (newTarget != null) throw BuiltinsKt.typeErr("Symbol is not a constructor");
                    Object description = Values.arg(args, 0);
                    return new JSSymbol(description == Undefined.INSTANCE ? null : Ops.toString(description));
                });
        BuiltinsKt.global(realm, "Symbol", constructor);
        for (Map.Entry<String, JSSymbol> entry : JSSymbol.wellKnown) {
            constructor.defineOwn(entry.getKey(), entry.getValue(), Attr.NONE);
        }
        BuiltinsKt.method(constructor, realm, "for", 1, (function, thisValue, args, newTarget) -> {
            String key = Ops.toString(Values.arg(args, 0));
            return function.realm.agent.symbolRegistry.computeIfAbsent(key, k -> new JSSymbol(k, k));
        });
        BuiltinsKt.method(constructor, realm, "keyFor", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (!(value instanceof JSSymbol symbol)) {
                throw BuiltinsKt.typeErr(Ops.toDisplayString(value) + " is not a symbol");
            }
            return symbol.getRegistryKey() == null ? Undefined.INSTANCE : symbol.getRegistryKey();
        });
        BuiltinsKt.getter(proto, realm, "description", (function, thisValue, args, newTarget) -> {
            String description = thisSymbol(thisValue).getDescription();
            return description == null ? Undefined.INSTANCE : description;
        });
        BuiltinsKt.method(proto, realm, "toString", 0, (function, thisValue, args, newTarget) ->
                thisSymbol(thisValue).toString());
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisValue, args, newTarget) ->
                thisSymbol(thisValue));
        BuiltinsKt.method(proto, realm, JSSymbol.toPrimitive, 1, Attr.CONFIGURABLE,
                (function, thisValue, args, newTarget) -> thisSymbol(thisValue));
        BuiltinsKt.value(proto, JSSymbol.toStringTag, "Symbol", Attr.CONFIGURABLE);
    }

    private static JSSymbol thisSymbol(Object value) {
        if (value instanceof JSSymbol symbol) return symbol;
        if (value instanceof JSPrimitiveWrapper wrapper && wrapper.primitive instanceof JSSymbol symbol) {
            return symbol;
        }
        throw JSException.typeError("Symbol.prototype method called on incompatible receiver");
    }
}
