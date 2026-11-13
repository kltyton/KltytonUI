package dev.mooner.neonjs.runtime;

/** Property lookup miss marker; derived from NeonJS and adapted to Java. */
public final class NotFound {
    public static final NotFound INSTANCE = new NotFound();

    private NotFound() {
    }

    @Override
    public String toString() {
        return "<not found>";
    }
}
