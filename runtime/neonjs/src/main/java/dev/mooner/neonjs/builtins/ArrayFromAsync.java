/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.AsyncFromSyncIterator;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Promises;

import java.util.Objects;
import java.util.function.Consumer;

import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/**
 * Array.fromAsync(asyncItems, mapfn, thisArg): the fromAsyncClosure run with AsyncFunctionStart semantics, as a state
 * machine whose Await steps resume through promise reactions. Every continuation settles {@code promise} itself, since
 * exceptions escaping host reactions are dropped.
 */
final class ArrayFromAsync {
    private final Realm realm;
    private final Object c;
    private final Object mapfn;
    private final Object thisArg;

    private final JSPromise promise;
    private boolean mapping = false;
    private JSObject target;
    private long k = 0L;

    /** Async iterator (possibly a wrapped sync iterator) of the iterable path. */
    private IteratorRecord iteratorRecord = null;

    /** Source object and length of the array-like path. */
    private JSObject arrayLike = null;
    private long len = 0L;

    private ArrayFromAsync(Realm realm, Object c, Object mapfn, Object thisArg) {
        this.realm = realm;
        this.c = c;
        this.mapfn = mapfn;
        this.thisArg = thisArg;
        this.promise = Promises.newPromise(realm);
    }

    static JSPromise start(Realm realm, Object c, Object asyncItems, Object mapfn, Object thisArg) {
        ArrayFromAsync job = new ArrayFromAsync(realm, c, mapfn, thisArg);
        job.guard(() -> job.begin(asyncItems));
        return job.promise;
    }

    /** Runs a step of the closure; a throw completion rejects the result promise. */
    private void guard(Runnable block) {
        try {
            block.run();
        } catch (TerminationException error) {
            throw error;
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            Promises.rejectPromise(realm, promise, DisposeCapability.thrownValue(realm, error));
        }
    }

    /** Await(value), continuing with {@code cont}; a rejection rejects the result promise. */
    private void await(Object value, Consumer<Object> cont) {
        awaitValue(realm, value, v -> guard(() -> cont.accept(v)),
                error -> Promises.rejectPromise(realm, promise, error));
    }

    /**
     * Await(value) for promise-driven state machines: PromiseResolve(%Promise%, value) and PerformPromiseThen with
     * host continuations. A throw from PromiseResolve calls onRejected synchronously.
     */
    private static void awaitValue(Realm realm, Object value, Consumer<Object> onFulfilled,
                                   Consumer<Object> onRejected) {
        JSPromise awaited;
        try {
            awaited = (JSPromise) Promises.promiseResolve(realm, realm.getPromiseConstructor(), value);
        } catch (TerminationException error) {
            throw error;
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            onRejected.accept(DisposeCapability.thrownValue(realm, error));
            return;
        }
        Promises.thenHost(realm, awaited,
                v -> {
                    onFulfilled.accept(v);
                    return Undefined.INSTANCE;
                },
                e -> {
                    onRejected.accept(e);
                    return Undefined.INSTANCE;
                });
    }

    private void begin(Object asyncItems) {
        if (mapfn != Undefined.INSTANCE) {
            if (!Ops.isCallable(mapfn)) throw realm.typeError(Ops.describe(mapfn) + " is not a function");
            mapping = true;
        }
        Object usingAsyncIterator = Ops.getMethod(asyncItems, JSSymbol.asyncIterator);
        Object usingSyncIterator = usingAsyncIterator == Undefined.INSTANCE
                ? Ops.getMethod(asyncItems, JSSymbol.iterator)
                : Undefined.INSTANCE;
        if (usingAsyncIterator != Undefined.INSTANCE || usingSyncIterator != Undefined.INSTANCE) {
            iteratorRecord = usingAsyncIterator != Undefined.INSTANCE
                    ? Iteration.fromMethod(asyncItems, usingAsyncIterator)
                    : AsyncFromSyncIterator.create(realm, Iteration.fromMethod(asyncItems, usingSyncIterator));
            target = Ops.isConstructor(c)
                    ? (JSObject) Ops.construct(c, EMPTY_ARGS)
                    : ArrayBuiltins.arrayCreate(realm, 0);
            iteratorStep();
        } else {
            // neither an AsyncIterable nor an Iterable: an array-like object
            JSObject o = Ops.toObject(realm, asyncItems);
            arrayLike = o;
            len = Ops.lengthOfArrayLike(o);
            target = Ops.isConstructor(c)
                    ? (JSObject) Ops.construct(c, new Object[] {Ops.num(len)})
                    : ArrayBuiltins.arrayCreate(realm, len);
            arrayLikeStep();
        }
    }

    // ------------------------------------------------------------------ iterable path

    private void iteratorStep() {
        IteratorRecord rec = Objects.requireNonNull(iteratorRecord);
        if (k >= ArrayBuiltins.MAX_SAFE) {
            closeAndReject(rec, realm.typeError("Array.fromAsync: too many elements"));
            return;
        }
        Object nextResult = Ops.call(rec.nextMethod, rec.iterator, EMPTY_ARGS);
        await(nextResult, r -> onNextResult(rec, r));
    }

    private void onNextResult(IteratorRecord rec, Object r) {
        if (!(r instanceof JSObject result)) {
            throw realm.typeError("Iterator result " + Ops.toDisplayString(r) + " is not an object");
        }
        if (Ops.toBoolean(result.get("done", result))) {
            target.setOrThrow("length", Ops.num(k));
            Promises.resolvePromise(realm, promise, target);
            return;
        }
        Object nextValue = result.get("value", result);
        if (!mapping) {
            addFromIterator(rec, nextValue);
            return;
        }
        Object mapped;
        try {
            mapped = Ops.call(mapfn, thisArg, new Object[] {nextValue, Ops.num(k)});
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            closeAndReject(rec, error);
            return;
        }
        awaitValue(realm, mapped,
                v -> guard(() -> addFromIterator(rec, v)),
                error -> guard(() -> closeAndReject(rec, new JSException(error))));
    }

    private void addFromIterator(IteratorRecord rec, Object value) {
        try {
            target.createDataPropertyOrThrow(ArrayBuiltins.key(k), value);
        } catch (Exception | StackOverflowError | OutOfMemoryError error) {
            closeAndReject(rec, error);
            return;
        }
        k++;
        iteratorStep();
    }

    /** AsyncIteratorClose(rec, ThrowCompletion(t)): awaits the result of return() but always rejects with {@code t}. */
    private void closeAndReject(IteratorRecord rec, Throwable t) {
        if (t instanceof TerminationException termination) throw termination;
        Object error = DisposeCapability.thrownValue(realm, t);
        Object inner = Iteration.asyncCloseCall(rec, true);
        if (inner == NotFound.INSTANCE) {
            Promises.rejectPromise(realm, promise, error);
        } else {
            awaitValue(realm, inner,
                    v -> Promises.rejectPromise(realm, promise, error),
                    e -> Promises.rejectPromise(realm, promise, error));
        }
    }

    // ------------------------------------------------------------------ array-like path

    private void arrayLikeStep() {
        if (k >= len) {
            target.setOrThrow("length", Ops.num(len));
            Promises.resolvePromise(realm, promise, target);
            return;
        }
        JSObject source = Objects.requireNonNull(arrayLike);
        Object kValue = source.get(ArrayBuiltins.key(k), source);
        await(kValue, v -> {
            if (mapping) {
                await(Ops.call(mapfn, thisArg, new Object[] {v, Ops.num(k)}), this::addFromArrayLike);
            } else {
                addFromArrayLike(v);
            }
        });
    }

    private void addFromArrayLike(Object value) {
        target.createDataPropertyOrThrow(ArrayBuiltins.key(k), value);
        k++;
        arrayLikeStep();
    }
}
