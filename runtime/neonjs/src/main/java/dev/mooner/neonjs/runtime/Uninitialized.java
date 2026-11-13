package dev.mooner.neonjs.runtime;

/** Temporal dead-zone marker; derived from NeonJS and adapted to Java. */
public final class Uninitialized {
    public static final Uninitialized INSTANCE = new Uninitialized();

    private Uninitialized() {
    }

    @Override
    public String toString() {
        return "<uninitialized>";
    }
}
