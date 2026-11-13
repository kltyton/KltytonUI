/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSFunction;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;

/**
 * Wrapped function exotic object: a callable from another realm. Only primitives and (wrapped) callables cross the
 * boundary; any exception becomes a TypeError of the caller's realm.
 */
public final class WrappedFunction extends JSFunction {
    public final JSObject wrappedTarget;

    public WrappedFunction(Realm callerRealm, JSObject wrappedTarget) {
        super(callerRealm, callerRealm.getFunctionPrototype());
        this.wrappedTarget = wrappedTarget;
    }

    @Override
    public Object call(Object thisArg, Object[] args) {
        Realm callerRealm = realm;
        Realm targetRealm;
        try {
            targetRealm = Ops.getFunctionRealm(wrappedTarget);
        } catch (Exception error) {
            throw ShadowRealms.typeError(callerRealm, error, "Wrapped function target is not available");
        }
        Object[] wrappedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            wrappedArgs[i] = ShadowRealms.wrap(targetRealm, args[i], callerRealm);
        }
        Object wrappedThis = ShadowRealms.wrap(targetRealm, thisArg, callerRealm);
        Object result;
        try {
            result = Ops.call(wrappedTarget, wrappedThis, wrappedArgs);
        } catch (Exception error) {
            throw ShadowRealms.typeError(callerRealm, error, "Wrapped function threw an exception");
        }
        return ShadowRealms.wrap(callerRealm, result);
    }

    @Override
    public String sourceText() {
        return "function () { [native code] }";
    }
}
