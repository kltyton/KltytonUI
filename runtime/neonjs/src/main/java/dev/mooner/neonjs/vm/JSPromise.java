package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;

/** Promise internal state; derived from NeonJS and adapted to Java. */
public final class JSPromise extends JSObject {
    public static final int PENDING = 0;
    public static final int FULFILLED = 1;
    public static final int REJECTED = 2;
    public int state = PENDING;
    public Object result = Undefined.INSTANCE;
    public ArrayList<PromiseReaction> fulfillReactions = new ArrayList<>(1);
    public ArrayList<PromiseReaction> rejectReactions = new ArrayList<>(1);
    public boolean isHandled;

    public JSPromise(JSObject prototype) {
        super(prototype);
    }

    @Override
    public String getClassName() {
        return "Promise";
    }
}
