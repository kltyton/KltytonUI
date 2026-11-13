/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Promises;
import dev.mooner.neonjs.vm.Rt;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** DisposeCapability Record and the DisposeResources algorithm. */
public final class DisposeCapability {
    private ArrayList<DisposableResource> stack = new ArrayList<>();

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public boolean getIsEmpty() {
        return isEmpty();
    }

    /** AddDisposableResource for using / await using / use(). */
    public void add(Realm realm, Object value, DisposeHint hint) {
        if (value == Undefined.INSTANCE || value == Null.INSTANCE) {
            if (hint == DisposeHint.ASYNC) {
                stack.add(new DisposableResource(Undefined.INSTANCE, hint, Undefined.INSTANCE));
            }
            return;
        }
        if (!(value instanceof JSObject)) {
            throw JSException.typeError(Ops.describe(value) + " is not an object and cannot be disposed");
        }
        Object method = getDisposeMethod(realm, (JSObject) value, hint);
        if (method == Undefined.INSTANCE) {
            String name = hint == DisposeHint.ASYNC
                    ? "Symbol.asyncDispose or Symbol.dispose" : "Symbol.dispose";
            throw JSException.typeError(Ops.describe(value) + " does not have a " + name + " method");
        }
        stack.add(new DisposableResource(value, hint, method));
    }

    /** AddDisposableResource for adopt() / defer(): the method is called with this = undefined. */
    public void addMethod(DisposeHint hint, Object method) {
        if (!Ops.isCallable(method)) {
            throw JSException.typeError(Ops.describe(method) + " is not a function");
        }
        stack.add(new DisposableResource(Undefined.INSTANCE, hint, method));
    }

    /** DisposeResources for a capability holding only sync-dispose resources. */
    public void disposeAll(Realm realm) {
        disposeAll(realm, null);
    }

    public void disposeAll(Realm realm, Throwable completion) {
        List<DisposableResource> resources = takeResources();
        Throwable error = completion;
        for (int i = resources.size() - 1; i >= 0; i--) {
            DisposableResource resource = resources.get(i);
            if (resource.hint != DisposeHint.SYNC) {
                throw new IllegalStateException("async-dispose resource in synchronous disposal");
            }
            try {
                Ops.call(resource.method, resource.value, Values.EMPTY_ARGS);
            } catch (Exception | Error thrown) {
                error = suppress(realm, error, thrown);
            }
        }
        if (error != null) rethrow(error);
    }

    /** Starts DisposeResources with Await steps. */
    public AsyncDisposal beginAsyncDisposal(Realm realm) {
        return beginAsyncDisposal(realm, null);
    }

    public AsyncDisposal beginAsyncDisposal(Realm realm, Throwable completion) {
        return new AsyncDisposal(realm, takeResources(), completion);
    }

    /** DisposeResources with Await steps driven by promise jobs. */
    public void disposeAllAsync(Realm realm, Throwable completion, Consumer<Throwable> onDone) {
        AsyncDisposal disposal = beginAsyncDisposal(realm, completion);
        runDisposal(realm, disposal, onDone);
    }

    private static void runDisposal(Realm realm, AsyncDisposal disposal, Consumer<Throwable> onDone) {
        if (!disposal.step()) {
            onDone.accept(disposal.getResult());
            return;
        }
        awaitValue(realm, disposal.getAwaitValue(),
                value -> runDisposal(realm, disposal, onDone),
                reason -> {
                    disposal.rejected(reason);
                    runDisposal(realm, disposal, onDone);
                });
    }

    private List<DisposableResource> takeResources() {
        ArrayList<DisposableResource> resources = stack;
        stack = new ArrayList<>();
        return resources;
    }

    /** GetDisposeMethod(V, hint). */
    public static Object getDisposeMethod(Realm realm, JSObject value, DisposeHint hint) {
        if (hint == DisposeHint.SYNC) return Ops.getMethod(value, JSSymbol.dispose);
        Object method = Ops.getMethod(value, JSSymbol.asyncDispose);
        if (method != Undefined.INSTANCE) return method;
        Object syncMethod = Ops.getMethod(value, JSSymbol.dispose);
        if (syncMethod == Undefined.INSTANCE) return Undefined.INSTANCE;
        return new NativeFunction(realm, "", 0, (function, thisArg, args, newTarget) -> {
            JSPromise promise = Promises.newPromise(function.realm);
            try {
                Ops.call(syncMethod, thisArg, Values.EMPTY_ARGS);
                Promises.resolvePromise(function.realm, promise, Undefined.INSTANCE);
            } catch (Exception | Error thrown) {
                Promises.rejectPromise(function.realm, promise, thrownValue(function.realm, thrown));
            }
            return promise;
        });
    }

    /** Combines a disposal error with the pending completion. */
    public static Throwable suppress(Realm realm, Throwable completion, Throwable thrown) {
        if (thrown instanceof TerminationException) throw (TerminationException) thrown;
        if (completion == null) return thrown;
        return new JSException(newSuppressedError(realm, thrownValue(realm, thrown),
                thrownValue(realm, completion)));
    }

    /** A newly created SuppressedError. */
    public static JSErrorObject newSuppressedError(Realm realm, Object error, Object suppressed) {
        JSObject prototype = realm.errorPrototypes.get(ErrorKind.SUPPRESSED);
        JSErrorObject object = new JSErrorObject(prototype != null ? prototype : realm.errorPrototype);
        object.defineOwn("error", error, Attr.WC);
        object.defineOwn("suppressed", suppressed, Attr.WC);
        Agent agent = realm.agent;
        if (agent.topFrame != null) object.stackTrace = agent.captureStack();
        return object;
    }

    /** The JS value of a throw completion represented by {@code thrown}. */
    public static Object thrownValue(Realm realm, Throwable thrown) {
        return thrown instanceof JSException exception ? exception.value : Rt.catchValue(thrown, realm, null, 0);
    }

    private static void awaitValue(Realm realm, Object value, Consumer<Object> onFulfilled,
                                   Consumer<Object> onRejected) {
        JSPromise promise;
        try {
            promise = (JSPromise) Promises.promiseResolve(realm, realm.getPromiseConstructor(), value);
        } catch (TerminationException termination) {
            throw termination;
        } catch (Exception | Error thrown) {
            onRejected.accept(thrownValue(realm, thrown));
            return;
        }
        Promises.thenHost(realm, promise,
                fulfilled -> {
                    onFulfilled.accept(fulfilled);
                    return Undefined.INSTANCE;
                },
                reason -> {
                    onRejected.accept(reason);
                    return Undefined.INSTANCE;
                });
    }

    private static void rethrow(Throwable error) {
        DisposeCapability.<RuntimeException>sneakyThrow(error);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable error) throws E {
        throw (E) error;
    }
}
