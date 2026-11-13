package dev.mooner.neonjs.vm;

/** Private method or accessor definition; derived from NeonJS and adapted to Java. */
public final class PrivateElement {
    public static final int FIELD = 0;
    public static final int METHOD = 1;
    public static final int ACCESSOR = 2;
    public final PrivateName key;
    public final int kind;
    public Object value;
    public Object getter;
    public Object setter;

    public PrivateElement(PrivateName key, int kind, Object value, Object getter, Object setter) {
        this.key = key;
        this.kind = kind;
        this.value = value;
        this.getter = getter;
        this.setter = setter;
    }
}
