package dev.mooner.neonjs.runtime;

/** Partial JavaScript property descriptor; derived from NeonJS and adapted to Java. */
public final class PropertyDescriptor {
    public static final int HAS_VALUE = 1;
    public static final int HAS_WRITABLE = 2;
    public static final int HAS_GET = 4;
    public static final int HAS_SET = 8;
    public static final int HAS_ENUMERABLE = 16;
    public static final int HAS_CONFIGURABLE = 32;
    public Object value = Undefined.INSTANCE;
    public Object getter = Undefined.INSTANCE;
    public Object setter = Undefined.INSTANCE;
    public boolean writable;
    public boolean enumerable;
    public boolean configurable;
    public int present;

    public boolean getHasValue() { return (present & HAS_VALUE) != 0; }
    public boolean getHasWritable() { return (present & HAS_WRITABLE) != 0; }
    public boolean getHasGet() { return (present & HAS_GET) != 0; }
    public boolean getHasSet() { return (present & HAS_SET) != 0; }
    public boolean getHasEnumerable() { return (present & HAS_ENUMERABLE) != 0; }
    public boolean getHasConfigurable() { return (present & HAS_CONFIGURABLE) != 0; }
    public boolean isAccessor() { return (present & (HAS_GET | HAS_SET)) != 0; }
    public boolean isData() { return (present & (HAS_VALUE | HAS_WRITABLE)) != 0; }
    public boolean isGeneric() { return !isAccessor() && !isData(); }

    public PropertyDescriptor value(Object value) { this.value = value; present |= HAS_VALUE; return this; }
    public PropertyDescriptor writable(boolean value) { writable = value; present |= HAS_WRITABLE; return this; }
    public PropertyDescriptor enumerable(boolean value) { enumerable = value; present |= HAS_ENUMERABLE; return this; }
    public PropertyDescriptor configurable(boolean value) { configurable = value; present |= HAS_CONFIGURABLE; return this; }
    public PropertyDescriptor getter(Object value) { getter = value; present |= HAS_GET; return this; }
    public PropertyDescriptor setter(Object value) { setter = value; present |= HAS_SET; return this; }

    public int attrs() {
        return (writable ? Attr.WRITABLE : 0) | (enumerable ? Attr.ENUMERABLE : 0)
                | (configurable ? Attr.CONFIGURABLE : 0) | (isAccessor() ? Attr.ACCESSOR : 0);
    }

    public static PropertyDescriptor data(Object value, int attributes) {
        PropertyDescriptor descriptor = new PropertyDescriptor();
        descriptor.value = value;
        descriptor.writable = (attributes & Attr.WRITABLE) != 0;
        descriptor.enumerable = (attributes & Attr.ENUMERABLE) != 0;
        descriptor.configurable = (attributes & Attr.CONFIGURABLE) != 0;
        descriptor.present = HAS_VALUE | HAS_WRITABLE | HAS_ENUMERABLE | HAS_CONFIGURABLE;
        return descriptor;
    }

    public static PropertyDescriptor accessor(Object getter, Object setter, int attributes) {
        PropertyDescriptor descriptor = new PropertyDescriptor();
        descriptor.getter = getter == null ? Undefined.INSTANCE : getter;
        descriptor.setter = setter == null ? Undefined.INSTANCE : setter;
        descriptor.enumerable = (attributes & Attr.ENUMERABLE) != 0;
        descriptor.configurable = (attributes & Attr.CONFIGURABLE) != 0;
        descriptor.present = HAS_GET | HAS_SET | HAS_ENUMERABLE | HAS_CONFIGURABLE;
        return descriptor;
    }
}
