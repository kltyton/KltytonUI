/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Rope;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import static dev.mooner.neonjs.builtins.BuiltinsKt.callable;
import static dev.mooner.neonjs.builtins.BuiltinsKt.getter;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.relIndex;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.runtime.Values.arg;

/** %TypedArray% constructor, its prototype and the concrete typed-array constructors. */
public final class TypedArrayBuiltins {
    private static final String[] CTOR_KEYS = new String[ElementType.values().length];
    private static final String[] PROTO_KEYS = new String[ElementType.values().length];

    static {
        ElementType[] types = ElementType.values();
        for (int i = 0; i < types.length; i++) {
            CTOR_KEYS[i] = "%" + types[i].ctorName() + "%";
            PROTO_KEYS[i] = "%" + types[i].ctorName() + ".prototype%";
        }
    }

    private TypedArrayBuiltins() {
    }

    public static JSObject ctorOf(Realm realm, ElementType type) {
        return realm.intrinsic(CTOR_KEYS[type.ordinal()]);
    }

    public static JSObject protoOf(Realm realm, ElementType type) {
        return realm.intrinsic(PROTO_KEYS[type.ordinal()]);
    }

    /**
     * ValidateTypedArray: the receiver must be a typed array that is not out of bounds; with {@code write} its buffer
     * must not be immutable.
     */
    public static JSTypedArray validate(Object target, String method) {
        return validate(target, method, false);
    }

    public static JSTypedArray validate(Object target, String method, boolean write) {
        if (!(target instanceof JSTypedArray array)) throw typeErr(method + ": receiver is not a typed array");
        if (write && array.buffer.immutable) {
            throw typeErr(method + ": typed array is backed by an immutable ArrayBuffer");
        }
        if (array.lengthOrOOB() < 0) throw typeErr(method + ": typed array is detached or out of bounds");
        return array;
    }

    private static JSTypedArray thisTA(Object target, String method) {
        if (target instanceof JSTypedArray array) return array;
        throw typeErr("Method %TypedArray%.prototype." + method
                + " called on incompatible receiver " + Ops.describe(target));
    }

    /** AllocateTypedArray with a length, using an already resolved prototype. */
    public static JSTypedArray allocate(Realm realm, ElementType type, JSObject prototype, long length) {
        if (length > (BufferOps.MAX_BYTE_LENGTH >> type.shift)) {
            throw rangeErr("Invalid typed array length: " + length);
        }
        JSArrayBuffer buffer = ArrayBufferBuiltins.allocate(realm, null, length << type.shift);
        return new JSTypedArray(prototype, type, buffer, 0, (int) length);
    }

    /** TypedArrayCreateFromConstructor; {@code write} = accessMode write (the result will be written). */
    public static JSTypedArray createFromConstructor(JSObject constructor, Object[] arguments) {
        return createFromConstructor(constructor, arguments, false);
    }

    public static JSTypedArray createFromConstructor(JSObject constructor, Object[] arguments, boolean write) {
        JSTypedArray array = validate(Ops.construct(constructor, arguments), "TypedArray constructor", write);
        if (arguments.length == 1) {
            Object length = arguments[0];
            if (length instanceof Double number && array.lengthOrOOB() < number) {
                throw typeErr("TypedArray constructor created an array which is too small");
            }
        }
        return array;
    }

    /** TypedArraySpeciesCreate */
    public static JSTypedArray speciesCreate(Realm realm, JSTypedArray exemplar, Object[] arguments) {
        return speciesCreate(realm, exemplar, arguments, false);
    }

    public static JSTypedArray speciesCreate(Realm realm, JSTypedArray exemplar, Object[] arguments, boolean write) {
        JSObject constructor = Ops.speciesConstructor(exemplar, ctorOf(realm, exemplar.type));
        JSTypedArray result = createFromConstructor(constructor, arguments, write);
        if (result.type.isBigInt != exemplar.type.isBigInt) {
            throw typeErr("TypedArray species constructor returned an array with an incompatible content type");
        }
        return result;
    }

    /** TypedArrayCreateSameType (the intrinsic constructor is unobservable, so allocate directly). */
    public static JSTypedArray createSameType(Realm realm, JSTypedArray exemplar, int length) {
        return allocate(realm, exemplar.type, protoOf(realm, exemplar.type), length);
    }

    /** IteratorToList(GetIteratorFromMethod(obj, method)) with a fast path for pristine arrays. */
    public static List<Object> iterableToList(Realm realm, Object object, Object method) {
        if (object instanceof JSArray array && !array.sparse && method == realm.getArrayProtoValues()
                && Iteration.isPristineArrayIteration(realm, array)) {
            return array.toList();
        }
        List<Object> out = new ArrayList<>();
        IteratorRecord record = Iteration.fromMethod(object, method);
        while (true) {
            Object value = Iteration.stepValue(record);
            if (value == NotFound.INSTANCE) return out;
            out.add(value);
        }
    }

    public static void install(Realm realm) {
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put("%TypedArray.prototype%", prototype);
        var constructor = makeCtor(realm, "TypedArray", 0, prototype,
                (function, receiver, arguments, newTarget) -> {
                    throw typeErr("Abstract class TypedArray not directly constructable");
                });
        realm.intrinsics.put("%TypedArray%", constructor);
        installStatics(realm, constructor);
        installPrototype(realm, prototype);
        for (ElementType type : ElementType.values()) installConcrete(realm, type, constructor, prototype);
        Uint8ArrayCodec.install(realm, ctorOf(realm, ElementType.UINT8), protoOf(realm, ElementType.UINT8));
    }

    // ================================================================== constructors

    private static void installConcrete(Realm realm, ElementType type, JSObject typedArrayConstructor,
                                        JSObject typedArrayPrototype) {
        JSObject prototype = new JSObject(typedArrayPrototype);
        var constructor = makeCtor(realm, type.ctorName(), 3, prototype, typedArrayConstructor,
                (function, receiver, arguments, newTarget) -> construct(function.realm, type, arguments, newTarget));
        constructor.defineOwn("BYTES_PER_ELEMENT", (double) type.size, Attr.NONE);
        prototype.defineOwn("BYTES_PER_ELEMENT", (double) type.size, Attr.NONE);
        realm.intrinsics.put(CTOR_KEYS[type.ordinal()], constructor);
        realm.intrinsics.put(PROTO_KEYS[type.ordinal()], prototype);
        global(realm, type.ctorName(), constructor);
    }

    private static JSTypedArray construct(Realm realm, ElementType type, Object[] arguments, JSObject newTarget) {
        if (newTarget == null) throw typeErr("Constructor " + type.ctorName() + " requires 'new'");
        Object first = arg(arguments, 0);
        if (!(first instanceof JSObject object)) {
            long length = Ops.toIndex(first);
            JSObject prototype = Ops.getPrototypeFromConstructor(newTarget,
                    r -> r.intrinsic(PROTO_KEYS[type.ordinal()]));
            return allocate(realm, type, prototype, length);
        }
        JSObject prototype = Ops.getPrototypeFromConstructor(newTarget,
                r -> r.intrinsic(PROTO_KEYS[type.ordinal()]));
        if (object instanceof JSTypedArray source) return fromTypedArray(realm, type, prototype, source);
        if (object instanceof JSArrayBuffer buffer) {
            return fromArrayBuffer(type, prototype, buffer, arg(arguments, 1), arg(arguments, 2));
        }
        Object usingIterator = Ops.getMethod(object, JSSymbol.iterator);
        if (usingIterator != Undefined.INSTANCE) {
            List<Object> values = iterableToList(realm, object, usingIterator);
            JSTypedArray array = allocate(realm, type, prototype, values.size());
            for (int k = 0; k < values.size(); k++) array.setIndex(k, values.get(k));
            return array;
        }
        long length = Ops.lengthOfArrayLike(object);
        JSTypedArray array = allocate(realm, type, prototype, length);
        for (int k = 0; k < (int) length; k++) array.setIndex(k, object.get(k, object));
        return array;
    }

    /** InitializeTypedArrayFromTypedArray */
    private static JSTypedArray fromTypedArray(Realm realm, ElementType type, JSObject prototype,
                                               JSTypedArray source) {
        int length = source.lengthOrOOB();
        if (length < 0) {
            throw typeErr("Cannot construct a typed array from a detached or out of bounds typed array");
        }
        JSTypedArray array = allocate(realm, type, prototype, length);
        if (source.type.isBigInt != type.isBigInt) {
            throw typeErr("Cannot mix BigInt and other types, use explicit conversions");
        }
        copyElements(source.buffer.data, source.byteOffset, source.type,
                array.buffer.data, 0, type, length);
        return array;
    }

    /** Copies {@code count} elements, converting between element types when they differ. */
    public static void copyElements(byte[] source, int sourceIndex, ElementType sourceType,
                                    byte[] destination, int destinationIndex, ElementType destinationType,
                                    int count) {
        if (sourceType == destinationType) {
            System.arraycopy(source, sourceIndex, destination, destinationIndex, count << sourceType.shift);
            return;
        }
        int si = sourceIndex;
        int di = destinationIndex;
        for (int i = 0; i < count; i++) {
            BufferOps.store(destination, di, destinationType, BufferOps.load(source, si, sourceType));
            si += sourceType.size;
            di += destinationType.size;
        }
    }

    /** InitializeTypedArrayFromArrayBuffer */
    private static JSTypedArray fromArrayBuffer(ElementType type, JSObject prototype, JSArrayBuffer buffer,
                                                Object byteOffset, Object length) {
        int size = type.size;
        long offset = Ops.toIndex(byteOffset);
        if (offset % size != 0L) {
            throw rangeErr("Start offset of " + type.ctorName() + " should be a multiple of " + size);
        }
        long newLength = length != Undefined.INSTANCE ? Ops.toIndex(length) : -1L;
        if (buffer.detached) {
            throw typeErr("Cannot construct " + type.ctorName() + " on a detached ArrayBuffer");
        }
        long bufferLength = buffer.byteLength();
        if (length == Undefined.INSTANCE && !buffer.isFixedLength()) {
            if (offset > bufferLength) {
                throw rangeErr("Start offset " + offset + " is outside the bounds of the buffer");
            }
            return new JSTypedArray(prototype, type, buffer, (int) offset, -1);
        }
        long newByteLength;
        if (length == Undefined.INSTANCE) {
            if (bufferLength % size != 0L) {
                throw rangeErr("Byte length of " + type.ctorName() + " should be a multiple of " + size);
            }
            newByteLength = bufferLength - offset;
            if (newByteLength < 0) {
                throw rangeErr("Start offset " + offset + " is outside the bounds of the buffer");
            }
        } else {
            newByteLength = newLength * size;
            if (offset + newByteLength > bufferLength) {
                throw rangeErr("Invalid typed array length: " + newLength);
            }
        }
        return new JSTypedArray(prototype, type, buffer, (int) offset, (int) (newByteLength / size));
    }

    // ================================================================== %TypedArray% statics

    private static void installStatics(Realm realm, JSObject constructor) {
        method(constructor, realm, "from", 1, (function, receiver, arguments, newTarget) -> {
            if (!Ops.isConstructor(receiver)) throw typeErr(Ops.describe(receiver) + " is not a constructor");
            JSObject targetConstructor = (JSObject) receiver;
            Object mapfn = arg(arguments, 1);
            Object thisArg = arg(arguments, 2);
            boolean mapping = mapfn != Undefined.INSTANCE;
            if (mapping && !Ops.isCallable(mapfn)) {
                throw typeErr(Ops.describe(mapfn) + " is not a function");
            }
            Object source = arg(arguments, 0);
            Object usingIterator = Ops.getMethod(source, JSSymbol.iterator);
            if (usingIterator != Undefined.INSTANCE) {
                List<Object> values = iterableToList(function.realm, source, usingIterator);
                JSTypedArray target = createFromConstructor(targetConstructor,
                        new Object[] {(double) values.size()}, true);
                for (int k = 0; k < values.size(); k++) {
                    Object value = values.get(k);
                    target.setIndex(k, mapping
                            ? ((JSObject) mapfn).call(thisArg, new Object[] {value, (double) k}) : value);
                }
                return target;
            }
            JSObject arrayLike = Ops.toObject(source);
            long length = Ops.lengthOfArrayLike(arrayLike);
            JSTypedArray target = createFromConstructor(targetConstructor,
                    new Object[] {(double) length}, true);
            for (int k = 0; k < (int) length; k++) {
                Object value = arrayLike.get(k, arrayLike);
                target.setIndex(k, mapping
                        ? ((JSObject) mapfn).call(thisArg, new Object[] {value, (double) k}) : value);
            }
            return target;
        });
        method(constructor, realm, "of", 0, (function, receiver, arguments, newTarget) -> {
            if (!Ops.isConstructor(receiver)) throw typeErr(Ops.describe(receiver) + " is not a constructor");
            JSTypedArray target = createFromConstructor((JSObject) receiver,
                    new Object[] {(double) arguments.length}, true);
            for (int k = 0; k < arguments.length; k++) target.setIndex(k, arguments[k]);
            return target;
        });
        getter(constructor, realm, JSSymbol.species, (function, receiver, arguments, newTarget) -> receiver);
    }

    // ================================================================== %TypedArray%.prototype

    private static int relative(Object value, int length) {
        return (int) relIndex(value, length, 0);
    }

    private static int relativeEnd(Object value, int length) {
        return (int) relIndex(value, length, length);
    }

    private static void installPrototype(Realm realm, JSObject prototype) {
        getter(prototype, realm, "buffer", (function, receiver, arguments, newTarget) ->
                thisTA(receiver, "buffer").buffer);
        getter(prototype, realm, "byteLength", (function, receiver, arguments, newTarget) ->
                (double) thisTA(receiver, "byteLength").byteLengthOrZero());
        getter(prototype, realm, "byteOffset", (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = thisTA(receiver, "byteOffset");
            return array.lengthOrOOB() < 0 ? 0.0 : (double) array.byteOffset;
        });
        getter(prototype, realm, "length", (function, receiver, arguments, newTarget) ->
                (double) Math.max(thisTA(receiver, "length").lengthOrOOB(), 0));
        getter(prototype, realm, JSSymbol.toStringTag, (function, receiver, arguments, newTarget) ->
                receiver instanceof JSTypedArray array ? array.type.ctorName() : Undefined.INSTANCE);

        method(prototype, realm, "at", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.at");
            int length = array.lengthOrOOB();
            double relative = Ops.toIntegerOrInfinity(arg(arguments, 0));
            double k = relative >= 0 ? relative : length + relative;
            return k < 0 || k >= length ? Undefined.INSTANCE : array.getIndex((int) k);
        });
        method(prototype, realm, "copyWithin", 2, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.copyWithin", true);
            int length = array.lengthOrOOB();
            int to = relative(arg(arguments, 0), length);
            int from = relative(arg(arguments, 1), length);
            int finish = relativeEnd(arg(arguments, 2), length);
            int count = Math.min(finish - from, length - to);
            if (count > 0) {
                length = array.lengthOrOOB();
                if (length < 0) {
                    throw typeErr("%TypedArray%.prototype.copyWithin: typed array is detached or out of bounds");
                }
                // side effects may have shrunk the array: copy the longest still-applicable prefix
                count = Math.min(count, Math.min(length - from, length - to));
                if (count > 0) {
                    int shift = array.type.shift;
                    byte[] data = array.buffer.data;
                    System.arraycopy(data, array.byteOffset + (from << shift), data,
                            array.byteOffset + (to << shift), count << shift);
                }
            }
            return array;
        });
        method(prototype, realm, "entries", 0, (function, receiver, arguments, newTarget) ->
                new ArrayIteratorObject(function.realm.getArrayIteratorPrototype(),
                        validate(receiver, "%TypedArray%.prototype.entries"), 2));
        method(prototype, realm, "keys", 0, (function, receiver, arguments, newTarget) ->
                new ArrayIteratorObject(function.realm.getArrayIteratorPrototype(),
                        validate(receiver, "%TypedArray%.prototype.keys"), 0));
        var values = method(prototype, realm, "values", 0, (function, receiver, arguments, newTarget) ->
                new ArrayIteratorObject(function.realm.getArrayIteratorPrototype(),
                        validate(receiver, "%TypedArray%.prototype.values"), 1));
        prototype.defineOwn(JSSymbol.iterator, values, Attr.WC);
        method(prototype, realm, "every", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.every");
            int length = array.lengthOrOOB();
            JSObject callback = callable(arg(arguments, 0));
            for (int k = 0; k < length; k++) {
                if (!Ops.toBoolean(callback.call(arg(arguments, 1),
                        new Object[] {array.getIndex(k), (double) k, array}))) return false;
            }
            return true;
        });
        method(prototype, realm, "some", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.some");
            int length = array.lengthOrOOB();
            JSObject callback = callable(arg(arguments, 0));
            for (int k = 0; k < length; k++) {
                if (Ops.toBoolean(callback.call(arg(arguments, 1),
                        new Object[] {array.getIndex(k), (double) k, array}))) return true;
            }
            return false;
        });
        method(prototype, realm, "forEach", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.forEach");
            int length = array.lengthOrOOB();
            JSObject callback = callable(arg(arguments, 0));
            for (int k = 0; k < length; k++) {
                callback.call(arg(arguments, 1), new Object[] {array.getIndex(k), (double) k, array});
            }
            return Undefined.INSTANCE;
        });
        method(prototype, realm, "fill", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.fill", true);
            int length = array.lengthOrOOB();
            Object value = array.type.coerce(arg(arguments, 0));
            int start = relative(arg(arguments, 1), length);
            int end = relativeEnd(arg(arguments, 2), length);
            length = array.lengthOrOOB();
            if (length < 0) throw typeErr("%TypedArray%.prototype.fill: typed array is detached or out of bounds");
            end = Math.min(end, length);
            if (start < end) {
                byte[] data = array.buffer.data;
                ElementType type = array.type;
                int first = array.byteOffset + (start << type.shift);
                BufferOps.store(data, first, type, value);
                if (type.size == 1) {
                    Arrays.fill(data, first + 1, array.byteOffset + end, data[first]);
                } else {
                    // replicate the encoded element by doubling copies
                    int total = (end - start) << type.shift;
                    int filled = type.size;
                    while (filled < total) {
                        int count = Math.min(filled, total - filled);
                        System.arraycopy(data, first, data, first + filled, count);
                        filled += count;
                    }
                }
            }
            return array;
        });
        method(prototype, realm, "filter", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.filter");
            int length = array.lengthOrOOB();
            JSObject callback = callable(arg(arguments, 0));
            ArrayList<Object> kept = new ArrayList<>();
            for (int k = 0; k < length; k++) {
                Object value = array.getIndex(k);
                if (Ops.toBoolean(callback.call(arg(arguments, 1),
                        new Object[] {value, (double) k, array}))) kept.add(value);
            }
            JSTypedArray result = speciesCreate(function.realm, array,
                    new Object[] {(double) kept.size()}, true);
            for (int i = 0; i < kept.size(); i++) result.setIndex(i, kept.get(i));
            return result;
        });
        String[] findNames = {"find", "findIndex", "findLast", "findLastIndex"};
        boolean[] findFromEnd = {false, false, true, true};
        boolean[] findIndex = {false, true, false, true};
        for (int f = 0; f < findNames.length; f++) {
            String name = findNames[f];
            boolean fromEnd = findFromEnd[f];
            boolean wantIndex = findIndex[f];
            method(prototype, realm, name, 1, (function, receiver, arguments, newTarget) -> {
                JSTypedArray array = validate(receiver, "%TypedArray%.prototype." + name);
                int length = array.lengthOrOOB();
                JSObject callback = callable(arg(arguments, 0));
                Object result = wantIndex ? (Object) (-1.0) : Undefined.INSTANCE;
                int k = fromEnd ? length - 1 : 0;
                while (fromEnd ? k >= 0 : k < length) {
                    Object value = array.getIndex(k);
                    if (Ops.toBoolean(callback.call(arg(arguments, 1),
                            new Object[] {value, (double) k, array}))) {
                        result = wantIndex ? (Object) (double) k : value;
                        break;
                    }
                    if (fromEnd) k--;
                    else k++;
                }
                return result;
            });
        }
        method(prototype, realm, "includes", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.includes");
            int length = array.lengthOrOOB();
            if (length == 0) return false;
            double n = Ops.toIntegerOrInfinity(arg(arguments, 1));
            if (n == Double.POSITIVE_INFINITY) return false;
            long k = n >= 0 ? (long) n : (long) Math.max(length + n, 0.0);
            Object search = arg(arguments, 0);
            while (k < length) {
                if (Ops.sameValueZero(search, array.getIndex((int) k))) return true;
                k++;
            }
            return false;
        });
        method(prototype, realm, "indexOf", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.indexOf");
            int length = array.lengthOrOOB();
            if (length == 0) return -1.0;
            double n = Ops.toIntegerOrInfinity(arg(arguments, 1));
            if (n == Double.POSITIVE_INFINITY) return -1.0;
            long k = n >= 0 ? (long) n : (long) Math.max(length + n, 0.0);
            Object search = arg(arguments, 0);
            if (!(search instanceof Double) && !(search instanceof BigInteger)) return -1.0;
            while (k < length) {
                Object value = array.getIndex((int) k);
                if (value != Undefined.INSTANCE && Ops.strictEquals(search, value)) return (double) k;
                k++;
            }
            return -1.0;
        });
        method(prototype, realm, "lastIndexOf", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.lastIndexOf");
            int length = array.lengthOrOOB();
            if (length == 0) return -1.0;
            double n = arguments.length > 1
                    ? Ops.toIntegerOrInfinity(arguments[1]) : (double) (length - 1);
            if (n == Double.NEGATIVE_INFINITY) return -1.0;
            long k = n >= 0 ? (long) Math.min(n, (double) (length - 1)) : (long) (length + n);
            Object search = arg(arguments, 0);
            if (!(search instanceof Double) && !(search instanceof BigInteger)) return -1.0;
            while (k >= 0) {
                Object value = array.getIndex((int) k);
                if (value != Undefined.INSTANCE && Ops.strictEquals(search, value)) return (double) k;
                k--;
            }
            return -1.0;
        });
        method(prototype, realm, "join", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.join");
            int length = array.lengthOrOOB();
            String separator = arg(arguments, 0) == Undefined.INSTANCE
                    ? "," : Ops.toString(arg(arguments, 0));
            StringBuilder builder = new StringBuilder();
            for (int k = 0; k < length; k++) {
                if (k > 0) builder.append(separator);
                Object element = array.getIndex(k);
                if (element != Undefined.INSTANCE) builder.append(Ops.toString(element));
                if (builder.length() > Rope.MAX_LENGTH) throw rangeErr("Invalid string length");
            }
            return builder.toString();
        });
        method(prototype, realm, "map", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.map");
            int length = array.lengthOrOOB();
            JSObject callback = callable(arg(arguments, 0));
            JSTypedArray result = speciesCreate(function.realm, array,
                    new Object[] {(double) length}, true);
            for (int k = 0; k < length; k++) {
                result.setIndex(k, callback.call(arg(arguments, 1),
                        new Object[] {array.getIndex(k), (double) k, array}));
            }
            return result;
        });
        String[] reduceNames = {"reduce", "reduceRight"};
        boolean[] reduceRight = {false, true};
        for (int r = 0; r < reduceNames.length; r++) {
            String name = reduceNames[r];
            boolean right = reduceRight[r];
            method(prototype, realm, name, 1, (function, receiver, arguments, newTarget) -> {
                JSTypedArray array = validate(receiver, "%TypedArray%.prototype." + name);
                int length = array.lengthOrOOB();
                JSObject callback = callable(arg(arguments, 0));
                if (length == 0 && arguments.length < 2) {
                    throw typeErr("Reduce of empty array with no initial value");
                }
                int k = right ? length - 1 : 0;
                Object accumulator;
                if (arguments.length >= 2) {
                    accumulator = arguments[1];
                } else {
                    accumulator = array.getIndex(k);
                    if (right) k--;
                    else k++;
                }
                while (right ? k >= 0 : k < length) {
                    accumulator = callback.call(Undefined.INSTANCE,
                            new Object[] {accumulator, array.getIndex(k), (double) k, array});
                    if (right) k--;
                    else k++;
                }
                return accumulator;
            });
        }
        method(prototype, realm, "reverse", 0, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.reverse", true);
            int length = array.lengthOrOOB();
            int size = array.type.size;
            byte[] data = array.buffer.data;
            int lo = array.byteOffset;
            int hi = array.byteOffset + ((length - 1) << array.type.shift);
            while (lo < hi) {
                for (int b = 0; b < size; b++) {
                    byte temporary = data[lo + b];
                    data[lo + b] = data[hi + b];
                    data[hi + b] = temporary;
                }
                lo += size;
                hi -= size;
            }
            return array;
        });
        method(prototype, realm, "set", 1, (function, receiver, arguments, newTarget) -> {
            JSTypedArray target = thisTA(receiver, "set");
            if (target.buffer.immutable) {
                throw typeErr("%TypedArray%.prototype.set: typed array is backed by an immutable ArrayBuffer");
            }
            double targetOffset = Ops.toIntegerOrInfinity(arg(arguments, 1));
            if (targetOffset < 0) throw rangeErr("offset is out of bounds");
            Object source = arg(arguments, 0);
            if (source instanceof JSTypedArray typedArray) setFromTypedArray(target, targetOffset, typedArray);
            else setFromArrayLike(target, targetOffset, source);
            return Undefined.INSTANCE;
        });
        method(prototype, realm, "slice", 2, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.slice");
            int sourceLength = array.lengthOrOOB();
            int start = relative(arg(arguments, 0), sourceLength);
            int end = relativeEnd(arg(arguments, 1), sourceLength);
            int count = Math.max(end - start, 0);
            JSTypedArray result = speciesCreate(function.realm, array,
                    new Object[] {(double) count}, true);
            if (count > 0) {
                int length = array.lengthOrOOB();
                if (length < 0) {
                    throw typeErr("%TypedArray%.prototype.slice: typed array is detached or out of bounds");
                }
                end = Math.min(end, length);
                count = Math.max(end - start, 0);
                if (array.type == result.type) {
                    int shift = array.type.shift;
                    byte[] source = array.buffer.data;
                    byte[] destination = result.buffer.data;
                    int si = (start << shift) + array.byteOffset;
                    int di = result.byteOffset;
                    int limit = di + (count << shift);
                    if (source == destination && si < di && di < si + (count << shift)) {
                        // byte-wise forward copy semantics on overlapping views of one buffer
                        while (di < limit) destination[di++] = source[si++];
                    } else if (limit > di) {
                        System.arraycopy(source, si, destination, di, limit - di);
                    }
                } else {
                    int n = 0;
                    for (int k = start; k < end; k++) result.setIndex(n++, array.getIndex(k));
                }
            }
            return result;
        });
        method(prototype, realm, "sort", 1, (function, receiver, arguments, newTarget) -> {
            Object comparator = arg(arguments, 0);
            if (comparator != Undefined.INSTANCE && !Ops.isCallable(comparator)) {
                throw typeErr("The comparison function must be either a function or undefined");
            }
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.sort", true);
            int length = array.lengthOrOOB();
            Object[] sorted = sortedValues(array, length, comparator);
            for (int j = 0; j < length; j++) array.storeIndex(j, sorted[j]);
            return array;
        });
        method(prototype, realm, "subarray", 2, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = thisTA(receiver, "subarray");
            int sourceLength = Math.max(array.lengthOrOOB(), 0);
            int start = relative(arg(arguments, 0), sourceLength);
            Object end = arg(arguments, 1);
            double beginByteOffset = (double) array.byteOffset + ((long) start << array.type.shift);
            Object[] argumentList;
            if (array.fixedLength < 0 && end == Undefined.INSTANCE) {
                argumentList = new Object[] {array.buffer, beginByteOffset};
            } else {
                int finish = relativeEnd(end, sourceLength);
                argumentList = new Object[] {array.buffer, beginByteOffset,
                        (double) Math.max(finish - start, 0)};
            }
            return speciesCreate(function.realm, array, argumentList);
        });
        method(prototype, realm, "toLocaleString", 0, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.toLocaleString");
            int length = array.lengthOrOOB();
            StringBuilder builder = new StringBuilder();
            for (int k = 0; k < length; k++) {
                if (k > 0) builder.append(',');
                Object element = array.getIndex(k);
                if (element != Undefined.INSTANCE) {
                    builder.append(Ops.toString(Ops.invoke(element, "toLocaleString", Values.EMPTY_ARGS)));
                }
            }
            return builder.toString();
        });
        method(prototype, realm, "toReversed", 0, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.toReversed");
            int length = array.lengthOrOOB();
            JSTypedArray result = createSameType(function.realm, array, length);
            for (int k = 0; k < length; k++) result.storeIndex(k, array.getIndex(length - k - 1));
            return result;
        });
        method(prototype, realm, "toSorted", 1, (function, receiver, arguments, newTarget) -> {
            Object comparator = arg(arguments, 0);
            if (comparator != Undefined.INSTANCE && !Ops.isCallable(comparator)) {
                throw typeErr("The comparison function must be either a function or undefined");
            }
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.toSorted");
            int length = array.lengthOrOOB();
            JSTypedArray result = createSameType(function.realm, array, length);
            Object[] sorted = sortedValues(array, length, comparator);
            for (int j = 0; j < length; j++) result.storeIndex(j, sorted[j]);
            return result;
        });
        method(prototype, realm, "with", 2, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validate(receiver, "%TypedArray%.prototype.with");
            int length = array.lengthOrOOB();
            double relative = Ops.toIntegerOrInfinity(arg(arguments, 0));
            double actual = relative >= 0 ? relative : length + relative;
            Object value = array.type.coerce(arg(arguments, 1));
            if (!(actual >= 0 && actual < array.lengthOrOOB())) throw rangeErr("Invalid typed array index");
            JSTypedArray result = createSameType(function.realm, array, length);
            int index = (int) actual;
            // elements beyond a shrunk length read as undefined and are converted again
            for (int k = 0; k < length; k++) {
                if (k == index) result.storeIndex(k, value);
                else result.setIndex(k, array.getIndex(k));
            }
            return result;
        });
        prototype.defineOwn("toString", realm.getArrayPrototype().get("toString"), Attr.WC);
    }

    /** SortIndexedProperties with CompareTypedArrayElements; reads through [[Get]]. */
    private static Object[] sortedValues(JSTypedArray array, int length, Object comparator) {
        if (comparator == Undefined.INSTANCE) {
            // no user code can run: sort the raw numeric values directly
            if (array.type.isBigInt) {
                BigInteger[] values = new BigInteger[length];
                for (int i = 0; i < length; i++) values[i] = (BigInteger) array.getIndex(i);
                Arrays.sort(values);
                return values;
            }
            double[] values = new double[length];
            for (int i = 0; i < length; i++) values[i] = (Double) array.getIndex(i);
            Arrays.sort(values); // total order: -0 < +0, NaN last
            Object[] result = new Object[length];
            for (int i = 0; i < length; i++) result[i] = values[i];
            return result;
        }
        Object[] items = new Object[length];
        for (int i = 0; i < length; i++) items[i] = array.getIndex(i);
        JSObject function = (JSObject) comparator;
        return mergeSort(items, (x, y) -> {
            double value = Ops.toNumber(function.call(Undefined.INSTANCE, new Object[] {x, y}));
            return value < 0 ? -1 : value > 0 ? 1 : 0;
        });
    }

    /** Stable bottom-up merge sort tolerant of inconsistent comparators. */
    private static Object[] mergeSort(Object[] items, Comparator<Object> comparator) {
        int n = items.length;
        Object[] source = items;
        Object[] destination = new Object[n];
        int width = 1;
        while (width < n) {
            int lo = 0;
            while (lo < n) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int a = lo;
                int b = mid;
                int o = lo;
                while (a < mid && b < hi) {
                    if (comparator.compare(source[b], source[a]) < 0) destination[o++] = source[b++];
                    else destination[o++] = source[a++];
                }
                while (a < mid) destination[o++] = source[a++];
                while (b < hi) destination[o++] = source[b++];
                lo = hi;
            }
            Object[] temporary = source;
            source = destination;
            destination = temporary;
            width *= 2;
        }
        return source;
    }

    /** SetTypedArrayFromTypedArray */
    private static void setFromTypedArray(JSTypedArray target, double targetOffset, JSTypedArray source) {
        int targetLength = target.lengthOrOOB();
        if (targetLength < 0) {
            throw typeErr("%TypedArray%.prototype.set: target is detached or out of bounds");
        }
        int sourceLength = source.lengthOrOOB();
        if (sourceLength < 0) {
            throw typeErr("%TypedArray%.prototype.set: source is detached or out of bounds");
        }
        if (targetOffset == Double.POSITIVE_INFINITY || sourceLength + targetOffset > targetLength) {
            throw rangeErr("offset is out of bounds");
        }
        if (target.type.isBigInt != source.type.isBigInt) {
            throw typeErr("Cannot mix BigInt and other types, use explicit conversions");
        }
        byte[] sourceData = source.buffer.data;
        int sourceIndex = source.byteOffset;
        boolean sameData = source.buffer == target.buffer
                || (source.buffer.isShared() && source.buffer.block == target.buffer.block);
        if (sameData && source.type != target.type) {
            int byteLength = sourceLength << source.type.shift;
            sourceData = Arrays.copyOfRange(sourceData, sourceIndex, sourceIndex + byteLength);
            sourceIndex = 0;
        }
        int targetIndex = (((int) targetOffset) << target.type.shift) + target.byteOffset;
        copyElements(sourceData, sourceIndex, source.type, target.buffer.data, targetIndex,
                target.type, sourceLength);
    }

    /** SetTypedArrayFromArrayLike */
    private static void setFromArrayLike(JSTypedArray target, double targetOffset, Object source) {
        int targetLength = target.lengthOrOOB();
        if (targetLength < 0) {
            throw typeErr("%TypedArray%.prototype.set: target is detached or out of bounds");
        }
        JSObject arrayLike = Ops.toObject(source);
        long sourceLength = Ops.lengthOfArrayLike(arrayLike);
        if (targetOffset == Double.POSITIVE_INFINITY || sourceLength + targetOffset > targetLength) {
            throw rangeErr("offset is out of bounds");
        }
        int offset = (int) targetOffset;
        for (int k = 0; k < (int) sourceLength; k++) {
            target.setIndex(offset + k, arrayLike.get(k, arrayLike));
        }
    }
}
