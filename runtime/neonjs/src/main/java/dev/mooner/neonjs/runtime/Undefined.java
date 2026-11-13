package dev.mooner.neonjs.runtime;

/** JavaScript undefined; derived from NeonJS and adapted to Java. */
public final class Undefined {
    public static final Undefined INSTANCE = new Undefined();

    private Undefined() {
    }

    @Override
    public String toString() {
        return "undefined";
    }
}
