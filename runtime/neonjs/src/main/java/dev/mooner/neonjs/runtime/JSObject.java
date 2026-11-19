package dev.mooner.neonjs.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/** Ordinary JavaScript object operations; derived from NeonJS and adapted to Java. */
public class JSObject {
    public static final int EXOTIC_OWN = 1;
    public static final int SPECIAL_GET = 2;
    public static final int SPECIAL_SET = 4;
    public static final int SPECIAL_HAS = 8;
    public static final int CALLABLE = 16;
    public static final int CONSTRUCTOR = 32;
    public static final int IMMUTABLE_PROTO = 64;
    public static final int CLASS_CONSTRUCTOR = 128;
    public static final int HTMLDDA = 256;
    public static final int SPECIAL_ALL = EXOTIC_OWN | SPECIAL_GET | SPECIAL_SET | SPECIAL_HAS;

    public JSObject proto;
    public PropertyMap props;
    public boolean extensible = true;
    public int special;
    public IdentityHashMap<Object, Object> privateElements;
    public Object childShapes;

    public JSObject(JSObject prototype) {
        proto = prototype;
    }

    public String getClassName() {
        return "Object";
    }

    public final boolean isCallable() {
        return (special & CALLABLE) != 0;
    }

    public final boolean isConstructor() {
        return (special & CONSTRUCTOR) != 0;
    }

    public Object call(Object receiver, Object[] arguments) {
        throw JSException.typeError(Ops.describe(this) + " is not a function");
    }

    public Object construct(Object[] arguments, JSObject newTarget) {
        throw JSException.typeError(Ops.describe(this) + " is not a constructor");
    }

    public JSObject getPrototypeOf() {
        return proto;
    }

    public boolean setPrototypeOf(JSObject prototype) {
        if (prototype == proto) return true;
        if ((special & IMMUTABLE_PROTO) != 0 || !extensible) return false;
        JSObject current = prototype;
        while (current != null) {
            if (current == this) return false;
            if ((current.special & SPECIAL_GET) != 0 && current instanceof ProxyObject) break;
            current = current.proto;
        }
        changeProto(prototype);
        return true;
    }

    public final void changeProto(JSObject prototype) {
        if (prototype == proto) return;
        proto = prototype;
        if (props != null) props.reroot(Shape.emptyShapeOf(this));
    }

    public boolean isExtensible() {
        return extensible;
    }

    public boolean preventExtensions() {
        extensible = false;
        return true;
    }

    public final PropertyMap ensureProps() {
        PropertyMap map = props;
        if (map == null) {
            map = new PropertyMap(Shape.emptyShapeOf(this));
            props = map;
        }
        return map;
    }

    public PropertyDescriptor getOwnProperty(Object key) {
        return ordinaryGetOwnProperty(key);
    }

    /** Property flags without materializing values; exotic objects retain their descriptor operation. */
    public int getOwnPropertyAttributes(Object key) {
        if ((special & EXOTIC_OWN) != 0) {
            PropertyDescriptor descriptor = getOwnProperty(key);
            return descriptor == null ? -1 : descriptor.attrs();
        }
        PropertyMap map = props;
        int entry = map == null ? -1 : map.find(key);
        return entry < 0 ? -1 : map.flags[entry];
    }

    public final PropertyDescriptor ordinaryGetOwnProperty(Object key) {
        PropertyMap map = props;
        if (map == null) return null;
        int entry = map.find(key);
        if (entry < 0) return null;
        int flags = map.flags[entry];
        if ((flags & Attr.ACCESSOR) != 0) {
            Accessor accessor = (Accessor) map.values[entry];
            return PropertyDescriptor.accessor(accessor.getter, accessor.setter, flags);
        }
        return PropertyDescriptor.data(map.values[entry], flags);
    }

    public Object getOwnValue(Object key, Object receiver) {
        PropertyMap map = props;
        if (map == null) return NotFound.INSTANCE;
        int entry = map.find(key);
        if (entry < 0) return NotFound.INSTANCE;
        if ((map.flags[entry] & Attr.ACCESSOR) != 0) {
            Object getter = ((Accessor) map.values[entry]).getter;
            return getter instanceof JSObject function ? function.call(receiver, EMPTY_ARGS) : Undefined.INSTANCE;
        }
        return map.values[entry];
    }

    public boolean icSafeKey(Object key) {
        return (special & EXOTIC_OWN) == 0;
    }

    public boolean hasOwnProperty(Object key) {
        if ((special & EXOTIC_OWN) != 0) return getOwnProperty(key) != null;
        PropertyMap map = props;
        return map != null && map.find(key) >= 0;
    }

    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        return ordinaryDefineOwnProperty(key, descriptor);
    }

    public final boolean ordinaryDefineOwnProperty(Object key, PropertyDescriptor descriptor) {
        PropertyMap map = props;
        int entry = map == null ? -1 : map.find(key);
        if (entry < 0) {
            if (!isExtensible()) return false;
            PropertyMap target = ensureProps();
            if (descriptor.isAccessor()) {
                target.add(key, new Accessor(descriptor.getHasGet() ? descriptor.getter : Undefined.INSTANCE,
                        descriptor.getHasSet() ? descriptor.setter : Undefined.INSTANCE), Attr.ACCESSOR
                        | (descriptor.enumerable ? Attr.ENUMERABLE : 0)
                        | (descriptor.configurable ? Attr.CONFIGURABLE : 0));
            } else {
                target.add(key, descriptor.getHasValue() ? descriptor.value : Undefined.INSTANCE,
                        (descriptor.writable ? Attr.WRITABLE : 0)
                        | (descriptor.enumerable ? Attr.ENUMERABLE : 0)
                        | (descriptor.configurable ? Attr.CONFIGURABLE : 0));
            }
            return true;
        }
        return applyDescriptor(map, entry, descriptor);
    }

    private boolean applyDescriptor(PropertyMap map, int entry, PropertyDescriptor descriptor) {
        if (descriptor.present == 0) return true;
        int flags = map.flags[entry];
        boolean accessor = (flags & Attr.ACCESSOR) != 0;
        boolean configurable = (flags & Attr.CONFIGURABLE) != 0;
        boolean enumerable = (flags & Attr.ENUMERABLE) != 0;
        if (!configurable) {
            if (descriptor.getHasConfigurable() && descriptor.configurable) return false;
            if (descriptor.getHasEnumerable() && descriptor.enumerable != enumerable) return false;
            if (!descriptor.isGeneric() && descriptor.isAccessor() != accessor) return false;
            if (accessor) {
                Accessor current = (Accessor) map.values[entry];
                if (descriptor.getHasGet() && !Ops.sameValue(descriptor.getter, current.getter)) return false;
                if (descriptor.getHasSet() && !Ops.sameValue(descriptor.setter, current.setter)) return false;
            } else if ((flags & Attr.WRITABLE) == 0) {
                if (descriptor.getHasWritable() && descriptor.writable) return false;
                if (descriptor.getHasValue() && !Ops.sameValue(descriptor.value, map.values[entry])) return false;
            }
        }
        boolean nextConfigurable = descriptor.getHasConfigurable() ? descriptor.configurable : configurable;
        boolean nextEnumerable = descriptor.getHasEnumerable() ? descriptor.enumerable : enumerable;
        int ce = (nextConfigurable ? Attr.CONFIGURABLE : 0) | (nextEnumerable ? Attr.ENUMERABLE : 0);
        if (!accessor && descriptor.isAccessor()) {
            map.values[entry] = new Accessor(descriptor.getHasGet() ? descriptor.getter : Undefined.INSTANCE,
                    descriptor.getHasSet() ? descriptor.setter : Undefined.INSTANCE);
            map.setFlags(entry, Attr.ACCESSOR | ce);
        } else if (accessor && descriptor.isData()) {
            map.values[entry] = descriptor.getHasValue() ? descriptor.value : Undefined.INSTANCE;
            map.setFlags(entry, ce | (descriptor.getHasWritable() && descriptor.writable ? Attr.WRITABLE : 0));
        } else if (accessor) {
            Accessor current = (Accessor) map.values[entry];
            if (descriptor.getHasGet()) current.getter = descriptor.getter;
            if (descriptor.getHasSet()) current.setter = descriptor.setter;
            map.setFlags(entry, Attr.ACCESSOR | ce);
        } else {
            if (descriptor.getHasValue()) map.values[entry] = descriptor.value;
            boolean writable = descriptor.getHasWritable() ? descriptor.writable : (flags & Attr.WRITABLE) != 0;
            map.setFlags(entry, ce | (writable ? Attr.WRITABLE : 0));
        }
        return true;
    }

    public final boolean validateAndApply(boolean extensible, PropertyDescriptor descriptor,
                                           PropertyDescriptor current) {
        return Ops.isCompatiblePropertyDescriptor(extensible, descriptor, current);
    }

    public boolean hasProperty(Object key) {
        JSObject current = this;
        while (true) {
            if (current != this && (current.special & SPECIAL_HAS) != 0) return current.hasProperty(key);
            if (current.hasOwnProperty(key)) return true;
            current = current.getPrototypeOf();
            if (current == null) return false;
        }
    }

    public Object get(Object key, Object receiver) {
        JSObject current = this;
        while (true) {
            Object value = current.getOwnValue(key, receiver);
            if (value != NotFound.INSTANCE) return value;
            current = current.getPrototypeOf();
            if (current == null) return Undefined.INSTANCE;
            if ((current.special & SPECIAL_GET) != 0) return current.get(key, receiver);
        }
    }

    public final Object get(Object key) {
        return get(key, this);
    }

    public boolean set(Object key, Object value, Object receiver) {
        return ordinarySet(key, value, receiver);
    }

    public final boolean ordinarySet(Object key, Object value, Object receiver) {
        JSObject current = this;
        while (true) {
            if ((current.special & EXOTIC_OWN) == 0) {
                PropertyMap map = current.props;
                int entry = map == null ? -1 : map.find(key);
                if (entry >= 0) {
                    int flags = map.flags[entry];
                    if ((flags & Attr.ACCESSOR) != 0) {
                        Object setter = ((Accessor) map.values[entry]).setter;
                        if (!(setter instanceof JSObject function)) return false;
                        function.call(receiver, new Object[] {value});
                        return true;
                    }
                    if ((flags & Attr.WRITABLE) == 0) return false;
                    if (receiver == current) {
                        map.values[entry] = value;
                        return true;
                    }
                    return setOnReceiver(key, value, receiver);
                }
            } else {
                PropertyDescriptor descriptor = current.getOwnProperty(key);
                if (descriptor != null) {
                    if (descriptor.isAccessor()) {
                        if (!(descriptor.setter instanceof JSObject function)) return false;
                        function.call(receiver, new Object[] {value});
                        return true;
                    }
                    if (!descriptor.writable) return false;
                    return setOnReceiver(key, value, receiver);
                }
            }
            JSObject parent = current.getPrototypeOf();
            if (parent == null) break;
            if ((parent.special & SPECIAL_SET) != 0) return parent.set(key, value, receiver);
            current = parent;
        }
        return setOnReceiver(key, value, receiver);
    }

    protected final boolean setOnReceiver(Object key, Object value, Object receiver) {
        if (!(receiver instanceof JSObject object)) return false;
        if ((object.special & EXOTIC_OWN) == 0 && (object.special & SPECIAL_GET) == 0) {
            PropertyMap map = object.props;
            int entry = map == null ? -1 : map.find(key);
            if (entry >= 0) {
                int flags = map.flags[entry];
                if ((flags & Attr.ACCESSOR) != 0 || (flags & Attr.WRITABLE) == 0) return false;
                map.values[entry] = value;
                return true;
            }
            if (!object.isExtensible()) return false;
            object.ensureProps().add(key, value, Attr.ALL);
            return true;
        }
        PropertyDescriptor existing = object.getOwnProperty(key);
        if (existing != null) {
            return !existing.isAccessor() && existing.writable
                    && object.defineOwnProperty(key, new PropertyDescriptor().value(value));
        }
        return object.defineOwnProperty(key, PropertyDescriptor.data(value, Attr.ALL));
    }

    public boolean delete(Object key) {
        PropertyMap map = props;
        if (map == null) return true;
        int entry = map.find(key);
        if (entry < 0) return true;
        if ((map.flags[entry] & Attr.CONFIGURABLE) == 0) return false;
        map.removeAt(entry);
        return true;
    }

    public List<Object> ownPropertyKeys() {
        return ordinaryOwnKeys(null);
    }

    public final List<Object> ordinaryOwnKeys(List<Object> extraIndices) {
        PropertyMap map = props;
        List<Object> out = new ArrayList<>((map == null ? 0 : map.count)
                + (extraIndices == null ? 0 : extraIndices.size()));
        if (extraIndices != null) out.addAll(extraIndices);
        if (map == null) return out;
        ArrayList<Object> indices = null;
        if (map.hasIndexKeys) {
            indices = new ArrayList<>();
            for (int i = 0, limit = map.used; i < limit; i++) {
                if (!map.isLive(i)) continue;
                Object key = map.keys[i];
                if (key instanceof Integer) indices.add(key);
            }
        }
        for (int i = 0, limit = map.used; i < limit; i++) {
            if (!map.isLive(i)) continue;
            Object key = map.keys[i];
            if (key instanceof String text && text.length() == 10 && PK.arrayIndex(key) >= 0) {
                if (indices == null) indices = new ArrayList<>();
                indices.add(key);
            }
        }
        if (indices != null) {
            indices.sort(Comparator.comparingLong(PK::arrayIndex));
            if (extraIndices != null && !extraIndices.isEmpty()) {
                indices.addAll(0, extraIndices);
                indices.sort(Comparator.comparingLong(PK::arrayIndex));
                out.clear();
            }
            out.addAll(indices);
        }
        for (int i = 0, limit = map.used; i < limit; i++) {
            if (!map.isLive(i)) continue;
            Object key = map.keys[i];
            if (key instanceof String text && !(text.length() == 10 && PK.arrayIndex(key) >= 0)) out.add(key);
        }
        for (int i = 0, limit = map.used; i < limit; i++) {
            if (map.isLive(i) && map.keys[i] instanceof JSSymbol) out.add(map.keys[i]);
        }
        return out;
    }

    public final void defineOwn(Object key, Object value) {
        defineOwn(key, value, Attr.WC);
    }

    public final void defineOwn(Object key, Object value, int attributes) {
        PropertyMap map = ensureProps();
        int entry = map.find(key);
        if (entry >= 0) {
            map.values[entry] = value;
            map.setFlags(entry, attributes);
        } else {
            map.add(key, value, attributes);
        }
    }

    public final void defineAccessor(Object key, Object getter, Object setter) {
        defineAccessor(key, getter, setter, Attr.CONFIGURABLE);
    }

    public final void defineAccessor(Object key, Object getter, Object setter, int attributes) {
        PropertyMap map = ensureProps();
        int entry = map.find(key);
        Accessor accessor = new Accessor(getter == null ? Undefined.INSTANCE : getter,
                setter == null ? Undefined.INSTANCE : setter);
        if (entry >= 0) {
            map.values[entry] = accessor;
            map.setFlags(entry, attributes | Attr.ACCESSOR);
        } else {
            map.add(key, accessor, attributes | Attr.ACCESSOR);
        }
    }

    public final boolean createDataProperty(Object key, Object value) {
        if (this instanceof JSArray array && key instanceof Integer integer) {
            int result = array.createIndexFast(integer, value);
            if (result >= 0) return result == 1;
        }
        if ((special & SPECIAL_ALL) == 0) {
            PropertyMap map = props;
            int entry = map == null ? -1 : map.find(key);
            if (entry < 0) {
                if (!isExtensible()) return false;
                ensureProps().add(key, value, Attr.ALL);
                return true;
            }
        }
        return defineOwnProperty(key, PropertyDescriptor.data(value, Attr.ALL));
    }

    public final void createDataPropertyOrThrow(Object key, Object value) {
        if (!createDataProperty(key, value)) throw JSException.typeError("Cannot define property " + PK.toStringKey(key));
    }

    public final void definePropertyOrThrow(Object key, PropertyDescriptor descriptor) {
        if (!defineOwnProperty(key, descriptor)) throw JSException.typeError("Cannot redefine property: " + PK.toStringKey(key));
    }

    public final void setOrThrow(Object key, Object value) {
        if (!set(key, value, this)) throw JSException.typeError("Cannot assign to read only property '"
                + PK.toStringKey(key) + "' of " + Ops.describe(this));
    }

    public final void deleteOrThrow(Object key) {
        if (!delete(key)) throw JSException.typeError("Cannot delete property '" + PK.toStringKey(key)
                + "' of " + Ops.describe(this));
    }

    @Override
    public String toString() {
        return "[object " + getClassName() + "]";
    }
}
