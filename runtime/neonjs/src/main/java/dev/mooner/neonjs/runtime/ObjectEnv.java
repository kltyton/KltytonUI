package dev.mooner.neonjs.runtime;

/** Object-backed environment; derived from NeonJS and adapted to Java. */
public final class ObjectEnv extends Env {
    public final JSObject obj;
    public final boolean isWith;

    public ObjectEnv(Env parent, JSObject object, boolean withStatement) {
        super(parent);
        obj = object;
        isWith = withStatement;
    }
}
