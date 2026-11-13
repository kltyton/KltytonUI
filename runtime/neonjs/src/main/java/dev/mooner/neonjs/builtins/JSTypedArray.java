/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.PropertyMap;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;
import java.util.List;

/** TypedArray instance: an integer-indexed exotic object viewing an {@link JSArrayBuffer}. */
public final class JSTypedArray extends JSObject implements TypedArrayLike {
    /** {@link #numericKey} result for keys that are not canonical numeric strings. */
    public static final int NOT_NUMERIC = Integer.MIN_VALUE;

    public final ElementType type;
    public final JSArrayBuffer buffer;
    public final int byteOffset;
    /** {@code [[ArrayLength]]}, or -1 (auto) for length-tracking views of resizable buffers. */
    public final int fixedLength;

    public JSTypedArray(JSObject prototype, ElementType type, JSArrayBuffer buffer, int byteOffset, int fixedLength) {
        super(prototype);
        this.type = type;
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.fixedLength = fixedLength;
        special |= SPECIAL_ALL;
    }

    @Override
    public String getClassName() {
        return type.ctorName();
    }

    /** TypedArrayLength, or -1 when IsTypedArrayOutOfBounds (which includes a detached buffer). */
    public int lengthOrOOB() {
        JSArrayBuffer source = buffer;
        if (source.detached) return -1;
        int bufferLength = source.byteLength();
        int offset = byteOffset;
        if (offset > bufferLength) return -1;
        int length = fixedLength;
        if (length < 0) return (bufferLength - offset) >> type.shift;
        return (long) offset + ((long) length << type.shift) > bufferLength ? -1 : length;
    }

    @Override
    public long typedLength() {
        return Math.max(lengthOrOOB(), 0);
    }

    @Override
    public boolean isOutOfBounds() {
        return lengthOrOOB() < 0;
    }

    /** TypedArrayByteLength (0 when out of bounds). */
    public int byteLengthOrZero() {
        return Math.max(lengthOrOOB(), 0) << type.shift;
    }

    /** IsTypedArrayFixedLength */
    public boolean isFixedLength() {
        return fixedLength >= 0 && (buffer.isFixedLength() || buffer.isShared());
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < lengthOrOOB();
    }

    /** TypedArrayGetElement: Undefined for invalid indices. */
    public Object getIndex(int index) {
        if (index < 0 || index >= lengthOrOOB()) return Undefined.INSTANCE;
        return BufferOps.load(buffer.data, byteOffset + (index << type.shift), type);
    }

    /** TypedArraySetElement: converts {@code value} first, then stores if the index is valid. */
    public void setIndex(int index, Object value) {
        Object number = type.coerce(value);
        if (index >= 0 && index < lengthOrOOB()) {
            BufferOps.store(buffer.data, byteOffset + (index << type.shift), type, number);
        }
    }

    /** TypedArraySetElement of a Number at index {@code index} for a Number type. */
    public void setNumber(int index, double value) {
        if (index < lengthOrOOB()) {
            BufferOps.storeNumber(buffer.data, byteOffset + (index << type.shift), type, value);
        }
    }

    /** {@link #setNumber} of an int32. */
    public void setInt(int index, int value) {
        if (index < lengthOrOOB()) {
            BufferOps.storeInt(buffer.data, byteOffset + (index << type.shift), type, value);
        }
    }

    /** Stores an already converted value (Double / BigInteger) if the index is valid. */
    public void storeIndex(int index, Object number) {
        if (index >= 0 && index < lengthOrOOB()) {
            BufferOps.store(buffer.data, byteOffset + (index << type.shift), type, number);
        }
    }

    // ------------------------------------------------------------------ internal methods

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        int index = numericKey(key);
        if (index == NOT_NUMERIC) return ordinaryGetOwnProperty(key);
        Object value = getIndex(index);
        if (value == Undefined.INSTANCE) return null;
        // elements of an immutable buffer are non-writable and non-configurable
        return PropertyDescriptor.data(value, buffer.immutable ? Attr.ENUMERABLE : Attr.ALL);
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        int index = numericKey(key);
        if (index == NOT_NUMERIC) return super.getOwnValue(key, receiver);
        Object value = getIndex(index);
        return value == Undefined.INSTANCE ? NotFound.INSTANCE : value;
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        int index = numericKey(key);
        if (index == NOT_NUMERIC) {
            PropertyMap map = props;
            return map != null && map.find(key) >= 0;
        }
        return isValidIndex(index);
    }

    @Override
    public boolean hasProperty(Object key) {
        int index = numericKey(key);
        if (index == NOT_NUMERIC) return super.hasProperty(key);
        return isValidIndex(index);
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        int index = numericKey(key);
        if (index == NOT_NUMERIC) return ordinaryDefineOwnProperty(key, descriptor);
        if (!isValidIndex(index)) return false;
        if (buffer.immutable) {
            // redefining an immutable element only succeeds for a compatible descriptor with the SameValue value
            return validateAndApply(false, descriptor,
                    PropertyDescriptor.data(getIndex(index), Attr.ENUMERABLE));
        }
        if (descriptor.getHasConfigurable() && !descriptor.configurable) return false;
        if (descriptor.getHasEnumerable() && !descriptor.enumerable) return false;
        if (descriptor.isAccessor()) return false;
        if (descriptor.getHasWritable() && !descriptor.writable) return false;
        if (descriptor.getHasValue()) setIndex(index, descriptor.value);
        return true;
    }

    @Override
    public Object get(Object key, Object receiver) {
        if (key instanceof Integer index) return getIndex(index);
        if (key instanceof String text) {
            int index = stringIndex(text);
            if (index != NOT_NUMERIC) return getIndex(index);
        }
        return super.get(key, receiver);
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        int index = numericKey(key);
        if (index != NOT_NUMERIC) {
            // assignments to canonical numeric keys of an immutable buffer fail without converting the value
            if (buffer.immutable) return false;
            if (receiver == this) {
                setIndex(index, value);
                return true;
            }
            if (!isValidIndex(index)) return true;
        }
        return ordinarySet(key, value, receiver);
    }

    @Override
    public boolean delete(Object key) {
        int index = numericKey(key);
        if (index == NOT_NUMERIC) return super.delete(key);
        return !isValidIndex(index);
    }

    @Override
    public List<Object> ownPropertyKeys() {
        int length = lengthOrOOB();
        if (length <= 0) return ordinaryOwnKeys(null);
        List<Object> indices = new ArrayList<>(length);
        for (int i = 0; i < length; i++) indices.add(i);
        return ordinaryOwnKeys(indices);
    }

    @Override
    public boolean preventExtensions() {
        return isFixedLength() && super.preventExtensions();
    }

    /** Index for Integer keys / canonical numeric strings (-1 when never a valid integer index), else NOT_NUMERIC. */
    public static int numericKey(Object key) {
        if (key instanceof Integer index) return index;
        if (key instanceof String text) return stringIndex(text);
        return NOT_NUMERIC;
    }

    public static int stringIndex(String text) {
        if (text.isEmpty()) return NOT_NUMERIC;
        char first = text.charAt(0);
        // ToString(Number) always starts with a digit, '-', "Infinity" or "NaN"
        if (!(first >= '0' && first <= '9' || first == '-' || first == 'I' || first == 'N')) {
            return NOT_NUMERIC;
        }
        Double number = Ops.canonicalNumericIndexString(text);
        if (number == null) return NOT_NUMERIC;
        if (number >= 0 && number <= Integer.MAX_VALUE && number == Math.floor(number)
                && !(number == 0.0 && 1.0 / number < 0.0)) {
            return number.intValue();
        }
        return -1;
    }
}
