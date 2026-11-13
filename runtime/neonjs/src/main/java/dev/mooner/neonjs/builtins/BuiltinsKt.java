/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NativeImpl;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;

/** Builtin DSL helpers; derived from NeonJS and adapted to Java. */
public final class BuiltinsKt {
    private BuiltinsKt() {
    }

    /** The getter/setter pair returned by {@link #accessor}. */
    public record AccessorPair(NativeFunction getter, NativeFunction setter) {
    }

    /** Defines a builtin method (writable, configurable, non-enumerable by default). */
    public static NativeFunction method(JSObject object, Realm realm, Object name, int length, NativeImpl impl) {
        return method(object, realm, name, length, Attr.WC, impl);
    }

    public static NativeFunction method(JSObject object, Realm realm, Object name, int length, int attrs,
                                        NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, length, impl, false,
                realm.getFunctionPrototype(), null);
        object.defineOwn(name, function, attrs);
        return function;
    }

    public static NativeFunction getter(JSObject object, Realm realm, Object name, NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, 0, impl, false,
                realm.getFunctionPrototype(), "get");
        object.defineAccessor(name, function, Undefined.INSTANCE, Attr.CONFIGURABLE);
        return function;
    }

    public static AccessorPair accessor(JSObject object, Realm realm, Object name, NativeImpl get,
                                        NativeImpl set) {
        NativeFunction getter = new NativeFunction(realm, name, 0, get, false,
                realm.getFunctionPrototype(), "get");
        NativeFunction setter = new NativeFunction(realm, name, 1, set, false,
                realm.getFunctionPrototype(), "set");
        object.defineAccessor(name, getter, setter, Attr.CONFIGURABLE);
        return new AccessorPair(getter, setter);
    }

    public static void value(JSObject object, Object name, Object value) {
        value(object, name, value, Attr.WC);
    }

    public static void value(JSObject object, Object name, Object value, int attrs) {
        object.defineOwn(name, value, attrs);
    }

    /** Creates a constructor function with its prototype wiring (prototype: non-writable, constructor back-link). */
    public static NativeFunction makeCtor(Realm realm, String name, int length, JSObject proto, NativeImpl impl) {
        return makeCtor(realm, name, length, proto, realm.getFunctionPrototype(), impl);
    }

    public static NativeFunction makeCtor(Realm realm, String name, int length, JSObject proto,
                                          JSObject ctorProto, NativeImpl impl) {
        NativeFunction constructor = new NativeFunction(realm, name, length, impl, true, ctorProto, null);
        if (proto != null) {
            constructor.defineOwn("prototype", proto, Attr.NONE);
            proto.defineOwn("constructor", constructor, Attr.WC);
        }
        return constructor;
    }

    public static void global(Realm realm, String name, Object value) {
        global(realm, name, value, Attr.WC);
    }

    public static void global(Realm realm, String name, Object value, int attrs) {
        realm.getGlobalObject().defineOwn(name, value, attrs);
    }

    /** Relative index helper used by slice/splice/at etc. */
    public static long relIndex(Object value, long length, long defaultValue) {
        if (value == Undefined.INSTANCE) return defaultValue;
        double relative = Ops.toIntegerOrInfinity(value);
        if (relative < 0) return (long) Math.max(0.0, length + relative);
        return (long) Math.min(relative, (double) length);
    }

    public static JSException typeErr(String message) {
        return JSException.typeError(message);
    }

    public static JSException rangeErr(String message) {
        return JSException.rangeError(message);
    }

    public static JSObject callable(Object value) {
        if (!Ops.isCallable(value)) throw typeErr(Ops.describe(value) + " is not a function");
        return (JSObject) value;
    }
}
