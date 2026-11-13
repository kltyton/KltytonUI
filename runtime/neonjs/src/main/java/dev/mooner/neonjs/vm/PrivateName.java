package dev.mooner.neonjs.vm;

/** Identity-based private class name; derived from NeonJS and adapted to Java. */
public final class PrivateName {
    public final String description;

    public PrivateName(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return description;
    }
}
