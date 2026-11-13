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
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** JS view of a host class: static members, constructor ({@code new}), instanceof checks and nested classes. */
public class HostClassObject extends JSObject {
    public final HostBridge bridge;
    public final Class<?> cls;
    public final HostClassInfo info;
    private final HashMap<String, HostMethodFunction> methodCache = new HashMap<>();
    private HostObject companion;
    private boolean companionReady;

    public HostClassObject(HostBridge bridge, Class<?> cls) {
        super(bridge.realm.getFunctionPrototype());
        this.bridge = bridge;
        this.cls = cls;
        info = bridge.classInfo(cls);
        special |= SPECIAL_ALL | CALLABLE;
        if (!info.constructors.isEmpty()
                || (cls.isInterface() && bridge.access.allowImplementations)
                || cls.isArray()) {
            special |= CONSTRUCTOR;
        }
        extensible = false;
    }

    @Override
    public String getClassName() {
        return "Function";
    }

    /** Kotlin companion object (its members are also visible on the class, like {@code @JvmStatic} ones). */
    private synchronized HostObject companion() {
        if (companionReady) return companion;
        companionReady = true;
        Field field = info.staticFields.get("Companion");
        if (field == null) return null;
        Object value;
        try {
            value = field.get(null);
        } catch (IllegalAccessException denied) {
            value = null;
        }
        companion = value == null ? null : bridge.wrap(value);
        return companion;
    }

    private Object member(Object key) {
        if (key == JSSymbol.hasInstance) {
            return new NativeFunction(bridge.realm, "[Symbol.hasInstance]", 1, (function, thisArg, args, newTarget) -> {
                Object value = bridge.gate.normalizeGuest(Values.arg(args, 0));
                return value instanceof HostObject object && cls.isInstance(object.target);
            });
        }
        if (!(key instanceof String name)) return NotFound.INSTANCE;
        if (name.equals("name")) return cls.getSimpleName();
        if (name.equals("length")) {
            int length = Integer.MAX_VALUE;
            for (Constructor<?> constructor : info.constructors) {
                length = Math.min(length, constructor.getParameterCount());
            }
            if (length == Integer.MAX_VALUE) length = 0;
            return (double) length;
        }
        Field field = info.staticFields.get(name);
        if (field != null) {
            try {
                return bridge.toJS(field.get(null));
            } catch (IllegalAccessException denied) {
                throw JSException.typeError("Cannot access field " + name);
            }
        }
        List<Method> methods = info.staticMethods.get(name);
        if (methods != null) {
            HostMethodFunction function = methodCache.get(name);
            if (function == null) {
                function = new HostMethodFunction(bridge, name, methods, null);
                methodCache.put(name, function);
            }
            return function;
        }
        Method overload = info.overload(name, true);
        if (overload != null) {
            int open = name.indexOf('(');
            String display = open < 0 ? name : name.substring(0, open);
            HostMethodFunction function = methodCache.get(name);
            if (function == null) {
                function = new HostMethodFunction(bridge, display, List.of(overload), null);
                methodCache.put(name, function);
            }
            return function;
        }
        Method getter = info.staticGetters.get(name);
        if (getter != null) return bridge.invoke(getter, null, Values.EMPTY_ARGS);
        Class<?> memberClass = info.memberClasses.get(name);
        if (memberClass != null) return bridge.toJS(bridge.classObject(memberClass));
        if (cls.isEnum()) {
            Object[] constants = cls.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (((Enum<?>) constant).name().equals(name)) return bridge.toJS(constant);
                }
            }
        }
        HostObject companion = companion();
        if (companion != null) {
            Object value = companion.getOwnValue(name, companion);
            if (value != NotFound.INSTANCE) return value;
        }
        return NotFound.INSTANCE;
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        return member(key);
    }

    @Override
    public Object get(Object key, Object receiver) {
        Object value = member(key);
        if (value != NotFound.INSTANCE) return value;
        return proto == null ? Undefined.INSTANCE : proto.get(key, receiver);
    }

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        Object value = member(key);
        if (value == NotFound.INSTANCE) return null;
        return PropertyDescriptor.data(value, Attr.ENUMERABLE);
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        return member(key) != NotFound.INSTANCE;
    }

    @Override
    public boolean hasProperty(Object key) {
        return hasOwnProperty(key) || (proto != null && proto.hasProperty(key));
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        if (!(key instanceof String name)) return false;
        Field field = info.staticFields.get(name);
        if (field != null && !Modifier.isFinal(field.getModifiers())) {
            try {
                field.set(null, bridge.toField(value, field));
            } catch (IllegalAccessException denied) {
                return false;
            } catch (IllegalArgumentException error) {
                throw JSException.typeError("Cannot set field " + key + ": " + error.getMessage());
            }
            return true;
        }
        List<Method> setters = info.staticSetters.get(name);
        if (setters == null) {
            HostObject companion = companion();
            if (companion == null) return false;
            return companion.set(key, value, companion);
        }
        Method method = bridge.choose(setters, new Object[]{value});
        if (method == null) return false;
        bridge.invoke(method, null, new Object[]{value});
        return true;
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        return false;
    }

    @Override
    public boolean delete(Object key) {
        return !hasOwnProperty(key);
    }

    @Override
    public List<Object> ownPropertyKeys() {
        List<Object> out = new ArrayList<>();
        out.addAll(info.staticFields.keySet());
        out.addAll(info.staticGetters.keySet());
        out.addAll(info.staticMethods.keySet());
        out.addAll(info.memberClasses.keySet());
        return out;
    }

    @Override
    public boolean setPrototypeOf(JSObject prototype) {
        return prototype == proto;
    }

    @Override
    public boolean preventExtensions() {
        return true;
    }

    @Override
    public boolean isExtensible() {
        return false;
    }

    @Override
    public Object call(Object thisArg, Object[] args) {
        throw JSException.typeError("Host class " + cls.getSimpleName() + " must be invoked with 'new'");
    }

    @Override
    public Object construct(Object[] args, JSObject newTarget) {
        if (cls.isArray()) return bridge.newArray(cls.getComponentType(), Values.arg(args, 0));
        if (cls.isInterface()) {
            Object argument = Values.arg(args, 0);
            if (!(argument instanceof JSObject implementation)) {
                throw JSException.typeError("Implementing " + cls.getSimpleName()
                        + " requires a function or object");
            }
            return bridge.toJS(bridge.implement(implementation, cls));
        }
        Constructor<?> constructor = bridge.choose(info.constructors, args);
        if (constructor == null) {
            throw JSException.typeError("No applicable constructor for " + cls.getSimpleName()
                    + " with arguments (" + joinTypes(args) + ")");
        }
        return bridge.construct(constructor, args);
    }

    private static String joinTypes(Object[] args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(Ops.typeOf(args[i]));
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "HostClass[" + cls.getName() + "]";
    }
}
