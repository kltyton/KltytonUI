/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.Builtins;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import static dev.mooner.neonjs.ext.Idl.typeErr;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

/**
 * {@code Event}, {@code CustomEvent} and {@code EventTarget} of the DOM Standard, for targets outside any tree.
 * Also MessageEvent, ErrorEvent and CloseEvent.
 */
final class Events {
    static final int NONE = 0;
    static final int CAPTURING_PHASE = 1;
    static final int AT_TARGET = 2;
    static final int BUBBLING_PHASE = 3;

    private Events() {
    }

    /** An event: the DOM Standard's internal state and flags. */
    static class JSEvent extends JSObject {
        String type = "";
        JSObject target;
        JSObject currentTarget;
        int eventPhase = NONE;
        boolean bubbles;
        boolean cancelable;
        boolean composed;
        boolean isTrusted;
        double timeStamp;
        boolean stopPropagation;
        boolean stopImmediatePropagation;
        boolean canceled;
        boolean inPassiveListener;
        boolean initialized;
        boolean dispatching;

        JSEvent(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "Event";
        }

        void initialize(String type, boolean bubbles, boolean cancelable) {
            initialized = true;
            stopPropagation = false;
            stopImmediatePropagation = false;
            canceled = false;
            isTrusted = false;
            target = null;
            this.type = type;
            this.bubbles = bubbles;
            this.cancelable = cancelable;
        }

        void cancel() {
            if (cancelable && !inPassiveListener) canceled = true;
        }
    }

    static final class JSCustomEvent extends JSEvent {
        Object detail = Null.INSTANCE;

        JSCustomEvent(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "CustomEvent";
        }
    }

    static final class JSMessageEvent extends JSEvent {
        Object data = Null.INSTANCE;
        String origin = "";
        String lastEventId = "";
        JSObject ports;

        JSMessageEvent(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "MessageEvent";
        }
    }

    static final class JSCloseEvent extends JSEvent {
        boolean wasClean;
        int code;
        String reason = "";

        JSCloseEvent(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "CloseEvent";
        }
    }

    static final class JSErrorEvent extends JSEvent {
        String message = "";
        String filename = "";
        double lineno;
        double colno;
        Object error = Undefined.INSTANCE;

        JSErrorEvent(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "ErrorEvent";
        }
    }

    /** An event listener; {@code callback} is null for a listener that has been removed. */
    static final class Listener {
        final String type;
        JSObject callback;
        final boolean capture;
        final boolean passive;
        final boolean once;
        boolean removed;
        boolean resistStopPropagation;
        HandlerOf handlerOf;

        Listener(String type, JSObject callback, boolean capture, boolean passive, boolean once) {
            this.type = type;
            this.callback = callback;
            this.capture = capture;
            this.passive = passive;
            this.once = once;
        }
    }

    record HandlerOf(JSEventTarget target, String name) {
    }

    record HandlerValue(Object value, Listener listener) {
    }

    static class JSEventTarget extends JSObject {
        final ArrayList<Listener> listeners = new ArrayList<>(2);
        HashMap<String, HandlerValue> handlers;

        JSEventTarget(JSObject prototype) {
            super(prototype);
        }

        @Override
        public String getClassName() {
            return "EventTarget";
        }

        void addListener(Listener listener) {
            for (Listener existing : listeners) {
                if (!existing.removed && existing.type.equals(listener.type)
                        && existing.callback == listener.callback && existing.capture == listener.capture
                        && existing.handlerOf == null) return;
            }
            listeners.add(listener);
            listenerAdded(listener);
        }

        void listenerAdded(Listener listener) {
        }

        void removeListener(Listener listener) {
            listener.removed = true;
            listeners.remove(listener);
        }
    }

    static void install(Realm realm) {
        installEvent(realm);
        installCustomEvent(realm);
        installEventTarget(realm);
        installMessageEvent(realm);
        installCloseEvent(realm);
        installErrorEvent(realm);
    }

    private static void installEvent(Realm realm) {
        NativeFunction isTrusted = new NativeFunction(realm, "isTrusted", 0,
                (function, receiver, arguments, newTarget) ->
                        Idl.self(JSEvent.class, receiver, "Event", "isTrusted").isTrusted,
                false, realm.getFunctionPrototype(), "get");
        realm.intrinsicsAny.put("%Event.isTrusted%", isTrusted);
        WebInterface iface = WebInterface.define(realm, "Event", 1, null, null, (arguments, prototype) -> {
            Idl.required(arguments, 1, "Event constructor");
            String type = Idl.domString(arguments[0]);
            JSObject init = Idl.dictionary(arg(arguments, 1), "EventInit");
            return newEvent(realm, new JSEvent(prototype), type, init);
        });
        iface.attribute("type", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "type").type);
        iface.attribute("target", (function, receiver, arguments, newTarget) -> {
            JSObject target = Idl.self(JSEvent.class, receiver, "Event", "target").target;
            return target == null ? Null.INSTANCE : target;
        });
        iface.attribute("srcElement", (function, receiver, arguments, newTarget) -> {
            JSObject target = Idl.self(JSEvent.class, receiver, "Event", "srcElement").target;
            return target == null ? Null.INSTANCE : target;
        });
        iface.attribute("currentTarget", (function, receiver, arguments, newTarget) -> {
            JSObject target = Idl.self(JSEvent.class, receiver, "Event", "currentTarget").currentTarget;
            return target == null ? Null.INSTANCE : target;
        });
        iface.operation("composedPath", 0, (function, receiver, arguments, newTarget) -> {
            JSEvent event = Idl.self(JSEvent.class, receiver, "Event", "composedPath");
            Object[] path = event.dispatching && event.currentTarget != null
                    ? new Object[] {event.currentTarget} : EMPTY_ARGS;
            return JSArray.of(function.realm.getArrayPrototype(), path);
        });
        iface.constant("NONE", NONE);
        iface.constant("CAPTURING_PHASE", CAPTURING_PHASE);
        iface.constant("AT_TARGET", AT_TARGET);
        iface.constant("BUBBLING_PHASE", BUBBLING_PHASE);
        iface.attribute("eventPhase", (function, receiver, arguments, newTarget) ->
                (double) Idl.self(JSEvent.class, receiver, "Event", "eventPhase").eventPhase);
        iface.operation("stopPropagation", 0, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSEvent.class, receiver, "Event", "stopPropagation").stopPropagation = true;
            return Undefined.INSTANCE;
        });
        iface.attribute("cancelBubble", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "cancelBubble").stopPropagation,
                (function, receiver, arguments, newTarget) -> {
                    JSEvent event = Idl.self(JSEvent.class, receiver, "Event", "cancelBubble");
                    if (Ops.toBoolean(arg(arguments, 0))) event.stopPropagation = true;
                    return Undefined.INSTANCE;
                });
        iface.operation("stopImmediatePropagation", 0, (function, receiver, arguments, newTarget) -> {
            JSEvent event = Idl.self(JSEvent.class, receiver, "Event", "stopImmediatePropagation");
            event.stopPropagation = true;
            event.stopImmediatePropagation = true;
            return Undefined.INSTANCE;
        });
        iface.attribute("bubbles", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "bubbles").bubbles);
        iface.attribute("cancelable", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "cancelable").cancelable);
        iface.attribute("returnValue", (function, receiver, arguments, newTarget) ->
                !Idl.self(JSEvent.class, receiver, "Event", "returnValue").canceled,
                (function, receiver, arguments, newTarget) -> {
                    JSEvent event = Idl.self(JSEvent.class, receiver, "Event", "returnValue");
                    if (!Ops.toBoolean(arg(arguments, 0))) event.cancel();
                    return Undefined.INSTANCE;
                });
        iface.operation("preventDefault", 0, (function, receiver, arguments, newTarget) -> {
            Idl.self(JSEvent.class, receiver, "Event", "preventDefault").cancel();
            return Undefined.INSTANCE;
        });
        iface.attribute("defaultPrevented", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "defaultPrevented").canceled);
        iface.attribute("composed", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "composed").composed);
        iface.attribute("timeStamp", (function, receiver, arguments, newTarget) ->
                Idl.self(JSEvent.class, receiver, "Event", "timeStamp").timeStamp);
        iface.operation("initEvent", 1, (function, receiver, arguments, newTarget) -> {
            JSEvent event = Idl.self(JSEvent.class, receiver, "Event", "initEvent");
            Idl.required(arguments, 1, "Event.initEvent");
            String type = Idl.domString(arguments[0]);
            boolean bubbles = Ops.toBoolean(arg(arguments, 1));
            boolean cancelable = Ops.toBoolean(arg(arguments, 2));
            if (!event.dispatching) event.initialize(type, bubbles, cancelable);
            return Undefined.INSTANCE;
        });
    }

    /** The steps of the Event constructor, on an event of the right class and prototype. */
    static <E extends JSEvent> E newEvent(Realm realm, E event, String type, JSObject init) {
        event.bubbles = Ops.toBoolean(Idl.member(init, "bubbles"));
        event.cancelable = Ops.toBoolean(Idl.member(init, "cancelable"));
        event.composed = Ops.toBoolean(Idl.member(init, "composed"));
        event.initialized = true;
        event.type = type;
        event.timeStamp = Performance.now(realm);
        event.defineAccessor("isTrusted", realm.intrinsicsAny.get("%Event.isTrusted%"),
                Undefined.INSTANCE, Attr.ENUMERABLE);
        return event;
    }

    /** A new trusted-looking event of {@code realm} fired by the engine itself. */
    static JSEvent engineEvent(Realm realm, String type) {
        JSEvent event = newEvent(realm, new JSEvent(realm.intrinsic("%Event.prototype%")), type, null);
        event.isTrusted = true;
        return event;
    }

    /** An event of a specific class, initialized as a trusted engine event. */
    static <E extends JSEvent> E engineEvent(Realm realm, E event, String type) {
        newEvent(realm, event, type, null);
        event.isTrusted = true;
        return event;
    }

    private static void installCustomEvent(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "CustomEvent", 1, "Event", (arguments, prototype) -> {
            Idl.required(arguments, 1, "CustomEvent constructor");
            String type = Idl.domString(arguments[0]);
            JSObject init = Idl.dictionary(arg(arguments, 1), "CustomEventInit");
            JSCustomEvent event = newEvent(realm, new JSCustomEvent(prototype), type, init);
            Object detail = Idl.member(init, "detail");
            event.detail = detail == Undefined.INSTANCE ? Null.INSTANCE : detail;
            return event;
        });
        iface.attribute("detail", (function, receiver, arguments, newTarget) ->
                Idl.self(JSCustomEvent.class, receiver, "CustomEvent", "detail").detail);
        iface.operation("initCustomEvent", 1, (function, receiver, arguments, newTarget) -> {
            JSCustomEvent event = Idl.self(JSCustomEvent.class, receiver, "CustomEvent", "initCustomEvent");
            Idl.required(arguments, 1, "CustomEvent.initCustomEvent");
            String type = Idl.domString(arguments[0]);
            boolean bubbles = Ops.toBoolean(arg(arguments, 1));
            boolean cancelable = Ops.toBoolean(arg(arguments, 2));
            if (!event.dispatching) {
                event.initialize(type, bubbles, cancelable);
                event.detail = arguments.length > 3 ? arguments[3] : Null.INSTANCE;
            }
            return Undefined.INSTANCE;
        });
    }

    private static JSObject emptyPorts(Realm realm) {
        JSArray array = Builtins.arrayOf(realm, List.of());
        Builtins.freeze(array);
        return array;
    }

    private static JSObject ports(Realm realm, Object value, String what) {
        if (value == Undefined.INSTANCE) return emptyPorts(realm);
        Idl.sequence(realm, value, what + " ports", item -> {
            throw typeErr(what + ": " + Ops.describe(item) + " is not a MessagePort");
        });
        return emptyPorts(realm);
    }

    private static void checkSource(Object value, String what) {
        if (value != Undefined.INSTANCE && value != Null.INSTANCE) {
            throw typeErr(what + ": " + Ops.describe(value) + " is not a MessageEventSource");
        }
    }

    private static void installMessageEvent(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "MessageEvent", 1, "Event", (arguments, prototype) -> {
            Idl.required(arguments, 1, "MessageEvent constructor");
            String type = Idl.domString(arguments[0]);
            JSObject init = Idl.dictionary(arg(arguments, 1), "MessageEventInit");
            JSMessageEvent event = newEvent(realm, new JSMessageEvent(prototype), type, init);
            Object data = Idl.member(init, "data");
            event.data = data == Undefined.INSTANCE ? Null.INSTANCE : data;
            Object lastEventId = Idl.member(init, "lastEventId");
            if (lastEventId != Undefined.INSTANCE) event.lastEventId = Idl.domString(lastEventId);
            Object origin = Idl.member(init, "origin");
            if (origin != Undefined.INSTANCE) event.origin = Idl.usvString(origin);
            event.ports = ports(realm, Idl.member(init, "ports"), "MessageEventInit");
            checkSource(Idl.member(init, "source"), "MessageEventInit");
            return event;
        });
        iface.attribute("data", (function, receiver, arguments, newTarget) ->
                Idl.self(JSMessageEvent.class, receiver, "MessageEvent", "data").data);
        iface.attribute("origin", (function, receiver, arguments, newTarget) ->
                Idl.self(JSMessageEvent.class, receiver, "MessageEvent", "origin").origin);
        iface.attribute("lastEventId", (function, receiver, arguments, newTarget) ->
                Idl.self(JSMessageEvent.class, receiver, "MessageEvent", "lastEventId").lastEventId);
        iface.attribute("source", (function, receiver, arguments, newTarget) -> {
            Idl.self(JSMessageEvent.class, receiver, "MessageEvent", "source");
            return Null.INSTANCE;
        });
        iface.attribute("ports", (function, receiver, arguments, newTarget) -> {
            JSMessageEvent event = Idl.self(JSMessageEvent.class, receiver, "MessageEvent", "ports");
            if (event.ports == null) event.ports = emptyPorts(function.realm);
            return event.ports;
        });
        iface.operation("initMessageEvent", 1, (function, receiver, arguments, newTarget) -> {
            JSMessageEvent event = Idl.self(JSMessageEvent.class, receiver, "MessageEvent", "initMessageEvent");
            Idl.required(arguments, 1, "MessageEvent.initMessageEvent");
            String type = Idl.domString(arguments[0]);
            boolean bubbles = Ops.toBoolean(arg(arguments, 1));
            boolean cancelable = Ops.toBoolean(arg(arguments, 2));
            Object data = arguments.length > 3 ? arguments[3] : Null.INSTANCE;
            String origin = arguments.length > 4 && arguments[4] != Undefined.INSTANCE
                    ? Idl.usvString(arguments[4]) : "";
            String lastEventId = arguments.length > 5 && arguments[5] != Undefined.INSTANCE
                    ? Idl.domString(arguments[5]) : "";
            checkSource(arg(arguments, 6), "MessageEvent.initMessageEvent");
            JSObject ports = ports(function.realm, arg(arguments, 7), "MessageEvent.initMessageEvent");
            if (!event.dispatching) {
                event.initialize(type, bubbles, cancelable);
                event.data = data;
                event.origin = origin;
                event.lastEventId = lastEventId;
                event.ports = ports;
            }
            return Undefined.INSTANCE;
        });
    }

    private static void installCloseEvent(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "CloseEvent", 1, "Event", (arguments, prototype) -> {
            Idl.required(arguments, 1, "CloseEvent constructor");
            String type = Idl.domString(arguments[0]);
            JSObject init = Idl.dictionary(arg(arguments, 1), "CloseEventInit");
            JSCloseEvent event = newEvent(realm, new JSCloseEvent(prototype), type, init);
            Object code = Idl.member(init, "code");
            if (code != Undefined.INSTANCE) {
                event.code = (int) Idl.integer(code, 16, false, "CloseEventInit code");
            }
            Object reason = Idl.member(init, "reason");
            if (reason != Undefined.INSTANCE) event.reason = Idl.usvString(reason);
            event.wasClean = Ops.toBoolean(Idl.member(init, "wasClean"));
            return event;
        });
        iface.attribute("wasClean", (function, receiver, arguments, newTarget) ->
                Idl.self(JSCloseEvent.class, receiver, "CloseEvent", "wasClean").wasClean);
        iface.attribute("code", (function, receiver, arguments, newTarget) ->
                (double) Idl.self(JSCloseEvent.class, receiver, "CloseEvent", "code").code);
        iface.attribute("reason", (function, receiver, arguments, newTarget) ->
                Idl.self(JSCloseEvent.class, receiver, "CloseEvent", "reason").reason);
    }

    private static void installErrorEvent(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "ErrorEvent", 1, "Event", (arguments, prototype) -> {
            Idl.required(arguments, 1, "ErrorEvent constructor");
            String type = Idl.domString(arguments[0]);
            JSObject init = Idl.dictionary(arg(arguments, 1), "ErrorEventInit");
            JSErrorEvent event = newEvent(realm, new JSErrorEvent(prototype), type, init);
            Object colno = Idl.member(init, "colno");
            if (colno != Undefined.INSTANCE) {
                event.colno = Idl.integer(colno, 32, false, "ErrorEventInit colno");
            }
            event.error = Idl.member(init, "error");
            Object filename = Idl.member(init, "filename");
            if (filename != Undefined.INSTANCE) event.filename = Idl.usvString(filename);
            Object lineno = Idl.member(init, "lineno");
            if (lineno != Undefined.INSTANCE) {
                event.lineno = Idl.integer(lineno, 32, false, "ErrorEventInit lineno");
            }
            Object message = Idl.member(init, "message");
            if (message != Undefined.INSTANCE) event.message = Idl.domString(message);
            return event;
        });
        iface.attribute("message", (function, receiver, arguments, newTarget) ->
                Idl.self(JSErrorEvent.class, receiver, "ErrorEvent", "message").message);
        iface.attribute("filename", (function, receiver, arguments, newTarget) ->
                Idl.self(JSErrorEvent.class, receiver, "ErrorEvent", "filename").filename);
        iface.attribute("lineno", (function, receiver, arguments, newTarget) ->
                Idl.self(JSErrorEvent.class, receiver, "ErrorEvent", "lineno").lineno);
        iface.attribute("colno", (function, receiver, arguments, newTarget) ->
                Idl.self(JSErrorEvent.class, receiver, "ErrorEvent", "colno").colno);
        iface.attribute("error", (function, receiver, arguments, newTarget) ->
                Idl.self(JSErrorEvent.class, receiver, "ErrorEvent", "error").error);
    }

    private static void installEventTarget(Realm realm) {
        WebInterface iface = WebInterface.define(realm, "EventTarget", 0, null, null,
                (arguments, prototype) -> new JSEventTarget(prototype));
        iface.operation("addEventListener", 2, (function, receiver, arguments, newTarget) -> {
            JSEventTarget target = Idl.self(JSEventTarget.class, receiver, "EventTarget", "addEventListener");
            Idl.required(arguments, 2, "EventTarget.addEventListener");
            String type = Idl.domString(arguments[0]);
            JSObject callback = listenerCallback(arguments[1]);
            Object options = arg(arguments, 2);
            boolean capture = false;
            boolean once = false;
            boolean passive = false;
            Object signal = Undefined.INSTANCE;
            boolean resist = false;
            if (options instanceof JSObject object) {
                capture = Ops.toBoolean(object.get("capture", object));
                once = Ops.toBoolean(object.get("once", object));
                Object passiveValue = object.get("passive", object);
                passive = passiveValue != Undefined.INSTANCE && Ops.toBoolean(passiveValue);
                signal = object.get("signal", object);
                if (signal != Undefined.INSTANCE) signal = Abort.signalOf(signal);
                JSSymbol resistSymbol = function.realm.agent.symbolRegistry.get(RESIST_STOP_PROPAGATION);
                if (resistSymbol == null) {
                    resistSymbol = new JSSymbol(RESIST_STOP_PROPAGATION, RESIST_STOP_PROPAGATION);
                    function.realm.agent.symbolRegistry.put(RESIST_STOP_PROPAGATION, resistSymbol);
                }
                resist = Ops.toBoolean(object.get(resistSymbol, object));
            } else {
                capture = Ops.toBoolean(options);
            }
            if (callback == null) return Undefined.INSTANCE;
            Abort.JSAbortSignal abortSignal = signal instanceof Abort.JSAbortSignal value ? value : null;
            if (abortSignal != null && abortSignal.aborted) return Undefined.INSTANCE;
            Listener listener = new Listener(type, callback, capture, passive, once);
            listener.resistStopPropagation = resist;
            target.addListener(listener);
            if (abortSignal != null) abortSignal.addAlgorithm(() -> target.removeListener(listener));
            return Undefined.INSTANCE;
        });
        iface.operation("removeEventListener", 2, (function, receiver, arguments, newTarget) -> {
            JSEventTarget target = Idl.self(JSEventTarget.class, receiver, "EventTarget", "removeEventListener");
            Idl.required(arguments, 2, "EventTarget.removeEventListener");
            String type = Idl.domString(arguments[0]);
            JSObject callback = listenerCallback(arguments[1]);
            Object options = arg(arguments, 2);
            boolean capture = options instanceof JSObject object
                    ? Ops.toBoolean(object.get("capture", object)) : Ops.toBoolean(options);
            if (callback != null) {
                Listener found = null;
                for (Listener listener : target.listeners) {
                    if (!listener.removed && listener.handlerOf == null && listener.type.equals(type)
                            && listener.callback == callback && listener.capture == capture) {
                        found = listener;
                        break;
                    }
                }
                if (found != null) target.removeListener(found);
            }
            return Undefined.INSTANCE;
        });
        iface.operation("dispatchEvent", 1, (function, receiver, arguments, newTarget) -> {
            JSEventTarget target = Idl.self(JSEventTarget.class, receiver, "EventTarget", "dispatchEvent");
            Idl.required(arguments, 1, "EventTarget.dispatchEvent");
            if (!(arguments[0] instanceof JSEvent event)) {
                throw typeErr("EventTarget.dispatchEvent: the argument is not an Event");
            }
            if (event.dispatching || !event.initialized) {
                throw WebGlobals.domException(function.realm,
                        "The event is already being dispatched or was not initialized", "InvalidStateError");
            }
            event.isTrusted = false;
            return dispatch(function.realm, target, event);
        });
    }

    private static JSObject listenerCallback(Object value) {
        if (value == Null.INSTANCE || value == Undefined.INSTANCE) return null;
        if (value instanceof JSObject object) return object;
        throw typeErr("EventTarget: the listener is not an object");
    }

    /**
     * Dispatches {@code event} to {@code target} and returns whether the default action is allowed. Exceptions
     * thrown by listeners are reported as microtask exceptions.
     */
    static boolean dispatch(Realm realm, JSEventTarget target, JSEvent event) {
        event.dispatching = true;
        event.target = target;
        event.currentTarget = target;
        event.eventPhase = AT_TARGET;
        try {
            Listener[] listeners = target.listeners.toArray(new Listener[0]);
            if (!invoke(realm, target, event, listeners, true)) {
                invoke(realm, target, event, listeners, false);
            }
        } finally {
            event.eventPhase = NONE;
            event.currentTarget = null;
            event.dispatching = false;
            event.stopPropagation = false;
            event.stopImmediatePropagation = false;
        }
        return !event.canceled;
    }

    /** Registered symbol of the listener option that resists stopImmediatePropagation. */
    static final String RESIST_STOP_PROPAGATION = "nodejs.event_target.resistStopPropagation";

    private static boolean invoke(Realm realm, JSEventTarget target, JSEvent event,
                                  Listener[] listeners, boolean capture) {
        for (Listener listener : listeners) {
            if (listener.removed || !listener.type.equals(event.type) || listener.capture != capture) continue;
            if (event.stopImmediatePropagation && !listener.resistStopPropagation) continue;
            if (listener.once) target.removeListener(listener);
            if (listener.passive) event.inPassiveListener = true;
            try {
                callListener(listener, event, target);
            } catch (JSException error) {
                Object value = error.value;
                realm.agent.enqueueJob(() -> {
                    throw new JSException(value);
                });
            } finally {
                event.inPassiveListener = false;
            }
        }
        return event.stopImmediatePropagation;
    }

    private static void callListener(Listener listener, JSEvent event, JSObject self) {
        HandlerOf handler = listener.handlerOf;
        if (handler != null) {
            HandlerValue current = handler.target().handlers == null ? null
                    : handler.target().handlers.get(handler.name());
            Object value = current == null ? null : current.value();
            if (value instanceof JSObject function && function.isCallable()
                    && Boolean.FALSE.equals(Ops.call(function, self, new Object[] {event}))) {
                event.cancel();
            }
            return;
        }
        JSObject callback = listener.callback;
        if (callback == null) return;
        if (callback.isCallable()) {
            Ops.call(callback, self, new Object[] {event});
            return;
        }
        Object handleEvent = callback.get("handleEvent", callback);
        if (!Ops.isCallable(handleEvent)) throw typeErr("The listener has no handleEvent method");
        Ops.call(handleEvent, callback, new Object[] {event});
    }

    /**
     * An event handler IDL attribute ({@code onabort}) of {@code iface} for events of {@code type}.
     */
    static void eventHandler(WebInterface iface, String name, String type) {
        iface.attribute(name, (function, receiver, arguments, newTarget) -> {
            JSEventTarget target = Idl.self(JSEventTarget.class, receiver, iface.name, name);
            HandlerValue value = target.handlers == null ? null : target.handlers.get(name);
            return value == null ? Null.INSTANCE : value.value();
        }, (function, receiver, arguments, newTarget) -> {
            JSEventTarget target = Idl.self(JSEventTarget.class, receiver, iface.name, name);
            JSObject value = arg(arguments, 0) instanceof JSObject object ? object : null;
            HashMap<String, HandlerValue> map = target.handlers;
            if (map == null) {
                map = new HashMap<>();
                target.handlers = map;
            }
            HandlerValue old = map.get(name);
            if (value == null) {
                if (old != null) {
                    target.removeListener(old.listener());
                    map.remove(name);
                }
            } else if (old != null) {
                map.put(name, new HandlerValue(value, old.listener()));
            } else {
                Listener listener = new Listener(type, null, false, false, false);
                listener.handlerOf = new HandlerOf(target, name);
                target.listeners.add(listener);
                map.put(name, new HandlerValue(value, listener));
                target.listenerAdded(listener);
            }
            return Undefined.INSTANCE;
        });
    }
}
