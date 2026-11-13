/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import static dev.mooner.neonjs.ext.Idl.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/** {@code AbortController} and {@code AbortSignal} of the DOM Standard. */
final class Abort {
    private Abort() {
    }

    static final class JSAbortSignal extends Events.JSEventTarget {
        boolean aborted;
        Object reason = Undefined.INSTANCE;
        final ArrayList<Runnable> algorithms = new ArrayList<>(1);
        boolean dependent;
        final ArrayList<WeakReference<JSAbortSignal>> sources = new ArrayList<>(0);
        final ArrayList<WeakReference<JSAbortSignal>> dependents = new ArrayList<>(0);
        private LinkedHashSet<JSAbortSignal> kept;

        JSAbortSignal(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "AbortSignal";
        }

        void addAlgorithm(Runnable runnable) {
            if (aborted) return;
            algorithms.add(runnable);
            keepForSources();
        }

        @Override
        void listenerAdded(Events.Listener listener) {
            if (listener.type.equals("abort")) keepForSources();
        }

        private void keepForSources() {
            if (!dependent || aborted) return;
            for (WeakReference<JSAbortSignal> reference : sources) {
                JSAbortSignal source = reference.get();
                if (source == null || source.aborted) continue;
                if (source.kept == null) source.kept = new LinkedHashSet<>();
                source.kept.add(this);
            }
        }

        /** "signal abort" */
        void abort(Realm realm, Object reason) {
            if (aborted) return;
            aborted = true;
            this.reason = reason == Undefined.INSTANCE
                    ? newDOMError(realm, "signal is aborted without reason", "AbortError") : reason;
            ArrayList<JSAbortSignal> toAbort = new ArrayList<>();
            for (WeakReference<JSAbortSignal> reference : dependents) {
                JSAbortSignal dependent = reference.get();
                if (dependent == null || dependent.aborted) continue;
                dependent.aborted = true;
                dependent.reason = this.reason;
                toAbort.add(dependent);
            }
            kept = null;
            runAbortSteps(realm);
            for (JSAbortSignal dependent : toAbort) dependent.runAbortSteps(realm);
        }

        private void runAbortSteps(Realm realm) {
            Runnable[] run = algorithms.toArray(new Runnable[0]);
            algorithms.clear();
            for (Runnable runnable : run) runnable.run();
            Events.dispatch(realm, this, Events.engineEvent(realm, "abort"));
        }
    }

    static final class JSAbortController extends JSObject {
        final JSAbortSignal signal;

        JSAbortController(JSObject prototype, JSAbortSignal signal) {
            super(prototype);
            this.signal = signal;
        }

        @Override
        public String getClassName() {
            return "AbortController";
        }
    }

    private static JSObject newDOMError(Realm realm, String message, String name) {
        return WebGlobals.newDOMException(realm, message, name);
    }

    /** An {@code AbortSignal} argument, or a TypeError. */
    static JSAbortSignal signalOf(Object value) {
        if (value instanceof JSAbortSignal signal) return signal;
        throw typeErr(Ops.describe(value) + " is not an AbortSignal");
    }

    static JSAbortSignal newSignal(Realm realm) {
        return new JSAbortSignal(realm.intrinsic("%AbortSignal.prototype%"));
    }

    static void install(Realm realm) {
        WebInterface controller = WebInterface.define(realm, "AbortController", 0, null, null,
                (arguments, prototype) -> {
                    JSAbortSignal signal = newSignal(realm);
                    return new JSAbortController(prototype, signal);
                });
        controller.attribute("signal", (function, receiver, arguments, newTarget) ->
                Idl.self(JSAbortController.class, receiver, "AbortController", "signal").signal);
        controller.operation("abort", 0, (function, receiver, arguments, newTarget) -> {
            JSAbortController target = Idl.self(JSAbortController.class, receiver,
                    "AbortController", "abort");
            target.signal.abort(function.realm, arg(arguments, 0));
            return Undefined.INSTANCE;
        });

        WebInterface signalInterface = WebInterface.define(realm, "AbortSignal", 0, "EventTarget");
        signalInterface.staticOperation("abort", 0, (function, receiver, arguments, newTarget) -> {
            JSAbortSignal signal = newSignal(function.realm);
            signal.abort(function.realm, arg(arguments, 0));
            return signal;
        });
        signalInterface.staticOperation("timeout", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "AbortSignal.timeout");
            double milliseconds = Idl.integer(arguments[0], 64, false,
                    "AbortSignal.timeout milliseconds", true);
            JSAbortSignal signal = newSignal(function.realm);
            Realm targetRealm = function.realm;
            WebGlobals.Timers timers = WebGlobals.timersOf(targetRealm);
            timers.task((long) milliseconds, false,
                    () -> signal.abort(targetRealm,
                            newDOMError(targetRealm, "signal timed out", "TimeoutError")));
            return signal;
        });
        signalInterface.staticOperation("any", 1, (function, receiver, arguments, newTarget) -> {
            Idl.required(arguments, 1, "AbortSignal.any");
            List<JSAbortSignal> signals = Idl.sequence(function.realm, arguments[0],
                    "AbortSignal.any signals", Abort::signalOf);
            JSAbortSignal result = newSignal(function.realm);
            JSAbortSignal aborted = null;
            for (JSAbortSignal signal : signals) {
                if (signal.aborted) {
                    aborted = signal;
                    break;
                }
            }
            if (aborted != null) {
                result.aborted = true;
                result.reason = aborted.reason;
                return result;
            }
            result.dependent = true;
            for (JSAbortSignal signal : signals) {
                if (!signal.dependent) follow(result, signal);
                else {
                    for (WeakReference<JSAbortSignal> reference : signal.sources) {
                        JSAbortSignal source = reference.get();
                        if (source != null) follow(result, source);
                    }
                }
            }
            return result;
        });
        signalInterface.attribute("aborted", (function, receiver, arguments, newTarget) ->
                Idl.self(JSAbortSignal.class, receiver, "AbortSignal", "aborted").aborted);
        signalInterface.attribute("reason", (function, receiver, arguments, newTarget) ->
                Idl.self(JSAbortSignal.class, receiver, "AbortSignal", "reason").reason);
        signalInterface.operation("throwIfAborted", 0, (function, receiver, arguments, newTarget) -> {
            JSAbortSignal signal = Idl.self(JSAbortSignal.class, receiver, "AbortSignal", "throwIfAborted");
            if (signal.aborted) throw new JSException(signal.reason);
            return Undefined.INSTANCE;
        });
        Events.eventHandler(signalInterface, "onabort", "abort");
    }

    private static void follow(JSAbortSignal result, JSAbortSignal source) {
        for (WeakReference<JSAbortSignal> reference : result.sources) {
            if (reference.get() == source) return;
        }
        result.sources.add(new WeakReference<>(source));
        source.dependents.removeIf(reference -> reference.get() == null);
        source.dependents.add(new WeakReference<>(result));
    }
}
