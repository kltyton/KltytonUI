package dev.mooner.neonjs.runtime;

/** Built-in function implemented by the host; derived from NeonJS and adapted to Java. */
public class NativeFunction extends JSFunction {
    public final NativeImpl impl;
    public Object slot0;

    public NativeFunction(Realm realm, Object name, int length, NativeImpl impl) {
        this(realm, name, length, impl, false, realm.getFunctionPrototype(), null);
    }

    public NativeFunction(Realm realm, Object name, int length, NativeImpl impl, boolean constructor) {
        this(realm, name, length, impl, constructor, realm.getFunctionPrototype(), null);
    }

    public NativeFunction(Realm realm, Object name, int length, NativeImpl impl, boolean constructor, JSObject prototype) {
        this(realm, name, length, impl, constructor, prototype, null);
    }

    public NativeFunction(Realm realm, Object name, int length, NativeImpl impl, boolean constructor,
                          JSObject prototype, String namePrefix) {
        super(realm, prototype);
        this.impl = impl;
        if (constructor) special |= CONSTRUCTOR;
        defineOwn("length", (double) length, Attr.CONFIGURABLE);
        setFunctionName(name, namePrefix);
    }

    @Override
    public Object call(Object receiver, Object[] arguments) {
        Agent agent = realm.agent;
        Realm previous = agent.currentRealm;
        if (previous == realm) return impl.invoke(this, receiver, arguments, null);
        agent.currentRealm = realm;
        try {
            return impl.invoke(this, receiver, arguments, null);
        } finally {
            agent.currentRealm = previous;
        }
    }

    @Override
    public Object construct(Object[] arguments, JSObject newTarget) {
        Agent agent = realm.agent;
        Realm previous = agent.currentRealm;
        if (previous == realm) return impl.invoke(this, Undefined.INSTANCE, arguments, newTarget);
        agent.currentRealm = realm;
        try {
            return impl.invoke(this, Undefined.INSTANCE, arguments, newTarget);
        } finally {
            agent.currentRealm = previous;
        }
    }

    @Override
    public String sourceText() {
        return "function " + debugName() + "() { [native code] }";
    }
}
