package dev.mooner.neonjs.runtime;

/** Named-property access site; derived from NeonJS and adapted to Java. */
public final class PropSite {
    public final Object key;
    public PropIC entry;
    public int fills;

    public PropSite(Object key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return PK.toStringKey(key);
    }
}
