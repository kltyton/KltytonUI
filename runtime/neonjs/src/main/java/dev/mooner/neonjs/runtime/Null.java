package dev.mooner.neonjs.runtime;

/** JavaScript null; derived from NeonJS and adapted to Java. */
public final class Null {
    public static final Null INSTANCE = new Null();

    private Null() {
    }

    @Override
    public String toString() {
        return "null";
    }
}
