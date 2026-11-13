/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Set methods (union, intersection, ...). */
final class SetMethods {
    private SetMethods() {
    }

    private record SetRecord(JSObject obj, double size, JSObject has, JSObject keys) {
    }

    private static SetRecord getSetRecord(Object value) {
        if (!(value instanceof JSObject object)) throw typeErr("Set operation argument must be an object");
        Object rawSize = object.get("size", object);
        double numericSize = Ops.toNumber(rawSize);
        if (Double.isNaN(numericSize)) throw typeErr("The 'size' property must be a number");
        double integerSize = Ops.integerPart(numericSize);
        if (integerSize < 0) throw rangeErr("The 'size' property must not be negative");
        Object has = object.get("has", object);
        if (!Ops.isCallable(has)) throw typeErr("The 'has' property must be callable");
        Object keys = object.get("keys", object);
        if (!Ops.isCallable(keys)) throw typeErr("The 'keys' property must be callable");
        return new SetRecord(object, integerSize, (JSObject) has, (JSObject) keys);
    }

    private static IteratorRecord keysIter(SetRecord record) {
        Object iterator = record.keys().call(record.obj(), EMPTY_ARGS);
        if (!(iterator instanceof JSObject object)) throw typeErr("keys() result is not an object");
        return new IteratorRecord(object, object.get("next", object));
    }

    private static JSMapObject thisSet(Object receiver) {
        if (receiver instanceof JSMapObject map && map.isSet) return map;
        throw typeErr("Set method called on incompatible receiver");
    }

    private static JSMapObject newSet(Realm realm) {
        return new JSMapObject(realm.intrinsic("%Set.prototype%"), true);
    }

    private static JSMapObject copy(Realm realm, JSMapObject source) {
        JSMapObject result = newSet(realm);
        source.table.forEachLive((key, value) -> result.table.set(key, key));
        return result;
    }

    private static Object canon(Object value) {
        if (value instanceof Double d && d == 0.0) return 0.0;
        return value;
    }

    static void install(Realm realm, JSObject prototype) {
        method(prototype, realm, "union", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            IteratorRecord iterator = keysIter(other);
            JSMapObject result = copy(function.realm, set);
            while (true) {
                Object value = Iteration.stepValue(iterator);
                if (value == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                Object canonical = canon(value);
                if (!result.table.has(canonical)) result.table.set(canonical, canonical);
            }
            return result;
        });
        method(prototype, realm, "intersection", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            JSMapObject result = newSet(function.realm);
            if (set.table.size() <= other.size()) {
                // live iteration over [[SetData]]: elements removed and re-added are visited again (spec index loop)
                TableCursor cursor = set.table.cursor();
                while (set.table.advance(cursor)) {
                    Object element = cursor.key;
                    if (Ops.toBoolean(other.has().call(other.obj(), new Object[] {element}))) {
                        if (!result.table.has(element)) result.table.set(element, element);
                    }
                }
            } else {
                IteratorRecord iterator = keysIter(other);
                while (true) {
                    Object value = Iteration.stepValue(iterator);
                    if (value == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                    Object canonical = canon(value);
                    if (set.table.has(canonical) && !result.table.has(canonical)) {
                        result.table.set(canonical, canonical);
                    }
                }
            }
            return result;
        });
        method(prototype, realm, "difference", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            JSMapObject result = copy(function.realm, set);
            if (set.table.size() <= other.size()) {
                // iterates the result copy (not the live receiver), removing elements found in other
                TableCursor cursor = result.table.cursor();
                while (result.table.advance(cursor)) {
                    Object element = cursor.key;
                    if (Ops.toBoolean(other.has().call(other.obj(), new Object[] {element}))) {
                        result.table.delete(element);
                    }
                }
            } else {
                IteratorRecord iterator = keysIter(other);
                while (true) {
                    Object value = Iteration.stepValue(iterator);
                    if (value == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                    result.table.delete(canon(value));
                }
            }
            return result;
        });
        method(prototype, realm, "symmetricDifference", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            IteratorRecord iterator = keysIter(other);
            JSMapObject result = copy(function.realm, set);
            while (true) {
                Object value = Iteration.stepValue(iterator);
                if (value == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                Object canonical = canon(value);
                if (set.table.has(canonical)) {
                    result.table.delete(canonical);
                } else if (!result.table.has(canonical)) {
                    result.table.set(canonical, canonical);
                }
            }
            return result;
        });
        method(prototype, realm, "isSubsetOf", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            if (set.table.size() > other.size()) return false;
            boolean result = true;
            // live iteration over [[SetData]]: elements removed and re-added are visited again (spec index loop)
            TableCursor cursor = set.table.cursor();
            while (set.table.advance(cursor)) {
                Object element = cursor.key;
                if (!Ops.toBoolean(other.has().call(other.obj(), new Object[] {element}))) {
                    result = false;
                    break;
                }
            }
            return result;
        });
        method(prototype, realm, "isSupersetOf", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            if (set.table.size() < other.size()) return false;
            IteratorRecord iterator = keysIter(other);
            boolean result = true;
            while (true) {
                Object value = Iteration.stepValue(iterator);
                if (value == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                if (!set.table.has(value)) {
                    Iteration.closeNormal(iterator);
                    result = false;
                    break;
                }
            }
            return result;
        });
        method(prototype, realm, "isDisjointFrom", 1, (function, receiver, arguments, newTarget) -> {
            JSMapObject set = thisSet(receiver);
            SetRecord other = getSetRecord(arg(arguments, 0));
            boolean result = true;
            if (set.table.size() <= other.size()) {
                // live iteration over [[SetData]]: elements removed and re-added are visited again (spec index loop)
                TableCursor cursor = set.table.cursor();
                while (set.table.advance(cursor)) {
                    Object element = cursor.key;
                    if (Ops.toBoolean(other.has().call(other.obj(), new Object[] {element}))) {
                        result = false;
                        break;
                    }
                }
            } else {
                IteratorRecord iterator = keysIter(other);
                while (true) {
                    Object value = Iteration.stepValue(iterator);
                    if (value == dev.mooner.neonjs.runtime.NotFound.INSTANCE) break;
                    if (set.table.has(value)) {
                        Iteration.closeNormal(iterator);
                        result = false;
                        break;
                    }
                }
            }
            return result;
        });
    }
}
