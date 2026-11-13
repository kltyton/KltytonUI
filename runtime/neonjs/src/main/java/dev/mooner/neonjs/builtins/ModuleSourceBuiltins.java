/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.JSModuleSource;

/** %AbstractModuleSource% for source-phase imports. */
public final class ModuleSourceBuiltins {
    public static final ModuleSourceBuiltins INSTANCE = new ModuleSourceBuiltins();

    private ModuleSourceBuiltins() {
    }

    public static void install(Realm realm) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        JSObject ctor = BuiltinsKt.makeCtor(realm, "AbstractModuleSource", 0, proto,
                (function, thisArg, args, newTarget) -> {
                    throw JSException.typeError(
                            "AbstractModuleSource is an abstract class and cannot be constructed");
                });
        BuiltinsKt.getter(proto, realm, JSSymbol.toStringTag,
                (function, target, args, newTarget) ->
                        target instanceof JSModuleSource source
                                ? source.moduleSourceClassName : Undefined.INSTANCE);
        realm.getIntrinsics().put("%AbstractModuleSource%", ctor);
        realm.getIntrinsics().put("%AbstractModuleSource.prototype%", proto);
    }
}
