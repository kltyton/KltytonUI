/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.GlobalEnv;
import dev.mooner.neonjs.runtime.IntlProvider;
import dev.mooner.neonjs.runtime.IntlSupport;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Builtin intrinsics and global installation; derived from NeonJS and adapted to Java. */
public final class Builtins {
    /** Additional installers (RegExp, Date, TypedArrays, Intl, ...) registered by other modules. */
    public static final ArrayList<Consumer<Realm>> extensions = new ArrayList<>();

    private Builtins() {
    }

    /** Installs all intrinsics and globals into {@code realm}. */
    public static void install(Realm realm) {
        realm.enter(() -> {
            JSObject objectPrototype = new JSObject(null);
            objectPrototype.special |= JSObject.IMMUTABLE_PROTO;
            realm.objectPrototype = objectPrototype;
            NativeFunction functionPrototype = new NativeFunction(realm, "", 0,
                    (function, receiver, args, newTarget) -> Undefined.INSTANCE,
                    false, objectPrototype, null);
            realm.functionPrototype = functionPrototype;
            JSObject global = new JSObject(objectPrototype);
            realm.globalObject = global;
            realm.globalEnv = new GlobalEnv(realm, global, global);
            realm.throwTypeError = makeThrowTypeError(realm);

            ObjectBuiltins.install(realm);
            FunctionBuiltins.install(realm);
            ErrorBuiltins.install(realm);
            SymbolBuiltins.install(realm);
            BooleanBuiltins.install(realm);
            NumberBuiltins.install(realm);
            MathBuiltins.install(realm);
            BigIntBuiltins.install(realm);
            IteratorBuiltins.install(realm);
            ArrayBuiltins.install(realm);
            StringBuiltins.install(realm);
            GeneratorBuiltins.install(realm);
            PromiseBuiltins.install(realm);
            GlobalBuiltins.install(realm);
            JSONBuiltins.install(realm);
            ReflectBuiltins.install(realm);
            CollectionBuiltins.install(realm);
            DisposableBuiltins.install(realm);
            ModuleSourceBuiltins.install(realm);
            ShadowRealms.install(realm);
            RegExpBuiltins.install(realm);
            ArrayBufferBuiltins.install(realm);
            TypedArrayBuiltins.install(realm);
            DataViewBuiltins.install(realm);
            AtomicsBuiltins.install(realm);
            DateBuiltins.install(realm);
            dev.mooner.neonjs.builtins.temporal.TemporalBuiltins.install(realm);
            // last, so the provider can replace the locale-sensitive methods of every builtin
            if (realm.agent.config.intl) {
                IntlProvider provider = IntlSupport.getProvider();
                if (provider != null) provider.install(realm);
            }
            for (Consumer<Realm> extension : extensions) extension.accept(realm);
            return null;
        });
    }

    private static JSObject makeThrowTypeError(Realm realm) {
        NativeFunction function = new NativeFunction(realm, "", 0, (f, receiver, args, newTarget) -> {
            throw JSException.typeError("'caller', 'callee', and 'arguments' properties may not be accessed "
                    + "on strict mode functions or the arguments objects for calls to them");
        });
        function.defineOwn("length", 0.0, Attr.NONE);
        function.defineOwn("name", "", Attr.NONE);
        function.preventExtensions();
        return function;
    }

    /** SetIntegrityLevel(O, frozen) */
    public static boolean freeze(JSObject object) {
        return setIntegrityLevel(object, true);
    }

    public static boolean setIntegrityLevel(JSObject object, boolean frozen) {
        if (!object.preventExtensions()) return false;
        List<Object> keys = object.ownPropertyKeys();
        if (!frozen) {
            for (Object key : keys) {
                object.definePropertyOrThrow(key, new PropertyDescriptor().configurable(false));
            }
        } else {
            for (Object key : keys) {
                PropertyDescriptor descriptor = object.getOwnProperty(key);
                if (descriptor == null) continue;
                PropertyDescriptor next = descriptor.isAccessor()
                        ? new PropertyDescriptor().configurable(false)
                        : new PropertyDescriptor().configurable(false).writable(false);
                object.definePropertyOrThrow(key, next);
            }
        }
        return true;
    }

    public static boolean testIntegrityLevel(JSObject object, boolean frozen) {
        if (object.isExtensible()) return false;
        for (Object key : object.ownPropertyKeys()) {
            PropertyDescriptor descriptor = object.getOwnProperty(key);
            if (descriptor == null) continue;
            if (descriptor.configurable) return false;
            if (frozen && descriptor.isData() && descriptor.writable) return false;
        }
        return true;
    }

    /** CreateArrayFromList */
    public static JSArray arrayOf(Realm realm, List<?> items) {
        return JSArray.of(realm, items);
    }

    /** OrdinaryCreateFromConstructor helper: prototype from newTarget or intrinsic default. */
    public static JSObject protoFrom(JSObject newTarget, Function<Realm, JSObject> defaultPrototype, Realm realm) {
        return newTarget == null ? defaultPrototype.apply(realm)
                : Ops.getPrototypeFromConstructor(newTarget, defaultPrototype);
    }
}
