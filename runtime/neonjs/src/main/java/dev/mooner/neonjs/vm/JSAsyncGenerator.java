package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;
import java.util.ArrayDeque;

/** Asynchronous generator state; derived from NeonJS and adapted to Java. */
public final class JSAsyncGenerator extends JSObject {
    public static final int SUSPENDED_START = 0;
    public static final int SUSPENDED_YIELD = 1;
    public static final int EXECUTING = 2;
    public static final int AWAITING_RETURN = 3;
    public static final int COMPLETED = 4;
    public Frame frame;
    public int state = SUSPENDED_START;
    public final ArrayDeque<AsyncGenRequest> queue = new ArrayDeque<>();

    public JSAsyncGenerator(JSObject prototype, Frame frame) {
        super(prototype);
        this.frame = frame;
    }
}
