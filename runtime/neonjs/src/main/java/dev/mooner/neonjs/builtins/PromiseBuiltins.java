/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.PromiseCapability;
import dev.mooner.neonjs.vm.Promises;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Promise constructor, combinators and prototype (@@species); derived from NeonJS and adapted to Java. */
final class PromiseBuiltins {
    private PromiseBuiltins() {
    }

    static void install(Realm realm) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.setPromisePrototype(proto);
        NativeFunction ctor = BuiltinsKt.makeCtor(realm, "Promise", 1, proto, (function, thisArg, args, newTarget) -> {
            if (newTarget == null) throw BuiltinsKt.typeErr("Promise constructor cannot be invoked without 'new'");
            Object executor = Values.arg(args, 0);
            if (!Ops.isCallable(executor)) {
                throw BuiltinsKt.typeErr("Promise resolver " + Ops.describe(executor) + " is not a function");
            }
            JSPromise promise = new JSPromise(Ops.getPrototypeFromConstructor(newTarget, Realm::getPromisePrototype));
            Promises.ResolvingFunctions functions =
                    Promises.createResolvingFunctions(function.realm, promise);
            try {
                ((JSObject) executor).call(Undefined.INSTANCE,
                        new Object[] {functions.resolve(), functions.reject()});
            } catch (JSException error) {
                functions.reject().call(Undefined.INSTANCE, new Object[] {error.value});
            }
            return promise;
        });
        realm.setPromiseConstructor(ctor);
        BuiltinsKt.global(realm, "Promise", ctor);
        BuiltinsKt.getter(ctor, realm, JSSymbol.species, (function, thisArg, args, newTarget) -> thisArg);

        BuiltinsKt.method(ctor, realm, "all", 1, (function, thisArg, args, newTarget) ->
                combinator(function.realm, thisArg, Values.arg(args, 0), 0));
        BuiltinsKt.method(ctor, realm, "allSettled", 1, (function, thisArg, args, newTarget) ->
                combinator(function.realm, thisArg, Values.arg(args, 0), 1));
        BuiltinsKt.method(ctor, realm, "allKeyed", 1, (function, thisArg, args, newTarget) ->
                keyedCombinator(function.realm, thisArg, Values.arg(args, 0), false));
        BuiltinsKt.method(ctor, realm, "allSettledKeyed", 1, (function, thisArg, args, newTarget) ->
                keyedCombinator(function.realm, thisArg, Values.arg(args, 0), true));
        BuiltinsKt.method(ctor, realm, "any", 1, (function, thisArg, args, newTarget) ->
                combinator(function.realm, thisArg, Values.arg(args, 0), 2));
        BuiltinsKt.method(ctor, realm, "race", 1, (function, thisArg, args, newTarget) ->
                combinator(function.realm, thisArg, Values.arg(args, 0), 3));
        BuiltinsKt.method(ctor, realm, "reject", 1, (function, thisArg, args, newTarget) -> {
            PromiseCapability capability = Promises.newPromiseCapability(function.realm, thisArg);
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {Values.arg(args, 0)});
            return capability.promise;
        });
        BuiltinsKt.method(ctor, realm, "resolve", 1, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSObject constructor)) {
                throw BuiltinsKt.typeErr("PromiseResolve called on non-object");
            }
            return Promises.promiseResolve(function.realm, constructor, Values.arg(args, 0));
        });
        BuiltinsKt.method(ctor, realm, "withResolvers", 0, (function, thisArg, args, newTarget) -> {
            PromiseCapability capability = Promises.newPromiseCapability(function.realm, thisArg);
            JSObject result = new JSObject(function.realm.getObjectPrototype());
            result.createDataPropertyOrThrow("promise", capability.promise);
            result.createDataPropertyOrThrow("resolve", capability.resolve);
            result.createDataPropertyOrThrow("reject", capability.reject);
            return result;
        });
        BuiltinsKt.method(ctor, realm, "try", 1, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSObject constructor)) {
                throw BuiltinsKt.typeErr("Promise.try called on non-object");
            }
            Object result;
            try {
                result = Ops.call(Values.arg(args, 0), Undefined.INSTANCE,
                        args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : Values.EMPTY_ARGS);
            } catch (JSException error) {
                PromiseCapability capability = Promises.newPromiseCapability(function.realm, constructor);
                Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
                return capability.promise;
            }
            // a returned promise of the same constructor is not wrapped
            return Promises.promiseResolve(function.realm, constructor, result);
        });

        BuiltinsKt.method(proto, realm, "then", 2, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSPromise promise)) {
                throw BuiltinsKt.typeErr("Method Promise.prototype.then called on incompatible receiver "
                        + Ops.describe(thisArg));
            }
            JSObject constructor = Ops.speciesConstructor(promise, function.realm.getPromiseConstructor());
            PromiseCapability capability = Promises.newPromiseCapability(function.realm, constructor);
            return Promises.performThen(function.realm, promise, Values.arg(args, 0), Values.arg(args, 1),
                    capability);
        });
        BuiltinsKt.method(proto, realm, "catch", 1, (function, thisArg, args, newTarget) ->
                Ops.invoke(thisArg, "then", new Object[] {Undefined.INSTANCE, Values.arg(args, 0)}));
        BuiltinsKt.method(proto, realm, "finally", 1, (function, thisArg, args, newTarget) -> {
            if (!(thisArg instanceof JSObject object)) {
                throw BuiltinsKt.typeErr("Promise.prototype.finally called on non-object");
            }
            JSObject constructor = Ops.speciesConstructor(object, function.realm.getPromiseConstructor());
            Object onFinally = Values.arg(args, 0);
            Object thenFinally;
            Object catchFinally;
            if (!Ops.isCallable(onFinally)) {
                thenFinally = onFinally;
                catchFinally = onFinally;
            } else {
                thenFinally = new NativeFunction(function.realm, "", 1, (ff, receiver, a, nt) -> {
                    Object value = Values.arg(a, 0);
                    Object result = ((JSObject) onFinally).call(Undefined.INSTANCE, Values.EMPTY_ARGS);
                    JSObject promise = Promises.promiseResolve(ff.realm, constructor, result);
                    NativeFunction valueThunk = new NativeFunction(ff.realm, "", 0, (f2, r2, a2, n2) -> value);
                    return Ops.invoke(promise, "then", new Object[] {valueThunk});
                });
                catchFinally = new NativeFunction(function.realm, "", 1, (ff, receiver, a, nt) -> {
                    Object reason = Values.arg(a, 0);
                    Object result = ((JSObject) onFinally).call(Undefined.INSTANCE, Values.EMPTY_ARGS);
                    JSObject promise = Promises.promiseResolve(ff.realm, constructor, result);
                    NativeFunction thrower = new NativeFunction(ff.realm, "", 0, (f2, r2, a2, n2) -> {
                        throw new JSException(reason);
                    });
                    return Ops.invoke(promise, "then", new Object[] {thrower});
                });
            }
            return Ops.invoke(thisArg, "then", new Object[] {thenFinally, catchFinally});
        });
        BuiltinsKt.value(proto, JSSymbol.toStringTag, "Promise", Attr.CONFIGURABLE);
    }

    /** kind: 0 all, 1 allSettled, 2 any, 3 race */
    private static Object combinator(Realm realm, Object constructorValue, Object iterable, int kind) {
        PromiseCapability capability = Promises.newPromiseCapability(realm, constructorValue);
        JSObject constructor = (JSObject) constructorValue;
        Object promiseResolve;
        try {
            promiseResolve = constructor.get("resolve", constructor);
            if (!Ops.isCallable(promiseResolve)) {
                throw BuiltinsKt.typeErr("Promise resolve is not a function");
            }
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return capability.promise;
        }
        IteratorRecord record;
        try {
            record = Iteration.getIterator(realm, iterable, false);
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return capability.promise;
        }
        try {
            return performCombinator(realm, record, constructor, capability, promiseResolve, kind);
        } catch (JSException error) {
            if (!record.done) Iteration.closeOnThrow(record);
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return capability.promise;
        }
    }

    /** Promise.allKeyed ([settled] false) and Promise.allSettledKeyed ([settled] true), proposal-await-dictionary. */
    private static Object keyedCombinator(Realm realm, Object constructorValue, Object promises, boolean settled) {
        PromiseCapability capability = Promises.newPromiseCapability(realm, constructorValue);
        JSObject constructor = (JSObject) constructorValue;
        Object promiseResolve;
        try {
            promiseResolve = constructor.get("resolve", constructor);
            if (!Ops.isCallable(promiseResolve)) {
                throw BuiltinsKt.typeErr("Promise resolve is not a function");
            }
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
            return capability.promise;
        }
        if (!(promises instanceof JSObject object)) {
            String name = settled ? "allSettledKeyed" : "allKeyed";
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {
                    realm.newError(ErrorKind.TYPE, "Promise." + name + " requires an object argument")});
            return capability.promise;
        }
        try {
            performKeyed(realm, object, constructor, capability, promiseResolve, settled);
        } catch (JSException error) {
            Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error.value});
        }
        return capability.promise;
    }

    /** Shared state of one PerformPromiseAllKeyed: the entries list and {@code [[RemainingElements]]}. */
    private static final class KeyedState {
        final PromiseCapability capability;
        final ArrayList<Object> keys = new ArrayList<>();
        final ArrayList<Object> values = new ArrayList<>();
        int remaining = 1;

        KeyedState(PromiseCapability capability) {
            this.capability = capability;
        }

        /** Decrements {@code [[RemainingElements]]}; at zero resolves with CreateKeyedPromiseCombinatorResultObject(entries). */
        Object elementDone() {
            if (--remaining != 0) return Undefined.INSTANCE;
            JSObject result = new JSObject(null);
            for (int i = 0; i < keys.size(); i++) result.createDataPropertyOrThrow(keys.get(i), values.get(i));
            return Ops.call(capability.resolve, Undefined.INSTANCE, new Object[] {result});
        }
    }

    /** PerformPromiseAllKeyed(variant, promises, ctor, resultCapability, promiseResolve) */
    private static void performKeyed(Realm realm, JSObject promises, JSObject constructor,
                                     PromiseCapability capability, Object promiseResolve, boolean settled) {
        KeyedState state = new KeyedState(capability);
        int steps = 0;
        for (Object key : promises.ownPropertyKeys()) {
            if ((++steps & 1023) == 0) realm.agent.checkInterrupt();
            PropertyDescriptor descriptor = promises.getOwnProperty(key);
            if (descriptor == null) continue;
            if (!descriptor.enumerable) continue;
            Object propertyValue = promises.get(key, promises);
            int index = state.keys.size();
            state.keys.add(key);
            state.values.add(Undefined.INSTANCE);
            Object nextPromise = Ops.call(promiseResolve, constructor, new Object[] {propertyValue});
            boolean[] alreadyCalled = new boolean[1];
            Object onFulfilled = keyedElement(realm, state, index, alreadyCalled,
                    settled ? "fulfilled" : null);
            Object onRejected = settled
                    ? keyedElement(realm, state, index, alreadyCalled, "rejected") : capability.reject;
            state.remaining++;
            Ops.invoke(nextPromise, "then", new Object[] {onFulfilled, onRejected});
        }
        state.elementDone();
    }

    /**
     * A keyed combinator element function: stores the value (or, with a {@code status}, a settlement record) at
     * {@code index} once, sharing {@code alreadyCalled} with its sibling.
     */
    private static NativeFunction keyedElement(Realm realm, KeyedState state, int index,
                                               boolean[] alreadyCalled, String status) {
        return new NativeFunction(realm, "", 1, (function, thisArg, args, newTarget) -> {
            if (alreadyCalled[0]) return Undefined.INSTANCE;
            alreadyCalled[0] = true;
            Object value = Values.arg(args, 0);
            if (status == null) {
                state.values.set(index, value);
            } else {
                JSObject record = new JSObject(function.realm.getObjectPrototype());
                record.createDataPropertyOrThrow("status", status);
                record.createDataPropertyOrThrow("fulfilled".equals(status) ? "value" : "reason", value);
                state.values.set(index, record);
            }
            return state.elementDone();
        });
    }

    private static Object performCombinator(Realm realm, IteratorRecord record, JSObject constructor,
                                            PromiseCapability capability, Object promiseResolve, int kind) {
        ArrayList<Object> values = new ArrayList<>();
        int[] remaining = new int[] {1};
        int index = 0;
        while (true) {
            Object next = Iteration.stepValue(record);
            if (next == NotFound.INSTANCE) {
                if (kind != 3) {
                    remaining[0]--;
                    if (remaining[0] == 0) finishCombinator(realm, kind, values, capability);
                }
                return capability.promise;
            }
            if (kind != 3) values.add(Undefined.INSTANCE);
            Object nextPromise = Ops.call(promiseResolve, constructor, new Object[] {next});
            int i = index;
            switch (kind) {
                case 0 -> {
                    boolean[] called = new boolean[1];
                    NativeFunction onFulfilled = new NativeFunction(realm, "", 1,
                            (function, thisArg, args, newTarget) -> {
                                if (!called[0]) {
                                    called[0] = true;
                                    values.set(i, Values.arg(args, 0));
                                    remaining[0]--;
                                    if (remaining[0] == 0) finishCombinator(realm, kind, values, capability);
                                }
                                return Undefined.INSTANCE;
                            });
                    remaining[0]++;
                    Ops.invoke(nextPromise, "then", new Object[] {onFulfilled, capability.reject});
                }
                case 1 -> {
                    boolean[] called = new boolean[1];
                    remaining[0]++;
                    Ops.invoke(nextPromise, "then", new Object[] {
                            settleElement(realm, "fulfilled", "value", called, values, i, remaining, kind,
                                    capability),
                            settleElement(realm, "rejected", "reason", called, values, i, remaining, kind,
                                    capability)});
                }
                case 2 -> {
                    boolean[] called = new boolean[1];
                    NativeFunction onRejected = new NativeFunction(realm, "", 1,
                            (function, thisArg, args, newTarget) -> {
                                if (!called[0]) {
                                    called[0] = true;
                                    values.set(i, Values.arg(args, 0));
                                    remaining[0]--;
                                    if (remaining[0] == 0) finishCombinator(realm, kind, values, capability);
                                }
                                return Undefined.INSTANCE;
                            });
                    remaining[0]++;
                    Ops.invoke(nextPromise, "then", new Object[] {capability.resolve, onRejected});
                }
                default -> Ops.invoke(nextPromise, "then", new Object[] {capability.resolve, capability.reject});
            }
            index++;
        }
    }

    private static NativeFunction settleElement(Realm realm, String status, String key, boolean[] called,
                                                ArrayList<Object> values, int index, int[] remaining, int kind,
                                                PromiseCapability capability) {
        return new NativeFunction(realm, "", 1, (function, thisArg, args, newTarget) -> {
            if (!called[0]) {
                called[0] = true;
                JSObject record = new JSObject(function.realm.getObjectPrototype());
                record.createDataPropertyOrThrow("status", status);
                record.createDataPropertyOrThrow(key, Values.arg(args, 0));
                values.set(index, record);
                remaining[0]--;
                if (remaining[0] == 0) finishCombinator(realm, kind, values, capability);
            }
            return Undefined.INSTANCE;
        });
    }

    private static void finishCombinator(Realm realm, int kind, List<Object> values,
                                         PromiseCapability capability) {
        switch (kind) {
            case 0, 1 -> Ops.call(capability.resolve, Undefined.INSTANCE,
                    new Object[] {Builtins.arrayOf(realm, values)});
            case 2 -> {
                JSErrorObject error = realm.newError(ErrorKind.AGGREGATE, "All promises were rejected");
                error.defineOwn("errors", Builtins.arrayOf(realm, values), Attr.WC);
                Ops.call(capability.reject, Undefined.INSTANCE, new Object[] {error});
            }
            default -> {
            }
        }
    }
}
