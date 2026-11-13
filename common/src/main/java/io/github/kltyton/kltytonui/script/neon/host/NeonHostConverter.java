package io.github.kltyton.kltytonui.script.neon.host;

import io.github.kltyton.kltytonui.script.host.HideFromScript;
import dev.mooner.neonjs.HostAccess;
import dev.mooner.neonjs.NeonValue;
import dev.mooner.neonjs.interop.ContextGate;
import dev.mooner.neonjs.interop.HostBridge;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import io.github.kltyton.kltytonui.script.neon.NeonDocumentRuntime;
import io.github.kltyton.kltytonui.script.neon.NeonEngine;
import io.github.kltyton.kltytonui.script.neon.NeonPageRuntime;
import java.lang.ref.WeakReference;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** One bridge preserves typed SDK conversions and live JavaScript views throughout the agent. */
public final class NeonHostConverter implements ContextGate {
    private final NeonEngine engine;
    private final HostBridge bridge;
    private final Map<CallbackKey, WeakReference<NeonCallback>> callbacks = new HashMap<>();
    private final Map<Long, WeakReference<NeonScriptValue>> objects = new HashMap<>();
    private final Map<Long, WeakReference<Object>> tokens = new HashMap<>();
    private final Map<InterfaceKey, WeakReference<Object>> interfaces = new HashMap<>();
    private final Map<NeonValue, WeakReference<NeonReferenceValue>> symbols = new WeakHashMap<>();

    public NeonHostConverter(NeonEngine engine, Realm anchor, HostAccess access) {
        this.engine = engine;
        bridge = new HostBridge(anchor, access, this);
    }

    public static HostAccess hostAccess() {
        return HostAccess.builder(HostAccess.Level.ALL).defaultDenyList(false).allowLookup(name -> true)
                .filterMembers(member -> !(member instanceof AnnotatedElement annotated
                        && annotated.isAnnotationPresent(HideFromScript.class))).build();
    }

    @Override public <R> R enter(Supplier<? extends R> block) {
        return engine.run(NeonPageRuntime.activeDocument(), context -> block.get());
    }
    @Override public <R> R enterCallback(Class<?> resultType, Supplier<? extends R> block) {
        return enter(block);
    }
    @Override public Object wrapValue(Object raw) { return fromGuest(engine.value(raw)); }
    @Override public Object unwrapValue(Object host) {
        if (host instanceof NeonScriptValue view) return engine.raw(view.toGuestValue());
        if (host instanceof NeonCallbackAdapter adapter) return engine.raw(adapter.function().toGuestValue());
        if (host instanceof NeonValue value) return engine.raw(value);
        if (host == NeonUndefined.INSTANCE) return Undefined.INSTANCE;
        return NotFound.INSTANCE;
    }
    @Override public Class<?> getValueClass() { return NeonScriptValue.class; }
    @Override public Object undefinedToHost() { return NeonUndefined.INSTANCE; }
    @Override public Object implementation(JSObject value, Class<?> type, Type declaredType,
                                           Supplier<?> factory) {
        NeonDocumentRuntime owner = NeonPageRuntime.activeDocument();
        if (owner == null) throw new IllegalStateException("JavaScript interface requires a document owner");
        long identity = engine.api().callMember("identity", engine.value(value)).asLong();
        InterfaceKey key = new InterfaceKey(owner, identity, type, declaredType);
        WeakReference<Object> reference = interfaces.get(key);
        Object result = reference == null ? null : reference.get();
        if (result == null) {
            result = factory.get();
            interfaces.put(key, new WeakReference<>(result));
        }
        return result;
    }
    @Override public Throwable rejectionToHost(Object reason) { return engine.context().rejectionToHost(reason); }

    @Override public Object normalizeGuest(Object raw) {
        if (!(raw instanceof JSObject)) return raw;
        NeonValue unwrapped = engine.api().callMember("unwrap", engine.value(raw));
        return unwrapped.isUndefined() ? raw : engine.raw(unwrapped);
    }

    @Override public JSObject wrapHostValue(JSObject raw, Object target) {
        NeonDocumentRuntime owner = NeonPageRuntime.activeDocument();
        if (owner == null || owner.bindings() == null) return raw;
        NeonValue cached = owner.bindings().cached(target);
        NeonValue wrapped = cached == null ? owner.bindings().wrap(target, engine.value(raw)) : cached;
        return (JSObject) engine.raw(wrapped);
    }

    @Override public ContextGate callbackGate() {
        NeonDocumentRuntime owner = NeonPageRuntime.activeDocument();
        if (owner == null) throw new IllegalStateException("JavaScript callback requires a document owner");
        return new ContextGate() {
            @Override public <R> R enter(Supplier<? extends R> block) {
                return owner.run(context -> block.get());
            }
            @Override public <R> R enterCallback(Class<?> resultType, Supplier<? extends R> block) {
                return owner.runCallback(resultType, context -> block.get());
            }
            @Override public Object wrapValue(Object value) { return NeonHostConverter.this.wrapValue(value); }
            @Override public Object unwrapValue(Object value) { return NeonHostConverter.this.unwrapValue(value); }
            @Override public Class<?> getValueClass() { return NeonHostConverter.this.getValueClass(); }
            @Override public Object undefinedToHost() { return NeonHostConverter.this.undefinedToHost(); }
            @Override public Object implementation(JSObject value, Class<?> type, Type declaredType,
                                                   Supplier<?> factory) {
                return NeonHostConverter.this.implementation(value, type, declaredType, factory);
            }
            @Override public Throwable rejectionToHost(Object value) { return NeonHostConverter.this.rejectionToHost(value); }
            @Override public Object normalizeGuest(Object value) { return NeonHostConverter.this.normalizeGuest(value); }
            @Override public JSObject wrapHostValue(JSObject value, Object target) { return NeonHostConverter.this.wrapHostValue(value, target); }
            @Override public ContextGate callbackGate() { return this; }
        };
    }

    public NeonValue toGuest(Object host) {
        if (host instanceof NeonValue value) return value;
        Object unwrapped = unwrapValue(host);
        return engine.value(unwrapped == NotFound.INSTANCE ? bridge.toJS(host) : unwrapped);
    }

    public Object fromGuest(NeonValue value) {
        if (value == null || value.isNull()) return null;
        if (value.isUndefined()) return NeonUndefined.INSTANCE;
        Object normalized = normalizeGuest(engine.raw(value));
        if (normalized != engine.raw(value)) return fromGuest(engine.value(normalized));
        if (value.isHostObject()) return value.asHostObject();
        if (value.isHostClass()) return bridge.toObject(engine.raw(value));
        if (value.isString()) return value.asString();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isBigInt()) return value.asBigInteger();
        if (value.isNumber()) {
            double number = value.asDouble();
            if (number == Math.rint(number) && !(number == 0 && Double.doubleToRawLongBits(number) < 0)) {
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int) number;
                if (Math.abs(number) <= 9007199254740991L) return (long) number;
            }
            return number;
        }
        NeonDocumentRuntime owner = NeonPageRuntime.activeDocument();
        if (owner == null) throw new IllegalStateException("JavaScript value conversion requires a document owner");
        if (value.isSymbol()) {
            WeakReference<NeonReferenceValue> reference = symbols.get(value);
            NeonReferenceValue result = reference == null ? null : reference.get();
            if (result == null) {
                result = new NeonReferenceValue(owner, value);
                symbols.put(value, new WeakReference<>(result));
            }
            return result;
        }
        long identity = engine.api().callMember("identity", value).asLong();
        if (value.isFunction()) {
            CallbackKey key = new CallbackKey(owner, identity);
            WeakReference<NeonCallback> reference = callbacks.get(key);
            NeonCallback callback = reference == null ? null : reference.get();
            if (callback == null) {
                callback = new NeonCallback(this, owner, value, token(identity));
                callbacks.put(key, new WeakReference<>(callback));
            }
            return callback;
        }
        WeakReference<NeonScriptValue> reference = objects.get(identity);
        NeonScriptValue result = reference == null ? null : reference.get();
        if (result == null) {
            result = engine.api().callMember("isArray", value).asBoolean() ? new NeonScriptArray(owner, value)
                    : engine.api().callMember("isOpaque", value).asBoolean() ? new NeonReferenceValue(owner, value)
                    : new NeonScriptObject(owner, value);
            objects.put(identity, new WeakReference<>(result));
        }
        return result;
    }

    public boolean isNativeCallback(Object value) {
        return value != null && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof HostBridge.JSImplementation implementation
                && implementation.getBridge() == bridge;
    }
    public NeonCallback nativeCallback(Object value) {
        if (!isNativeCallback(value)) return null;
        return engine.run(NeonPageRuntime.activeDocument(), context -> {
            HostBridge.JSImplementation implementation = (HostBridge.JSImplementation) Proxy.getInvocationHandler(value);
            for (Map.Entry<InterfaceKey, WeakReference<Object>> entry : interfaces.entrySet()) {
                if (entry.getValue().get() != value) continue;
                NeonDocumentRuntime owner = entry.getKey().owner();
                return engine.run(owner, runtime -> {
                    Object callback = fromGuest(engine.value(implementation.getTarget()));
                    return callback instanceof NeonCallback function ? function : null;
                });
            }
            throw new IllegalStateException("JavaScript interface has no registered document owner");
        });
    }

    public Object nativeIdentity(Object value) {
        if (!isNativeCallback(value)) return value;
        HostBridge.JSImplementation implementation = (HostBridge.JSImplementation) Proxy.getInvocationHandler(value);
        return engine.run(NeonPageRuntime.activeDocument(), context ->
                token(engine.api().callMember("identity", engine.value(implementation.getTarget())).asLong()));
    }

    public void installPackages() {
        engine.api().putMember("loadClass", engine.context().createFunction("loadJavaClass", arguments -> {
            try {
                Class<?> type = loadClass(arguments[0].asString());
                return toGuest(type);
            } catch (ClassNotFoundException missingClass) {
                return undefinedValue();
            }
        }));
    }

    public NeonValue undefinedValue() { return engine.value(Undefined.INSTANCE); }
    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return HostBridge.classForName(name, NeonHostConverter.class.getClassLoader());
    }
    NeonValue classReflection(Object target) {
        return target instanceof Class<?> ? engine.value(bridge.wrap(target)) : undefinedValue();
    }
    NeonValue invoke(NeonValue function, Object receiver, Object value, boolean argumentRequired) {
        return NeonPageRuntime.activeDocument().bindings().invoke(function, receiver, value, argumentRequired);
    }
    NeonValue invokeArgs(NeonValue function, Object receiver, Object[] values) {
        return NeonPageRuntime.activeDocument().bindings().invokeArgs(function, receiver, values);
    }
    public void prune() {
        callbacks.values().removeIf(reference -> reference.get() == null);
        objects.values().removeIf(reference -> reference.get() == null);
        tokens.values().removeIf(reference -> reference.get() == null);
        interfaces.values().removeIf(reference -> reference.get() == null);
        symbols.values().removeIf(reference -> reference.get() == null);
    }
    public void close() { callbacks.clear(); objects.clear(); tokens.clear(); interfaces.clear(); symbols.clear(); }
    private Object token(long identity) {
        WeakReference<Object> reference = tokens.get(identity);
        Object token = reference == null ? null : reference.get();
        if (token == null) { token = new Object(); tokens.put(identity, new WeakReference<>(token)); }
        return token;
    }
    private record CallbackKey(NeonDocumentRuntime owner, long identity) { }
    private record InterfaceKey(NeonDocumentRuntime owner, long identity, Class<?> type, Type declaredType) { }
}

