package dev.mooner.neonjs.runtime;

/** Dense array hole marker; derived from NeonJS and adapted to Java. */
public final class Hole {
    public static final Hole INSTANCE = new Hole();

    private Hole() {
    }

    @Override
    public String toString() {
        return "<hole>";
    }
}
