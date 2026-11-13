package dev.mooner.neonjs.runtime;

/** Getter and setter values in a property slot; derived from NeonJS and adapted to Java. */
public final class Accessor {
    public Object getter;
    public Object setter;

    public Accessor(Object getter, Object setter) {
        this.getter = getter;
        this.setter = setter;
    }
}
