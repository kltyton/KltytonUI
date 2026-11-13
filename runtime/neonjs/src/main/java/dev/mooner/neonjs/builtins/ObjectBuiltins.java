/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Object constructor and prototype; derived from NeonJS and adapted to Java. */
final class ObjectBuiltins {
    private ObjectBuiltins() {
    }

    private record PropertyEntry(Object key, PropertyDescriptor descriptor) {
    }

    static void install(Realm realm) {
        JSObject proto = realm.objectPrototype;
        NativeFunction constructor = BuiltinsKt.makeCtor(realm, "Object", 1, proto,
                (function, thisValue, args, newTarget) -> {
                    if (newTarget != null && newTarget != function) {
                        return new JSObject(Ops.getPrototypeFromConstructor(newTarget,
                                Realm::getObjectPrototype));
                    }
                    Object value = Values.arg(args, 0);
                    return value == Undefined.INSTANCE || value == dev.mooner.neonjs.runtime.Null.INSTANCE
                            ? new JSObject(function.realm.getObjectPrototype())
                            : Ops.toObject(function.realm, value);
                });
        realm.objectConstructor = constructor;
        BuiltinsKt.global(realm, "Object", constructor);

        BuiltinsKt.method(constructor, realm, "assign", 2, (function, thisValue, args, newTarget) -> {
            JSObject to = Ops.toObject(Values.arg(args, 0));
            for (int i = 1; i < args.length; i++) {
                Object source = args[i];
                if (source == Undefined.INSTANCE || source == dev.mooner.neonjs.runtime.Null.INSTANCE) continue;
                JSObject from = Ops.toObject(source);
                for (Object key : from.ownPropertyKeys()) {
                    PropertyDescriptor descriptor = from.getOwnProperty(key);
                    if (descriptor != null && descriptor.enumerable) {
                        to.setOrThrow(key, from.get(key, from));
                    }
                }
            }
            return to;
        });
        BuiltinsKt.method(constructor, realm, "create", 2, (function, thisValue, args, newTarget) -> {
            Object prototype = Values.arg(args, 0);
            if (!(prototype instanceof JSObject) && prototype != dev.mooner.neonjs.runtime.Null.INSTANCE) {
                throw BuiltinsKt.typeErr("Object prototype may only be an Object or null: "
                        + Ops.toDisplayString(prototype));
            }
            JSObject object = new JSObject(prototype instanceof JSObject value ? value : null);
            Object properties = Values.arg(args, 1);
            if (properties != Undefined.INSTANCE) defineProperties(object, properties);
            return object;
        });
        BuiltinsKt.method(constructor, realm, "defineProperties", 2, (function, thisValue, args, newTarget) -> {
            Object target = Values.arg(args, 0);
            if (!(target instanceof JSObject object)) {
                throw BuiltinsKt.typeErr("Object.defineProperties called on non-object");
            }
            defineProperties(object, Values.arg(args, 1));
            return object;
        });
        BuiltinsKt.method(constructor, realm, "defineProperty", 3, (function, thisValue, args, newTarget) -> {
            Object target = Values.arg(args, 0);
            if (!(target instanceof JSObject object)) {
                throw BuiltinsKt.typeErr("Object.defineProperty called on non-object");
            }
            Object key = Ops.toPropertyKey(Values.arg(args, 1));
            PropertyDescriptor descriptor = Ops.toPropertyDescriptor(Values.arg(args, 2));
            object.definePropertyOrThrow(key, descriptor);
            return object;
        });
        BuiltinsKt.method(constructor, realm, "entries", 1, (function, thisValue, args, newTarget) ->
                enumerableOwn(function.realm, Ops.toObject(Values.arg(args, 0)), 2));
        BuiltinsKt.method(constructor, realm, "freeze", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (value instanceof JSObject object && !Builtins.setIntegrityLevel(object, true)) {
                throw BuiltinsKt.typeErr("Cannot freeze");
            }
            return value;
        });
        BuiltinsKt.method(constructor, realm, "fromEntries", 1, (function, thisValue, args, newTarget) -> {
            Object iterable = Ops.requireObjectCoercible(Values.arg(args, 0));
            JSObject object = new JSObject(function.realm.getObjectPrototype());
            IteratorRecord record = Iteration.getIterator(function.realm, iterable, false);
            while (true) {
                Object next = Iteration.stepValue(record);
                if (next == NotFound.INSTANCE) break;
                try {
                    if (!(next instanceof JSObject entry)) {
                        throw BuiltinsKt.typeErr("Iterator value " + Ops.toDisplayString(next)
                                + " is not an entry object");
                    }
                    Object key = entry.get(0, entry);
                    Object value = entry.get(1, entry);
                    object.createDataPropertyOrThrow(Ops.toPropertyKey(key), value);
                } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                    Iteration.<Object, RuntimeException>closeAndRethrow(record, error);
                    throw new IllegalStateException();
                }
            }
            return object;
        });
        BuiltinsKt.method(constructor, realm, "getOwnPropertyDescriptor", 2, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(Values.arg(args, 0));
            Object key = Ops.toPropertyKey(Values.arg(args, 1));
            return Ops.fromPropertyDescriptor(function.realm, object.getOwnProperty(key));
        });
        BuiltinsKt.method(constructor, realm, "getOwnPropertyDescriptors", 1,
                (function, thisValue, args, newTarget) -> {
                    JSObject object = Ops.toObject(Values.arg(args, 0));
                    JSObject result = new JSObject(function.realm.getObjectPrototype());
                    for (Object key : object.ownPropertyKeys()) {
                        Object descriptor = Ops.fromPropertyDescriptor(function.realm, object.getOwnProperty(key));
                        if (descriptor != Undefined.INSTANCE) result.createDataPropertyOrThrow(key, descriptor);
                    }
                    return result;
                });
        BuiltinsKt.method(constructor, realm, "getOwnPropertyNames", 1, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(Values.arg(args, 0));
            List<Object> names = new ArrayList<>();
            for (Object key : object.ownPropertyKeys()) {
                if (!(key instanceof JSSymbol)) names.add(PK.toValue(key));
            }
            return Builtins.arrayOf(function.realm, names);
        });
        BuiltinsKt.method(constructor, realm, "getOwnPropertySymbols", 1, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(Values.arg(args, 0));
            List<Object> symbols = new ArrayList<>();
            for (Object key : object.ownPropertyKeys()) {
                if (key instanceof JSSymbol) symbols.add(key);
            }
            return Builtins.arrayOf(function.realm, symbols);
        });
        BuiltinsKt.method(constructor, realm, "getPrototypeOf", 1, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(Values.arg(args, 0));
            JSObject prototype = object.getPrototypeOf();
            return prototype == null ? dev.mooner.neonjs.runtime.Null.INSTANCE : prototype;
        });
        BuiltinsKt.method(constructor, realm, "groupBy", 2, (function, thisValue, args, newTarget) -> {
            LinkedHashMap<Object, ArrayList<Object>> groups = groupBy(function.realm,
                    Values.arg(args, 0), Values.arg(args, 1), true);
            JSObject object = new JSObject(null);
            for (Map.Entry<Object, ArrayList<Object>> entry : groups.entrySet()) {
                object.createDataPropertyOrThrow(entry.getKey(), Builtins.arrayOf(function.realm, entry.getValue()));
            }
            return object;
        });
        BuiltinsKt.method(constructor, realm, "hasOwn", 2, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(Values.arg(args, 0));
            return object.hasOwnProperty(Ops.toPropertyKey(Values.arg(args, 1)));
        });
        BuiltinsKt.method(constructor, realm, "is", 2, (function, thisValue, args, newTarget) ->
                Ops.sameValue(Values.arg(args, 0), Values.arg(args, 1)));
        BuiltinsKt.method(constructor, realm, "isExtensible", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            return value instanceof JSObject object && object.isExtensible();
        });
        BuiltinsKt.method(constructor, realm, "isFrozen", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (!(value instanceof JSObject object)) return true;
            return Builtins.testIntegrityLevel(object, true);
        });
        BuiltinsKt.method(constructor, realm, "isSealed", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (!(value instanceof JSObject object)) return true;
            return Builtins.testIntegrityLevel(object, false);
        });
        BuiltinsKt.method(constructor, realm, "keys", 1, (function, thisValue, args, newTarget) ->
                enumerableOwn(function.realm, Ops.toObject(Values.arg(args, 0)), 0));
        BuiltinsKt.method(constructor, realm, "preventExtensions", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (value instanceof JSObject object && !object.preventExtensions()) {
                throw BuiltinsKt.typeErr("Cannot prevent extensions");
            }
            return value;
        });
        BuiltinsKt.method(constructor, realm, "seal", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (value instanceof JSObject object && !Builtins.setIntegrityLevel(object, false)) {
                throw BuiltinsKt.typeErr("Cannot seal");
            }
            return value;
        });
        BuiltinsKt.method(constructor, realm, "setPrototypeOf", 2, (function, thisValue, args, newTarget) -> {
            Object object = Ops.requireObjectCoercible(Values.arg(args, 0));
            Object prototype = Values.arg(args, 1);
            if (!(prototype instanceof JSObject) && prototype != dev.mooner.neonjs.runtime.Null.INSTANCE) {
                throw BuiltinsKt.typeErr("Object prototype may only be an Object or null: "
                        + Ops.toDisplayString(prototype));
            }
            if (object instanceof JSObject value
                    && !value.setPrototypeOf(prototype instanceof JSObject targetPrototype ? targetPrototype : null)) {
                throw BuiltinsKt.typeErr("Cannot set prototype");
            }
            return object;
        });
        BuiltinsKt.method(constructor, realm, "values", 1, (function, thisValue, args, newTarget) ->
                enumerableOwn(function.realm, Ops.toObject(Values.arg(args, 0)), 1));

        BuiltinsKt.method(proto, realm, "hasOwnProperty", 1, (function, thisValue, args, newTarget) -> {
            Object key = Ops.toPropertyKey(Values.arg(args, 0));
            return Ops.toObject(thisValue).hasOwnProperty(key);
        });
        BuiltinsKt.method(proto, realm, "isPrototypeOf", 1, (function, thisValue, args, newTarget) -> {
            Object value = Values.arg(args, 0);
            if (!(value instanceof JSObject objectValue)) return false;
            JSObject object = Ops.toObject(thisValue);
            JSObject prototype = objectValue.getPrototypeOf();
            while (prototype != null) {
                if (prototype == object) return true;
                prototype = prototype.getPrototypeOf();
            }
            return false;
        });
        BuiltinsKt.method(proto, realm, "propertyIsEnumerable", 1, (function, thisValue, args, newTarget) -> {
            Object key = Ops.toPropertyKey(Values.arg(args, 0));
            PropertyDescriptor descriptor = Ops.toObject(thisValue).getOwnProperty(key);
            return descriptor != null && descriptor.enumerable;
        });
        BuiltinsKt.method(proto, realm, "toLocaleString", 0, (function, thisValue, args, newTarget) ->
                Ops.invoke(thisValue, "toString", Values.EMPTY_ARGS));
        NativeFunction toString = BuiltinsKt.method(proto, realm, "toString", 0,
                (function, thisValue, args, newTarget) -> objectToString(thisValue));
        realm.intrinsics.put("%Object.prototype.toString%", toString);
        BuiltinsKt.method(proto, realm, "valueOf", 0, (function, thisValue, args, newTarget) ->
                Ops.toObject(thisValue));
        BuiltinsKt.accessor(proto, realm, "__proto__", (function, thisValue, args, newTarget) -> {
            JSObject prototype = Ops.toObject(thisValue).getPrototypeOf();
            return prototype == null ? dev.mooner.neonjs.runtime.Null.INSTANCE : prototype;
        }, (function, thisValue, args, newTarget) -> {
            Ops.requireObjectCoercible(thisValue);
            Object prototype = Values.arg(args, 0);
            if ((prototype instanceof JSObject || prototype == dev.mooner.neonjs.runtime.Null.INSTANCE)
                    && thisValue instanceof JSObject object) {
                if (!object.setPrototypeOf(prototype instanceof JSObject targetPrototype ? targetPrototype : null)) {
                    throw BuiltinsKt.typeErr("Object.prototype.__proto__ setter failed");
                }
            }
            return Undefined.INSTANCE;
        });
        BuiltinsKt.method(proto, realm, "__defineGetter__", 2, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(thisValue);
            Object getter = Values.arg(args, 1);
            if (!Ops.isCallable(getter)) throw BuiltinsKt.typeErr("Getter must be a function");
            object.definePropertyOrThrow(Ops.toPropertyKey(Values.arg(args, 0)),
                    new PropertyDescriptor().getter(getter).enumerable(true).configurable(true));
            return Undefined.INSTANCE;
        });
        BuiltinsKt.method(proto, realm, "__defineSetter__", 2, (function, thisValue, args, newTarget) -> {
            JSObject object = Ops.toObject(thisValue);
            Object setter = Values.arg(args, 1);
            if (!Ops.isCallable(setter)) throw BuiltinsKt.typeErr("Setter must be a function");
            object.definePropertyOrThrow(Ops.toPropertyKey(Values.arg(args, 0)),
                    new PropertyDescriptor().setter(setter).enumerable(true).configurable(true));
            return Undefined.INSTANCE;
        });
        BuiltinsKt.method(proto, realm, "__lookupGetter__", 1, (function, thisValue, args, newTarget) ->
                lookupAccessor(Ops.toObject(thisValue), Ops.toPropertyKey(Values.arg(args, 0)), true));
        BuiltinsKt.method(proto, realm, "__lookupSetter__", 1, (function, thisValue, args, newTarget) ->
                lookupAccessor(Ops.toObject(thisValue), Ops.toPropertyKey(Values.arg(args, 0)), false));
    }

    private static Object lookupAccessor(JSObject object, Object key, boolean getter) {
        JSObject current = object;
        while (current != null) {
            PropertyDescriptor descriptor = current.getOwnProperty(key);
            if (descriptor != null) {
                if (descriptor.isAccessor()) return getter ? descriptor.getter : descriptor.setter;
                return Undefined.INSTANCE;
            }
            current = current.getPrototypeOf();
        }
        return Undefined.INSTANCE;
    }

    static String objectToString(Object value) {
        if (value == Undefined.INSTANCE) return "[object Undefined]";
        if (value == dev.mooner.neonjs.runtime.Null.INSTANCE) return "[object Null]";
        JSObject object = Ops.toObject(value);
        String builtinTag;
        if (Ops.isArray(object)) {
            builtinTag = "Array";
        } else if (object.isCallable()) {
            builtinTag = "Function";
        } else {
            String name = object.getClassName();
            builtinTag = name.equals("Arguments") || name.equals("Error") || name.equals("Boolean")
                    || name.equals("Number") || name.equals("String") || name.equals("Date")
                    || name.equals("RegExp") ? name : "Object";
        }
        Object tag = object.get(JSSymbol.toStringTag, object);
        return "[object " + (tag instanceof CharSequence text ? text.toString() : builtinTag) + "]";
    }

    static void defineProperties(JSObject object, Object properties) {
        JSObject source = Ops.toObject(properties);
        ArrayList<PropertyEntry> descriptors = new ArrayList<>();
        for (Object key : source.ownPropertyKeys()) {
            PropertyDescriptor property = source.getOwnProperty(key);
            if (property != null && property.enumerable) {
                descriptors.add(new PropertyEntry(key, Ops.toPropertyDescriptor(source.get(key, source))));
            }
        }
        for (PropertyEntry entry : descriptors) object.definePropertyOrThrow(entry.key(), entry.descriptor());
    }

    /** EnumerableOwnProperties: kind 0 keys, 1 values, 2 entries. */
    static JSArray enumerableOwn(Realm realm, JSObject object, int kind) {
        ArrayList<Object> out = new ArrayList<>();
        for (Object key : object.ownPropertyKeys()) {
            if (key instanceof JSSymbol) continue;
            PropertyDescriptor descriptor = object.getOwnProperty(key);
            if (descriptor == null || !descriptor.enumerable) continue;
            if (kind == 0) out.add(PK.toValue(key));
            else if (kind == 1) out.add(object.get(key, object));
            else out.add(Builtins.arrayOf(realm, new ArrayList<>(
                    List.of(PK.toValue(key), object.get(key, object)))));
        }
        return Builtins.arrayOf(realm, out);
    }

    /** GroupBy(items, callback, property|zero key coercion). */
    static LinkedHashMap<Object, ArrayList<Object>> groupBy(Realm realm, Object items, Object callback,
                                                            boolean propertyKeys) {
        Ops.requireObjectCoercible(items);
        if (!Ops.isCallable(callback)) throw BuiltinsKt.typeErr("callback is not a function");
        LinkedHashMap<Object, ArrayList<Object>> groups = new LinkedHashMap<>();
        IteratorRecord record = Iteration.getIterator(realm, items, false);
        double index = 0.0;
        while (true) {
            Object value = Iteration.stepValue(record);
            if (value == NotFound.INSTANCE) break;
            try {
                Object key = ((JSObject) callback).call(Undefined.INSTANCE, new Object[]{value, index});
                if (propertyKeys) key = Ops.toPropertyKey(key);
                else if (key instanceof Double doubleKey && doubleKey == 0.0) key = 0.0;
                Object mapKey = key instanceof CharSequence text ? text.toString()
                        : (!propertyKeys ? MapKey.of(key) : key);
                groups.computeIfAbsent(mapKey, ignored -> new ArrayList<>()).add(value);
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                Iteration.<Object, RuntimeException>closeAndRethrow(record, error);
                throw new IllegalStateException();
            }
            index++;
        }
        return groups;
    }
}
