package dev.mooner.neonjs.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dense and sparse JavaScript array semantics; derived from NeonJS and adapted to Java. */
public final class JSArray extends JSObject {
    public static final long MAX_LENGTH = 4294967295L;
    private static final Object[] EMPTY = new Object[0];
    public Object[] dense;
    public int denseLen;
    public long length;
    public boolean lengthWritable = true;
    public boolean sparse;

    public JSArray(JSObject prototype) {
        this(prototype, 0);
    }

    public JSArray(JSObject prototype, int capacity) {
        super(prototype);
        dense = capacity == 0 ? EMPTY : new Object[capacity];
        special |= EXOTIC_OWN;
    }

    public static JSArray of(JSObject prototype, Object[] values) {
        JSArray array = new JSArray(prototype);
        array.dense = values;
        array.denseLen = values.length;
        array.length = values.length;
        return array;
    }

    public static JSArray of(Realm realm, List<?> values) {
        return of(realm.arrayPrototype, values.toArray());
    }

    @Override
    public String getClassName() {
        return "Array";
    }

    public boolean protoChainClean() {
        JSObject prototype = proto;
        while (prototype != null) {
            if ((prototype.special & SPECIAL_ALL) != 0) return false;
            PropertyMap map = prototype.props;
            if (map != null && map.hasIndexKeys) return false;
            prototype = prototype.proto;
        }
        return true;
    }

    public Object getIndexFast(int index) {
        if (!sparse && index < denseLen) {
            Object value = dense[index];
            if (value != Hole.INSTANCE) return value;
        }
        return get(index, this);
    }

    public boolean trySetIndexFast(int index, Object value) {
        if (sparse) return false;
        if (index < denseLen) {
            if (dense[index] != Hole.INSTANCE) {
                dense[index] = value;
                return true;
            }
            if (!extensible || !protoChainClean()) return false;
            dense[index] = value;
            return true;
        }
        if (index == denseLen && extensible && lengthWritable && protoChainClean()) {
            appendDense(value);
            if (denseLen > length) length = denseLen;
            return true;
        }
        return false;
    }

    public int createIndexFast(int index, Object value) {
        if (sparse || index < 0) return -1;
        if (index >= length && !lengthWritable) return 0;
        if (index < denseLen && dense[index] != Hole.INSTANCE) {
            dense[index] = value;
        } else {
            if (!extensible) return 0;
            if (index < denseLen) dense[index] = value;
            else if (index == denseLen) appendDense(value);
            else return -1;
        }
        if (index >= length) length = index + 1L;
        return 1;
    }

    private void appendDense(Object value) {
        if (denseLen == dense.length) {
            int capacity = dense.length == 0 ? 8 : dense.length + (dense.length >> 1) + 1;
            dense = Arrays.copyOf(dense, capacity);
        }
        dense[denseLen++] = value;
    }

    public void pushInit(Object value) {
        if (sparse) {
            long index = length;
            ensureProps().add(PK.fromIndex(index), value, Attr.ALL);
            length = index + 1;
        } else if (denseLen == length) {
            appendDense(value);
            length = denseLen;
        } else {
            defineOwnProperty(PK.fromIndex(length), PropertyDescriptor.data(value, Attr.ALL));
        }
    }

    public void pushHoleInit() {
        if (!sparse && denseLen == length) {
            appendDense(Hole.INSTANCE);
            length = denseLen;
        } else {
            length++;
        }
    }

    private void toSparse() {
        if (sparse) return;
        PropertyMap map = ensureProps();
        for (int i = 0, limit = denseLen; i < limit; i++) {
            Object value = dense[i];
            if (value != Hole.INSTANCE) map.add(i, value, Attr.ALL);
        }
        dense = EMPTY;
        denseLen = 0;
        sparse = true;
    }

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        if (key instanceof Integer integer && !sparse) {
            if (integer < denseLen) {
                Object value = dense[integer];
                if (value != Hole.INSTANCE) return PropertyDescriptor.data(value, Attr.ALL);
            }
            return null;
        }
        if (key.equals("length")) return PropertyDescriptor.data((double) length, lengthWritable ? Attr.WRITABLE : 0);
        return ordinaryGetOwnProperty(key);
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        if (key instanceof Integer integer && !sparse) {
            if (integer < denseLen) {
                Object value = dense[integer];
                if (value != Hole.INSTANCE) return value;
            }
            return NotFound.INSTANCE;
        }
        if (key.equals("length")) return (double) length;
        return super.getOwnValue(key, receiver);
    }

    @Override
    public boolean icSafeKey(Object key) {
        return key instanceof String && !key.equals("length") && PK.arrayIndex(key) < 0;
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        if (key instanceof Integer integer && !sparse) return integer < denseLen && dense[integer] != Hole.INSTANCE;
        if (key.equals("length")) return true;
        return props != null && props.find(key) >= 0;
    }

    @Override
    public Object get(Object key, Object receiver) {
        if (key instanceof Integer integer && !sparse && integer < denseLen) {
            Object value = dense[integer];
            if (value != Hole.INSTANCE) return value;
        }
        return super.get(key, receiver);
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        return (key instanceof Integer integer && receiver == this && trySetIndexFast(integer, value))
                || ordinarySet(key, value, receiver);
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        if (key.equals("length")) return setLength(descriptor);
        long index = PK.arrayIndex(key);
        if (index >= 0) {
            if (index >= length && !lengthWritable) return false;
            if (!defineIndex(key, descriptor)) return false;
            if (index >= length) length = index + 1;
            return true;
        }
        return ordinaryDefineOwnProperty(key, descriptor);
    }

    private boolean defineIndex(Object key, PropertyDescriptor descriptor) {
        if (!sparse && key instanceof Integer integer) {
            boolean exists = integer < denseLen && dense[integer] != Hole.INSTANCE;
            boolean defaultAttributes = !descriptor.isAccessor() && (exists
                    ? (!descriptor.getHasWritable() || descriptor.writable)
                        && (!descriptor.getHasEnumerable() || descriptor.enumerable)
                        && (!descriptor.getHasConfigurable() || descriptor.configurable)
                    : descriptor.getHasWritable() && descriptor.writable
                        && descriptor.getHasEnumerable() && descriptor.enumerable
                        && descriptor.getHasConfigurable() && descriptor.configurable);
            if (defaultAttributes) {
                if (exists) {
                    if (descriptor.getHasValue()) dense[integer] = descriptor.value;
                    return true;
                }
                if (!extensible) return false;
                if (integer < denseLen) {
                    dense[integer] = descriptor.getHasValue() ? descriptor.value : Undefined.INSTANCE;
                    return true;
                }
                if ((long) integer - denseLen < 1024 || integer < denseLen * 2) {
                    while (denseLen < integer) appendDense(Hole.INSTANCE);
                    appendDense(descriptor.getHasValue() ? descriptor.value : Undefined.INSTANCE);
                    return true;
                }
            }
            if (!exists && !extensible) return false;
            toSparse();
        }
        return ordinaryDefineOwnProperty(key, descriptor);
    }

    private boolean setLength(PropertyDescriptor descriptor) {
        if (!descriptor.getHasValue()) {
            boolean valid = validateLengthAttrs(descriptor);
            if (valid && descriptor.getHasWritable() && !descriptor.writable) lengthWritable = false;
            return valid;
        }
        long newLength = Ops.toUint32(descriptor.value);
        double numericLength = Ops.toNumber(descriptor.value);
        if ((double) newLength != numericLength) throw JSException.rangeError("Invalid array length");
        if (!validateLengthAttrs(descriptor)) return false;
        if (newLength >= length) {
            if (!lengthWritable && newLength != length) return false;
            length = newLength;
            if (descriptor.getHasWritable() && !descriptor.writable) lengthWritable = false;
            return true;
        }
        if (!lengthWritable) return false;
        boolean newWritable = !(descriptor.getHasWritable() && !descriptor.writable);
        boolean success = truncate(newLength);
        if (!newWritable) lengthWritable = false;
        return success;
    }

    private boolean validateLengthAttrs(PropertyDescriptor descriptor) {
        if (descriptor.getHasConfigurable() && descriptor.configurable) return false;
        if (descriptor.getHasEnumerable() && descriptor.enumerable) return false;
        if (descriptor.isAccessor()) return false;
        return lengthWritable || !descriptor.getHasWritable() || !descriptor.writable;
    }

    private boolean truncate(long newLength) {
        if (!sparse) {
            if (newLength < denseLen) {
                Arrays.fill(dense, (int) newLength, denseLen, null);
                denseLen = (int) newLength;
                if (dense.length > 64 && denseLen < dense.length / 4) {
                    dense = Arrays.copyOf(dense, Math.max(denseLen, 8));
                }
            }
            PropertyMap map = props;
            if (map != null) {
                List<String> largeKeys = new ArrayList<>();
                map.forEachLive(i -> {
                    Object key = map.keys[i];
                    if (key instanceof String text && PK.arrayIndex(text) >= newLength) largeKeys.add(text);
                });
                largeKeys.sort((a, b) -> Long.compare(PK.arrayIndex(b), PK.arrayIndex(a)));
                for (String key : largeKeys) {
                    if (!delete(key)) {
                        length = PK.arrayIndex(key) + 1;
                        return false;
                    }
                }
            }
            length = newLength;
            return true;
        }
        PropertyMap map = props;
        if (map != null) {
            List<Object> keys = new ArrayList<>();
            map.forEachLive(i -> {
                Object key = map.keys[i];
                if (PK.arrayIndex(key) >= newLength) keys.add(key);
            });
            keys.sort((a, b) -> Long.compare(PK.arrayIndex(b), PK.arrayIndex(a)));
            for (Object key : keys) {
                if (!delete(key)) {
                    length = PK.arrayIndex(key) + 1;
                    return false;
                }
            }
        }
        length = newLength;
        return true;
    }

    @Override
    public boolean delete(Object key) {
        if (key instanceof Integer integer && !sparse) {
            if (integer < denseLen) {
                dense[integer] = Hole.INSTANCE;
                if (integer == denseLen - 1) {
                    int end = denseLen - 1;
                    while (end > 0 && dense[end - 1] == Hole.INSTANCE) end--;
                    Arrays.fill(dense, end, denseLen, null);
                    denseLen = end;
                }
            }
            return true;
        }
        return !key.equals("length") && super.delete(key);
    }

    @Override
    public boolean preventExtensions() {
        extensible = false;
        return true;
    }

    @Override
    public List<Object> ownPropertyKeys() {
        List<Object> result = new ArrayList<>();
        PropertyMap map = props;
        if (!sparse) {
            for (int i = 0, limit = denseLen; i < limit; i++) if (dense[i] != Hole.INSTANCE) result.add(i);
            if (map != null) {
                List<String> large = new ArrayList<>();
                map.forEachLive(i -> {
                    Object key = map.keys[i];
                    if (key instanceof String text && PK.arrayIndex(text) >= 0) large.add(text);
                });
                if (!large.isEmpty()) {
                    large.sort((a, b) -> Long.compare(PK.arrayIndex(a), PK.arrayIndex(b)));
                    result.addAll(large);
                }
            }
        } else if (map != null) {
            List<Object> indices = new ArrayList<>();
            map.forEachLive(i -> {
                Object key = map.keys[i];
                if (PK.arrayIndex(key) >= 0) indices.add(key);
            });
            indices.sort((a, b) -> Long.compare(PK.arrayIndex(a), PK.arrayIndex(b)));
            result.addAll(indices);
        }
        result.add("length");
        if (map != null) {
            map.forEachLive(i -> {
                Object key = map.keys[i];
                if (key instanceof String && PK.arrayIndex(key) < 0) result.add(key);
            });
            map.forEachLive(i -> {
                Object key = map.keys[i];
                if (key instanceof JSSymbol) result.add(key);
            });
        }
        return result;
    }

    public List<Object> toList() {
        int size = (int) length;
        List<Object> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(get(i, this));
        return result;
    }
}
