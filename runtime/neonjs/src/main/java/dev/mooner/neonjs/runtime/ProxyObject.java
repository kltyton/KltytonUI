/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Proxy exotic object (ECMA-262 10.5); derived from NeonJS and adapted to Java. */
public final class ProxyObject extends JSObject {
    public JSObject target;
    public JSObject handler;
    private JSObject attributesHandler;
    private ToIntFunction<Object> attributesLookup;

    public ProxyObject(JSObject target, JSObject handler) {
        super(null);
        this.target = target;
        this.handler = handler;
        special |= SPECIAL_ALL;
        JSObject checked = Objects.requireNonNull(target);
        if (checked.isCallable()) {
            special |= CALLABLE;
            if (checked.isConstructor()) special |= CONSTRUCTOR;
        }
    }

    @Override
    public String getClassName() {
        return "Object";
    }

    /** Java replacement for the Kotlin Pair returned by check(). */
    private record Checked(JSObject target, JSObject handler) {
    }

    private Checked check() {
        JSObject checkedHandler = handler;
        if (checkedHandler == null) {
            throw JSException.typeError("Cannot perform operation on a revoked proxy");
        }
        return new Checked(Objects.requireNonNull(target), checkedHandler);
    }

    /** Installs a native host adapter's value-free descriptor flags. Guest proxies use their traps. */
    public void setHostPropertyAttributesLookup(ToIntFunction<Object> lookup) {
        attributesHandler = handler;
        attributesLookup = Objects.requireNonNull(lookup);
    }

    @Override
    public int getOwnPropertyAttributes(Object key) {
        check();
        return attributesLookup != null && handler == attributesHandler
                ? attributesLookup.applyAsInt(key) : super.getOwnPropertyAttributes(key);
    }

    private static JSObject trap(JSObject handler, String name) {
        Object value = Ops.getMethod(handler, name);
        return value == Undefined.INSTANCE ? null : (JSObject) value;
    }

    @Override
    public JSObject getPrototypeOf() {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "getPrototypeOf");
        if (trapFunction == null) return target.getPrototypeOf();
        Object result = trapFunction.call(handler, new Object[]{target});
        if (!(result instanceof JSObject) && result != Null.INSTANCE) {
            throw JSException.typeError("'getPrototypeOf' on proxy: trap returned neither object nor null");
        }
        JSObject prototype = result instanceof JSObject object ? object : null;
        if (target.isExtensible()) return prototype;
        JSObject targetPrototype = target.getPrototypeOf();
        if (prototype != targetPrototype) {
            throw JSException.typeError("'getPrototypeOf' on proxy: proxy target is non-extensible "
                    + "but the trap did not return its actual prototype");
        }
        return prototype;
    }

    @Override
    public boolean setPrototypeOf(JSObject prototype) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "setPrototypeOf");
        if (trapFunction == null) return target.setPrototypeOf(prototype);
        boolean result = Ops.toBoolean(trapFunction.call(handler,
                new Object[]{target, prototype == null ? Null.INSTANCE : prototype}));
        if (!result) return false;
        if (target.isExtensible()) return true;
        if (target.getPrototypeOf() != prototype) {
            throw JSException.typeError("'setPrototypeOf' on proxy: trap returned truish for setting a new prototype "
                    + "on the non-extensible proxy target");
        }
        return true;
    }

    @Override
    public boolean isExtensible() {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "isExtensible");
        if (trapFunction == null) return target.isExtensible();
        boolean result = Ops.toBoolean(trapFunction.call(handler, new Object[]{target}));
        if (result != target.isExtensible()) {
            throw JSException.typeError("'isExtensible' on proxy: trap result does not reflect extensibility "
                    + "of proxy target");
        }
        return result;
    }

    @Override
    public boolean preventExtensions() {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "preventExtensions");
        if (trapFunction == null) return target.preventExtensions();
        boolean result = Ops.toBoolean(trapFunction.call(handler, new Object[]{target}));
        if (result && target.isExtensible()) {
            throw JSException.typeError("'preventExtensions' on proxy: trap returned truish but the proxy target "
                    + "is extensible");
        }
        return result;
    }

    @Override
    public PropertyDescriptor getOwnProperty(Object key) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "getOwnPropertyDescriptor");
        if (trapFunction == null) return target.getOwnProperty(key);
        Object result = trapFunction.call(handler, new Object[]{target, PK.toValue(key)});
        if (!(result instanceof JSObject) && result != Undefined.INSTANCE) {
            throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned neither object nor "
                    + "undefined for property '" + Ops.describeKey(key) + "'");
        }
        PropertyDescriptor targetDescriptor = target.getOwnProperty(key);
        if (result == Undefined.INSTANCE) {
            if (targetDescriptor == null) return null;
            if (!targetDescriptor.configurable) {
                throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned undefined for "
                        + "property '" + Ops.describeKey(key) + "' which is non-configurable in the proxy target");
            }
            if (!target.isExtensible()) {
                throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned undefined for "
                        + "property '" + Ops.describeKey(key) + "' which exists in the non-extensible proxy target");
            }
            return null;
        }
        boolean extensible = target.isExtensible();
        PropertyDescriptor descriptor = Ops.completePropertyDescriptor(Ops.toPropertyDescriptor(result));
        if (!Ops.isCompatiblePropertyDescriptor(extensible, descriptor, targetDescriptor)) {
            throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned descriptor for property '"
                    + Ops.describeKey(key) + "' that is incompatible with the existing property in the proxy target");
        }
        if (!descriptor.configurable) {
            if (targetDescriptor == null || targetDescriptor.configurable) {
                throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap reported non-configurability "
                        + "for property '" + Ops.describeKey(key) + "' which is either non-existent or configurable "
                        + "in the proxy target");
            }
            if (descriptor.getHasWritable() && !descriptor.writable && targetDescriptor.writable) {
                throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap reported non-configurable "
                        + "and writable for property '" + Ops.describeKey(key) + "' which is non-configurable, "
                        + "non-writable in the proxy target");
            }
        }
        return descriptor;
    }

    @Override
    public Object getOwnValue(Object key, Object receiver) {
        PropertyDescriptor descriptor = getOwnProperty(key);
        if (descriptor == null) return NotFound.INSTANCE;
        if (descriptor.isAccessor()) {
            Object getter = descriptor.getter;
            return getter instanceof JSObject function
                    ? function.call(receiver, Values.EMPTY_ARGS)
                    : Undefined.INSTANCE;
        }
        return descriptor.value;
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        return getOwnProperty(key) != null;
    }

    @Override
    public boolean defineOwnProperty(Object key, PropertyDescriptor descriptor) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "defineProperty");
        if (trapFunction == null) return target.defineOwnProperty(key, descriptor);
        Object descriptorObject = Ops.fromPropertyDescriptor(Agent.currentRealm(), descriptor);
        boolean result = Ops.toBoolean(trapFunction.call(handler,
                new Object[]{target, PK.toValue(key), descriptorObject}));
        if (!result) return false;
        PropertyDescriptor targetDescriptor = target.getOwnProperty(key);
        boolean extensible = target.isExtensible();
        boolean settingConfigFalse = descriptor.getHasConfigurable() && !descriptor.configurable;
        if (targetDescriptor == null) {
            if (!extensible) {
                throw JSException.typeError("'defineProperty' on proxy: trap returned truish for adding property '"
                        + Ops.describeKey(key) + "'  to the non-extensible proxy target");
            }
            if (settingConfigFalse) {
                throw JSException.typeError("'defineProperty' on proxy: trap returned truish for defining "
                        + "non-configurable property '" + Ops.describeKey(key) + "' which is either non-existent "
                        + "or configurable in the proxy target");
            }
        } else {
            if (!Ops.isCompatiblePropertyDescriptor(extensible, descriptor, targetDescriptor)) {
                throw JSException.typeError("'defineProperty' on proxy: trap returned truish for adding property '"
                        + Ops.describeKey(key) + "'  that is incompatible with the existing property in the proxy target");
            }
            if (settingConfigFalse && targetDescriptor.configurable) {
                throw JSException.typeError("'defineProperty' on proxy: trap returned truish for defining "
                        + "non-configurable property '" + Ops.describeKey(key) + "' which is either non-existent "
                        + "or configurable in the proxy target");
            }
            if (targetDescriptor.isData() && !targetDescriptor.configurable && targetDescriptor.writable) {
                if (descriptor.getHasWritable() && !descriptor.writable) {
                    throw JSException.typeError("'defineProperty' on proxy: trap returned truish for defining "
                            + "non-configurable property '" + Ops.describeKey(key) + "' which cannot be non-writable, "
                            + "unless there exists a corresponding non-configurable, non-writable own property "
                            + "of the target object.");
                }
            }
        }
        return true;
    }

    @Override
    public boolean hasProperty(Object key) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "has");
        if (trapFunction == null) return target.hasProperty(key);
        boolean result = Ops.toBoolean(trapFunction.call(handler, new Object[]{target, PK.toValue(key)}));
        if (!result) {
            PropertyDescriptor targetDescriptor = target.getOwnProperty(key);
            if (targetDescriptor != null) {
                if (!targetDescriptor.configurable) {
                    throw JSException.typeError("'has' on proxy: trap returned falsish for property '"
                            + Ops.describeKey(key) + "' which exists in the proxy target as non-configurable");
                }
                if (!target.isExtensible()) {
                    throw JSException.typeError("'has' on proxy: trap returned falsish for property '"
                            + Ops.describeKey(key) + "' but the proxy target is not extensible");
                }
            }
        }
        return result;
    }

    @Override
    public Object get(Object key, Object receiver) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "get");
        if (trapFunction == null) return target.get(key, receiver);
        Object value = trapFunction.call(handler, new Object[]{target, PK.toValue(key), receiver});
        PropertyDescriptor targetDescriptor = target.getOwnProperty(key);
        if (targetDescriptor != null && !targetDescriptor.configurable) {
            if (targetDescriptor.isData() && !targetDescriptor.writable
                    && !Ops.sameValue(value, targetDescriptor.value)) {
                throw JSException.typeError("'get' on proxy: property '" + Ops.describeKey(key) + "' is a read-only "
                        + "and non-configurable data property on the proxy target but the proxy did not return "
                        + "its actual value");
            }
            if (targetDescriptor.isAccessor() && targetDescriptor.getter == Undefined.INSTANCE
                    && value != Undefined.INSTANCE) {
                throw JSException.typeError("'get' on proxy: property '" + Ops.describeKey(key) + "' is a "
                        + "non-configurable accessor property on the proxy target and does not have a getter "
                        + "function, but the trap did not return 'undefined'");
            }
        }
        return value;
    }

    @Override
    public boolean set(Object key, Object value, Object receiver) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "set");
        if (trapFunction == null) return target.set(key, value, receiver);
        boolean result = Ops.toBoolean(trapFunction.call(handler,
                new Object[]{target, PK.toValue(key), value, receiver}));
        if (!result) return false;
        PropertyDescriptor targetDescriptor = target.getOwnProperty(key);
        if (targetDescriptor != null && !targetDescriptor.configurable) {
            if (targetDescriptor.isData() && !targetDescriptor.writable
                    && !Ops.sameValue(value, targetDescriptor.value)) {
                throw JSException.typeError("'set' on proxy: trap returned truish for property '"
                        + Ops.describeKey(key) + "' which exists in the proxy target as a non-configurable "
                        + "and non-writable data property with a different value");
            }
            if (targetDescriptor.isAccessor() && targetDescriptor.setter == Undefined.INSTANCE) {
                throw JSException.typeError("'set' on proxy: trap returned truish for property '"
                        + Ops.describeKey(key) + "' which exists in the proxy target as a non-configurable "
                        + "and non-writable accessor property without a setter");
            }
        }
        return true;
    }

    @Override
    public boolean delete(Object key) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "deleteProperty");
        if (trapFunction == null) return target.delete(key);
        boolean result = Ops.toBoolean(trapFunction.call(handler, new Object[]{target, PK.toValue(key)}));
        if (!result) return false;
        PropertyDescriptor targetDescriptor = target.getOwnProperty(key);
        if (targetDescriptor == null) return true;
        if (!targetDescriptor.configurable) {
            throw JSException.typeError("'deleteProperty' on proxy: trap returned truish for property '"
                    + Ops.describeKey(key) + "' which is non-configurable in the proxy target");
        }
        if (!target.isExtensible()) {
            throw JSException.typeError("'deleteProperty' on proxy: trap returned truish for property '"
                    + Ops.describeKey(key) + "' but the proxy target is non-extensible");
        }
        return true;
    }

    @Override
    public List<Object> ownPropertyKeys() {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "ownKeys");
        if (trapFunction == null) return target.ownPropertyKeys();
        Object result = trapFunction.call(handler, new Object[]{target});
        if (!(result instanceof JSObject array)) {
            throw JSException.typeError("CreateListFromArrayLike called on non-object");
        }
        Object[] raw = Ops.createListFromArrayLike(array, true);
        List<Object> keys = new ArrayList<>(raw.length);
        HashSet<Object> seen = new HashSet<>();
        for (Object value : raw) {
            Object key = value instanceof JSSymbol ? value : PK.fromString(value.toString());
            if (!seen.add(key)) {
                throw JSException.typeError("'ownKeys' on proxy: trap returned duplicate entries");
            }
            keys.add(key);
        }
        boolean extensible = target.isExtensible();
        List<Object> targetKeys = target.ownPropertyKeys();
        List<Object> configurable = new ArrayList<>();
        List<Object> nonConfigurable = new ArrayList<>();
        for (Object key : targetKeys) {
            int attributes = target.getOwnPropertyAttributes(key);
            if (attributes >= 0 && (attributes & Attr.CONFIGURABLE) == 0) nonConfigurable.add(key);
            else configurable.add(key);
        }
        if (extensible && nonConfigurable.isEmpty()) return keys;
        LinkedHashSet<Object> unchecked = new LinkedHashSet<>(keys);
        for (Object key : nonConfigurable) {
            if (!unchecked.remove(key)) {
                throw JSException.typeError("'ownKeys' on proxy: trap result did not include '"
                        + Ops.describeKey(key) + "'");
            }
        }
        if (extensible) return keys;
        for (Object key : configurable) {
            if (!unchecked.remove(key)) {
                throw JSException.typeError("'ownKeys' on proxy: trap result did not include '"
                        + Ops.describeKey(key) + "'");
            }
        }
        if (!unchecked.isEmpty()) {
            throw JSException.typeError("'ownKeys' on proxy: trap returned extra keys but proxy target "
                    + "is non-extensible");
        }
        return keys;
    }

    @Override
    public Object call(Object thisArg, Object[] arguments) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "apply");
        if (trapFunction == null) return target.call(thisArg, arguments);
        Realm realm = Agent.currentRealm();
        return trapFunction.call(handler, new Object[]{target, thisArg,
                JSArray.of(realm.getArrayPrototype(), arguments.clone())});
    }

    @Override
    public Object construct(Object[] arguments, JSObject newTarget) {
        Checked checked = check();
        JSObject target = checked.target();
        JSObject handler = checked.handler();
        JSObject trapFunction = trap(handler, "construct");
        if (trapFunction == null) return target.construct(arguments, newTarget);
        Realm realm = Agent.currentRealm();
        Object result = trapFunction.call(handler, new Object[]{target,
                JSArray.of(realm.getArrayPrototype(), arguments.clone()), newTarget});
        if (!(result instanceof JSObject)) {
            throw JSException.typeError("proxy [[Construct]] must return an object");
        }
        return result;
    }
}
