/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.ProxyObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;

import java.util.ArrayList;
import java.util.List;

/** Reflect and Proxy built-ins; derived from NeonJS and adapted to Java. */
final class ReflectBuiltins {
    private ReflectBuiltins() {
    }

    private static JSObject target(Object value) {
        if (value instanceof JSObject object) return object;
        throw BuiltinsKt.typeErr("Reflect method called on non-object");
    }

    static void install(Realm realm) {
        JSObject r = new JSObject(realm.getObjectPrototype());
        BuiltinsKt.global(realm, "Reflect", r);
        BuiltinsKt.value(r, JSSymbol.toStringTag, "Reflect", Attr.CONFIGURABLE);
        BuiltinsKt.method(r, realm, "apply", 3, (function, receiver, args, newTarget) -> {
            Object callback = Values.arg(args, 0);
            if (!Ops.isCallable(callback)) throw BuiltinsKt.typeErr(Ops.describe(callback) + " is not a function");
            return ((JSObject) callback).call(Values.arg(args, 1), Ops.createListFromArrayLike(Values.arg(args, 2)));
        });
        BuiltinsKt.method(r, realm, "construct", 2, (function, receiver, args, newTarget) -> {
            Object constructor = Values.arg(args, 0);
            if (!Ops.isConstructor(constructor)) {
                throw BuiltinsKt.typeErr(Ops.describe(constructor) + " is not a constructor");
            }
            Object actualNewTarget = args.length > 2 ? args[2] : constructor;
            if (!Ops.isConstructor(actualNewTarget)) {
                throw BuiltinsKt.typeErr(Ops.describe(actualNewTarget) + " is not a constructor");
            }
            return ((JSObject) constructor).construct(Ops.createListFromArrayLike(Values.arg(args, 1)),
                    (JSObject) actualNewTarget);
        });
        BuiltinsKt.method(r, realm, "defineProperty", 3, (function, receiver, args, newTarget) -> {
            JSObject object = target(Values.arg(args, 0));
            Object key = Ops.toPropertyKey(Values.arg(args, 1));
            return object.defineOwnProperty(key, Ops.toPropertyDescriptor(Values.arg(args, 2)));
        });
        BuiltinsKt.method(r, realm, "deleteProperty", 2, (function, receiver, args, newTarget) ->
                target(Values.arg(args, 0)).delete(Ops.toPropertyKey(Values.arg(args, 1))));
        BuiltinsKt.method(r, realm, "get", 2, (function, receiver, args, newTarget) -> {
            JSObject object = target(Values.arg(args, 0));
            Object key = Ops.toPropertyKey(Values.arg(args, 1));
            return object.get(key, args.length > 2 ? args[2] : object);
        });
        BuiltinsKt.method(r, realm, "getOwnPropertyDescriptor", 2, (function, receiver, args, newTarget) -> {
            JSObject object = target(Values.arg(args, 0));
            return Ops.fromPropertyDescriptor(function.realm, object.getOwnProperty(Ops.toPropertyKey(Values.arg(args, 1))));
        });
        BuiltinsKt.method(r, realm, "getPrototypeOf", 1, (function, receiver, args, newTarget) -> {
            JSObject prototype = target(Values.arg(args, 0)).getPrototypeOf();
            return prototype == null ? Null.INSTANCE : prototype;
        });
        BuiltinsKt.method(r, realm, "has", 2, (function, receiver, args, newTarget) ->
                target(Values.arg(args, 0)).hasProperty(Ops.toPropertyKey(Values.arg(args, 1))));
        BuiltinsKt.method(r, realm, "isExtensible", 1, (function, receiver, args, newTarget) ->
                target(Values.arg(args, 0)).isExtensible());
        BuiltinsKt.method(r, realm, "ownKeys", 1, (function, receiver, args, newTarget) -> {
            List<Object> keys = target(Values.arg(args, 0)).ownPropertyKeys();
            List<Object> values = new ArrayList<>(keys.size());
            for (Object key : keys) values.add(PK.toValue(key));
            return Builtins.arrayOf(function.realm, values);
        });
        BuiltinsKt.method(r, realm, "preventExtensions", 1, (function, receiver, args, newTarget) ->
                target(Values.arg(args, 0)).preventExtensions());
        BuiltinsKt.method(r, realm, "set", 3, (function, receiver, args, newTarget) -> {
            JSObject object = target(Values.arg(args, 0));
            Object key = Ops.toPropertyKey(Values.arg(args, 1));
            return object.set(key, Values.arg(args, 2), args.length > 3 ? args[3] : object);
        });
        BuiltinsKt.method(r, realm, "setPrototypeOf", 2, (function, receiver, args, newTarget) -> {
            JSObject object = target(Values.arg(args, 0));
            Object prototype = Values.arg(args, 1);
            if (!(prototype instanceof JSObject) && prototype != Null.INSTANCE) {
                throw BuiltinsKt.typeErr("Object prototype may only be an Object or null");
            }
            return object.setPrototypeOf(prototype instanceof JSObject value ? value : null);
        });

        // Proxy
        NativeFunction proxy = new NativeFunction(realm, "Proxy", 2, (function, receiver, args, newTarget) -> {
            if (newTarget == null) throw BuiltinsKt.typeErr("Constructor Proxy requires 'new'");
            return createProxy(Values.arg(args, 0), Values.arg(args, 1));
        }, true);
        BuiltinsKt.global(realm, "Proxy", proxy);
        BuiltinsKt.method(proxy, realm, "revocable", 2, (function, receiver, args, newTarget) -> {
            ProxyObject p = createProxy(Values.arg(args, 0), Values.arg(args, 1));
            NativeFunction revoke = new NativeFunction(function.realm, "", 0, (revokeFunction, revokeReceiver,
                                                                             revokeArgs, revokeNewTarget) -> {
                ProxyObject proxyObject = revokeFunction.slot0 instanceof ProxyObject value ? value : null;
                if (proxyObject != null) {
                    revokeFunction.slot0 = null;
                    proxyObject.target = null;
                    proxyObject.handler = null;
                }
                return Undefined.INSTANCE;
            });
            revoke.slot0 = p;
            JSObject o = new JSObject(function.realm.getObjectPrototype());
            o.createDataPropertyOrThrow("proxy", p);
            o.createDataPropertyOrThrow("revoke", revoke);
            return o;
        });
    }

    static ProxyObject createProxy(Object target, Object handler) {
        if (!(target instanceof JSObject targetObject)) {
            throw BuiltinsKt.typeErr("Cannot create proxy with a non-object as target or handler");
        }
        if (!(handler instanceof JSObject handlerObject)) {
            throw BuiltinsKt.typeErr("Cannot create proxy with a non-object as target or handler");
        }
        return new ProxyObject(targetObject, handlerObject);
    }
}
