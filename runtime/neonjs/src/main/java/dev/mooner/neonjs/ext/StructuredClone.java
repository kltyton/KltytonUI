/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.ext;

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins;
import dev.mooner.neonjs.builtins.JSArrayBuffer;
import dev.mooner.neonjs.builtins.JSDataView;
import dev.mooner.neonjs.builtins.JSDate;
import dev.mooner.neonjs.builtins.JSMapObject;
import dev.mooner.neonjs.builtins.JSRegExp;
import dev.mooner.neonjs.builtins.JSTypedArray;
import dev.mooner.neonjs.builtins.TypedArrayBuiltins;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSPrimitiveWrapper;
import dev.mooner.neonjs.runtime.JSStringObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** {@code structuredClone(value, { transfer })} within one realm. */
final class StructuredClone {
    private final Realm realm;
    private final IdentityHashMap<Object, Object> memory = new IdentityHashMap<>();
    private int depth;
    private int work;

    StructuredClone(Realm realm) {
        this.realm = realm;
    }

    Object run(Object value, List<Object> transfer) {
        ArrayList<JSArrayBuffer> transferred = new ArrayList<>();
        for (Object item : transfer) {
            if (!(item instanceof JSArrayBuffer buffer) || buffer.isShared()) {
                throw dataCloneError("Value at index " + transferred.size()
                        + " of the transfer list is not transferable");
            }
            for (JSArrayBuffer existing : transferred) {
                if (existing == buffer) {
                    throw dataCloneError("ArrayBuffer at index " + transferred.size()
                            + " is a duplicate in the transfer list");
                }
            }
            if (buffer.detached) throw dataCloneError("An ArrayBuffer in the transfer list is detached");
            if (buffer.immutable) throw dataCloneError("An immutable ArrayBuffer cannot be transferred");
            transferred.add(buffer);
        }
        for (JSArrayBuffer buffer : transferred) {
            JSArrayBuffer copy = buffer.isFixedLength()
                    ? new JSArrayBuffer(realm.intrinsic("%ArrayBuffer.prototype%"), buffer.data,
                    buffer.byteLength(), -1, null)
                    : new JSArrayBuffer(realm.intrinsic("%ArrayBuffer.prototype%"), buffer.data,
                    buffer.byteLength(), buffer.maxByteLength, null);
            memory.put(buffer, copy);
        }
        Object result = clone(value);
        for (JSArrayBuffer buffer : transferred) buffer.detach();
        return result;
    }

    private JSException dataCloneError(String message) {
        return WebGlobals.domException(realm, message, "DataCloneError");
    }

    private void tick() {
        if ((++work & 1023) == 0) realm.agent.checkInterrupt();
    }

    private Object clone(Object value) {
        if (value == null || value == Undefined.INSTANCE || value == Null.INSTANCE
                || value instanceof Boolean || value instanceof Double || value instanceof CharSequence
                || value instanceof BigInteger) {
            return value;
        }
        if (value instanceof JSSymbol) throw dataCloneError("Symbol() could not be cloned");
        if (!(value instanceof JSObject object)) {
            throw dataCloneError(Ops.typeOf(value) + " could not be cloned");
        }
        if (memory.containsKey(object)) return memory.get(object);
        if (++depth > 5000) throw JSException.rangeError("Maximum call stack size exceeded");
        try {
            tick();
            return cloneObject(object);
        } finally {
            depth--;
        }
    }

    private JSObject proto(String name) {
        return realm.intrinsic(name);
    }

    private Object cloneObject(JSObject value) {
        if (value instanceof JSPrimitiveWrapper wrapper) {
            Object primitive = wrapper.primitive;
            if (primitive instanceof JSSymbol) throw dataCloneError("Symbol object could not be cloned");
            JSObject result = Ops.toObject(realm, primitive);
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSStringObject string) {
            JSObject result = Ops.toObject(realm, string.value);
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSDate date) {
            JSDate result = new JSDate(proto("%Date.prototype%"), date.timeValue);
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSRegExp regexp) {
            Object result = Ops.construct(realm.intrinsic("%RegExp%"),
                    new Object[] {regexp.originalSource, regexp.originalFlags});
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSArrayBuffer buffer) return cloneBuffer(buffer);
        if (value instanceof JSTypedArray array) {
            if (array.isOutOfBounds()) {
                throw dataCloneError("An out-of-bounds typed array could not be cloned");
            }
            JSArrayBuffer buffer = (JSArrayBuffer) clone(array.buffer);
            JSTypedArray result = new JSTypedArray(TypedArrayBuiltins.protoOf(realm, array.type),
                    array.type, buffer, array.byteOffset, array.fixedLength);
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSDataView view) {
            if (view.byteLengthOrOOB() < 0) {
                throw dataCloneError("An out-of-bounds DataView could not be cloned");
            }
            JSArrayBuffer buffer = (JSArrayBuffer) clone(view.buffer);
            JSDataView result = new JSDataView(proto("%DataView.prototype%"), buffer,
                    view.byteOffset, view.fixedByteLength);
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSMapObject map) {
            JSMapObject result = new JSMapObject(proto(map.isSet
                    ? "%Set.prototype%" : "%Map.prototype%"), map.isSet);
            memory.put(value, result);
            ArrayList<Object> keys = new ArrayList<>();
            ArrayList<Object> values = new ArrayList<>();
            map.table.forEachLive((key, item) -> {
                keys.add(key);
                values.add(item);
            });
            for (int i = 0; i < keys.size(); i++) {
                Object key = clone(keys.get(i));
                result.table.set(key, map.isSet ? key : clone(values.get(i)));
            }
            return result;
        }
        if (value instanceof WebGlobals.JSQuotaExceededError error) {
            WebGlobals.JSQuotaExceededError result = new WebGlobals.JSQuotaExceededError(
                    proto("%QuotaExceededError.prototype%"), error.excMessage, error.quota, error.requested);
            memory.put(value, result);
            return result;
        }
        if (value instanceof Blob.JSFile file) {
            Blob.JSFile result = new Blob.JSFile(proto("%File.prototype%"), file.data, file.offset,
                    file.size, file.type, file.name, file.lastModified);
            memory.put(value, result);
            return result;
        }
        if (value instanceof Blob.JSBlob blob) {
            Blob.JSBlob result = new Blob.JSBlob(proto("%Blob.prototype%"), blob.data, blob.offset,
                    blob.size, blob.type);
            memory.put(value, result);
            return result;
        }
        if (value instanceof WebGlobals.JSDOMException exception) {
            WebGlobals.JSDOMException result = WebGlobals.newDOMException(realm,
                    exception.excMessage, exception.excName);
            memory.put(value, result);
            return result;
        }
        if (value instanceof JSErrorObject error) return cloneError(error);
        if (value instanceof JSArray array) {
            JSArray result = new JSArray(realm.getArrayPrototype());
            result.setOrThrow("length", (double) array.length);
            memory.put(value, result);
            copyProperties(array, result);
            return result;
        }
        if (value.getClass() != JSObject.class) {
            throw dataCloneError(Ops.describe(value) + " could not be cloned");
        }
        JSObject result = new JSObject(realm.getObjectPrototype());
        memory.put(value, result);
        copyProperties(value, result);
        return result;
    }

    private void copyProperties(JSObject from, JSObject to) {
        for (Object key : from.ownPropertyKeys()) {
            if (key instanceof JSSymbol) continue;
            var descriptor = from.getOwnProperty(key);
            if (descriptor == null || !descriptor.enumerable) continue;
            Object value = clone(from.get(key, from));
            to.createDataProperty(key, value);
        }
    }

    private JSArrayBuffer cloneBuffer(JSArrayBuffer buffer) {
        JSArrayBuffer result;
        if (buffer.isShared()) {
            result = JSArrayBuffer.wrapShared(realm, buffer.block);
        } else if (buffer.detached) {
            throw dataCloneError("A detached ArrayBuffer could not be cloned");
        } else {
            int length = buffer.byteLength();
            JSArrayBuffer copy = ArrayBufferBuiltins.allocate(realm, null, length,
                    buffer.isFixedLength() ? -1 : buffer.maxByteLength);
            System.arraycopy(buffer.data, 0, copy.data, 0, length);
            copy.immutable = buffer.immutable;
            result = copy;
        }
        memory.put(buffer, result);
        return result;
    }

    private JSObject cloneError(JSErrorObject error) {
        Object nameValue = error.get("name", error);
        String name = nameValue == Undefined.INSTANCE ? "Error" : Ops.toString(nameValue);
        ErrorKind kind = ErrorKind.ERROR;
        for (ErrorKind candidate : ErrorKind.values()) {
            if (candidate.getJsName().equals(name) && candidate != ErrorKind.AGGREGATE
                    && candidate != ErrorKind.SUPPRESSED) {
                kind = candidate;
                break;
            }
        }
        var messageDescriptor = error.getOwnProperty("message");
        boolean hasMessage = messageDescriptor != null && !messageDescriptor.isAccessor();
        String message = hasMessage ? Ops.toString(error.get("message", error)) : "";
        JSErrorObject result = realm.newError(kind, message);
        if (!hasMessage) result.delete("message");
        result.stackTrace = error.stackTrace;
        memory.put(error, result);
        return result;
    }

    /** Reads {@code options.transfer} (an iterable) for structuredClone. */
    static List<Object> transferList(Realm realm, Object options) {
        if (options == Undefined.INSTANCE || options == Null.INSTANCE) return List.of();
        if (!(options instanceof JSObject object)) {
            throw JSException.typeError("structuredClone options must be an object");
        }
        Object transfer = object.get("transfer", object);
        if (transfer == Undefined.INSTANCE) return List.of();
        ArrayList<Object> out = new ArrayList<>();
        IteratorRecord record = Iteration.getIterator(realm, transfer, false);
        while (true) {
            Object next = Iteration.stepValue(record);
            if (next == NotFound.INSTANCE) break;
            out.add(next);
            if (out.size() > 100_000) throw JSException.rangeError("structuredClone: transfer list too long");
        }
        return out;
    }
}
