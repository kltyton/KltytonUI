package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;
import java.util.List;

/** Class field definition and decorator initializers; derived from NeonJS and adapted to Java. */
public final class FieldRecord {
    public final Object key;
    public final Object initializer;
    public List<JSObject> decoratorInitializers;
    public List<JSObject> extraInitializers;

    public FieldRecord(Object key, Object initializer) {
        this.key = key;
        this.initializer = initializer;
    }
}
