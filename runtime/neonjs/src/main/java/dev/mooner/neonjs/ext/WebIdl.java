/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.JSArrayBuffer;
import dev.mooner.neonjs.builtins.JSDataView;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NativeImpl;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A web interface as WebIDL's ECMAScript binding defines it: an interface object that must be called with {@code new}
 * (and always throws when the interface has no constructor), a prototype whose operations and attributes are
 * enumerable properties and whose @@toStringTag is the interface name, constants on both, and inheritance on both
 * chains.
 */
final class WebInterface {
    final Realm realm;
    final String name;
    final JSObject proto;
    final NativeFunction ctor;

    private WebInterface(Realm realm, String name, JSObject proto, NativeFunction ctor) {
        this.realm = realm;
        this.name = name;
        this.proto = proto;
        this.ctor = ctor;
    }

    @FunctionalInterface
    interface Construct {
        JSObject create(Object[] args, JSObject prototype);
    }

    /** A regular operation: an enumerable method of the prototype. */
    NativeFunction operation(Object name, int length, NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, length, impl);
        proto.defineOwn(name, function, Attr.ALL);
        return function;
    }

    /** An operation returning a promise: an exception thrown by the implementation rejects the promise. */
    NativeFunction promiseOperation(Object name, int length, NativeImpl impl) {
        return operation(name, length, (function, receiver, arguments, newTarget) -> {
            try {
                return dev.mooner.neonjs.vm.Promises.promiseResolve(function.realm,
                        function.realm.getPromiseConstructor(), impl.invoke(function, receiver, arguments, newTarget));
            } catch (JSException error) {
                var promise = dev.mooner.neonjs.vm.Promises.newPromise(function.realm);
                dev.mooner.neonjs.vm.Promises.rejectPromise(function.realm, promise, error.value);
                return promise;
            }
        });
    }

    /** A static operation: an enumerable method of the interface object. */
    NativeFunction staticOperation(String name, int length, NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, length, impl);
        ctor.defineOwn(name, function, Attr.ALL);
        return function;
    }

    /** A regular attribute: an enumerable accessor of the prototype, read-only without a setter. */
    void attribute(String name, NativeImpl get) {
        attribute(name, get, null);
    }

    void attribute(String name, NativeImpl get, NativeImpl set) {
        NativeFunction getter = new NativeFunction(realm, name, 0, get, false,
                realm.getFunctionPrototype(), "get");
        Object setter = set == null ? Undefined.INSTANCE
                : new NativeFunction(realm, name, 1, set, false, realm.getFunctionPrototype(), "set");
        proto.defineAccessor(name, getter, setter, Attr.ENUMERABLE | Attr.CONFIGURABLE);
    }

    /** An iterator of a pair iterable: the object iterated, the kind (0 keys, 1 values, 2 entries) and the next index. */
    static final class PairIterator extends JSObject {
        final JSObject target;
        final int kind;
        int index;

        PairIterator(JSObject prototype, JSObject target, int kind) {
            super(prototype);
            this.target = target;
            this.kind = kind;
        }
    }

    /**
     * A pair iterator declaration ({@code iterable<K, V>}): entries, keys, values and forEach on the prototype,
     * @@iterator the same function as entries, and iterators inheriting from a {@code <Name> Iterator} prototype.
     */
    <T extends JSObject> void pairIterable(Class<T> cls,
                                           Function<T, ? extends List<? extends Map.Entry<?, ?>>> pairs) {
        final String iface = name;
        JSObject iteratorPrototype = new JSObject(realm.getIteratorPrototype());
        iteratorPrototype.defineOwn("next", new NativeFunction(realm, "next", 0,
                (function, receiver, arguments, newTarget) -> {
                    if (!(receiver instanceof PairIterator iterator) || iterator.proto != iteratorPrototype) {
                        throw Idl.typeErr(iface + " Iterator.next called on " + Ops.describe(receiver));
                    }
                    List<? extends Map.Entry<?, ?>> list = pairs.apply(cls.cast(iterator.target));
                    if (iterator.index >= list.size()) {
                        return Iteration.createIterResult(function.realm, Undefined.INSTANCE, true);
                    }
                    Map.Entry<?, ?> pair = list.get(iterator.index++);
                    Object value;
                    if (iterator.kind == 0) value = pair.getKey();
                    else if (iterator.kind == 1) value = pair.getValue();
                    else value = dev.mooner.neonjs.builtins.Builtins.arrayOf(function.realm,
                            List.of(pair.getKey(), pair.getValue()));
                    return Iteration.createIterResult(function.realm, value, false);
                }), Attr.ALL);
        iteratorPrototype.defineOwn(JSSymbol.toStringTag, iface + " Iterator", Attr.CONFIGURABLE);

        NativeFunction entries = operation("entries", 0, (function, receiver, arguments, newTarget) ->
                new PairIterator(iteratorPrototype, selfType(cls, receiver, iface, "entries"), 2));
        operation("keys", 0, (function, receiver, arguments, newTarget) ->
                new PairIterator(iteratorPrototype, selfType(cls, receiver, iface, "keys"), 0));
        operation("values", 0, (function, receiver, arguments, newTarget) ->
                new PairIterator(iteratorPrototype, selfType(cls, receiver, iface, "values"), 1));
        operation("forEach", 1, (function, receiver, arguments, newTarget) -> {
            T target = selfType(cls, receiver, iface, "forEach");
            JSObject callback = Idl.callback(Values.arg(arguments, 0), iface + ".forEach callback");
            Object thisArg = Values.arg(arguments, 1);
            int i = 0;
            while (true) {
                List<? extends Map.Entry<?, ?>> list = pairs.apply(target);
                if (i >= list.size()) break;
                Map.Entry<?, ?> pair = list.get(i++);
                Ops.call(callback, thisArg, new Object[] {pair.getValue(), pair.getKey(), target});
            }
            return Undefined.INSTANCE;
        });
        proto.defineOwn(JSSymbol.iterator, entries, Attr.WC);
    }

    private static <T extends JSObject> T selfType(Class<T> cls, Object value, String iface, String member) {
        if (cls.isInstance(value)) return cls.cast(value);
        throw Idl.typeErr(iface + "." + member + " called on " + Ops.describe(value)
                + ", which is not a " + iface);
    }

    /** A constant: an enumerable, read-only, non-configurable property of the interface object and the prototype. */
    void constant(String name, double value) {
        ctor.defineOwn(name, value, Attr.ENUMERABLE);
        proto.defineOwn(name, value, Attr.ENUMERABLE);
    }

    /**
     * Defines the interface {@code name} in {@code realm} and binds it on the global object.
     */
    static WebInterface define(Realm realm, String name, int length) {
        return define(realm, name, length, null, null, null);
    }

    static WebInterface define(Realm realm, String name, int length, String parent) {
        return define(realm, name, length, parent, null, null);
    }

    static WebInterface define(Realm realm, String name, int length, Construct construct) {
        return define(realm, name, length, null, null, construct);
    }

    static WebInterface define(Realm realm, String name, int length, String parent, Construct construct) {
        return define(realm, name, length, parent, null, construct);
    }

    static WebInterface define(Realm realm, String name, int length, String parent, JSObject protoParent,
                               Construct construct) {
        JSObject prototype = new JSObject(protoParent != null ? protoParent
                : parent != null ? realm.intrinsic("%" + parent + ".prototype%") : realm.getObjectPrototype());
        String prototypeKey = "%" + name + ".prototype%";
        NativeFunction constructor = new NativeFunction(realm, name, length,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) {
                        throw Idl.typeErr("Failed to construct '" + name + "': use the 'new' operator");
                    }
                    if (construct == null) throw Idl.typeErr("Illegal constructor");
                    return construct.create(arguments, Ops.getPrototypeFromConstructor(newTarget, targetRealm -> {
                        JSObject value = targetRealm.intrinsics.get(prototypeKey);
                        return value != null ? value : targetRealm.getObjectPrototype();
                    }));
                }, true, parent != null ? realm.intrinsic("%" + parent + "%") : realm.getFunctionPrototype(), null);
        constructor.defineOwn("prototype", prototype, Attr.NONE);
        prototype.defineOwn("constructor", constructor, Attr.WC);
        prototype.defineOwn(JSSymbol.toStringTag, name, Attr.CONFIGURABLE);
        realm.intrinsics.put(prototypeKey, prototype);
        realm.intrinsics.put("%" + name + "%", constructor);
        realm.getGlobalObject().defineOwn(name, constructor, Attr.WC);
        return new WebInterface(realm, name, prototype, constructor);
    }

    /** An operation of the global object ({@code setTimeout}, {@code atob}...): an enumerable function property. */
    static NativeFunction globalOperation(Realm realm, String name, int length, NativeImpl impl) {
        NativeFunction function = new NativeFunction(realm, name, length, impl);
        realm.getGlobalObject().defineOwn(name, function, Attr.ALL);
        return function;
    }
}

/** WebIDL conversions of arguments and dictionary members, and receiver checks. */
final class Idl {
    private Idl() {
    }

    static JSException typeErr(String message) {
        return JSException.typeError(message);
    }

    /** The receiver of {@code member} of interface {@code iface} as its implementation class. */
    static <T> T self(Class<T> cls, Object receiver, String iface, String member) {
        if (cls.isInstance(receiver)) return cls.cast(receiver);
        throw typeErr(iface + "." + member + " called on " + Ops.describe(receiver)
                + ", which is not a " + iface);
    }

    /** Throws a TypeError unless {@code args} has at least {@code n} elements. */
    static void required(Object[] args, int n, String what) {
        if (args.length < n) {
            throw typeErr(what + ": " + n + " argument" + (n == 1 ? "" : "s")
                    + " required, but only " + args.length + " present");
        }
    }

    static String domString(Object value) {
        return value instanceof CharSequence text ? text.toString() : Ops.toString(value);
    }

    /** USVString: a DOMString with each lone surrogate replaced by U+FFFD. */
    static String usvString(Object value) {
        return toWellFormed(domString(value));
    }

    static String toWellFormed(String text) {
        StringBuilder builder = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                if (builder != null) builder.append(c).append(text.charAt(++i));
                else i++;
                continue;
            }
            boolean replace = Character.isSurrogate(c);
            if (builder == null && !replace) continue;
            if (builder == null) {
                builder = new StringBuilder(text.length());
                builder.append(text, 0, i);
            }
            builder.append(replace ? '\uFFFD' : c);
        }
        return builder == null ? text : builder.toString();
    }

    /** ByteString: a DOMString whose code units are all at most 0xFF, else a TypeError. */
    static String byteString(Object value, String what) {
        String text = domString(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0xFF) {
                throw typeErr(what + ": not a ByteString (contains U+" + String.format("%04X", (int) c) + ")");
            }
        }
        return text;
    }

    /** A dictionary argument: null for undefined or null, the object, else a TypeError. */
    static JSObject dictionary(Object value, String what) {
        if (value == Undefined.INSTANCE || value == Null.INSTANCE) return null;
        if (value instanceof JSObject object) return object;
        throw typeErr(what + " is not an object");
    }

    /** A dictionary member: undefined when the dictionary or the member is missing. */
    static Object member(JSObject dictionary, String key) {
        return dictionary == null ? Undefined.INSTANCE : dictionary.get(key, dictionary);
    }

    /** A callback function argument. */
    static JSObject callback(Object value, String what) {
        if (value instanceof JSObject object && object.isCallable()) return object;
        throw typeErr(what + " is not a function");
    }

    /** {@code double}: a finite number, else a TypeError. */
    static double doubleValue(Object value, String what) {
        double result = Ops.toNumber(value);
        if (Double.isNaN(result) || Double.isInfinite(result)) throw typeErr(what + " is not a finite number");
        return result;
    }

    static double integer(Object value, int bits, boolean signed, String what) {
        return integer(value, bits, signed, what, false, false);
    }

    static double integer(Object value, int bits, boolean signed, String what, boolean enforceRange) {
        return integer(value, bits, signed, what, enforceRange, false);
    }

    /**
     * ConvertToInt for an integer type of {@code bits} bits, with EnforceRange or Clamp when given.
     */
    static double integer(Object value, int bits, boolean signed, String what,
                          boolean enforceRange, boolean clamp) {
        double upper;
        double lower;
        if (bits == 64) {
            upper = 9007199254740991.0;
            lower = signed ? -9007199254740991.0 : 0.0;
        } else {
            upper = signed ? Math.pow(2.0, bits - 1.0) - 1 : Math.pow(2.0, bits) - 1;
            lower = signed ? -Math.pow(2.0, bits - 1.0) : 0.0;
        }
        double x = Ops.toNumber(value);
        if (x == 0.0) x = 0.0;
        if (enforceRange) {
            if (Double.isNaN(x) || Double.isInfinite(x)) throw typeErr(what + " is not a finite number");
            x = truncate(x);
            if (x < lower || x > upper) throw typeErr(what + " is outside the range " + lower + ".." + upper);
            return x + 0.0;
        }
        if (!Double.isNaN(x) && clamp) {
            x = Math.min(Math.max(x, lower), upper);
            return Math.rint(x) + 0.0;
        }
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0.0) return 0.0;
        x = truncate(x);
        if (bits == 64) {
            BigInteger modulus = BigInteger.ONE.shiftLeft(64);
            BigInteger m = new BigDecimal(x).toBigInteger().mod(modulus);
            BigInteger result = signed && m.testBit(63) ? m.subtract(modulus) : m;
            return result.doubleValue() + 0.0;
        }
        double mod = Math.pow(2.0, bits);
        x = ((x % mod) + mod) % mod;
        if (signed && x >= mod / 2) x -= mod;
        return x + 0.0;
    }

    private static double truncate(double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /**
     * A {@code record<K, V>} argument: for each own enumerable key, the key and value converted in order.
     */
    static <K, V> List<Map.Entry<K, V>> record(JSObject object, Function<Object, K> key,
                                               Function<Object, V> value) {
        LinkedHashMap<K, V> out = new LinkedHashMap<>();
        for (Object rawKey : object.ownPropertyKeys()) {
            var descriptor = object.getOwnProperty(rawKey);
            if (descriptor == null || !descriptor.enumerable) continue;
            K convertedKey = key.apply(rawKey instanceof JSSymbol ? rawKey : PK.toStringKey(rawKey));
            out.put(convertedKey, value.apply(object.get(rawKey, object)));
        }
        ArrayList<Map.Entry<K, V>> entries = new ArrayList<>(out.size());
        for (Map.Entry<K, V> entry : out.entrySet()) {
            entries.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    /** A {@code sequence<T>} argument: the values of an iterable object, each converted by {@code item}. */
    static <T> List<T> sequence(Realm realm, Object value, String what, Function<Object, T> item) {
        Object method = value instanceof JSObject object ? Ops.getMethod(object, JSSymbol.iterator)
                : Undefined.INSTANCE;
        if (method == Undefined.INSTANCE) throw typeErr(what + " is not iterable");
        IteratorRecord record = Iteration.fromMethod(value, method);
        ArrayList<T> out = new ArrayList<>();
        while (true) {
            Object next = Iteration.stepValue(record);
            if (next == NotFound.INSTANCE) return out;
            out.add(item.apply(next));
            if ((out.size() & 1023) == 0) realm.agent.checkInterrupt();
        }
    }

    /**
     * A copy of the bytes of a BufferSource (ArrayBuffer or view; a SharedArrayBuffer or a view of one only when
     * {@code allowShared}), or a TypeError. A detached buffer gives no bytes.
     */
    static byte[] bytes(Object value, String what, boolean allowShared) {
        JSArrayBuffer buffer;
        if (value instanceof JSArrayBuffer arrayBuffer) buffer = arrayBuffer;
        else if (value instanceof JSTypedArray array) buffer = array.buffer;
        else if (value instanceof JSDataView view) buffer = view.buffer;
        else throw typeErr(what + " is not an ArrayBuffer or a view of one");
        if (buffer.isShared() && !allowShared) throw typeErr(what + " is a SharedArrayBuffer or a view of one");
        if (value instanceof JSArrayBuffer arrayBuffer) {
            return Arrays.copyOfRange(arrayBuffer.data, 0, arrayBuffer.byteLength());
        }
        if (value instanceof JSTypedArray array) {
            int start = array.byteOffset;
            int end = start + Math.max(array.lengthOrOOB(), 0) * array.type.size;
            return Arrays.copyOfRange(array.buffer.data, start, end);
        }
        JSDataView view = (JSDataView) value;
        int start = view.byteOffset;
        int end = start + Math.max(view.byteLengthOrOOB(), 0);
        return Arrays.copyOfRange(view.buffer.data, start, end);
    }
}
