package dev.mooner.neonjs.runtime;

/** Global variable access site; derived from NeonJS and adapted to Java. */
public final class GlobalSite {
    public final String name;
    public GlobalIC cache;
    public int fills;

    public GlobalSite(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
