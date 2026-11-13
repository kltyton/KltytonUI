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
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import static dev.mooner.neonjs.builtins.BuiltinsKt.getter;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.relIndex;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.runtime.Values.arg;

/** ArrayBuffer / SharedArrayBuffer constructors and their prototypes. */
public final class ArrayBufferBuiltins {
    private ArrayBufferBuiltins() {
    }

    static void install(Realm realm) {
        installArrayBuffer(realm);
        installSharedArrayBuffer(realm);
        realm.intrinsics.put("%DetachArrayBuffer%", new NativeFunction(realm, "DetachArrayBuffer", 1,
                (function, receiver, arguments, newTarget) -> {
                    JSArrayBuffer buffer = arg(arguments, 0) instanceof JSArrayBuffer value ? value : null;
                    if (buffer == null || buffer.isShared()) {
                        throw typeErr("DetachArrayBuffer: argument is not an ArrayBuffer");
                    }
                    if (buffer.immutable) throw typeErr("Cannot detach an immutable ArrayBuffer");
                    buffer.detach();
                    return Null.INSTANCE;
                }));
    }

    /** GetArrayBufferMaxByteLengthOption: -1 when absent. */
    private static long maxByteLengthOption(Object options) {
        if (!(options instanceof JSObject object)) return -1;
        Object max = object.get("maxByteLength", object);
        if (max == Undefined.INSTANCE) return -1;
        return Ops.toIndex(max);
    }

    /** AllocateArrayBuffer(constructor, byteLength [, maxByteLength]); maxByteLength less than 0 means fixed length. */
    public static JSArrayBuffer allocate(Realm realm, JSObject constructor, long byteLength) {
        return allocate(realm, constructor, byteLength, -1);
    }

    public static JSArrayBuffer allocate(Realm realm, JSObject constructor, long byteLength, long maxByteLength) {
        if (maxByteLength >= 0 && byteLength > maxByteLength) {
            throw rangeErr("byteLength exceeds maxByteLength");
        }
        JSObject prototype = constructor == null
                ? realm.intrinsic("%ArrayBuffer.prototype%")
                : Ops.getPrototypeFromConstructor(constructor, r -> r.intrinsic("%ArrayBuffer.prototype%"));
        byte[] data = BufferOps.allocate(byteLength);
        if (maxByteLength > BufferOps.MAX_BYTE_LENGTH) throw rangeErr("Invalid array buffer max length");
        return new JSArrayBuffer(prototype, data, (int) byteLength, (int) maxByteLength, null);
    }

    /** AllocateImmutableArrayBuffer(%ArrayBuffer%, byteLength, fromBlock, fromIndex, count). */
    private static JSArrayBuffer allocateImmutable(Realm realm, long byteLength, byte[] from, int fromIndex, int count) {
        JSArrayBuffer buffer = allocate(realm, null, byteLength);
        System.arraycopy(from, fromIndex, buffer.data, 0, count);
        buffer.immutable = true;
        return buffer;
    }

    private static JSArrayBuffer thisBuffer(Object target, String method, boolean shared) {
        JSArrayBuffer buffer = target instanceof JSArrayBuffer value ? value : null;
        if (buffer == null || buffer.isShared() != shared) {
            throw typeErr("Method " + (shared ? "SharedArrayBuffer" : "ArrayBuffer")
                    + ".prototype." + method + " called on incompatible receiver " + Ops.describe(target));
        }
        return buffer;
    }

    private static void installArrayBuffer(Realm realm) {
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put("%ArrayBuffer.prototype%", prototype);
        NativeFunction constructor = makeCtor(realm, "ArrayBuffer", 1, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor ArrayBuffer requires 'new'");
                    long byteLength = Ops.toIndex(arg(arguments, 0));
                    long max = maxByteLengthOption(arg(arguments, 1));
                    return allocate(function.realm, newTarget, byteLength, max);
                });
        realm.intrinsics.put("%ArrayBuffer%", constructor);
        global(realm, "ArrayBuffer", constructor);
        method(constructor, realm, "isView", 1, (function, receiver, arguments, newTarget) -> {
            Object value = arg(arguments, 0);
            return value instanceof JSTypedArray || value instanceof JSDataView;
        });
        getter(constructor, realm, JSSymbol.species, (function, receiver, arguments, newTarget) -> receiver);

        getter(prototype, realm, "byteLength", (function, receiver, arguments, newTarget) ->
                (double) thisBuffer(receiver, "byteLength", false).byteLength());
        getter(prototype, realm, "detached", (function, receiver, arguments, newTarget) ->
                thisBuffer(receiver, "detached", false).detached);
        getter(prototype, realm, "immutable", (function, receiver, arguments, newTarget) ->
                thisBuffer(receiver, "immutable", false).immutable);
        getter(prototype, realm, "maxByteLength", (function, receiver, arguments, newTarget) -> {
            JSArrayBuffer buffer = thisBuffer(receiver, "maxByteLength", false);
            return (double) (buffer.detached ? 0
                    : buffer.isFixedLength() ? buffer.byteLength() : buffer.maxByteLength);
        });
        getter(prototype, realm, "resizable", (function, receiver, arguments, newTarget) ->
                !thisBuffer(receiver, "resizable", false).isFixedLength());
        method(prototype, realm, "resize", 1, (function, receiver, arguments, newTarget) -> {
            JSArrayBuffer buffer = thisBuffer(receiver, "resize", false);
            if (buffer.isFixedLength()) {
                throw typeErr("Method ArrayBuffer.prototype.resize called on a non-resizable ArrayBuffer");
            }
            long newLength = Ops.toIndex(arg(arguments, 0));
            if (buffer.detached) throw typeErr("Cannot resize a detached ArrayBuffer");
            if (newLength > buffer.maxByteLength) {
                throw rangeErr("ArrayBuffer.prototype.resize: Invalid length parameter");
            }
            buffer.resize((int) newLength);
            return Undefined.INSTANCE;
        });
        method(prototype, realm, "slice", 2, (function, receiver, arguments, newTarget) -> {
            JSArrayBuffer source = thisBuffer(receiver, "slice", false);
            if (source.detached) {
                throw typeErr("Cannot perform ArrayBuffer.prototype.slice on a detached ArrayBuffer");
            }
            long length = source.byteLength();
            long first = relIndex(arg(arguments, 0), length, 0);
            long finish = relIndex(arg(arguments, 1), length, length);
            long newLength = Math.max(finish - first, 0);
            JSObject species = Ops.speciesConstructor(source, function.realm.intrinsic("%ArrayBuffer%"));
            Object created = Ops.construct(species, new Object[] {(double) newLength});
            if (!(created instanceof JSArrayBuffer result) || result.isShared()) {
                throw typeErr("ArrayBuffer subclass returned this from species constructor");
            }
            if (result.detached) throw typeErr("Species constructor returned a detached ArrayBuffer");
            if (result.immutable) throw typeErr("Species constructor returned an immutable ArrayBuffer");
            if (result == source) throw typeErr("ArrayBuffer subclass returned this from species constructor");
            if (result.byteLength() < newLength) {
                throw typeErr("Species constructor returned a too small ArrayBuffer");
            }
            if (source.detached) {
                throw typeErr("Cannot perform ArrayBuffer.prototype.slice on a detached ArrayBuffer");
            }
            int current = source.byteLength();
            if (first < current) {
                int count = (int) Math.min(newLength, current - first);
                System.arraycopy(source.data, (int) first, result.data, 0, count);
            }
            return result;
        });
        method(prototype, realm, "transfer", 0,
                (function, receiver, arguments, newTarget) ->
                        copyAndDetach(function.realm, receiver, arg(arguments, 0), true, "transfer", false));
        method(prototype, realm, "transferToFixedLength", 0,
                (function, receiver, arguments, newTarget) ->
                        copyAndDetach(function.realm, receiver, arg(arguments, 0), false, "transferToFixedLength", false));
        method(prototype, realm, "transferToImmutable", 0,
                (function, receiver, arguments, newTarget) ->
                        copyAndDetach(function.realm, receiver, arg(arguments, 0), false, "transferToImmutable", true));
        method(prototype, realm, "sliceToImmutable", 2,
                (function, receiver, arguments, newTarget) ->
                        sliceToImmutable(function.realm, receiver, arg(arguments, 0), arg(arguments, 1)));
        value(prototype, JSSymbol.toStringTag, "ArrayBuffer", Attr.CONFIGURABLE);
    }

    /** ArrayBufferCopyAndDetach; {@code immutable} selects the immutable mode (transferToImmutable). */
    private static JSArrayBuffer copyAndDetach(Realm realm, Object target, Object newLength,
                                               boolean preserveResizability, String method,
                                               boolean immutable) {
        JSArrayBuffer source = thisBuffer(target, method, false);
        long newByteLength = newLength == Undefined.INSTANCE ? source.byteLength() : Ops.toIndex(newLength);
        if (source.detached) {
            throw typeErr("Cannot perform ArrayBuffer.prototype." + method + " on a detached ArrayBuffer");
        }
        if (source.immutable) {
            throw typeErr("Cannot perform ArrayBuffer.prototype." + method + " on an immutable ArrayBuffer");
        }
        long newMax = preserveResizability && !source.isFixedLength() ? source.maxByteLength : -1L;
        int oldLength = source.byteLength();
        JSArrayBuffer result;
        if (newByteLength == oldLength) {
            // same length: move the data block instead of copying it
            if (newMax >= 0 && newByteLength > newMax) throw rangeErr("byteLength exceeds maxByteLength");
            result = new JSArrayBuffer(realm.intrinsic("%ArrayBuffer.prototype%"), source.data,
                    oldLength, (int) newMax, null);
        } else {
            JSArrayBuffer buffer = allocate(realm, null, newByteLength, newMax);
            System.arraycopy(source.data, 0, buffer.data, 0,
                    (int) Math.min(newByteLength, (long) oldLength));
            result = buffer;
        }
        result.immutable = immutable;
        source.detach();
        return result;
    }

    /** ArrayBuffer.prototype.sliceToImmutable(start, end). */
    private static JSArrayBuffer sliceToImmutable(Realm realm, Object target, Object start, Object end) {
        JSArrayBuffer source = thisBuffer(target, "sliceToImmutable", false);
        if (source.detached) {
            throw typeErr("Cannot perform ArrayBuffer.prototype.sliceToImmutable on a detached ArrayBuffer");
        }
        long length = source.byteLength();
        long first = relIndex(start, length, 0);
        long finish = relIndex(end, length, length);
        long newLength = Math.max(finish - first, 0);
        if (source.detached) {
            throw typeErr("Cannot perform ArrayBuffer.prototype.sliceToImmutable on a detached ArrayBuffer");
        }
        if (source.byteLength() < finish) {
            throw rangeErr("ArrayBuffer.prototype.sliceToImmutable: the buffer was shrunk below the end of the slice");
        }
        return allocateImmutable(realm, newLength, source.data, (int) first, (int) newLength);
    }

    private static void installSharedArrayBuffer(Realm realm) {
        JSObject prototype = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put("%SharedArrayBuffer.prototype%", prototype);
        NativeFunction constructor = makeCtor(realm, "SharedArrayBuffer", 1, prototype,
                (function, receiver, arguments, newTarget) -> {
                    if (newTarget == null) throw typeErr("Constructor SharedArrayBuffer requires 'new'");
                    long byteLength = Ops.toIndex(arg(arguments, 0));
                    long max = maxByteLengthOption(arg(arguments, 1));
                    return allocateShared(newTarget, byteLength, max);
                });
        realm.intrinsics.put("%SharedArrayBuffer%", constructor);
        global(realm, "SharedArrayBuffer", constructor);
        getter(constructor, realm, JSSymbol.species, (function, receiver, arguments, newTarget) -> receiver);

        getter(prototype, realm, "byteLength", (function, receiver, arguments, newTarget) ->
                (double) thisBuffer(receiver, "byteLength", true).byteLength());
        getter(prototype, realm, "growable", (function, receiver, arguments, newTarget) ->
                !thisBuffer(receiver, "growable", true).isFixedLength());
        getter(prototype, realm, "maxByteLength", (function, receiver, arguments, newTarget) -> {
            JSArrayBuffer buffer = thisBuffer(receiver, "maxByteLength", true);
            return (double) (buffer.isFixedLength() ? buffer.byteLength() : buffer.maxByteLength);
        });
        method(prototype, realm, "grow", 1, (function, receiver, arguments, newTarget) -> {
            JSArrayBuffer buffer = thisBuffer(receiver, "grow", true);
            if (buffer.isFixedLength()) {
                throw typeErr("Method SharedArrayBuffer.prototype.grow called on a non-growable SharedArrayBuffer");
            }
            long newLength = Ops.toIndex(arg(arguments, 0));
            SharedDataBlock block = buffer.block;
            block.lock.lock();
            try {
                int current = block.byteLength;
                if (newLength != current) {
                    if (newLength < current || newLength > buffer.maxByteLength) {
                        throw rangeErr("SharedArrayBuffer.prototype.grow: Invalid length parameter");
                    }
                    block.byteLength = (int) newLength;
                }
            } finally {
                block.lock.unlock();
            }
            return Undefined.INSTANCE;
        });
        method(prototype, realm, "slice", 2, (function, receiver, arguments, newTarget) -> {
            JSArrayBuffer source = thisBuffer(receiver, "slice", true);
            long length = source.byteLength();
            long first = relIndex(arg(arguments, 0), length, 0);
            long finish = relIndex(arg(arguments, 1), length, length);
            long newLength = Math.max(finish - first, 0);
            JSObject species = Ops.speciesConstructor(source, function.realm.intrinsic("%SharedArrayBuffer%"));
            Object created = Ops.construct(species, new Object[] {(double) newLength});
            if (!(created instanceof JSArrayBuffer result) || !result.isShared()) {
                throw typeErr("Species constructor did not return a SharedArrayBuffer");
            }
            if (result.block == source.block) {
                throw typeErr("SharedArrayBuffer subclass returned this from species constructor");
            }
            if (result.byteLength() < newLength) {
                throw typeErr("Species constructor returned a too small SharedArrayBuffer");
            }
            System.arraycopy(source.data, (int) first, result.data, 0, (int) newLength);
            return result;
        });
        value(prototype, JSSymbol.toStringTag, "SharedArrayBuffer", Attr.CONFIGURABLE);
    }

    /** AllocateSharedArrayBuffer(constructor, byteLength [, maxByteLength]). */
    public static JSArrayBuffer allocateShared(JSObject constructor, long byteLength, long maxByteLength) {
        if (maxByteLength >= 0 && byteLength > maxByteLength) {
            throw rangeErr("byteLength exceeds maxByteLength");
        }
        JSObject prototype = Ops.getPrototypeFromConstructor(constructor,
                r -> r.intrinsic("%SharedArrayBuffer.prototype%"));
        byte[] data = BufferOps.allocate(maxByteLength >= 0 ? maxByteLength : byteLength);
        SharedDataBlock block = new SharedDataBlock(data, (int) byteLength, (int) maxByteLength);
        return new JSArrayBuffer(prototype, data, (int) byteLength, block.maxByteLength, block);
    }
}
