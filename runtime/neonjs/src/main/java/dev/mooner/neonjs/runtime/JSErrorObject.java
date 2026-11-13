package dev.mooner.neonjs.runtime;

import java.lang.ref.WeakReference;

/** Object with JavaScript error data; derived from NeonJS and adapted to Java. */
public class JSErrorObject extends JSObject {
    public String stackTrace;
    public WeakReference<Object> capturedAt;
    public int capturedPc = -1;

    public JSErrorObject(JSObject prototype) {
        super(prototype);
    }

    @Override
    public String getClassName() {
        return "Error";
    }

    public String getSlotName() {
        return null;
    }

    public String getSlotMessage() {
        return null;
    }
}
