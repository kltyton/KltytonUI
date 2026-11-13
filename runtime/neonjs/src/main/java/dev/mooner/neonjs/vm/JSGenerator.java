package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;

/** Synchronous generator state; derived from NeonJS and adapted to Java. */
public final class JSGenerator extends JSObject {
    public static final int SUSPENDED_START = 0;
    public static final int SUSPENDED_YIELD = 1;
    public static final int EXECUTING = 2;
    public static final int COMPLETED = 3;
    public Frame frame;
    public int state = SUSPENDED_START;

    public JSGenerator(JSObject prototype, Frame frame) {
        super(prototype);
        this.frame = frame;
    }

    public Object resume(Realm realm, Object value, int mode) {
        if (state == EXECUTING) throw JSException.typeError("Generator is already running");
        if (state == COMPLETED) {
            if (mode == Frame.MODE_THROW) throw new JSException(value);
            return Iteration.createIterResult(realm, mode == Frame.MODE_RETURN ? value : Undefined.INSTANCE, true);
        }
        if (state == SUSPENDED_START) {
            if (mode == Frame.MODE_RETURN) {
                state = COMPLETED;
                frame = null;
                return Iteration.createIterResult(realm, value, true);
            }
            if (mode == Frame.MODE_THROW) {
                state = COMPLETED;
                frame = null;
                throw new JSException(value);
            }
        } else {
            frame.slots[frame.sp++] = value;
            frame.setResumeMode(mode);
        }
        Frame current = frame;
        state = EXECUTING;
        boolean executed = false;
        Object result;
        try {
            result = Interpreter.execute(current);
            executed = true;
        } finally {
            if (!executed) {
                state = COMPLETED;
                frame = null;
            }
        }
        if (result == Interpreter.SUSPENDED) {
            state = SUSPENDED_YIELD;
            Object yielded = current.getSuspendValue();
            current.setSuspendValue(null);
            return current.getSuspendKind() == Frame.SUSPEND_YIELD_RAW ? yielded
                    : Iteration.createIterResult(realm, yielded, false);
        }
        state = COMPLETED;
        frame = null;
        return Iteration.createIterResult(realm, result, true);
    }
}
