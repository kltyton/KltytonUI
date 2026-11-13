package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.vm.Frame;
import java.lang.ref.WeakReference;

/** A thrown JavaScript value without a host stack trace; derived from NeonJS and adapted to Java. */
public final class JSException extends RuntimeException {
    public final Object value;

    public JSException(Object value) {
        super(null, null, false, false);
        this.value = value;
    }

    @Override
    public String getMessage() {
        return describe();
    }

    public String describe() {
        if (value instanceof JSObject object) {
            try {
                Object name = object.get("name", object);
                Object message = object.get("message", object);
                String displayName = name == Undefined.INSTANCE ? "Error" : Ops.toDisplayString(name);
                String displayMessage = message == Undefined.INSTANCE ? "" : Ops.toDisplayString(message);
                return displayMessage.isEmpty() ? displayName
                        : displayName.isEmpty() ? displayMessage : displayName + ": " + displayMessage;
            } catch (RuntimeException error) {
                return "[object]";
            }
        }
        try {
            return Ops.toDisplayString(value);
        } catch (RuntimeException error) {
            return "<value>";
        }
    }

    public String getJsStack() {
        return value instanceof JSErrorObject error ? error.stackTrace : null;
    }

    public static JSException create(ErrorKind kind, String message) {
        Realm realm = Agent.currentRealmOrNull();
        if (realm == null) return new JSException(kind.getJsName() + ": " + message);
        JSErrorObject error = realm.newError(kind, message);
        Frame top = realm.agent.topFrame;
        if (top != null) {
            error.capturedAt = new WeakReference<>(top);
            error.capturedPc = top.pc;
        }
        return new JSException(error);
    }

    public static JSException typeError(String message) { return create(ErrorKind.TYPE, message); }
    public static JSException rangeError(String message) { return create(ErrorKind.RANGE, message); }
    public static JSException referenceError(String message) { return create(ErrorKind.REFERENCE, message); }
    public static JSException syntaxError(String message) { return create(ErrorKind.SYNTAX, message); }
    public static JSException uriError(String message) { return create(ErrorKind.URI, message); }
    public static JSException rangeErrorNoRealm(String message) { return create(ErrorKind.RANGE, message); }
}
