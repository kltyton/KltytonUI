package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import java.util.function.Function;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Promise resolution and reaction jobs; derived from NeonJS and adapted to Java. */
public final class Promises {
    private Promises() {
    }

    public static JSPromise newPromise(Realm realm) {
        return new JSPromise(realm.getPromisePrototype());
    }

    public static JSPromise rejected(Realm realm, Object reason) {
        JSPromise promise = newPromise(realm);
        rejectPromise(realm, promise, reason);
        return promise;
    }

    public static JSPromise resolved(Realm realm, Object value) {
        JSPromise promise = newPromise(realm);
        resolvePromise(realm, promise, value);
        return promise;
    }

    public static ResolvingFunctions createResolvingFunctions(Realm realm, JSPromise promise) {
        boolean[] alreadyResolved = new boolean[1];
        NativeFunction resolve = new NativeFunction(realm, "", 1, (function, receiver, arguments, target) -> {
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true;
                resolvePromise(function.realm, promise, arg(arguments, 0));
            }
            return Undefined.INSTANCE;
        });
        NativeFunction reject = new NativeFunction(realm, "", 1, (function, receiver, arguments, target) -> {
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true;
                rejectPromise(function.realm, promise, arg(arguments, 0));
            }
            return Undefined.INSTANCE;
        });
        return new ResolvingFunctions(resolve, reject);
    }

    public static void resolvePromise(Realm realm, JSPromise promise, Object resolution) {
        if (resolution == promise) {
            rejectPromise(realm, promise, realm.newError(ErrorKind.TYPE, "Chaining cycle detected for promise"));
            return;
        }
        if (!(resolution instanceof JSObject object)) {
            fulfillPromise(realm, promise, resolution);
            return;
        }
        Object then;
        try {
            then = object.get("then", object);
        } catch (JSException error) {
            rejectPromise(realm, promise, error.value);
            return;
        }
        if (!Ops.isCallable(then)) {
            fulfillPromise(realm, promise, resolution);
            return;
        }
        realm.agent.enqueueJob(() -> {
            ResolvingFunctions functions = createResolvingFunctions(realm, promise);
            try {
                ((JSObject) then).call(resolution, new Object[] {functions.resolve(), functions.reject()});
            } catch (JSException error) {
                functions.reject().call(Undefined.INSTANCE, new Object[] {error.value});
            } catch (StackOverflowError error) {
                functions.reject().call(Undefined.INSTANCE,
                        new Object[] {realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded")});
            }
        });
    }

    public static void fulfillPromise(Realm realm, JSPromise promise, Object value) {
        if (promise.state != JSPromise.PENDING) return;
        ArrayList<PromiseReaction> reactions = promise.fulfillReactions;
        promise.result = value;
        promise.fulfillReactions = null;
        promise.rejectReactions = null;
        promise.state = JSPromise.FULFILLED;
        if (reactions != null) for (PromiseReaction reaction : reactions) enqueueReaction(realm, reaction, value);
    }

    public static void rejectPromise(Realm realm, JSPromise promise, Object reason) {
        if (promise.state != JSPromise.PENDING) return;
        ArrayList<PromiseReaction> reactions = promise.rejectReactions;
        promise.result = reason;
        promise.fulfillReactions = null;
        promise.rejectReactions = null;
        promise.state = JSPromise.REJECTED;
        if (!promise.isHandled) realm.agent.trackRejection(promise, false);
        if (reactions != null) for (PromiseReaction reaction : reactions) enqueueReaction(realm, reaction, reason);
    }

    private static void enqueueReaction(Realm realm, PromiseReaction reaction, Object argument) {
        realm.agent.enqueueJob(() -> runReaction(realm, reaction, argument));
    }

    private static void runReaction(Realm realm, PromiseReaction reaction, Object argument) {
        boolean success = true;
        Object value;
        try {
            if (reaction.hostHandler != null) {
                value = reaction.hostHandler.apply(argument);
            } else if (reaction.handler == Undefined.INSTANCE || reaction.handler == null) {
                if (!reaction.isFulfill) success = false;
                value = argument;
            } else {
                value = ((JSObject) reaction.handler).call(Undefined.INSTANCE, new Object[] {argument});
            }
        } catch (JSException error) {
            success = false;
            value = error.value;
        } catch (StackOverflowError error) {
            success = false;
            value = realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded");
        }
        PromiseCapability capability = reaction.capability;
        if (capability == null) return;
        Ops.call(success ? capability.resolve : capability.reject, Undefined.INSTANCE, new Object[] {value});
    }

    public static PromiseCapability newPromiseCapability(Realm realm, Object constructor) {
        if (constructor == realm.getPromiseConstructor()) {
            JSPromise promise = newPromise(realm);
            ResolvingFunctions functions = createResolvingFunctions(realm, promise);
            return new PromiseCapability(promise, functions.resolve(), functions.reject());
        }
        if (!Ops.isConstructor(constructor)) throw JSException.typeError("Promise resolve or reject function is not callable");
        Object[] slots = {Undefined.INSTANCE, Undefined.INSTANCE};
        NativeFunction executor = new NativeFunction(realm, "", 2, (function, receiver, arguments, target) -> {
            if (slots[0] != Undefined.INSTANCE || slots[1] != Undefined.INSTANCE) {
                throw JSException.typeError("Promise executor has already been invoked with non-undefined arguments");
            }
            slots[0] = arg(arguments, 0);
            slots[1] = arg(arguments, 1);
            return Undefined.INSTANCE;
        });
        Object promise = ((JSObject) constructor).construct(new Object[] {executor}, (JSObject) constructor);
        if (!Ops.isCallable(slots[0])) throw JSException.typeError("Promise resolve function is not callable");
        if (!Ops.isCallable(slots[1])) throw JSException.typeError("Promise reject function is not callable");
        return new PromiseCapability((JSObject) promise, slots[0], slots[1]);
    }

    public static JSObject promiseResolve(Realm realm, JSObject constructor, Object value) {
        if (value instanceof JSPromise promise && promise.get("constructor", promise) == constructor) return promise;
        PromiseCapability capability = newPromiseCapability(realm, constructor);
        Ops.call(capability.resolve, Undefined.INSTANCE, new Object[] {value});
        return capability.promise;
    }

    public static Object performThen(Realm realm, JSPromise promise, Object onFulfilled,
                                     Object onRejected, PromiseCapability capability) {
        Object fulfilled = Ops.isCallable(onFulfilled) ? onFulfilled : Undefined.INSTANCE;
        Object rejected = Ops.isCallable(onRejected) ? onRejected : Undefined.INSTANCE;
        addReactions(realm, promise, new PromiseReaction(capability, true, fulfilled),
                new PromiseReaction(capability, false, rejected));
        return capability == null ? Undefined.INSTANCE : capability.promise;
    }

    public static void thenHost(Realm realm, JSPromise promise, Function<Object, Object> onFulfilled,
                                Function<Object, Object> onRejected) {
        addReactions(realm, promise, new PromiseReaction(null, true, null, onFulfilled),
                new PromiseReaction(null, false, null, onRejected));
    }

    private static void addReactions(Realm realm, JSPromise promise, PromiseReaction fulfilled, PromiseReaction rejected) {
        if (promise.state == JSPromise.PENDING) {
            promise.fulfillReactions.add(fulfilled);
            promise.rejectReactions.add(rejected);
        } else if (promise.state == JSPromise.FULFILLED) {
            enqueueReaction(realm, fulfilled, promise.result);
        } else {
            if (!promise.isHandled) realm.agent.trackRejection(promise, true);
            enqueueReaction(realm, rejected, promise.result);
        }
        promise.isHandled = true;
    }

    public record ResolvingFunctions(NativeFunction resolve, NativeFunction reject) {
    }
}
