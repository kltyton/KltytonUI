/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import static dev.mooner.neonjs.builtins.BuiltinsKt.callable;
import static dev.mooner.neonjs.builtins.BuiltinsKt.getter;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;

final class CollectionBuiltins {
    private CollectionBuiltins() {
    }

    static boolean canBeHeldWeakly(Object value) {
        return value instanceof JSObject || (value instanceof JSSymbol symbol && symbol.getRegistryKey() == null);
    }

    static void install(Realm realm) {
        installMapSet(realm, false);
        installMapSet(realm, true);
        installWeak(realm, false);
        installWeak(realm, true);
        installWeakRef(realm);
        installFinalization(realm);
    }

    private static void installMapSet(Realm realm, boolean isSet) {
        String name = isSet ? "Set" : "Map";
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%" + name + ".prototype%", prototype);
        var constructor = makeCtor(realm, name, 0, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor " + name + " requires 'new'");
                    JSMapObject object = new JSMapObject(
                            Ops.getPrototypeFromConstructor(newTarget,
                                    targetRealm -> targetRealm.intrinsic("%" + name + ".prototype%")),
                            isSet);
                    Object iterable = Values.arg(arguments, 0);
                    if (iterable != Undefined.INSTANCE && iterable != Null.INSTANCE) {
                        Object adder = object.get(isSet ? "add" : "set", object);
                        if (!Ops.isCallable(adder)) {
                            throw typeErr("'" + (isSet ? "add" : "set")
                                    + "' returned for property is not a function");
                        }
                        IteratorRecord record = Iteration.getIterator(function.realm, iterable, false);
                        while (true) {
                            Object next = Iteration.stepValue(record);
                            if (next == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                            try {
                                if (isSet) {
                                    Ops.call(adder, object, new Object[] {next});
                                } else {
                                    if (!(next instanceof JSObject entry)) {
                                        throw typeErr("Iterator value " + Ops.toDisplayString(next)
                                                + " is not an entry object");
                                    }
                                    Object key = entry.get(0, entry);
                                    Object val = entry.get(1, entry);
                                    Ops.call(adder, object, new Object[] {key, val});
                                }
                            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                                throw closeAndRethrow(record, error);
                            }
                        }
                    }
                    return object;
                });
        realm.getIntrinsics().put("%" + name + "%", constructor);
        global(realm, name, constructor);
        getter(constructor, realm, JSSymbol.species, (function, receiver, arguments, newTarget) -> receiver);

        method(prototype, realm, "clear", 0, (function, receiver, arguments, newTarget) -> {
            thisMap(receiver, isSet, name, "clear").table.clear();
            return Undefined.INSTANCE;
        });
        method(prototype, realm, "delete", 1, (function, receiver, arguments, newTarget) ->
                thisMap(receiver, isSet, name, "delete").table.delete(Values.arg(arguments, 0)));
        method(prototype, realm, "has", 1, (function, receiver, arguments, newTarget) ->
                thisMap(receiver, isSet, name, "has").table.has(Values.arg(arguments, 0)));
        method(prototype, realm, "forEach", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject map = thisMap(receiver, isSet, name, "forEach");
            JSObject callback = callable(Values.arg(arguments, 0));
            Object thisArg = Values.arg(arguments, 1);
            map.table.forEachLive((key, val) ->
                    callback.call(thisArg, new Object[] {isSet ? key : val, key, map}));
            return Undefined.INSTANCE;
        });
        getter(prototype, realm, "size", (function, receiver, arguments, newTarget) ->
                (double) thisMap(receiver, isSet, name, "size").table.size());

        JSObject iteratorPrototype = new JSObject(realm.getIteratorPrototype());
        method(iteratorPrototype, realm, "next", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof CollectionIterator iterator)) {
                throw typeErr("next method called on incompatible receiver");
            }
            TableCursor cursor = iterator.cursor;
            if (cursor == null || !cursor.table.advance(cursor)) {
                iterator.cursor = null;
                return Iteration.createIterResult(function.realm, Undefined.INSTANCE, true);
            }
            Object result = switch (iterator.kind) {
                case 0 -> cursor.key;
                case 1 -> cursor.value;
                default -> Builtins.arrayOf(function.realm,
                        new ArrayList<>(java.util.Arrays.asList(cursor.key, cursor.value)));
            };
            return Iteration.createIterResult(function.realm, result, false);
        });
        value(iteratorPrototype, JSSymbol.toStringTag, name + " Iterator", Attr.CONFIGURABLE);

        if (isSet) {
            method(prototype, realm, "add", 1, (function, receiver, arguments, newTarget) -> {
                JSMapObject map = thisMap(receiver, isSet, name, "add");
                Object val = Values.arg(arguments, 0);
                if (!map.table.has(val)) map.table.set(val, val);
                return map;
            });
            var values = method(prototype, realm, "values", 0,
                    (function, receiver, arguments, newTarget) -> new CollectionIterator(iteratorPrototype,
                            thisMap(receiver, isSet, name, "values").table.cursor(), 0));
            prototype.defineOwn("keys", values, Attr.WC);
            prototype.defineOwn(JSSymbol.iterator, values, Attr.WC);
            method(prototype, realm, "entries", 0,
                    (function, receiver, arguments, newTarget) -> new CollectionIterator(iteratorPrototype,
                            thisMap(receiver, isSet, name, "entries").table.cursor(), 2));
            SetMethods.install(realm, prototype);
        } else {
            method(prototype, realm, "get", 1, (function, receiver, arguments, newTarget) ->
                    thisMap(receiver, isSet, name, "get").table.get(Values.arg(arguments, 0)));
            method(prototype, realm, "set", 2, (function, receiver, arguments, newTarget) -> {
                JSMapObject map = thisMap(receiver, isSet, name, "set");
                map.table.set(Values.arg(arguments, 0), Values.arg(arguments, 1));
                return map;
            });
            method(prototype, realm, "getOrInsert", 2, (function, receiver, arguments, newTarget) -> {
                JSMapObject map = thisMap(receiver, isSet, name, "getOrInsert");
                Object key = Values.arg(arguments, 0);
                if (!map.table.has(key)) map.table.set(key, Values.arg(arguments, 1));
                return map.table.get(key);
            });
            method(prototype, realm, "getOrInsertComputed", 2, (function, receiver, arguments, newTarget) -> {
                JSMapObject map = thisMap(receiver, isSet, name, "getOrInsertComputed");
                JSObject callback = callable(Values.arg(arguments, 1));
                Object key = Values.arg(arguments, 0);
                if (key instanceof Double d && d == 0.0) key = 0.0;
                if (map.table.has(key)) return map.table.get(key);
                Object computed = callback.call(Undefined.INSTANCE, new Object[] {key});
                map.table.set(key, computed);
                return computed;
            });
            method(prototype, realm, "keys", 0,
                    (function, receiver, arguments, newTarget) -> new CollectionIterator(iteratorPrototype,
                            thisMap(receiver, isSet, name, "keys").table.cursor(), 0));
            method(prototype, realm, "values", 0,
                    (function, receiver, arguments, newTarget) -> new CollectionIterator(iteratorPrototype,
                            thisMap(receiver, isSet, name, "values").table.cursor(), 1));
            var entries = method(prototype, realm, "entries", 0,
                    (function, receiver, arguments, newTarget) -> new CollectionIterator(iteratorPrototype,
                            thisMap(receiver, isSet, name, "entries").table.cursor(), 2));
            prototype.defineOwn(JSSymbol.iterator, entries, Attr.WC);
            method(constructor, realm, "groupBy", 2, (function, receiver, arguments, newTarget) -> {
                LinkedHashMap<Object, ArrayList<Object>> groups = ObjectBuiltins.groupBy(function.realm,
                        Values.arg(arguments, 0), Values.arg(arguments, 1), false);
                JSMapObject map = new JSMapObject(function.realm.intrinsic("%Map.prototype%"), false);
                for (Map.Entry<Object, ArrayList<Object>> entry : groups.entrySet()) {
                    Object key = entry.getKey();
                    map.table.set(key instanceof MapKey mapKey ? mapKey.value : key,
                            Builtins.arrayOf(function.realm, entry.getValue()));
                }
                return map;
            });
        }
        value(prototype, JSSymbol.toStringTag, name, Attr.CONFIGURABLE);
    }

    private static JSMapObject thisMap(Object receiver, boolean isSet, String name, String methodName) {
        if (receiver instanceof JSMapObject map && map.isSet == isSet) return map;
        throw typeErr("Method " + name + ".prototype." + methodName + " called on incompatible receiver "
                + Ops.describe(receiver));
    }

    private static void installWeak(Realm realm, boolean isSet) {
        String name = isSet ? "WeakSet" : "WeakMap";
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%" + name + ".prototype%", prototype);
        var constructor = makeCtor(realm, name, 0, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor " + name + " requires 'new'");
                    JSWeakCollection object = new JSWeakCollection(
                            Ops.getPrototypeFromConstructor(newTarget,
                                    targetRealm -> targetRealm.intrinsic("%" + name + ".prototype%")),
                            isSet);
                    Object iterable = Values.arg(arguments, 0);
                    if (iterable != Undefined.INSTANCE && iterable != Null.INSTANCE) {
                        Object adder = object.get(isSet ? "add" : "set", object);
                        if (!Ops.isCallable(adder)) throw typeErr("adder is not a function");
                        IteratorRecord record = Iteration.getIterator(function.realm, iterable, false);
                        while (true) {
                            Object next = Iteration.stepValue(record);
                            if (next == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                            try {
                                if (isSet) {
                                    Ops.call(adder, object, new Object[] {next});
                                } else {
                                    if (!(next instanceof JSObject entry)) {
                                        throw typeErr("Iterator value is not an entry object");
                                    }
                                    Ops.call(adder, object,
                                            new Object[] {entry.get(0, entry), entry.get(1, entry)});
                                }
                            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                                throw closeAndRethrow(record, error);
                            }
                        }
                    }
                    return object;
                });
        global(realm, name, constructor);

        method(prototype, realm, "delete", 1, (function, receiver, arguments, newTarget) -> {
            JSWeakCollection collection = thisWeak(receiver, isSet, name, "delete");
            Object key = Values.arg(arguments, 0);
            return canBeHeldWeakly(key) && removeKey(collection, key);
        });
        method(prototype, realm, "has", 1, (function, receiver, arguments, newTarget) -> {
            JSWeakCollection collection = thisWeak(receiver, isSet, name, "has");
            Object key = Values.arg(arguments, 0);
            return canBeHeldWeakly(key) && collection.map.containsKey(key);
        });
        if (isSet) {
            method(prototype, realm, "add", 1, (function, receiver, arguments, newTarget) -> {
                JSWeakCollection collection = thisWeak(receiver, isSet, name, "add");
                Object key = Values.arg(arguments, 0);
                if (!canBeHeldWeakly(key)) throw typeErr("Invalid value used in weak set");
                collection.map.put(key, Boolean.TRUE);
                return collection;
            });
        } else {
            method(prototype, realm, "get", 1, (function, receiver, arguments, newTarget) -> {
                JSWeakCollection collection = thisWeak(receiver, isSet, name, "get");
                Object key = Values.arg(arguments, 0);
                return !canBeHeldWeakly(key) || !collection.map.containsKey(key)
                        ? Undefined.INSTANCE : collection.map.get(key);
            });
            method(prototype, realm, "set", 2, (function, receiver, arguments, newTarget) -> {
                JSWeakCollection collection = thisWeak(receiver, isSet, name, "set");
                Object key = Values.arg(arguments, 0);
                if (!canBeHeldWeakly(key)) throw typeErr("Invalid value used as weak map key");
                collection.map.put(key, Values.arg(arguments, 1));
                return collection;
            });
            method(prototype, realm, "getOrInsert", 2, (function, receiver, arguments, newTarget) -> {
                JSWeakCollection collection = thisWeak(receiver, isSet, name, "getOrInsert");
                Object key = Values.arg(arguments, 0);
                if (!canBeHeldWeakly(key)) throw typeErr("Invalid value used as weak map key");
                if (!collection.map.containsKey(key)) collection.map.put(key, Values.arg(arguments, 1));
                return collection.map.get(key);
            });
            method(prototype, realm, "getOrInsertComputed", 2, (function, receiver, arguments, newTarget) -> {
                JSWeakCollection collection = thisWeak(receiver, isSet, name, "getOrInsertComputed");
                Object key = Values.arg(arguments, 0);
                if (!canBeHeldWeakly(key)) throw typeErr("Invalid value used as weak map key");
                JSObject callback = callable(Values.arg(arguments, 1));
                if (collection.map.containsKey(key)) return collection.map.get(key);
                Object computed = callback.call(Undefined.INSTANCE, new Object[] {key});
                collection.map.put(key, computed);
                return computed;
            });
        }
        value(prototype, JSSymbol.toStringTag, name, Attr.CONFIGURABLE);
    }

    private static JSWeakCollection thisWeak(Object receiver, boolean isSet, String name, String methodName) {
        if (receiver instanceof JSWeakCollection collection && collection.isSet == isSet) return collection;
        throw typeErr("Method " + name + ".prototype." + methodName + " called on incompatible receiver "
                + Ops.describe(receiver));
    }

    private static boolean removeKey(JSWeakCollection collection, Object key) {
        if (key == null) return false;
        if (!collection.map.containsKey(key)) return false;
        collection.map.remove(key);
        return true;
    }

    private static void installWeakRef(Realm realm) {
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%WeakRef.prototype%", prototype);
        var constructor = makeCtor(realm, "WeakRef", 1, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor WeakRef requires 'new'");
                    Object target = Values.arg(arguments, 0);
                    if (!canBeHeldWeakly(target)) {
                        throw typeErr("WeakRef: target must be an object or non-registered symbol");
                    }
                    JSWeakRef object = new JSWeakRef(
                            Ops.getPrototypeFromConstructor(newTarget,
                                    targetRealm -> targetRealm.intrinsic("%WeakRef.prototype%")),
                            target);
                    function.realm.agent.keptAlive.add(target);
                    return object;
                });
        global(realm, "WeakRef", constructor);
        method(prototype, realm, "deref", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof JSWeakRef weakRef)) {
                throw typeErr("WeakRef.prototype.deref called on incompatible receiver");
            }
            Object target = weakRef.ref.get();
            if (target == null) return Undefined.INSTANCE;
            function.realm.agent.keptAlive.add(target);
            return target;
        });
        value(prototype, JSSymbol.toStringTag, "WeakRef", Attr.CONFIGURABLE);
    }

    private static void installFinalization(Realm realm) {
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put("%FinalizationRegistry.prototype%", prototype);
        var constructor = makeCtor(realm, "FinalizationRegistry", 1, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor FinalizationRegistry requires 'new'");
                    Object callback = Values.arg(arguments, 0);
                    if (!Ops.isCallable(callback)) {
                        throw typeErr("FinalizationRegistry: cleanup must be callable");
                    }
                    return new JSFinalizationRegistry(
                            Ops.getPrototypeFromConstructor(newTarget,
                                    targetRealm -> targetRealm.intrinsic("%FinalizationRegistry.prototype%")),
                            (JSObject) callback, function.realm);
                });
        global(realm, "FinalizationRegistry", constructor);
        method(prototype, realm, "register", 2, (function, receiver, arguments, newTarget) -> {
            JSFinalizationRegistry registry = thisFinalization(receiver);
            Object target = Values.arg(arguments, 0);
            if (!canBeHeldWeakly(target)) {
                throw typeErr("FinalizationRegistry.prototype.register: invalid target");
            }
            Object held = Values.arg(arguments, 1);
            if (Ops.sameValue(target, held)) {
                throw typeErr("FinalizationRegistry.prototype.register: target and holdings must not be same");
            }
            Object token = Values.arg(arguments, 2);
            if (!canBeHeldWeakly(token) && token != Undefined.INSTANCE) {
                throw typeErr("FinalizationRegistry.prototype.register: invalid unregister token");
            }
            registry.cells.add(new JSFinalizationRegistry.Cell(target, held,
                    token == Undefined.INSTANCE ? null : token, registry.queue, registry));
            return Undefined.INSTANCE;
        });
        method(prototype, realm, "unregister", 1, (function, receiver, arguments, newTarget) -> {
            JSFinalizationRegistry registry = thisFinalization(receiver);
            Object token = Values.arg(arguments, 0);
            if (!canBeHeldWeakly(token)) throw typeErr("Invalid unregisterToken");
            boolean removed = false;
            var iterator = registry.cells.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().token == token) {
                    iterator.remove();
                    removed = true;
                }
            }
            return removed;
        });
        method(prototype, realm, "cleanupSome", 0, (function, receiver, arguments, newTarget) -> {
            JSFinalizationRegistry registry = thisFinalization(receiver);
            Object callback = Values.arg(arguments, 0);
            if (callback != Undefined.INSTANCE && !Ops.isCallable(callback)) {
                throw typeErr("cleanupSome callback must be callable");
            }
            cleanup(registry, callback == Undefined.INSTANCE ? registry.cleanup : (JSObject) callback);
            return Undefined.INSTANCE;
        });
        value(prototype, JSSymbol.toStringTag, "FinalizationRegistry", Attr.CONFIGURABLE);
    }

    private static JSFinalizationRegistry thisFinalization(Object receiver) {
        if (receiver instanceof JSFinalizationRegistry registry) return registry;
        throw typeErr("FinalizationRegistry method called on incompatible receiver");
    }

    static void cleanup(JSFinalizationRegistry registry, JSObject callback) {
        while (true) {
            var reference = registry.queue.poll();
            if (reference == null) break;
            JSFinalizationRegistry.Cell cell = (JSFinalizationRegistry.Cell) reference;
            if (registry.cells.remove(cell)) callback.call(Undefined.INSTANCE, new Object[] {cell.held});
        }
    }

    private static RuntimeException closeAndRethrow(IteratorRecord record, Throwable error) {
        Iteration.<Object, RuntimeException>closeAndRethrow(record, error);
        throw new AssertionError("unreachable");
    }
}
