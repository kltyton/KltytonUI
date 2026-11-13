/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Promises;

/** DisposableStack / AsyncDisposableStack builtins. */
final class DisposableBuiltins {
    private DisposableBuiltins() {
    }

    static void install(Realm realm) {
        installStack(realm, false);
        installStack(realm, true);
    }

    private static void installStack(Realm realm, boolean isAsync) {
        String name = isAsync ? "AsyncDisposableStack" : "DisposableStack";
        DisposeHint hint = isAsync ? DisposeHint.ASYNC : DisposeHint.SYNC;
        String protoName = "%" + name + ".prototype%";
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put(protoName, proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, name, 0, proto,
                (function, thisArg, args, newTarget) -> {
                    if (newTarget == null) {
                        throw JSException.typeError("Constructor " + name + " requires 'new'");
                    }
                    return new JSDisposableStack(Ops.getPrototypeFromConstructor(newTarget,
                            r -> r.intrinsic(protoName)), isAsync);
                });
        realm.getIntrinsics().put("%" + name + "%", ctor);
        BuiltinsKt.global(realm, name, ctor);

        String onDisposeName = isAsync ? "onDisposeAsync" : "onDispose";

        BuiltinsKt.method(proto, realm, "adopt", 2, (function, target, args, newTarget) -> {
            JSDisposableStack stack = pendingStack(target, name, isAsync, "adopt");
            Object value = Values.arg(args, 0);
            Object onDispose = Values.arg(args, 1);
            if (!Ops.isCallable(onDispose)) {
                throw JSException.typeError(onDisposeName + " " + Ops.describe(onDispose)
                        + " is not a function");
            }
            NativeFunction closure = new NativeFunction(function.realm, "", 0,
                    (fn, thisArg, callArgs, callNewTarget) ->
                            Ops.call(onDispose, Undefined.INSTANCE, new Object[]{value}));
            stack.capability.addMethod(hint, closure);
            return value;
        });
        BuiltinsKt.method(proto, realm, "defer", 1, (function, target, args, newTarget) -> {
            JSDisposableStack stack = pendingStack(target, name, isAsync, "defer");
            Object onDispose = Values.arg(args, 0);
            if (!Ops.isCallable(onDispose)) {
                throw JSException.typeError(onDisposeName + " " + Ops.describe(onDispose)
                        + " is not a function");
            }
            stack.capability.addMethod(hint, onDispose);
            return Undefined.INSTANCE;
        });
        NativeFunction dispose;
        if (isAsync) {
            dispose = BuiltinsKt.method(proto, realm, "disposeAsync", 0,
                    (function, target, args, newTarget) -> {
                        JSPromise promise = Promises.newPromise(function.realm);
                        if (!(target instanceof JSDisposableStack stack) || !stack.isAsync) {
                            Promises.rejectPromise(function.realm, promise, function.realm.newError(
                                    ErrorKind.TYPE, "Method " + name
                                            + ".prototype.disposeAsync called on incompatible receiver "
                                            + Ops.describe(target)));
                        } else if (stack.disposed) {
                            Promises.resolvePromise(function.realm, promise, Undefined.INSTANCE);
                        } else {
                            stack.disposed = true;
                            stack.capability.disposeAllAsync(function.realm, null, error -> {
                                if (error == null) {
                                    Promises.resolvePromise(function.realm, promise, Undefined.INSTANCE);
                                } else {
                                    Promises.rejectPromise(function.realm, promise,
                                            DisposeCapability.thrownValue(function.realm, error));
                                }
                            });
                        }
                        return promise;
                    });
        } else {
            dispose = BuiltinsKt.method(proto, realm, "dispose", 0,
                    (function, target, args, newTarget) -> {
                        JSDisposableStack stack = thisStack(target, name, isAsync, "dispose");
                        if (!stack.disposed) {
                            stack.disposed = true;
                            stack.capability.disposeAll(function.realm);
                        }
                        return Undefined.INSTANCE;
                    });
        }
        BuiltinsKt.getter(proto, realm, "disposed",
                (function, target, args, newTarget) ->
                        thisStack(target, name, isAsync, "disposed").disposed);
        BuiltinsKt.method(proto, realm, "move", 0, (function, target, args, newTarget) -> {
            JSDisposableStack stack = pendingStack(target, name, isAsync, "move");
            JSDisposableStack moved = new JSDisposableStack(function.realm.intrinsic(protoName), isAsync);
            moved.capability = stack.capability;
            stack.capability = new DisposeCapability();
            stack.disposed = true;
            return moved;
        });
        BuiltinsKt.method(proto, realm, "use", 1, (function, target, args, newTarget) -> {
            JSDisposableStack stack = pendingStack(target, name, isAsync, "use");
            Object value = Values.arg(args, 0);
            stack.capability.add(function.realm, value, hint);
            return value;
        });
        proto.defineOwn(isAsync ? JSSymbol.asyncDispose : JSSymbol.dispose, dispose, Attr.WC);
        BuiltinsKt.value(proto, JSSymbol.toStringTag, name, Attr.CONFIGURABLE);
    }

    private static JSDisposableStack thisStack(Object target, String name, boolean isAsync, String method) {
        if (target instanceof JSDisposableStack stack && stack.isAsync == isAsync) return stack;
        throw JSException.typeError("Method " + name + ".prototype." + method
                + " called on incompatible receiver " + Ops.describe(target));
    }

    private static JSDisposableStack pendingStack(Object target, String name, boolean isAsync, String method) {
        JSDisposableStack stack = thisStack(target, name, isAsync, method);
        if (stack.disposed) {
            throw JSException.referenceError("Cannot call " + name + ".prototype." + method
                    + " on a disposed " + name);
        }
        return stack;
    }
}
