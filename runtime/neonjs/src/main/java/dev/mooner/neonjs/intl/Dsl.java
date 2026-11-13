/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NativeImpl;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;

import java.util.List;

/**
 * Small copies of the core builtin DSL (whose helpers are internal to neonjs-core).
 * Java adaptation: Kotlin lambdas with receivers and default arguments are represented by
 * explicit helper overloads; all objects use the runtime's Java accessors.
 */
final class Dsl {
    private Dsl() {
    }

    static NativeFunction method(JSObject object, Realm realm, Object name, int length, NativeImpl impl) {
        return method(object, realm, name, length, Attr.WC, impl);
    }

    static NativeFunction method(JSObject object, Realm realm, Object name, int length, int attrs, NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, length, impl, false, realm.getFunctionPrototype(), null);
        object.defineOwn(name, function, attrs);
        return function;
    }

    static NativeFunction getter(JSObject object, Realm realm, Object name, NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, 0, impl, false, realm.getFunctionPrototype(), "get");
        object.defineAccessor(name, function, Undefined.INSTANCE, Attr.CONFIGURABLE);
        return function;
    }

    static NativeFunction makeCtor(Realm realm, String name, int length, JSObject proto, NativeImpl impl) {
        NativeFunction constructor = new NativeFunction(realm, name, length, impl, true, realm.getFunctionPrototype(), null);
        constructor.defineOwn("prototype", proto, Attr.NONE);
        proto.defineOwn("constructor", constructor, Attr.WC);
        return constructor;
    }

    static JSException typeErr(String message) {
        return JSException.typeError(message);
    }

    static JSException rangeErr(String message) {
        return JSException.rangeError(message);
    }

    /** CreateArrayFromList */
    static JSArray jsArray(Realm realm, List<Object> items) {
        return JSArray.of(realm, items);
    }

    /** OrdinaryObjectCreate(%Object.prototype%) */
    static JSObject plainObject(Realm realm) {
        return new JSObject(realm.getObjectPrototype());
    }

    /** OrdinaryCreateFromConstructor: prototype from newTarget (or the intrinsic named {@code protoName}). */
    static JSObject protoFromCtor(JSObject newTarget, String protoName, Realm realm) {
        if (newTarget == null) {
            return realm.intrinsic(protoName);
        }
        Object prototype = newTarget.get("prototype", newTarget);
        if (prototype instanceof JSObject) {
            return (JSObject) prototype;
        }
        return Ops.getFunctionRealm(newTarget).intrinsic(protoName);
    }

    /** Creates a complete data property descriptor (CreateDataPropertyDescriptor). */
    static dev.mooner.neonjs.runtime.PropertyDescriptor data(Object value, int attrs) {
        dev.mooner.neonjs.runtime.PropertyDescriptor descriptor =
                new dev.mooner.neonjs.runtime.PropertyDescriptor();
        descriptor.value = value;
        descriptor.writable = (attrs & Attr.WRITABLE) != 0;
        descriptor.enumerable = (attrs & Attr.ENUMERABLE) != 0;
        descriptor.configurable = (attrs & Attr.CONFIGURABLE) != 0;
        descriptor.present = dev.mooner.neonjs.runtime.PropertyDescriptor.HAS_VALUE
                | dev.mooner.neonjs.runtime.PropertyDescriptor.HAS_WRITABLE
                | dev.mooner.neonjs.runtime.PropertyDescriptor.HAS_ENUMERABLE
                | dev.mooner.neonjs.runtime.PropertyDescriptor.HAS_CONFIGURABLE;
        return descriptor;
    }

    static String str(Object value) {
        return value instanceof CharSequence ? value.toString() : Ops.toString(value);
    }

    /** Checks a produced string against the context's string-length limit before returning it to JS. */
    static String checkedString(Realm realm, CharSequence value) {
        realm.agent.checkStringLength(value.length());
        return value.toString();
    }

    /** Calls {@code Agent.checkInterrupt} once every 1024 iterations of a loop over a user-controlled size. */
    static void tick(Realm realm, long index) {
        if ((index & 1023L) == 1023L) {
            realm.agent.checkInterrupt();
        }
    }

    /** Creates a { type, value } part object as used by every formatToParts. */
    static JSObject partObject(Realm realm, String type, String value) {
        JSObject object = plainObject(realm);
        object.createDataPropertyOrThrow("type", type);
        object.createDataPropertyOrThrow("value", value);
        return object;
    }
}
