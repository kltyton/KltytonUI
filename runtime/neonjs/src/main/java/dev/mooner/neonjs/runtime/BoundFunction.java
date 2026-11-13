package dev.mooner.neonjs.runtime;

/** Bound function exotic object; derived from NeonJS and adapted to Java. */
public final class BoundFunction extends JSObject {
    public final JSObject target;
    public final Object boundThis;
    public final Object[] boundArgs;

    public BoundFunction(JSObject target, Object receiver, Object[] arguments, JSObject prototype) {
        super(prototype);
        this.target = target;
        boundThis = receiver;
        boundArgs = arguments;
        special |= CALLABLE;
        if (target.isConstructor()) special |= CONSTRUCTOR;
    }

    @Override
    public String getClassName() {
        return "Function";
    }

    private Object[] allArgs(Object[] arguments) {
        if (boundArgs.length == 0) return arguments;
        if (arguments.length == 0) return boundArgs.clone();
        Object[] combined = new Object[boundArgs.length + arguments.length];
        System.arraycopy(boundArgs, 0, combined, 0, boundArgs.length);
        System.arraycopy(arguments, 0, combined, boundArgs.length, arguments.length);
        return combined;
    }

    @Override
    public Object call(Object receiver, Object[] arguments) {
        return target.call(boundThis, allArgs(arguments));
    }

    @Override
    public Object construct(Object[] arguments, JSObject newTarget) {
        return target.construct(allArgs(arguments), newTarget == this ? target : newTarget);
    }
}
