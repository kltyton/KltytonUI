/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Values;

/** Boolean constructor and prototype; derived from NeonJS and adapted to Java. */
final class BooleanBuiltins {
    private BooleanBuiltins() {
    }

    static void install(Realm realm) {
        JSPrimitiveWrapper proto = new JSPrimitiveWrapper(realm.objectPrototype, false);
        realm.booleanPrototype = proto;
        NativeFunction constructor = BuiltinsKt.makeCtor(realm, "Boolean", 1, proto,
                (function, thisValue, args, newTarget) -> {
                    boolean value = Ops.toBoolean(Values.arg(args, 0));
                    if (newTarget == null) return value;
                    return new JSPrimitiveWrapper(
                            Ops.getPrototypeFromConstructor(newTarget, Realm::getBooleanPrototype), value);
                });
        BuiltinsKt.global(realm, "Boolean", constructor);
        BuiltinsKt.method(proto, realm, "toString", 0, (function, thisValue, args, newTarget) ->
                thisBoolean(thisValue) ? "true" : "false");
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisValue, args, newTarget) ->
                thisBoolean(thisValue));
    }

    private static boolean thisBoolean(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof JSPrimitiveWrapper wrapper && wrapper.primitive instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw JSException.typeError("Boolean.prototype method called on incompatible receiver");
    }
}
