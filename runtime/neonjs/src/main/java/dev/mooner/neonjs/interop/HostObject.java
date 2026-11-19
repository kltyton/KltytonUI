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
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Iteration;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Exotic object exposing a host (Java/Kotlin) object's public members as properties: fields, methods, and bean-style
 * properties (getX/isX/setX, i.e. Kotlin properties). Java arrays and Lists are array-like; Maps expose entries
 * through get/set; Iterables are JS-iterable. Functions (lambdas, {@code @FunctionalInterface} and Kotlin function
 * type implementations) are callable. A signature key such as {@code append(java.lang.String)} names one overload.
 */
public class HostObject extends JSObject {
    public final HostBridge bridge;
    public final Object target;
    public final HostClassInfo info;
    private HashMap<String, HostMethodFunction> methodCache;

    public HostObject(HostBridge bridge, Object target, HostClassInfo info) {
        super(bridge.realm.getObjectPrototype());
        this.bridge = bridge;
        this.target = target;
        this.info = info;
        special |= SPECIAL_ALL;
        if (info.functionalMethod != null) special |= CALLABLE;
        extensible = false;
    }

    @Override
    public String getClassName() {
        return target.getClass().isArray()
                || (target instanceof List<?> && bridge.access.allowListAccess) ? "Array" : "Object";
    }

    private boolean isArrayLike() {
        return (target.getClass().isArray() && bridge.access.allowArrayAccess)
                || (target instanceof List<?> && bridge.access.allowListAccess);
    }

    private int length() {
        if (target instanceof List<?> list) return list.size();
        return Array.getLength(target);
    }

    private Object element(int index) {
        if (target instanceof List<?> list) return list.get(index);
        return Array.get(target, index);
    }

    private HostMethodFunction method(String name) {
        HashMap<String, HostMethodFunction> cache = methodCache;
        HostMethodFunction function = cache == null ? null : cache.get(name);
        if (function != null) return function;
        List<Method> overloads = info.instanceMethods.get(name);
        if (overloads == null) {
            Method single = info.overload(name, false);
            if (single == null) return null;
            overloads = List.of(single);
        }
        if (cache == null) {
            cache = new HashMap<>();
            methodCache = cache;
        }
        int open = name.indexOf('(');
        String display = open < 0 ? name : name.substring(0, open);
        function = new HostMethodFunction(bridge, display, overloads, target);
        cache.put(name, function);
        return function;
    }

    private boolean isMap() {
        return target instanceof Map<?, ?> && bridge.access.allowMapAccess;
    }

    private boolean isIterable() {
        return ((target instanceof Iterable<?> || target instanceof Iterator<?>)
                && bridge.access.allowIterableAccess) || isArrayLike();
    }

    /** True if {@code name} is a Java member (members take precedence over Map entries). */
    private boolean isMember(String name) {
        return info.instanceFields.containsKey(name) || info.instanceMethods.containsKey(name)
                || info.instanceGetters.containsKey(name);
    }

    /** Own member value or NotFound. */
    private Object member(Object key) {
        if (key instanceof Integer index) {
            if (isArrayLike() && index < length()) return bridge.toJS(element(index));
            if (isMap()) return mapEntry(key.toString());
            return NotFound.INSTANCE;
        }
        if (key instanceof JSSymbol symbol) {
            if (symbol == JSSymbol.iterator && isIterable()) return iteratorFunction();
            return NotFound.INSTANCE;
        }
        String name = (String) key;
        if (name.equals("length") && isArrayLike()) return (double) length();
        Field field = info.instanceFields.get(name);
        if (field != null) return bridge.toJS(read(field));
        HostMethodFunction function = method(name);
        if (function != null) return function;
        Method getter = info.instanceGetters.get(name);
        if (getter != null) return bridge.invoke(getter, target, Values.EMPTY_ARGS);
        if (isMap()) return mapEntry(name);
        return NotFound.INSTANCE;
    }

    /** Java Map entries are visible as properties when the key is not a Java member name. */
    private Object mapEntry(String name) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) target;
        Object value;
        try {
            if (!map.containsKey(name)) return NotFound.INSTANCE;
            value = map.get(name);
        } catch (RuntimeException error) {
            throw bridge.hostError(error);
        }
        return bridge.toJS(value);
    }

    private Object read(Field field) {
        return info.fieldAccess(field).read(target);
    }

    private JSObject iteratorFunction() {
        return new NativeFunction(bridge.realm, "[Symbol.iterator]", 0, (function, thisArg, args, newTarget) -> {
            Iterator<Object> iterator;
            if (target instanceof Iterable<?> iterable) {
                @SuppressWarnings("unchecked")
                Iterator<Object> source = (Iterator<Object>) iterable.iterator();
                iterator = source;
            } else if (target instanceof Iterator<?> source) {
                @SuppressWarnings("unchecked")
                Iterator<Object> cast = (Iterator<Object>) source;
                iterator = cast;
            } else {
                // live index-based view over arrays / lists
                iterator = new Iterator<>() {
                    private int index;

                    @Override
                    public boolean hasNext() {
                        return index < length();
                    }

                    @Override
                    public Object next() {
                        return element(index++);
                    }
                };
            }
            JSObject object = new JSObject(function.realm.getIteratorPrototype());
            object.defineOwn("next", new NativeFunction(function.realm, "next", 0,
                    (nextFunction, nextThis, nextArgs, nextTarget) -> {
                        if (iterator.hasNext()) {
                            return Iteration.createIterResult(nextFunction.realm,
                                    bridge.toJS(iterator.next()), false);
                        }
                        return Iteration.createIterResult(nextFunction.realm, Undefined.INSTANCE, true);
                    }), Attr.WC);
            return object;
        });
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

    /**
     * Attributes follow JS conventions: elements and Map entries are enumerable; methods and {@code length} are not;
     * fields and bean properties are enumerable except on collections (whose enumerable keys are just their entries).
     */
    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        Object value = member(key);
        if (value == NotFound.INSTANCE) return null;
        boolean collection = isMap() || isArrayLike();
        int attributes;
        if (key instanceof Integer) {
            attributes = Attr.WRITABLE | Attr.ENUMERABLE;
        } else if (key instanceof String name) {
            Field field = info.instanceFields.get(name);
            if (name.equals("length") && isArrayLike()) {
                attributes = 0;
            } else if (field != null) {
                attributes = (Modifier.isFinal(field.getModifiers()) ? 0 : Attr.WRITABLE)
                        | (collection ? 0 : Attr.ENUMERABLE);
            } else if (info.instanceMethods.containsKey(name) || info.overload(name, false) != null) {
                attributes = 0;
            } else if (info.instanceGetters.containsKey(name)) {
                attributes = (info.instanceSetters.containsKey(name) ? Attr.WRITABLE : 0)
                        | (collection ? 0 : Attr.ENUMERABLE);
            } else {
                attributes = Attr.WRITABLE | Attr.ENUMERABLE; // Map entry
            }
        } else {
            attributes = 0;
        }
        return PropertyDescriptor.data(value, attributes);
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        return getOwnPropertyAttributes(key) >= 0;
    }

    @Override
    public int getOwnPropertyAttributes(Object key) {
        if (key instanceof Integer index) {
            if (isArrayLike()) return index >= 0 && index < length() ? Attr.WRITABLE | Attr.ENUMERABLE : -1;
            return isMap() && mapContains(key.toString()) ? Attr.WRITABLE | Attr.ENUMERABLE : -1;
        }
        if (key instanceof JSSymbol symbol) return symbol == JSSymbol.iterator && isIterable() ? 0 : -1;
        if (!(key instanceof String name)) return -1;
        if (name.equals("length") && isArrayLike()) return 0;
        boolean collection = isMap() || isArrayLike();
        Field field = info.instanceFields.get(name);
        if (field != null) return (Modifier.isFinal(field.getModifiers()) ? 0 : Attr.WRITABLE)
                | (collection ? 0 : Attr.ENUMERABLE);
        if (info.instanceMethods.containsKey(name) || info.overload(name, false) != null) return 0;
        if (info.instanceGetters.containsKey(name)) return (info.instanceSetters.containsKey(name) ? Attr.WRITABLE : 0)
                | (collection ? 0 : Attr.ENUMERABLE);
        return isMap() && mapContains(name) ? Attr.WRITABLE | Attr.ENUMERABLE : -1;
    }

    private boolean mapContains(String name) {
        try {
            return ((Map<?, ?>) target).containsKey(name);
        } catch (RuntimeException error) {
            throw bridge.hostError(error);
        }
    }

    @Override
    public boolean hasProperty(Object key) {
        return hasOwnProperty(key) || (proto != null && proto.hasProperty(key));
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        if (receiver != this) return false;
        if (key instanceof Integer index && isArrayLike()) {
            if (index >= length()) return false;
            try {
                if (target instanceof List<?> source) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) source;
                    list.set(index, bridge.toObject(value));
                } else {
                    Array.set(target, index, bridge.toHost(value, target.getClass().getComponentType()));
                }
                return true;
            } catch (UnsupportedOperationException unsupported) {
                return false;
            } catch (IllegalArgumentException error) {
                throw JSException.typeError("Cannot store " + Ops.describe(value) + " in a "
                        + target.getClass().getComponentType().getName() + " array: " + error.getMessage());
            }
        }
        if (key instanceof Integer && isMap()) return mapPut(key.toString(), value);
        if (!(key instanceof String name)) return false;
        if (isMap() && !isMember(name)) return mapPut(name, value);
        Field field = info.instanceFields.get(name);
        if (field != null) {
            if (Modifier.isFinal(field.getModifiers())) return false;
            try {
                info.fieldAccess(field).write(target, bridge.toField(value, field));
            } catch (IllegalArgumentException error) {
                throw JSException.typeError("Cannot set field " + key + ": " + error.getMessage());
            }
            return true;
        }
        List<Method> setters = info.instanceSetters.get(name);
        if (setters != null) {
            Method method = bridge.choose(setters, new Object[]{value});
            if (method == null) {
                throw JSException.typeError("Cannot convert value for property " + key);
            }
            bridge.invoke(method, target, new Object[]{value});
            return true;
        }
        return false;
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        if (descriptor.isAccessor()) return false;
        if (!descriptor.getHasValue()) return hasOwnProperty(key);
        return set(key, descriptor.value, this);
    }

    private boolean mapPut(String name, Object value) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) target;
        try {
            map.put(name, bridge.toObject(value));
            return true;
        } catch (UnsupportedOperationException unsupported) {
            return false;
        } catch (RuntimeException error) {
            throw bridge.hostError(error);
        }
    }

    @Override
    public boolean delete(Object key) {
        if (isMap() && ((key instanceof String name && !isMember(name)) || key instanceof Integer)) {
            String name = key.toString();
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) target;
            if (!map.containsKey(name)) return true;
            try {
                map.remove(name);
                return true;
            } catch (UnsupportedOperationException unsupported) {
                return false;
            }
        }
        return !hasOwnProperty(key);
    }

    @Override
    public List<Object> ownPropertyKeys() {
        List<Object> out = new ArrayList<>();
        if (isArrayLike()) {
            for (int i = 0; i < length(); i++) out.add(i);
            out.add("length");
        }
        if (isMap()) {
            int count = 0;
            for (Object key : ((Map<?, ?>) target).keySet()) {
                if (key instanceof String name && !isMember(name)) out.add(PK.fromString(name));
                if ((++count & 1023) == 0) bridge.realm.agent.checkInterrupt();
            }
        }
        out.addAll(info.instanceFields.keySet());
        out.addAll(info.instanceGetters.keySet());
        out.addAll(info.instanceMethods.keySet());
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
        Method functional = info.functionalMethod;
        if (functional == null) throw JSException.typeError("Host object is not callable");
        List<Method> all = info.callOverloads;
        if (all == null) {
            List<Method> methods = bridge.classInfo(functional.getDeclaringClass())
                    .instanceMethods.get(functional.getName());
            if (methods != null) {
                List<Method> matching = new ArrayList<>();
                for (Method method : methods) {
                    if (method.getParameterCount() == functional.getParameterCount()) matching.add(method);
                }
                all = matching.isEmpty() ? null : matching;
            }
            if (all == null) all = List.of(functional);
            info.callOverloads = all;
        }
        Method chosen = bridge.choose(all, args);
        if (chosen == null) chosen = functional;
        if (chosen.getParameterCount() != args.length && !chosen.isVarArgs()) {
            Object[] padded = new Object[chosen.getParameterCount()];
            for (int i = 0; i < padded.length; i++) padded[i] = i < args.length ? args[i] : Undefined.INSTANCE;
            return bridge.invoke(chosen, target, padded);
        }
        return bridge.invoke(chosen, target, args);
    }

    @Override
    public String toString() {
        return "HostObject[" + target.getClass().getName() + "]";
    }
}
