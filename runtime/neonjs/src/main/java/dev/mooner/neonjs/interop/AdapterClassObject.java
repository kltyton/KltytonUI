/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The JS view of an adapter class: {@code new Adapter(...ctorArgs)} or {@code new Adapter(impl, ...ctorArgs)}. */
public final class AdapterClassObject extends JSObject {
    public final HostBridge bridge;
    public final AdapterClass adapter;
    private final JSObject classImpl;
    private final String displayName;

    public AdapterClassObject(HostBridge bridge, AdapterClass adapter, JSObject classImpl, String displayName) {
        super(bridge.realm.getFunctionPrototype());
        this.bridge = bridge;
        this.adapter = adapter;
        this.classImpl = classImpl;
        this.displayName = displayName;
        special |= SPECIAL_ALL | CALLABLE | CONSTRUCTOR;
        extensible = false;
        defineOwn("name", displayName, Attr.CONFIGURABLE);
    }

    @Override
    public String getClassName() {
        return "Function";
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        if (key == JSSymbol.hasInstance) {
            return new NativeFunction(bridge.realm, "[Symbol.hasInstance]", 1, (function, thisArg, args, newTarget) -> {
                Object value = Values.arg(args, 0);
                return value instanceof HostObject object && adapter.cls.isInstance(object.target);
            });
        }
        return super.getOwnValue(key, receiver);
    }

    @Override
    public Object get(Object key, Object receiver) {
        Object value = getOwnValue(key, receiver);
        if (value != NotFound.INSTANCE) return value;
        return proto == null ? Undefined.INSTANCE : proto.get(key, receiver);
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        return false;
    }

    @Override
    public Object call(Object thisArg, Object[] args) {
        throw JSException.typeError("Adapter class " + displayName + " must be invoked with 'new'");
    }

    @Override
    public Object construct(Object[] args, JSObject newTarget) {
        JSObject implementation;
        Object[] constructorArgs;
        if (classImpl != null) {
            implementation = classImpl;
            constructorArgs = args;
        } else {
            Object first = Values.arg(args, 0);
            if (!(first instanceof JSObject object)) {
                throw JSException.typeError(displayName
                        + ": the first argument must be an object implementing the methods");
            }
            implementation = object;
            constructorArgs = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        }
        List<Constructor<?>> constructors = new java.util.ArrayList<>(adapter.constructors.size());
        for (AdapterClass.ConstructorPair pair : adapter.constructors) constructors.add(pair.base());
        Constructor<?> base = bridge.select(constructors, constructorArgs);
        if (base == null) {
            throw JSException.typeError("No applicable constructor for " + displayName
                    + " with arguments (" + joinTypes(constructorArgs) + ")");
        }
        Constructor<?> generated = null;
        for (AdapterClass.ConstructorPair pair : adapter.constructors) {
            if (pair.base() == base) {
                generated = pair.adapter();
                break;
            }
        }
        if (generated == null) {
            throw new java.util.NoSuchElementException("No generated adapter constructor for " + base);
        }
        Object[] converted = bridge.convertArgs(base, constructorArgs);
        Object instance;
        try {
            Object[] invocation = new Object[converted.length + 1];
            invocation[0] = new JSAdapterDelegate(bridge, implementation, adapter);
            System.arraycopy(converted, 0, invocation, 1, converted.length);
            instance = generated.newInstance(invocation);
        } catch (InvocationTargetException error) {
            throw bridge.hostError(error.getTargetException());
        } catch (IllegalArgumentException error) {
            throw JSException.typeError("Cannot construct " + displayName + ": " + error.getMessage());
        } catch (ReflectiveOperationException error) {
            throw JSException.typeError("Cannot construct " + displayName + ": " + error.getMessage());
        }
        return bridge.toJS(instance);
    }

    private static String joinTypes(Object[] args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(Ops.typeOf(args[i]));
        }
        return builder.toString();
    }

    /** {@code Java.super(instance)}: an object whose methods run the base-class implementations. */
    public JSObject superOf(HostObject instance) {
        JSObject object = new JSObject(bridge.realm.getObjectPrototype());
        for (Map.Entry<String, List<Method>> entry : adapter.superMethods.entrySet()) {
            object.defineOwn(entry.getKey(),
                    new HostMethodFunction(bridge, entry.getKey(), entry.getValue(), instance.target),
                    Attr.WC);
        }
        return object;
    }
}
