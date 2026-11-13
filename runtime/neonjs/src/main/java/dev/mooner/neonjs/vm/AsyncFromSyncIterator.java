package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Async-from-sync iterator protocol; derived from NeonJS and adapted to Java. */
public final class AsyncFromSyncIterator {
    private AsyncFromSyncIterator() {
    }

    public static IteratorRecord create(Realm realm, IteratorRecord sync) {
        Obj object = new Obj(realm.getAsyncFromSyncIteratorPrototype(), sync);
        Object next = realm.getAsyncFromSyncIteratorPrototype().get("next", object);
        return new IteratorRecord(object, next);
    }

    public static Object method(Realm realm, Object receiver, Object[] arguments, int which) {
        Obj object = (Obj) receiver;
        PromiseCapability capability = Promises.newPromiseCapability(realm, realm.getPromiseConstructor());
        IteratorRecord record = object.syncRec;
        JSObject sync = record.iterator;
        JSObject result;
        try {
            Object raw;
            if (which == 0) {
                raw = Ops.call(record.nextMethod, sync, arguments.length > 0 ? new Object[] {arguments[0]} : EMPTY_ARGS);
            } else if (which == 1) {
                Object method = Ops.getMethod(sync, "return");
                if (method == Undefined.INSTANCE) {
                    Ops.call(capability.resolve, Undefined.INSTANCE,
                            new Object[] {Iteration.createIterResult(realm, arg(arguments, 0), true)});
                    return capability.promise;
                }
                raw = Ops.call(method, sync, arguments.length > 0 ? new Object[] {arguments[0]} : EMPTY_ARGS);
            } else {
                Object method = Ops.getMethod(sync, "throw");
                if (method == Undefined.INSTANCE) {
                    record.done = false;
                    try {
                        Iteration.closeNormal(record);
                    } catch (JSException error) {
                        Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
                        return capability.promise;
                    }
                    Ops.call(capability.reject, Undefined.INSTANCE,
                            new Object[] {realm.newError(ErrorKind.TYPE, "The iterator does not provide a 'throw' method")});
                    return capability.promise;
                }
                raw = Ops.call(method, sync, new Object[] {arg(arguments, 0)});
            }
            if (!(raw instanceof JSObject returned)) {
                throw JSException.typeError("Iterator result " + Ops.toDisplayString(raw) + " is not an object");
            }
            result = returned;
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return capability.promise;
        }
        continuation(realm, result, capability, record, which != 1);
        return capability.promise;
    }

    private static void continuation(Realm realm, JSObject result, PromiseCapability capability,
                                     IteratorRecord record, boolean closeOnRejection) {
        boolean done;
        Object value;
        try {
            done = Ops.toBoolean(result.get("done", result));
            value = result.get("value", result);
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return;
        }
        JSObject promise;
        try {
            promise = Promises.promiseResolve(realm, realm.getPromiseConstructor(), value);
        } catch (JSException error) {
            if (!done && closeOnRejection) {
                record.done = false;
                Iteration.closeOnThrow(record);
            }
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return;
        }
        NativeFunction fulfilled = new NativeFunction(realm, "", 1,
                (function, receiver, arguments, target) -> Iteration.createIterResult(realm, arg(arguments, 0), done));
        Object rejected = done || !closeOnRejection ? Undefined.INSTANCE
                : new NativeFunction(realm, "", 1, (function, receiver, arguments, target) -> {
                    record.done = false;
                    Iteration.closeOnThrow(record);
                    throw new JSException(arg(arguments, 0));
                });
        Promises.performThen(realm, (JSPromise) promise, fulfilled, rejected, capability);
    }

    public static final class Obj extends JSObject {
        public final IteratorRecord syncRec;

        public Obj(JSObject prototype, IteratorRecord record) {
            super(prototype);
            syncRec = record;
        }
    }
}
