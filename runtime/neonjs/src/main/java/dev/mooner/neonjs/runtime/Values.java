package dev.mooner.neonjs.runtime;

/** Argument access for engine built-ins; derived from NeonJS and adapted to Java. */
public final class Values {
    public static final Object[] EMPTY_ARGS = new Object[0];

    private Values() {
    }

    public static Object arg(Object[] arguments, int index) {
        return index < arguments.length ? arguments[index] : Undefined.INSTANCE;
    }
}
