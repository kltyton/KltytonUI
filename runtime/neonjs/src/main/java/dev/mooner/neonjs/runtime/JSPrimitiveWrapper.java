package dev.mooner.neonjs.runtime;

/** Wrapper for a JavaScript primitive; derived from NeonJS and adapted to Java. */
public final class JSPrimitiveWrapper extends JSObject {
    public final Object primitive;

    public JSPrimitiveWrapper(JSObject prototype, Object primitive) {
        super(prototype);
        this.primitive = primitive;
    }

    @Override
    public String getClassName() {
        if (primitive instanceof Boolean) return "Boolean";
        if (primitive instanceof Double) return "Number";
        return "Object";
    }
}
