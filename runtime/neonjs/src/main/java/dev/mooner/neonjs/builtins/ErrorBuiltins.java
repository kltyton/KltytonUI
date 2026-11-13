/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.PropertyMap;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.util.List;

/** Error constructors and prototypes; derived from NeonJS and adapted to Java. */
public final class ErrorBuiltins {
    private ErrorBuiltins() {
    }

    static void install(Realm realm) {
        JSObject errorPrototype = new JSObject(realm.objectPrototype);
        realm.errorPrototype = errorPrototype;
        realm.errorPrototypes.put(ErrorKind.ERROR, errorPrototype);
        var errorConstructor = BuiltinsKt.makeCtor(realm, "Error", 1, errorPrototype,
                (function, thisValue, args, newTarget) -> construct(function, newTarget, args, ErrorKind.ERROR));
        realm.errorConstructors.put(ErrorKind.ERROR, errorConstructor);
        BuiltinsKt.value(errorPrototype, "name", "Error");
        BuiltinsKt.value(errorPrototype, "message", "");
        BuiltinsKt.method(errorPrototype, realm, "toString", 0, (function, thisValue, args, newTarget) -> {
            if (!(thisValue instanceof JSObject object)) {
                throw BuiltinsKt.typeErr("Error.prototype.toString called on non-object");
            }
            Object nameValue = object.get("name", object);
            Object messageValue = object.get("message", object);
            String name = nameValue == Undefined.INSTANCE ? "Error" : Ops.toString(nameValue);
            String message = messageValue == Undefined.INSTANCE ? "" : Ops.toString(messageValue);
            if (name.isEmpty()) return message;
            if (message.isEmpty()) return name;
            return name + ": " + message;
        });
        BuiltinsKt.method(errorConstructor, realm, "isError", 1, (function, thisValue, args, newTarget) ->
                Values.arg(args, 0) instanceof JSErrorObject);
        BuiltinsKt.accessor(errorPrototype, realm, "stack", (function, thisValue, args, newTarget) -> {
            if (!(thisValue instanceof JSObject object)) {
                throw BuiltinsKt.typeErr("Error.prototype.stack getter called on non-object");
            }
            return object instanceof JSErrorObject error ? stackString(error) : Undefined.INSTANCE;
        }, (function, thisValue, args, newTarget) -> {
            if (!(thisValue instanceof JSObject object)) {
                throw BuiltinsKt.typeErr("Error.prototype.stack setter called on non-object");
            }
            Object value = Values.arg(args, 0);
            if (!(value instanceof CharSequence text)) {
                throw BuiltinsKt.typeErr("Error.prototype.stack value must be a string");
            }
            IteratorBuiltins.setterThatIgnoresPrototypeProperties(object, function.realm.errorPrototype,
                    "stack", text.toString());
            return Undefined.INSTANCE;
        });
        BuiltinsKt.global(realm, "Error", errorConstructor);
        for (ErrorKind kind : List.of(ErrorKind.EVAL, ErrorKind.RANGE, ErrorKind.REFERENCE, ErrorKind.SYNTAX,
                ErrorKind.TYPE, ErrorKind.URI)) {
            JSObject prototype = new JSObject(errorPrototype);
            realm.errorPrototypes.put(kind, prototype);
            var constructor = BuiltinsKt.makeCtor(realm, kind.getJsName(), 1, prototype, errorConstructor,
                    (function, thisValue, args, newTarget) -> construct(function, newTarget, args, kind));
            realm.errorConstructors.put(kind, constructor);
            BuiltinsKt.value(prototype, "name", kind.getJsName());
            BuiltinsKt.value(prototype, "message", "");
            BuiltinsKt.global(realm, kind.getJsName(), constructor);
        }

        JSObject aggregatePrototype = new JSObject(errorPrototype);
        realm.errorPrototypes.put(ErrorKind.AGGREGATE, aggregatePrototype);
        var aggregateConstructor = BuiltinsKt.makeCtor(realm, "AggregateError", 2, aggregatePrototype, errorConstructor,
                (function, thisValue, args, newTarget) -> {
                    JSErrorObject object = new JSErrorObject(Builtins.protoFrom(
                            newTarget != null ? newTarget : function,
                            r -> r.errorPrototypes.get(ErrorKind.AGGREGATE), function.realm));
                    Object message = Values.arg(args, 1);
                    if (message != Undefined.INSTANCE) {
                        object.defineOwn("message", Ops.toString(message), Attr.WC);
                    }
                    installCause(object, Values.arg(args, 2));
                    List<Object> errors = Iteration.toList(function.realm, Values.arg(args, 0));
                    object.defineOwn("errors", Builtins.arrayOf(function.realm, errors), Attr.WC);
                    captureStack(function.realm, object);
                    return object;
                });
        realm.errorConstructors.put(ErrorKind.AGGREGATE, aggregateConstructor);
        BuiltinsKt.value(aggregatePrototype, "name", "AggregateError");
        BuiltinsKt.value(aggregatePrototype, "message", "");
        BuiltinsKt.global(realm, "AggregateError", aggregateConstructor);

        JSObject suppressedPrototype = new JSObject(errorPrototype);
        realm.errorPrototypes.put(ErrorKind.SUPPRESSED, suppressedPrototype);
        var suppressedConstructor = BuiltinsKt.makeCtor(realm, "SuppressedError", 3, suppressedPrototype,
                errorConstructor, (function, thisValue, args, newTarget) -> {
                    JSErrorObject object = new JSErrorObject(Builtins.protoFrom(
                            newTarget != null ? newTarget : function,
                            r -> r.errorPrototypes.get(ErrorKind.SUPPRESSED), function.realm));
                    Object message = Values.arg(args, 2);
                    if (message != Undefined.INSTANCE) {
                        object.defineOwn("message", Ops.toString(message), Attr.WC);
                    }
                    object.defineOwn("error", Values.arg(args, 0), Attr.WC);
                    object.defineOwn("suppressed", Values.arg(args, 1), Attr.WC);
                    captureStack(function.realm, object);
                    return object;
                });
        realm.errorConstructors.put(ErrorKind.SUPPRESSED, suppressedConstructor);
        BuiltinsKt.value(suppressedPrototype, "name", "SuppressedError");
        BuiltinsKt.value(suppressedPrototype, "message", "");
        BuiltinsKt.global(realm, "SuppressedError", suppressedConstructor);
    }

    /** Implementation-defined stack string ("Name: message" header + frames) without invoking user code. */
    public static String stackString(JSErrorObject error) {
        String name = error.getSlotName() != null ? error.getSlotName() : dataProperty(error, "name");
        if (name == null) name = "Error";
        String message = error.getSlotMessage() != null ? error.getSlotMessage() : dataProperty(error, "message");
        if (message == null) message = "";
        String head = message.isEmpty() ? name : name + ": " + message;
        String stack = error.stackTrace;
        return stack == null || stack.isEmpty() ? head : head + "\n" + stack;
    }

    private static String dataProperty(JSErrorObject error, String key) {
        JSObject object = error;
        while (object != null) {
            PropertyMap map = object.props;
            if (map != null) {
                int index = map.find(key);
                if (index >= 0) {
                    if ((map.flags[index] & Attr.ACCESSOR) == 0 && map.values[index] instanceof CharSequence text) {
                        return text.toString();
                    }
                    return null;
                }
            }
            if ((object.special & JSObject.SPECIAL_ALL) != 0) return null;
            object = object.proto;
        }
        return null;
    }

    private static void captureStack(Realm realm, JSErrorObject error) {
        if (realm.agent.topFrame != null) error.stackTrace = realm.agent.captureStack();
    }

    private static void installCause(JSObject object, Object options) {
        if (options instanceof JSObject value && value.hasProperty("cause")) {
            object.defineOwn("cause", value.get("cause", value), Attr.WC);
        }
    }

    private static JSObject construct(dev.mooner.neonjs.runtime.NativeFunction function, JSObject newTarget,
                                      Object[] args, ErrorKind kind) {
        JSErrorObject object = new JSErrorObject(Builtins.protoFrom(
                newTarget != null ? newTarget : function,
                r -> r.errorPrototypes.get(kind), function.realm));
        Object message = Values.arg(args, 0);
        if (message != Undefined.INSTANCE) object.defineOwn("message", Ops.toString(message), Attr.WC);
        installCause(object, Values.arg(args, 1));
        captureStack(function.realm, object);
        return object;
    }
}
