/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.Hole;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.mooner.neonjs.builtins.BuiltinsKt.callable;
import static dev.mooner.neonjs.builtins.BuiltinsKt.getter;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.relIndex;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Array constructor, prototype and iterator (ECMA-262 §23.1). */
final class ArrayBuiltins {
    static final long MAX_SAFE = 9007199254740991L;

    private ArrayBuiltins() {
    }

    /**
     * The property key of index {@code i}. Every generic loop over an array-like goes through here, and such a loop can
     * run up to 2^53 - 1 times without calling any JS ({@code Array.prototype.indexOf.call({ length: 2 ** 53 - 1 })}),
     * so this is also where those loops check the agent's limits: once per 1,024 consecutive indices.
     */
    static Object key(long i) {
        if ((i & 1023L) == 1023L) {
            Agent agent = Agent.current.get();
            if (agent != null) agent.checkInterrupt();
        }
        return PK.fromIndex(i);
    }

    /**
     * HasProperty(o, k) then Get(o, k), as the callback loops do them, with {@link #key}'s interrupt check: the value,
     * or {@link NotFound} if absent. An element of a dense array is read directly (for it neither step is observable);
     * anything else takes the generic steps. Called for each element anew, since a callback may change the array.
     */
    static Object element(JSObject o, long k) {
        if ((k & 1023L) == 1023L) {
            Agent agent = Agent.current.get();
            if (agent != null) agent.checkInterrupt();
        }
        if (o instanceof JSArray array && !array.sparse && k < array.denseLen) {
            Object v = array.dense[(int) k];
            if (v != Hole.INSTANCE) return v;
        }
        Object pk = PK.fromIndex(k);
        return o.hasProperty(pk) ? o.get(pk, o) : NotFound.INSTANCE;
    }

    /** CreateDataPropertyOrThrow(a, k, v), an array element directly when {@link JSArray#createIndexFast} decides it. */
    static void createElement(JSObject a, long k, Object v) {
        // a refusal (0) changed nothing: the generic steps refuse it again, with their error
        if (a instanceof JSArray array && k <= Integer.MAX_VALUE && array.createIndexFast((int) k, v) == 1) return;
        a.createDataPropertyOrThrow(PK.fromIndex(k), v);
    }

    static JSArray arrayCreate(Realm realm, long length) {
        return arrayCreate(realm, length, null);
    }

    static JSArray arrayCreate(Realm realm, long length, JSObject prototype) {
        if (length > JSArray.MAX_LENGTH) throw rangeErr("Invalid array length");
        JSArray a = new JSArray(prototype != null ? prototype : realm.getArrayPrototype());
        a.length = length;
        return a;
    }

    /** ArraySpeciesCreate */
    static JSObject speciesCreate(Realm realm, JSObject original, long length) {
        if (!Ops.isArray(original)) return arrayCreate(realm, length);
        Object c = original.get("constructor", original);
        if (Ops.isConstructor(c)) {
            Realm realmC = Ops.getFunctionRealm((JSObject) c);
            if (realmC != realm && c == realmC.getArrayConstructor()) c = Undefined.INSTANCE;
        }
        if (c instanceof JSObject object) {
            c = object.get(JSSymbol.species, object);
            if (c == Null.INSTANCE) c = Undefined.INSTANCE;
        }
        if (c == Undefined.INSTANCE) return arrayCreate(realm, length);
        if (!Ops.isConstructor(c)) throw typeErr("object.constructor[Symbol.species] is not a constructor");
        return (JSObject) ((JSObject) c).construct(new Object[] {Ops.num(length)}, (JSObject) c);
    }

    static long len(JSObject o) {
        return Ops.lengthOfArrayLike(o);
    }

    static void setLen(JSObject o, long n) {
        o.setOrThrow("length", Ops.num(n));
    }

    static boolean isConcatSpreadable(Object o) {
        if (!(o instanceof JSObject object)) return false;
        Object s = object.get(JSSymbol.isConcatSpreadable, object);
        if (s != Undefined.INSTANCE) return Ops.toBoolean(s);
        return Ops.isArray(object);
    }

    static void install(Realm realm) {
        JSObject proto = new JSArray(realm.getObjectPrototype());
        realm.setArrayPrototype(proto);
        NativeFunction ctor = makeCtor(realm, "Array", 1, proto, (f, receiver, args, nt) -> {
            JSObject p = Ops.getPrototypeFromConstructor(nt != null ? nt : f, r -> r.getArrayPrototype());
            if (args.length == 0) return arrayCreate(f.realm, 0, p);
            if (args.length == 1) {
                Object l = args[0];
                if (!(l instanceof Double)) {
                    JSArray a = arrayCreate(f.realm, 0, p);
                    a.pushInit(l);
                    return a;
                }
                long il = Ops.toUint32(l);
                if ((double) il != ((Double) l).doubleValue()) throw rangeErr("Invalid array length");
                return arrayCreate(f.realm, il, p);
            }
            return JSArray.of(p, Arrays.copyOf(args, args.length));
        });
        realm.setArrayConstructor(ctor);
        global(realm, "Array", ctor);
        getter(ctor, realm, JSSymbol.species, (f, receiver, args, nt) -> receiver);
        method(ctor, realm, "isArray", 1, (f, receiver, args, nt) -> Ops.isArray(arg(args, 0)));
        method(ctor, realm, "of", 0, (f, receiver, args, nt) -> {
            JSObject a;
            if (Ops.isConstructor(receiver)) {
                a = (JSObject) ((JSObject) receiver).construct(new Object[] {Ops.num(args.length)},
                        (JSObject) receiver);
            } else {
                a = arrayCreate(f.realm, args.length);
            }
            for (int i = 0; i < args.length; i++) a.createDataPropertyOrThrow(i, args[i]);
            setLen(a, args.length);
            return a;
        });
        method(ctor, realm, "from", 1, (f, receiver, args, nt) ->
                from(f.realm, receiver, arg(args, 0), arg(args, 1), arg(args, 2)));
        method(ctor, realm, "fromAsync", 1, (f, receiver, args, nt) ->
                ArrayFromAsync.start(f.realm, receiver, arg(args, 0), arg(args, 1), arg(args, 2)));

        // ---------------- prototype
        method(proto, realm, "at", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            double rel = Ops.toIntegerOrInfinity(arg(args, 0));
            double k = rel >= 0 ? rel : n + rel;
            if (k < 0 || k >= n) return Undefined.INSTANCE;
            return o.get(key((long) k), o);
        });
        method(proto, realm, "concat", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            JSObject a = speciesCreate(f.realm, o, 0);
            long n = 0L;
            ArrayList<Object> items = new ArrayList<>(args.length + 1);
            items.add(o);
            for (Object item : args) items.add(item);
            for (Object e : items) {
                if (isConcatSpreadable(e)) {
                    JSObject object = (JSObject) e;
                    long l = len(object);
                    if (n + l > MAX_SAFE) throw typeErr("Invalid array length");
                    long k = 0L;
                    while (k < l) {
                        Object pk = key(k);
                        if (object.hasProperty(pk)) {
                            a.createDataPropertyOrThrow(key(n), object.get(pk, object));
                        }
                        n++;
                        k++;
                    }
                } else {
                    if (n >= MAX_SAFE) throw typeErr("Invalid array length");
                    a.createDataPropertyOrThrow(key(n), e);
                    n++;
                }
            }
            setLen(a, n);
            return a;
        });
        method(proto, realm, "copyWithin", 2, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            long to = relIndex(arg(args, 0), n, 0);
            long from = relIndex(arg(args, 1), n, 0);
            long fin = relIndex(arg(args, 2), n, n);
            long count = Math.min(fin - from, n - to);
            if (count > 0) {
                int dir = 1;
                if (from < to && to < from + count) {
                    dir = -1;
                    from += count - 1;
                    to += count - 1;
                }
                while (count > 0) {
                    Object fk = key(from);
                    Object tk = key(to);
                    if (o.hasProperty(fk)) o.setOrThrow(tk, o.get(fk, o));
                    else o.deleteOrThrow(tk);
                    from += dir;
                    to += dir;
                    count--;
                }
            }
            return o;
        });
        method(proto, realm, "entries", 0, (f, receiver, args, nt) ->
                new ArrayIteratorObject(f.realm.getArrayIteratorPrototype(), Ops.toObject(receiver), 2));
        method(proto, realm, "every", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSObject cb = callable(arg(args, 0));
            boolean r = true;
            long k = 0L;
            while (k < n) {
                Object v = element(o, k);
                if (v != NotFound.INSTANCE
                        && !Ops.toBoolean(cb.call(arg(args, 1), new Object[] {v, Ops.num(k), o}))) {
                    r = false;
                    break;
                }
                k++;
            }
            return r;
        });
        method(proto, realm, "fill", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            Object v = arg(args, 0);
            long k = relIndex(arg(args, 1), n, 0);
            long fin = relIndex(arg(args, 2), n, n);
            while (k < fin) {
                o.setOrThrow(key(k), v);
                k++;
            }
            return o;
        });
        method(proto, realm, "filter", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSObject cb = callable(arg(args, 0));
            JSObject a = speciesCreate(f.realm, o, 0);
            long to = 0L;
            long k = 0L;
            while (k < n) {
                Object v = element(o, k);
                if (v != NotFound.INSTANCE
                        && Ops.toBoolean(cb.call(arg(args, 1), new Object[] {v, Ops.num(k), o}))) {
                    createElement(a, to, v);
                    to++;
                }
                k++;
            }
            return a;
        });
        FindSpec[] findSpecs = {
                new FindSpec("find", false, false),
                new FindSpec("findIndex", false, true),
                new FindSpec("findLast", true, false),
                new FindSpec("findLastIndex", true, true),
        };
        for (FindSpec spec : findSpecs) {
            method(proto, realm, spec.name(), 1, (f, receiver, args, nt) -> {
                JSObject o = Ops.toObject(receiver);
                long n = len(o);
                JSObject cb = callable(arg(args, 0));
                boolean fromEnd = spec.fromEnd();
                boolean wantIndex = spec.wantIndex();
                Object result = wantIndex ? Ops.num(-1.0) : Undefined.INSTANCE;
                long k = fromEnd ? n - 1 : 0L;
                while (fromEnd ? k >= 0 : k < n) {
                    Object v = o.get(key(k), o);
                    if (Ops.toBoolean(cb.call(arg(args, 1), new Object[] {v, Ops.num(k), o}))) {
                        result = wantIndex ? Ops.num(k) : v;
                        break;
                    }
                    if (fromEnd) k--;
                    else k++;
                }
                return result;
            });
        }
        method(proto, realm, "flat", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            double depth = 1.0;
            if (arg(args, 0) != Undefined.INSTANCE) {
                depth = Ops.toIntegerOrInfinity(arg(args, 0));
                if (depth < 0) depth = 0.0;
            }
            JSObject a = speciesCreate(f.realm, o, 0);
            flatten(a, o, n, 0L, depth, null, null);
            return a;
        });
        method(proto, realm, "flatMap", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSObject cb = callable(arg(args, 0));
            JSObject a = speciesCreate(f.realm, o, 0);
            flatten(a, o, n, 0L, 1.0, cb, arg(args, 1));
            return a;
        });
        method(proto, realm, "forEach", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSObject cb = callable(arg(args, 0));
            long k = 0L;
            while (k < n) {
                Object v = element(o, k);
                if (v != NotFound.INSTANCE) {
                    cb.call(arg(args, 1), new Object[] {v, Ops.num(k), o});
                }
                k++;
            }
            return Undefined.INSTANCE;
        });
        method(proto, realm, "includes", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            if (n == 0L) return false;
            long k = startIndex(arg(args, 1), n);
            boolean r = false;
            while (k < n) {
                if (Ops.sameValueZero(o.get(key(k), o), arg(args, 0))) {
                    r = true;
                    break;
                }
                k++;
            }
            return r;
        });
        method(proto, realm, "indexOf", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            if (n == 0L) return Ops.num(-1.0);
            long k = startIndex(arg(args, 1), n);
            double r = -1.0;
            while (k < n) {
                Object pk = key(k);
                if (o.hasProperty(pk) && Ops.strictEquals(o.get(pk, o), arg(args, 0))) {
                    r = (double) k;
                    break;
                }
                k++;
            }
            return Ops.num(r);
        });
        method(proto, realm, "join", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            String sep = arg(args, 0) == Undefined.INSTANCE ? "," : Ops.toString(arg(args, 0));
            StringBuilder sb = new StringBuilder();
            long k = 0L;
            while (k < n) {
                if (k > 0) sb.append(sep);
                Object e = o.get(key(k), o);
                if (e != Undefined.INSTANCE && e != Null.INSTANCE) sb.append(Ops.toString(e));
                if (sb.length() > 65536) f.realm.agent.checkStringLength(sb.length());
                k++;
            }
            return sb.toString();
        });
        method(proto, realm, "keys", 0, (f, receiver, args, nt) ->
                new ArrayIteratorObject(f.realm.getArrayIteratorPrototype(), Ops.toObject(receiver), 0));
        method(proto, realm, "lastIndexOf", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            if (n == 0L) return Ops.num(-1.0);
            long k;
            if (args.length > 1) {
                double fi = Ops.toIntegerOrInfinity(args[1]);
                if (fi == Double.NEGATIVE_INFINITY) k = -1L;
                else if (fi >= 0) k = (long) Math.min(fi, (double) (n - 1));
                else k = (long) (n + fi);
            } else {
                k = n - 1;
            }
            double r = -1.0;
            while (k >= 0) {
                Object pk = key(k);
                if (o.hasProperty(pk) && Ops.strictEquals(o.get(pk, o), arg(args, 0))) {
                    r = (double) k;
                    break;
                }
                k--;
            }
            return Ops.num(r);
        });
        method(proto, realm, "map", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSObject cb = callable(arg(args, 0));
            JSObject a = speciesCreate(f.realm, o, n);
            long k = 0L;
            while (k < n) {
                Object v = element(o, k);
                if (v != NotFound.INSTANCE) {
                    createElement(a, k, cb.call(arg(args, 1), new Object[] {v, Ops.num(k), o}));
                }
                k++;
            }
            return a;
        });
        method(proto, realm, "pop", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            if (n == 0L) {
                setLen(o, 0);
                return Undefined.INSTANCE;
            }
            Object pk = key(n - 1);
            Object e = o.get(pk, o);
            o.deleteOrThrow(pk);
            setLen(o, n - 1);
            return e;
        });
        method(proto, realm, "push", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            if (n + args.length > MAX_SAFE) {
                throw typeErr("Pushing " + args.length + " elements on an array-like of length " + n
                        + " is disallowed, as the total surpasses 2**53-1");
            }
            if (o instanceof JSArray array && array.lengthWritable && !array.sparse && array.extensible
                    && array.length == (long) array.denseLen && array.protoChainClean()) {
                for (Object a : args) array.pushInit(a);
                return Ops.num(array.length);
            }
            for (Object a : args) {
                o.setOrThrow(key(n), a);
                n++;
            }
            setLen(o, n);
            return Ops.num(n);
        });
        String[] reduceNames = {"reduce", "reduceRight"};
        boolean[] reduceFromRight = {false, true};
        for (int index = 0; index < reduceNames.length; index++) {
            String name = reduceNames[index];
            boolean right = reduceFromRight[index];
            method(proto, realm, name, 1, (f, receiver, args, nt) -> {
                JSObject o = Ops.toObject(receiver);
                long n = len(o);
                JSObject cb = callable(arg(args, 0));
                long k = right ? n - 1 : 0L;
                Object acc;
                if (args.length >= 2) {
                    acc = args[1];
                } else {
                    boolean found = false;
                    acc = Undefined.INSTANCE;
                    while (right ? k >= 0 : k < n) {
                        Object pk = key(k);
                        if (o.hasProperty(pk)) {
                            acc = o.get(pk, o);
                            found = true;
                            if (right) k--;
                            else k++;
                            break;
                        }
                        if (right) k--;
                        else k++;
                    }
                    if (!found) throw typeErr("Reduce of empty array with no initial value");
                }
                while (right ? k >= 0 : k < n) {
                    Object v = element(o, k);
                    if (v != NotFound.INSTANCE) {
                        acc = cb.call(Undefined.INSTANCE, new Object[] {acc, v, Ops.num(k), o});
                    }
                    if (right) k--;
                    else k++;
                }
                return acc;
            });
        }
        method(proto, realm, "reverse", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            long mid = n / 2;
            long lower = 0L;
            while (lower != mid) {
                long upper = n - lower - 1;
                Object lk = key(lower);
                Object uk = key(upper);
                boolean lowerExists = o.hasProperty(lk);
                Object lowerValue = lowerExists ? o.get(lk, o) : Undefined.INSTANCE;
                boolean upperExists = o.hasProperty(uk);
                Object upperValue = upperExists ? o.get(uk, o) : Undefined.INSTANCE;
                if (lowerExists && upperExists) {
                    o.setOrThrow(lk, upperValue);
                    o.setOrThrow(uk, lowerValue);
                } else if (upperExists) {
                    o.setOrThrow(lk, upperValue);
                    o.deleteOrThrow(uk);
                } else if (lowerExists) {
                    o.deleteOrThrow(lk);
                    o.setOrThrow(uk, lowerValue);
                }
                lower++;
            }
            return o;
        });
        method(proto, realm, "shift", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            if (n == 0L) {
                setLen(o, 0);
                return Undefined.INSTANCE;
            }
            Object first = o.get(0, o);
            long k = 1L;
            while (k < n) {
                Object from = key(k);
                Object to = key(k - 1);
                if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o));
                else o.deleteOrThrow(to);
                k++;
            }
            o.deleteOrThrow(key(n - 1));
            setLen(o, n - 1);
            return first;
        });
        method(proto, realm, "slice", 2, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            long k = relIndex(arg(args, 0), n, 0);
            long fin = relIndex(arg(args, 1), n, n);
            long count = Math.max(fin - k, 0L);
            JSObject a = speciesCreate(f.realm, o, count);
            long i = 0L;
            while (k < fin) {
                Object pk = key(k);
                if (o.hasProperty(pk)) a.createDataPropertyOrThrow(key(i), o.get(pk, o));
                k++;
                i++;
            }
            setLen(a, i);
            return a;
        });
        method(proto, realm, "some", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSObject cb = callable(arg(args, 0));
            boolean r = false;
            long k = 0L;
            while (k < n) {
                Object v = element(o, k);
                if (v != NotFound.INSTANCE
                        && Ops.toBoolean(cb.call(arg(args, 1), new Object[] {v, Ops.num(k), o}))) {
                    r = true;
                    break;
                }
                k++;
            }
            return r;
        });
        method(proto, realm, "sort", 1, (f, receiver, args, nt) -> {
            Object cmp = arg(args, 0);
            if (cmp != Undefined.INSTANCE && !Ops.isCallable(cmp)) {
                throw typeErr("The comparison function must be either a function or undefined");
            }
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            List<Object> items = collectItems(o, n, true);
            List<Object> sorted = mergeSort(items, cmp);
            long i = 0L;
            for (Object v : sorted) {
                o.setOrThrow(key(i), v);
                i++;
            }
            while (i < n) {
                o.deleteOrThrow(key(i));
                i++;
            }
            return o;
        });
        method(proto, realm, "splice", 2, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            long start = relIndex(arg(args, 0), n, 0);
            int itemCount;
            long delCount;
            if (args.length == 0) {
                itemCount = 0;
                delCount = 0;
            } else if (args.length == 1) {
                itemCount = 0;
                delCount = n - start;
            } else {
                itemCount = args.length - 2;
                double dc = Ops.toIntegerOrInfinity(args[1]);
                delCount = (long) Math.min(Math.max(dc, 0.0), (double) (n - start));
            }
            if (n + itemCount - delCount > MAX_SAFE) throw typeErr("Invalid array length");
            JSObject a = speciesCreate(f.realm, o, delCount);
            long k = 0L;
            while (k < delCount) {
                Object from = key(start + k);
                if (o.hasProperty(from)) a.createDataPropertyOrThrow(key(k), o.get(from, o));
                k++;
            }
            setLen(a, delCount);
            if (itemCount < delCount) {
                k = start;
                while (k < n - delCount) {
                    Object from = key(k + delCount);
                    Object to = key(k + itemCount);
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o));
                    else o.deleteOrThrow(to);
                    k++;
                }
                k = n;
                while (k > n - delCount + itemCount) {
                    o.deleteOrThrow(key(k - 1));
                    k--;
                }
            } else if (itemCount > delCount) {
                k = n - delCount;
                while (k > start) {
                    Object from = key(k + delCount - 1);
                    Object to = key(k + itemCount - 1);
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o));
                    else o.deleteOrThrow(to);
                    k--;
                }
            }
            for (int i = 0; i < itemCount; i++) o.setOrThrow(key(start + i), args[i + 2]);
            setLen(o, n - delCount + itemCount);
            return a;
        });
        method(proto, realm, "toLocaleString", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            StringBuilder sb = new StringBuilder();
            long k = 0L;
            while (k < n) {
                if (k > 0) sb.append(',');
                Object e = o.get(key(k), o);
                if (e != Undefined.INSTANCE && e != Null.INSTANCE) {
                    sb.append(Ops.toString(Ops.invoke(e, "toLocaleString", EMPTY_ARGS)));
                }
                k++;
            }
            return sb.toString();
        });
        method(proto, realm, "toReversed", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSArray a = arrayCreate(f.realm, n);
            long k = 0L;
            while (k < n) {
                a.createDataPropertyOrThrow(key(k), o.get(key(n - k - 1), o));
                k++;
            }
            return a;
        });
        method(proto, realm, "toSorted", 1, (f, receiver, args, nt) -> {
            Object cmp = arg(args, 0);
            if (cmp != Undefined.INSTANCE && !Ops.isCallable(cmp)) {
                throw typeErr("The comparison function must be either a function or undefined");
            }
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            JSArray a = arrayCreate(f.realm, n);
            List<Object> items = collectItems(o, n, false);
            List<Object> sorted = mergeSort(items, cmp);
            for (int i = 0; i < sorted.size(); i++) {
                a.createDataPropertyOrThrow(key(i), sorted.get(i));
            }
            return a;
        });
        method(proto, realm, "toSpliced", 2, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            long start = relIndex(arg(args, 0), n, 0);
            int insertCount;
            long skip;
            if (args.length == 0) {
                insertCount = 0;
                skip = 0;
            } else if (args.length == 1) {
                insertCount = 0;
                skip = n - start;
            } else {
                insertCount = args.length - 2;
                double sc = Ops.toIntegerOrInfinity(args[1]);
                skip = (long) Math.min(Math.max(sc, 0.0), (double) (n - start));
            }
            long newLen = n + insertCount - skip;
            if (newLen > MAX_SAFE) throw typeErr("Invalid array length");
            JSArray a = arrayCreate(f.realm, newLen);
            long i = 0L;
            long r = start + skip;
            while (i < start) {
                a.createDataPropertyOrThrow(key(i), o.get(key(i), o));
                i++;
            }
            for (int j = 0; j < insertCount; j++) {
                a.createDataPropertyOrThrow(key(i), args[j + 2]);
                i++;
            }
            while (i < newLen) {
                a.createDataPropertyOrThrow(key(i), o.get(key(r), o));
                i++;
                r++;
            }
            return a;
        });
        method(proto, realm, "toString", 0, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            Object join = o.get("join", o);
            if (Ops.isCallable(join)) return ((JSObject) join).call(o, EMPTY_ARGS);
            return ObjectBuiltins.objectToString(o);
        });
        method(proto, realm, "unshift", 1, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            int c = args.length;
            if (c > 0) {
                if (n + c > MAX_SAFE) throw typeErr("Invalid array length");
                long k = n;
                while (k > 0) {
                    Object from = key(k - 1);
                    Object to = key(k + c - 1);
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o));
                    else o.deleteOrThrow(to);
                    k--;
                }
                for (int j = 0; j < c; j++) o.setOrThrow(key(j), args[j]);
            }
            setLen(o, n + c);
            return Ops.num(n + c);
        });
        NativeFunction values = method(proto, realm, "values", 0, (f, receiver, args, nt) ->
                new ArrayIteratorObject(f.realm.getArrayIteratorPrototype(), Ops.toObject(receiver), 1));
        realm.setArrayProtoValues(values);
        proto.defineOwn(JSSymbol.iterator, values, Attr.WC);
        method(proto, realm, "with", 2, (f, receiver, args, nt) -> {
            JSObject o = Ops.toObject(receiver);
            long n = len(o);
            double rel = Ops.toIntegerOrInfinity(arg(args, 0));
            double actual = rel >= 0 ? rel : n + rel;
            if (actual >= n || actual < 0) throw rangeErr("Invalid index");
            JSArray a = arrayCreate(f.realm, n);
            long k = 0L;
            while (k < n) {
                a.createDataPropertyOrThrow(key(k),
                        (double) k == actual ? arg(args, 1) : o.get(key(k), o));
                k++;
            }
            return a;
        });
        JSObject uns = new JSObject(null);
        String[] unscopableNames = {"at", "copyWithin", "entries", "fill", "find", "findIndex", "findLast",
                "findLastIndex", "flat", "flatMap", "includes", "keys", "toReversed", "toSorted", "toSpliced",
                "values"};
        for (String name : unscopableNames) uns.createDataProperty(name, true);
        proto.defineOwn(JSSymbol.unscopables, uns, Attr.CONFIGURABLE);

        // ---------------- Array iterator
        JSObject aip = new JSObject(realm.getIteratorPrototype());
        realm.setArrayIteratorPrototype(aip);
        NativeFunction next = method(aip, realm, "next", 0, (f, receiver, args, nt) -> {
            if (!(receiver instanceof ArrayIteratorObject it)) {
                throw typeErr("next method called on incompatible receiver");
            }
            JSObject o = it.iterated;
            if (o == null) return Iteration.createIterResult(f.realm, Undefined.INSTANCE, true);
            long idx = it.index;
            long n;
            if (o instanceof TypedArrayLike typed) {
                if (typed.isOutOfBounds()) {
                    throw typeErr("Cannot perform %ArrayIteratorPrototype%.next on a detached "
                            + "or out-of-bounds TypedArray");
                }
                n = typed.typedLength();
            } else {
                n = len(o);
            }
            if (idx >= n) {
                it.iterated = null;
                return Iteration.createIterResult(f.realm, Undefined.INSTANCE, true);
            }
            it.index = idx + 1;
            switch (it.kind) {
                case 0:
                    return Iteration.createIterResult(f.realm, Ops.num(idx), false);
                case 1:
                    return Iteration.createIterResult(f.realm, o.get(key(idx), o), false);
                default:
                    return Iteration.createIterResult(f.realm,
                            Builtins.arrayOf(f.realm, Arrays.asList(Ops.num(idx), o.get(key(idx), o))), false);
            }
        });
        realm.setArrayIteratorNext(next);
        value(aip, JSSymbol.toStringTag, "Array Iterator", Attr.CONFIGURABLE);
    }

    private static long startIndex(Object v, long n) {
        double fi = Ops.toIntegerOrInfinity(v);
        if (fi == Double.POSITIVE_INFINITY) return n;
        if (fi == Double.NEGATIVE_INFINITY) return 0;
        return fi >= 0 ? (long) fi : Math.max(n + (long) fi, 0L);
    }

    private static long flatten(JSObject target, JSObject src, long srcLen, long start0, double depth,
                                JSObject mapper, Object thisArg) {
        long targetIndex = start0;
        long si = 0L;
        while (si < srcLen) {
            Object pk = key(si);
            if (src.hasProperty(pk)) {
                Object el = src.get(pk, src);
                if (mapper != null) el = mapper.call(thisArg, new Object[] {el, Ops.num(si), src});
                boolean shouldFlatten = false;
                if (depth > 0) shouldFlatten = Ops.isArray(el);
                if (shouldFlatten) {
                    JSObject object = (JSObject) el;
                    targetIndex = flatten(target, object, len(object), targetIndex,
                            depth == Double.POSITIVE_INFINITY ? depth : depth - 1, null, null);
                } else {
                    if (targetIndex >= MAX_SAFE) throw typeErr("Invalid array length");
                    target.createDataPropertyOrThrow(key(targetIndex), el);
                    targetIndex++;
                }
            }
            si++;
        }
        return targetIndex;
    }

    static ArrayList<Object> collectItems(JSObject o, long n, boolean skipHoles) {
        ArrayList<Object> items = new ArrayList<>();
        long k = 0L;
        while (k < n) {
            Object pk = key(k);
            if (!skipHoles || o.hasProperty(pk)) items.add(o.get(pk, o));
            k++;
        }
        return items;
    }

    /** SortCompare */
    static int sortCompare(Object x, Object y, Object cmp) {
        if (x == Undefined.INSTANCE && y == Undefined.INSTANCE) return 0;
        if (x == Undefined.INSTANCE) return 1;
        if (y == Undefined.INSTANCE) return -1;
        if (cmp != Undefined.INSTANCE) {
            double v = Ops.toNumber(((JSObject) cmp).call(Undefined.INSTANCE, new Object[] {x, y}));
            return Double.isNaN(v) ? 0 : v < 0 ? -1 : v > 0 ? 1 : 0;
        }
        String xs = Ops.toString(x);
        String ys = Ops.toString(y);
        int compared = xs.compareTo(ys);
        return compared < 0 ? -1 : compared > 0 ? 1 : 0;
    }

    /** Stable merge sort tolerant of inconsistent comparators. */
    static List<Object> mergeSort(List<Object> items, Object cmp) {
        int n = items.size();
        if (n < 2) return items;
        Object[] src = items.toArray();
        Object[] dst = new Object[n];
        // insertion sort runs of 8
        int run = 8;
        int i = 0;
        while (i < n) {
            int end = Math.min(i + run, n);
            for (int j = i + 1; j < end; j++) {
                Object v = src[j];
                int k = j - 1;
                while (k >= i && sortCompare(src[k], v, cmp) > 0) {
                    src[k + 1] = src[k];
                    k--;
                }
                src[k + 1] = v;
            }
            i = end;
        }
        int width = run;
        while (width < n) {
            int lo = 0;
            while (lo < n) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int a = lo;
                int b = mid;
                int o = lo;
                while (a < mid && b < hi) {
                    if (sortCompare(src[b], src[a], cmp) < 0) dst[o++] = src[b++];
                    else dst[o++] = src[a++];
                }
                while (a < mid) dst[o++] = src[a++];
                while (b < hi) dst[o++] = src[b++];
                lo = hi;
            }
            Object[] t = src;
            src = dst;
            dst = t;
            width *= 2;
        }
        return Arrays.asList(src);
    }

    /** Array.from */
    static Object from(Realm realm, Object c, Object items, Object mapfn, Object thisArg) {
        boolean mapping = mapfn != Undefined.INSTANCE;
        if (mapping && !Ops.isCallable(mapfn)) throw typeErr(Ops.describe(mapfn) + " is not a function");
        Object usingIterator = Ops.getMethod(items, JSSymbol.iterator);
        if (usingIterator != Undefined.INSTANCE) {
            JSObject a = Ops.isConstructor(c)
                    ? (JSObject) Ops.construct(c, EMPTY_ARGS)
                    : arrayCreate(realm, 0);
            IteratorRecord rec = Iteration.fromMethod(items, usingIterator);
            long k = 0L;
            while (true) {
                if (k >= MAX_SAFE) {
                    JSException err = JSException.typeError("Invalid array length");
                    Iteration.<Object, RuntimeException>closeAndRethrow(rec, err);
                    throw new IllegalStateException();
                }
                Object v = Iteration.stepValue(rec);
                if (v == NotFound.INSTANCE) {
                    setLen(a, k);
                    return a;
                }
                try {
                    Object mv = mapping
                            ? ((JSObject) mapfn).call(thisArg, new Object[] {v, Ops.num(k)})
                            : v;
                    a.createDataPropertyOrThrow(key(k), mv);
                } catch (Exception | StackOverflowError | OutOfMemoryError t) {
                    Iteration.<Object, RuntimeException>closeAndRethrow(rec, t);
                    throw new IllegalStateException();
                }
                k++;
            }
        }
        JSObject arrayLike = Ops.toObject(items);
        long n = len(arrayLike);
        JSObject a = Ops.isConstructor(c)
                ? (JSObject) Ops.construct(c, new Object[] {Ops.num(n)})
                : arrayCreate(realm, n);
        long k = 0L;
        while (k < n) {
            Object v = arrayLike.get(key(k), arrayLike);
            Object value = mapping
                    ? ((JSObject) mapfn).call(thisArg, new Object[] {v, Ops.num(k)})
                    : v;
            a.createDataPropertyOrThrow(key(k), value);
            k++;
        }
        setLen(a, n);
        return a;
    }

    private record FindSpec(String name, boolean fromEnd, boolean wantIndex) {
    }
}
