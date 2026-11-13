package dev.mooner.neonjs.vm;

/** Derived-constructor completion; derived from NeonJS and adapted to Java. */
public final class DerivedResult {
    public final Object value;
    public final Object thisValue;

    public DerivedResult(Object value, Object thisValue) {
        this.value = value;
        this.thisValue = thisValue;
    }
}
