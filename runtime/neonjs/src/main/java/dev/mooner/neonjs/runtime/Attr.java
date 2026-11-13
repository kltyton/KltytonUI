package dev.mooner.neonjs.runtime;

/** Property attribute bits; derived from NeonJS and adapted to Java. */
public final class Attr {
    public static final int WRITABLE = 1;
    public static final int ENUMERABLE = 2;
    public static final int CONFIGURABLE = 4;
    public static final int ACCESSOR = 8;
    public static final int ALL = WRITABLE | ENUMERABLE | CONFIGURABLE;
    public static final int WC = WRITABLE | CONFIGURABLE;
    public static final int NONE = 0;

    private Attr() {
    }
}
