package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;

/** Unmapped arguments exotic object; derived from NeonJS and adapted to Java. */
public class JSArgumentsObject extends JSObject {
    public JSArgumentsObject(JSObject prototype) {
        super(prototype);
    }

    @Override
    public String getClassName() {
        return "Arguments";
    }
}
