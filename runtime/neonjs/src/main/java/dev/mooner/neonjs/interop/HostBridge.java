/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.interop;

import dev.mooner.neonjs.HostAccess;
import dev.mooner.neonjs.builtins.DateTime;
import dev.mooner.neonjs.builtins.JSDate;
import dev.mooner.neonjs.builtins.temporal.JSTemporalInstant;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Rope;
import dev.mooner.neonjs.runtime.TerminationException;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.HostException;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Promises;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.MalformedParameterizedTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Converts values between JS and the host, selects overloads and creates host wrappers. One bridge per context.
 */
public final class HostBridge {
    public static final int IMPOSSIBLE = Integer.MAX_VALUE;
    /** Largest Java array created from JS (elements); larger requests are a RangeError. */
    public static final int MAX_ARRAY_LENGTH = 1 << 26;
    /** Number.MAX_SAFE_INTEGER: longs up to this magnitude are exact as numbers. */
    public static final long MAX_SAFE_LONG = 9007199254740991L;
    /** Combinations of argument kinds an {@link Overloads} remembers. */
    private static final int MEMO_SIZE = 8;

    public final Realm realm;
    public final HostAccess access;
    public final ContextGate gate;

    private final WrapperCache wrappers = new WrapperCache();
    private final HashMap<Class<?>, HostClassObject> classObjects = new HashMap<>();
    private final HashMap<Class<?>, HostClassInfo> adapterInfos = new HashMap<>();

    private final IdentityHashMap<Executable, Sig> sigs = new IdentityHashMap<>();
    private final HashMap<Method, Sig> proxySigs = new HashMap<>();
    private final IdentityHashMap<List<? extends Executable>, Overloads> overloadsByList = new IdentityHashMap<>();

    /** Widening primitive conversions (JLS 5.1.2): the types each primitive type converts to. */
    private static final Map<Class<?>, java.util.Set<Class<?>>> PRIMITIVE_WIDENING = primitiveWidening();

    private static Map<Class<?>, java.util.Set<Class<?>>> primitiveWidening() {
        HashMap<Class<?>, java.util.Set<Class<?>>> widening = new HashMap<>();
        widening.put(Byte.TYPE, widening(Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE));
        widening.put(Short.TYPE, widening(Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE));
        widening.put(Character.TYPE, widening(Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE));
        widening.put(Integer.TYPE, widening(Long.TYPE, Float.TYPE, Double.TYPE));
        widening.put(Long.TYPE, widening(Float.TYPE, Double.TYPE));
        widening.put(Float.TYPE, widening(Double.TYPE));
        return widening;
    }

    private static java.util.Set<Class<?>> widening(Class<?>... types) {
        return java.util.Set.of(types);
    }

    public HostBridge(Realm realm, HostAccess access, ContextGate gate) {
        this.realm = realm;
        this.access = access;
        this.gate = gate;
    }

    /** The JS object of host class {@code c}: one per class in a context, as a JS constructor is one object. */
    public HostClassObject classObject(Class<?> cls) {
        HostClassObject object = classObjects.get(cls);
        if (object != null) return object;
        object = new HostClassObject(this, cls);
        classObjects.put(cls, object);
        return object;
    }

    public HostClassInfo classInfo(Class<?> cls) {
        if (Adapters.isAdapter(cls)) {
            HostClassInfo info = adapterInfos.get(cls);
            if (info != null) return info;
            info = HostClassInfo.uncached(cls, access);
            adapterInfos.put(cls, info);
            return info;
        }
        return HostClassInfo.of(cls, access);
    }

    /**
     * What calls need of a method or constructor, computed once per context: reflection copies the parameter types on
     * every call, and ART parses generic signatures again each time.
     */
    static final class Sig {
        public final Class<?>[] params;
        public final boolean varArgs;
        public final Class<?> returnType;
        public final int arrays;
        private final Executable executable;
        private Type[] generics;
        private Type genericReturn;
        private String names;

        Sig(Executable executable) {
            this.executable = executable;
            params = executable.getParameterTypes();
            varArgs = executable.isVarArgs();
            returnType = executable instanceof Method method ? method.getReturnType() : Void.TYPE;
            int count = 0;
            for (Class<?> parameter : params) if (parameter.isArray()) count++;
            arrays = count;
        }

        /** The generic type of parameter {@code i} where conversions use it (element types of collections and futures). */
        Type generic(int index) {
            if (!needsGeneric(params[index])) return null;
            Type[] resolved = generics;
            if (resolved == null) {
                try {
                    resolved = executable.getGenericParameterTypes();
                } catch (TypeNotPresentException | MalformedParameterizedTypeException | LinkageError missing) {
                    resolved = null;
                }
                if (resolved == null) {
                    resolved = new Type[params.length];
                    System.arraycopy(params, 0, resolved, 0, params.length);
                }
                generics = resolved;
            }
            // a constructor of an inner class may leave the outer instance out of its generic signature
            return resolved.length == params.length ? resolved[index] : null;
        }

        Type genericReturn() {
            if (!(executable instanceof Method method)) return returnType;
            Type cached = genericReturn;
            if (cached != null) return cached;
            Type resolved;
            try {
                resolved = method.getGenericReturnType();
            } catch (TypeNotPresentException | MalformedParameterizedTypeException | LinkageError missing) {
                resolved = returnType;
            }
            genericReturn = resolved;
            return resolved;
        }

        String names() {
            String cached = names;
            if (cached != null) return cached;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) builder.append(',');
                builder.append(params[i].getName());
            }
            cached = builder.toString();
            names = cached;
            return cached;
        }

        boolean comesBefore(Sig other) {
            if (arrays != other.arrays) return arrays < other.arrays;
            return names().compareTo(other.names()) < 0;
        }
    }

    Sig sig(Executable executable) {
        Sig value = sigs.get(executable);
        if (value != null) return value;
        value = new Sig(executable);
        sigs.put(executable, value);
        return value;
    }

    Sig[] sigs(List<? extends Executable> candidates) {
        Sig[] result = new Sig[candidates.size()];
        for (int i = 0; i < result.length; i++) result[i] = sig(candidates.get(i));
        return result;
    }

    /**
     * The overloads of a method (or the constructors of a class) as calls choose among them: their Sigs, and the
     * choices made so far, by the kinds of the arguments.
     */
    static final class Overloads {
        public final Sig[] sigs;
        public final boolean memoizable;
        public final long[] keys = new long[MEMO_SIZE];
        public final int[] choices = new int[MEMO_SIZE];
        public int size;

        Overloads(Sig[] sigs) {
            this.sigs = sigs;
            boolean memo = sigs.length > 1;
            if (memo) {
                outer:
                for (Sig sig : sigs) {
                    for (Class<?> parameter : sig.params) {
                        if (parameter.isEnum() || (parameter.isArray() && parameter.getComponentType().isEnum())) {
                            memo = false;
                            break outer;
                        }
                    }
                }
            }
            memoizable = memo;
        }
    }

    Overloads overloads(List<? extends Executable> candidates) {
        if (candidates.size() == 1) return new Overloads(new Sig[]{sig(candidates.get(0))});
        Overloads cached = overloadsByList.get(candidates);
        if (cached != null) return cached;
        Overloads created = new Overloads(sigs(candidates));
        overloadsByList.put(candidates, created);
        return created;
    }

    // ------------------------------------------------------------------ host -> JS

    public Object toJS(Object value) {
        Object converted = toJSRaw(value);
        if (converted instanceof HostObject object) {
            return gate.wrapHostValue(object, object.target);
        }
        if (converted instanceof HostClassObject object) {
            return gate.wrapHostValue(object, object.cls);
        }
        return converted;
    }

    private Object toJSRaw(Object value) {
        if (value == null) return Null.INSTANCE;
        if (value instanceof JSObject) return value;
        if (value instanceof String) return value;
        if (value instanceof Boolean) return value;
        if (value instanceof Double) return value;
        if (value instanceof Integer integer) return (double) integer;
        // a long is a number while the number is exact (|v| <= 2^53 - 1), and a BigInt beyond that
        if (value instanceof Long longValue) {
            long v = longValue;
            return v >= -MAX_SAFE_LONG && v <= MAX_SAFE_LONG ? (double) v : BigInteger.valueOf(v);
        }
        if (value instanceof Float floatValue) return (double) floatValue;
        if (value instanceof Short shortValue) return (double) shortValue;
        if (value instanceof Byte byteValue) return (double) byteValue;
        if (value instanceof Character character) return character.toString();
        // the engine's own strings; any other CharSequence is mutable or carries more than its text, so it stays a
        // host object rather than a copy of the text
        if (value instanceof Rope) return value;
        if (value instanceof BigInteger) return value;
        if (value instanceof BigDecimal decimal) return decimal.doubleValue();
        if (value == Undefined.INSTANCE) return Undefined.INSTANCE;
        if (value == Null.INSTANCE) return Null.INSTANCE;
        if (value instanceof JSSymbol) return value;
        if (value.getClass().getName().equals("kotlin.Unit")) return Undefined.INSTANCE;
        if (value instanceof Class<?> cls) {
            return access.isClassAccessible(cls) ? classObject(cls) : opaque(value);
        }
        if (value instanceof CompletionStage<?> stage) return promiseOf(stage);
        Object unwrapped = gate.unwrapValue(value);
        if (unwrapped != NotFound.INSTANCE) return unwrapped;
        JSObject implemented = implementedBy(value);
        if (implemented != null) return implemented;
        JSObject viewed = viewedBy(value);
        if (viewed != null) return viewed;
        return wrap(value);
    }

    /** The JS array or object behind {@code v} if {@code v} is a {@code List} or {@code Map} view this bridge made, else null. */
    private JSObject viewedBy(Object value) {
        if (value instanceof JSListView list) return list.bridge == this ? list.arr : null;
        if (value instanceof JSMapView map) return map.bridge == this ? map.obj : null;
        return null;
    }

    /** The JS object behind {@code v} if {@code v} is an interface implementation this bridge made, else null. */
    private JSObject implementedBy(Object value) {
        if (!Proxy.isProxyClass(value.getClass())) return null;
        InvocationHandler handler = Proxy.getInvocationHandler(value);
        if (handler instanceof JSImplementation implementation && implementation.bridge == this) {
            return implementation.target;
        }
        return null;
    }

    /**
     * A promise settled with the outcome of {@code stage}. The completion (on whatever thread completes the stage) only
     * posts an external job; the promise is settled by that job on the context's thread.
     */
    private JSPromise promiseOf(CompletionStage<?> stage) {
        Agent agent = realm.agent;
        JSPromise promise = Promises.newPromise(realm);
        Agent.ExternalSource source = new Agent.ExternalSource() {
            @Override
            public void cancel() {
            }
        };
        if (!agent.addExternalSource(source)) return promise;
        stage.whenComplete((value, error) -> agent.postExternalJob(() -> {
            agent.removeExternalSource(source);
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                Promises.rejectPromise(realm, promise,
                        agent.hostExceptionToJS(new HostException(cause), realm));
            } else {
                Promises.resolvePromise(realm, promise, toJS(value));
            }
        }));
        return promise;
    }

    /**
     * A future completed when the JS value {@code v} settles (a promise or thenable; other values complete it at once),
     * its value converted to {@code elementType}. Completion happens in a job on the context's thread.
     */
    private CompletableFuture<Object> futureOf(Object value, Type elementType) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        java.util.function.Consumer<Object> complete = result -> {
            try {
                future.complete(toHost(result, HostTypeBindings.raw(elementType), elementType));
            } catch (JSException error) {
                future.completeExceptionally(gate.rejectionToHost(error.value));
            }
        };
        JSPromise promise;
        if (value instanceof JSPromise existing) {
            promise = existing;
        } else if (value instanceof JSObject object && Ops.isCallable(object.get("then", value))) {
            promise = (JSPromise) Promises.promiseResolve(realm, realm.promiseConstructor, value);
        } else {
            complete.accept(value);
            return future;
        }
        Promises.thenHost(realm, promise,
                result -> {
                    complete.accept(result);
                    return Undefined.INSTANCE;
                },
                reason -> {
                    future.completeExceptionally(gate.rejectionToHost(reason));
                    return Undefined.INSTANCE;
                });
        return future;
    }

    private HostObject opaque(Object value) {
        return new HostObject(this, value, HostClassInfo.of(Object.class, HostAccess.NONE));
    }

    /** Wraps a host object: the same wrapper for the same object while JS holds the wrapper. */
    public HostObject wrap(Object value) {
        HostObject cached = wrappers.get(value);
        if (cached != null) return cached;
        HostClassInfo info = classInfo(value.getClass());
        HostObject wrapper;
        if (info.instancesVisible || info.functionalMethod != null || value.getClass().isArray()
                || value instanceof List<?> || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            wrapper = new HostObject(this, value, info);
        } else {
            wrapper = opaque(value);
        }
        wrappers.set(value, wrapper);
        return wrapper;
    }

    /**
     * Host objects to their wrappers, by identity and weakly. Never by equality: an equal object (another list with
     * the same elements) is another object, and the host's equals and hashCode may be slow, recursive or throw.
     */
    private static final class WrapperCache {
        private static final class Key extends java.lang.ref.WeakReference<Object> {
            private final int hash;

            Key(Object object, java.lang.ref.ReferenceQueue<Object> queue) {
                super(object, queue);
                hash = System.identityHashCode(object);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                if (other == this) return true;
                if (!(other instanceof Key key)) return false;
                Object value = get();
                return value != null && value == key.get();
            }
        }

        private static final class Probe {
            private final Object object;

            Probe(Object object) {
                this.object = object;
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(object);
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof Key key && key.get() == object;
            }
        }

        private final java.lang.ref.ReferenceQueue<Object> queue = new java.lang.ref.ReferenceQueue<>();
        private final HashMap<Object, java.lang.ref.WeakReference<HostObject>> map = new HashMap<>();

        HostObject get(Object object) {
            java.lang.ref.WeakReference<HostObject> reference = map.get(new Probe(object));
            return reference == null ? null : reference.get();
        }

        void set(Object object, HostObject wrapper) {
            while (true) {
                Object expired = queue.poll();
                if (expired == null) break;
                map.remove(expired);
            }
            map.put(new Key(object, queue), new java.lang.ref.WeakReference<>(wrapper));
        }
    }

    // ------------------------------------------------------------------ JS -> host

    /** Cost of converting JS value {@code v} to {@code t}; lower is better, IMPOSSIBLE if not convertible. */
    public int cost(Object value, Class<?> t) {
        Object v = gate.normalizeGuest(value);
        if (t == gate.getValueClass()) return 1;
        if (!(v instanceof HostObject) && isFutureType(t)) {
            if (v == Undefined.INSTANCE || v == Null.INSTANCE || v == null) return 1;
            if (v instanceof JSObject) return 2;
            return 8;
        }
        if (v == Undefined.INSTANCE || v == Null.INSTANCE || v == null) {
            return t.isPrimitive() ? IMPOSSIBLE : 1;
        }
        if (v instanceof Boolean) {
            if (t == Boolean.TYPE || t == Boolean.class) return 0;
            if (t == Object.class) return 2;
            return IMPOSSIBLE;
        }
        if (v instanceof Double doubleValue) {
            double d = doubleValue;
            boolean integral = d == Math.rint(d) && !Double.isInfinite(d);
            if (t == Double.TYPE || t == Double.class) return integral ? 2 : 1;
            if (t == Float.TYPE || t == Float.class) return 3;
            if (t == Long.TYPE || t == Long.class) {
                return integral && Math.abs(d) <= 9007199254740991.0 ? 1 : IMPOSSIBLE;
            }
            if (t == Integer.TYPE || t == Integer.class) {
                return integral && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? 1 : IMPOSSIBLE;
            }
            if (t == Short.TYPE || t == Short.class) {
                return integral && d >= Short.MIN_VALUE && d <= Short.MAX_VALUE ? 2 : IMPOSSIBLE;
            }
            if (t == Byte.TYPE || t == Byte.class) {
                return integral && d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE ? 2 : IMPOSSIBLE;
            }
            if (t == Number.class || t == Object.class) return 3;
            if (t == BigInteger.class) return integral ? 4 : IMPOSSIBLE;
            if (t == BigDecimal.class) return 4;
            return IMPOSSIBLE;
        }
        if (v instanceof CharSequence text) {
            if (t == String.class || t == CharSequence.class) return 0;
            if (t == Character.TYPE || t == Character.class) return text.length() == 1 ? 1 : IMPOSSIBLE;
            if (t == Object.class) return 2;
            if (t.isPrimitive() || t.isArray()) return IMPOSSIBLE;
            if (t.isEnum()) {
                Object[] constants = t.getEnumConstants();
                if (constants != null) {
                    for (Object constant : constants) {
                        if (((Enum<?>) constant).name().equals(text.toString())) return 2;
                    }
                }
                return IMPOSSIBLE;
            }
            return IMPOSSIBLE;
        }
        if (v instanceof BigInteger integer) {
            if (t == BigInteger.class) return 0;
            if (t == Long.TYPE || t == Long.class) return integer.bitLength() < 64 ? 1 : IMPOSSIBLE;
            if (t == Number.class || t == Object.class) return 3;
            return IMPOSSIBLE;
        }
        if (v instanceof HostObject object) {
            Object target = object.target;
            if (t.isInstance(target)) return distance(target.getClass(), t);
            Class<?> boxed = boxed(t);
            if (boxed != null && boxed.isInstance(target)) return 1;
            // a StringBuilder where a String is expected: its text (after any overload taking the object itself)
            if (t == String.class && target instanceof CharSequence) return 8;
            return IMPOSSIBLE;
        }
        if (v instanceof HostClassObject) return t == Object.class ? 5 : IMPOSSIBLE;
        if (v instanceof JSObject object) {
            if ((object instanceof JSDate || object instanceof JSTemporalInstant) && isDateTimeType(t)) return 1;
            if (object.isCallable() && t.isInterface() && HostClassInfo.samOf(t) != null
                    && access.allowImplementations) return 1;
            if (Ops.isArray(object)) {
                if (t.isArray()) return 2;
                if (t == List.class || t == java.util.Collection.class || t == Iterable.class) return 2;
            }
            if (t == Map.class && access.allowMapAccess) return 3;
            if (t.isInterface() && access.allowImplementations && t != Map.class && t != List.class) return 6;
            if (t == Object.class) return 5;
            return IMPOSSIBLE;
        }
        if (v instanceof JSSymbol) return t == Object.class ? 5 : IMPOSSIBLE;
        return t.isInstance(v) ? 0 : IMPOSSIBLE;
    }

    private static Class<?> boxed(Class<?> t) {
        if (t == Integer.TYPE) return Integer.class;
        if (t == Long.TYPE) return Long.class;
        if (t == Double.TYPE) return Double.class;
        if (t == Boolean.TYPE) return Boolean.class;
        if (t == Character.TYPE) return Character.class;
        if (t == Float.TYPE) return Float.class;
        if (t == Short.TYPE) return Short.class;
        if (t == Byte.TYPE) return Byte.class;
        return null;
    }

    private static int distance(Class<?> from, Class<?> to) {
        if (from == to) return 0;
        int d = 0;
        Class<?> current = from;
        while (current != null) {
            if (current == to) return d;
            d++;
            current = current.getSuperclass();
        }
        return to.isInterface() ? 2 : 3;
    }

    /** Converts JS value {@code input} to host type {@code t} (generic type {@code gt} used for collections/functions). */
    public Object toHost(Object input, Class<?> t) {
        return toHost(input, t, null);
    }

    /** Converts JS value {@code input} to host type {@code t} (generic type {@code gt} used for collections/functions). */
    public Object toHost(Object input, Class<?> t, Type gt) {
        if (t == gate.getValueClass()) return gate.wrapValue(input);
        Object v = gate.normalizeGuest(input);
        if (v == Undefined.INSTANCE && t == Object.class) return gate.undefinedToHost();
        if (v == Undefined.INSTANCE || v == Null.INSTANCE || v == null) {
            if (t.isPrimitive()) {
                throw JSException.typeError("Cannot convert " + Ops.toDisplayString(v) + " to " + t.getName());
            }
            return null;
        }
        if (v instanceof HostObject object) {
            Object target = object.target;
            Class<?> boxed = boxed(t);
            if (t.isInstance(target) || (boxed != null && boxed.isInstance(target))) return target;
            if (t == String.class && target instanceof CharSequence) return target.toString();
        }
        if (v instanceof HostClassObject object && t == Object.class) return object.cls;
        if (t == Object.class) return toObject(v);
        if (t == String.class || t == CharSequence.class) {
            return v instanceof CharSequence text ? text.toString() : Ops.toString(v);
        }
        if (t == Boolean.TYPE || t == Boolean.class) return Ops.toBoolean(v);
        if (t == Double.TYPE || t == Double.class) return Ops.toNumber(v);
        if (t == Float.TYPE || t == Float.class) return (float) Ops.toNumber(v);
        if (t == Long.TYPE || t == Long.class) {
            if (!(v instanceof BigInteger integer)) return (long) Ops.toNumber(v);
            if (integer.bitLength() < 64) return integer.longValue();
            throw JSException.rangeError("BigInt " + integer + " does not fit in a long");
        }
        if (t == Integer.TYPE || t == Integer.class) return (int) Ops.toNumber(v);
        if (t == Short.TYPE || t == Short.class) return (short) (int) Ops.toNumber(v);
        if (t == Byte.TYPE || t == Byte.class) return (byte) (int) Ops.toNumber(v);
        if (t == Character.TYPE || t == Character.class) {
            if (v instanceof CharSequence text && text.length() == 1) return text.charAt(0);
            return (char) (int) Ops.toNumber(v);
        }
        if (t == Number.class) {
            Object object = toObject(v);
            if (object instanceof Number number) return number;
            return Ops.toNumber(v);
        }
        if (t == BigInteger.class) {
            return v instanceof BigInteger integer ? integer : Ops.bigIntFromDouble(Ops.toNumber(v));
        }
        if (t == BigDecimal.class) {
            return v instanceof BigInteger integer ? new BigDecimal(integer) : new BigDecimal(Ops.toNumber(v));
        }
        if (isFutureType(t)) return futureOf(v, elementType(gt, 0));
        if (v instanceof JSObject object) {
            Object dateTime = dateTimeOf(object, t);
            if (dateTime != null) return dateTime;
        }
        if (t.isEnum() && v instanceof CharSequence text) {
            String name = text.toString();
            Object[] constants = t.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (((Enum<?>) constant).name().equals(name)) return constant;
                }
            }
            throw JSException.typeError("No enum constant " + t.getName() + "." + name);
        }
        if (v instanceof JSObject object) {
            if (t.isArray() && Ops.isArray(object)) {
                Type componentGeneric = HostTypeBindings.component(gt, t.getComponentType());
                Class<?> component = HostTypeBindings.raw(componentGeneric);
                int length = (int) Ops.lengthOfArrayLike(object);
                Object array = Array.newInstance(component, length);
                for (int i = 0; i < length; i++) {
                    Array.set(array, i, toHost(object.get(i, object), component, componentGeneric));
                }
                return array;
            }
            if ((t == List.class || t == java.util.Collection.class || t == Iterable.class) && Ops.isArray(object)) {
                return new JSListView(this, object, elementType(gt, 0));
            }
            if (t == Map.class) return new JSMapView(this, object, elementType(gt, 1));
            if (t.isInterface() && access.allowImplementations) return implement(object, t, gt);
        }
        if (t.isInstance(v)) return v;
        throw JSException.typeError("Cannot convert " + Ops.describe(v) + " to " + t.getName());
    }

    /**
     * {@code value} converted for field {@code f}: a field takes what a parameter of its type would take (a TypeError
     * otherwise, rather than {@code 'abc'} becoming 0 or 1.9 becoming 1).
     */
    public Object toField(Object value, java.lang.reflect.Field field) {
        Class<?> t = field.getType();
        if (cost(value, t) == IMPOSSIBLE) {
            throw JSException.typeError("Cannot convert " + Ops.describe(value) + " to " + t.getName()
                    + " for field " + field.getName());
        }
        return toHost(value, t, needsGeneric(t) ? field.getGenericType() : null);
    }

    /** Date-time types a JS {@code Date} or {@code Temporal.Instant} converts to. */
    private static boolean isDateTimeType(Class<?> t) {
        return t == java.time.Instant.class || t == java.util.Date.class || t == ZonedDateTime.class
                || t == OffsetDateTime.class || t == LocalDateTime.class || t == LocalDate.class
                || t == LocalTime.class;
    }

    /**
     * A JS {@code Date} or {@code Temporal.Instant} as an instance of the date-time type {@code t} (local types in
     * the context's time zone); null when {@code v} is neither or {@code t} is not a date-time type.
     */
    private Object dateTimeOf(JSObject value, Class<?> t) {
        if (!isDateTimeType(t)) return null;
        java.time.Instant instant;
        if (value instanceof JSDate date) {
            if (Double.isNaN(date.timeValue)) {
                throw JSException.rangeError("Invalid Date cannot be converted to " + t.getSimpleName());
            }
            instant = java.time.Instant.ofEpochMilli((long) date.timeValue);
        } else if (value instanceof JSTemporalInstant temporal) {
            BigInteger[] quotient = temporal.epochNs.divideAndRemainder(BigInteger.valueOf(1_000_000_000L));
            instant = java.time.Instant.ofEpochSecond(quotient[0].longValue(), quotient[1].longValue());
        } else {
            return null;
        }
        ZoneId zone = currentZone();
        if (t == java.time.Instant.class) return instant;
        if (t == java.util.Date.class) return java.util.Date.from(instant);
        if (t == ZonedDateTime.class) return instant.atZone(zone);
        if (t == OffsetDateTime.class) return instant.atZone(zone).toOffsetDateTime();
        if (t == LocalDateTime.class) return LocalDateTime.ofInstant(instant, zone);
        if (t == LocalDate.class) return instant.atZone(zone).toLocalDate();
        return instant.atZone(zone).toLocalTime();
    }

    private static ZoneId currentZone() {
        return DateTime.zone();
    }

    private static boolean isFutureType(Class<?> t) {
        return t == CompletableFuture.class || t == CompletionStage.class || t == Future.class;
    }

    private static Type elementType(Type generic, int index) {
        return HostTypeBindings.element(generic, index);
    }

    /** Conversion for Object-typed targets: JS primitives become boxed Java values, objects become NeonValues. */
    public Object toObject(Object value) {
        Object v = gate.normalizeGuest(value);
        if (v == Undefined.INSTANCE) return gate.undefinedToHost();
        if (v == Null.INSTANCE || v == null) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Double doubleValue) {
            double d = doubleValue;
            if (d == Math.rint(d) && !Double.isInfinite(d) && !(d == 0.0 && 1.0 / d < 0)) {
                if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return (int) d;
                if (Math.abs(d) <= 9007199254740991.0) return (long) d;
                return doubleValue;
            }
            return doubleValue;
        }
        if (v instanceof CharSequence text) return text.toString();
        // a BigInt that fits in 64 bits is a Long (the type a long becomes on the way back), others stay BigIntegers
        if (v instanceof BigInteger integer) {
            if (integer.bitLength() < 64) return integer.longValue();
            return integer;
        }
        if (v instanceof HostObject object) return object.target;
        if (v instanceof HostClassObject object) return object.cls;
        return gate.wrapValue(v);
    }

    /** Creates a host implementation of interface {@code iface} backed by a JS function or object. */
    public Object implement(JSObject value, Class<?> iface) {
        return implement(value, iface, null);
    }

    public Object implement(JSObject value, Class<?> iface, Type gt) {
        return gate.implementation(value, iface, gt, () -> Proxy.newProxyInstance(
                iface.getClassLoader() != null ? iface.getClassLoader() : getClass().getClassLoader(),
                new Class<?>[]{iface},
                new JSImplementation(value, iface, gt)));
    }

    /**
     * The handler of the proxies {@link #implement} makes: calls run {@code target}, the JS function or object. Handed
     * back to this context, such a proxy is {@code target} again (see {@link #toJS}), so a JS function stays callable
     * and keeps its identity through host code whatever interface it was given as.
     */
    public final class JSImplementation implements InvocationHandler {
        public final JSObject target;
        public final HostBridge bridge = HostBridge.this;
        private final Class<?> iface;
        private final HostTypeBindings types;
        private final HashMap<Method, Type> returns = new HashMap<>();
        private final ContextGate callbackGate = gate.callbackGate();

        JSImplementation(JSObject target, Class<?> iface, Type gt) {
            this.target = target;
            this.iface = iface;
            types = new HostTypeBindings(gt != null ? gt : iface);
        }

        public JSObject getTarget() {
            return target;
        }

        public HostBridge getBridge() {
            return bridge;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString":
                        return "JSProxy[" + iface.getSimpleName() + "]";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    default:
                        return null;
                }
            }
            if (method.isDefault() && target.isCallable()) return invokeDefault(proxy, method, args);
            return callbackGate.enterCallback(method.getReturnType(), () -> {
                Object[] jsArgs = new Object[args == null ? 0 : args.length];
                if (args != null) {
                    for (int i = 0; i < args.length; i++) jsArgs[i] = toJS(args[i]);
                }
                Object r;
                if (target.isCallable()) {
                    r = target.call(Undefined.INSTANCE, jsArgs);
                } else {
                    Object function = target.get(method.getName(), target);
                    if (!Ops.isCallable(function)) {
                        if (method.isDefault()) {
                            return invokeDefault(proxy, method, args);
                        }
                        throw JSException.typeError(method.getName() + " is not implemented");
                    }
                    r = ((JSObject) function).call(target, jsArgs);
                }
                Sig sig = proxySigs.get(method);
                if (sig == null) {
                    sig = new Sig(method);
                    proxySigs.put(method, sig);
                }
                if (sig.returnType == Void.TYPE) return null;
                Type returnType = returns.get(method);
                if (returnType == null) {
                    Type generic = sig.genericReturn();
                    returnType = types.resolve(generic != null ? generic : sig.returnType);
                    returns.put(method, returnType);
                }
                return toHost(r, HostTypeBindings.raw(returnType), returnType);
            });
        }
    }

    /** Runs the default implementation of an interface method the JS object does not provide. */
    private Object invokeDefault(Object proxy, Method method, Object[] args) {
        Object[] arguments = args == null ? dev.mooner.neonjs.runtime.Values.EMPTY_ARGS : args;
        if (DefaultMethods.INVOKE_DEFAULT != null) {
            return DefaultMethods.invokeEntry(DefaultMethods.INVOKE_DEFAULT, null,
                    new Object[] {proxy, method, arguments});
        }
        MethodHandle handle;
        try {
            handle = DefaultMethods.special(method);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw JSException.typeError(method.getName()
                    + " is not implemented (the platform cannot call default methods: " + error + ")");
        }
        return DefaultMethods.invokeEntry(DefaultMethods.HandleCall.INVOKE_WITH_ARGUMENTS,
                handle.bindTo(proxy), new Object[] {arguments});
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable error) throws T {
        throw (T) error;
    }

    private static RuntimeException sneaky(Throwable error) {
        HostBridge.<RuntimeException>sneakyThrow(error);
        return null;
    }

    /**
     * Calls of interface default methods on proxies. {@code InvocationHandler.invokeDefault} (JDK 16+) is absent on
     * Android; there a method handle with private access to the interface invokes the default implementation, as
     * Retrofit does: the lookup comes from {@code MethodHandles.privateLookupIn} (Android 13+) or from
     * {@code Lookup}'s (Class, int) constructor.
     */
    private static final class DefaultMethods {
        static final Method INVOKE_DEFAULT;
        private static final Method PRIVATE_LOOKUP_IN;
        private static final Constructor<MethodHandles.Lookup> LOOKUP_CONSTRUCTOR;
        private static final ConcurrentHashMap<Method, MethodHandle> HANDLES = new ConcurrentHashMap<>();
        /** PUBLIC | PRIVATE | PROTECTED | PACKAGE. */
        private static final int ALL_MODES = 15;

        static {
            Method invokeDefault;
            try {
                invokeDefault = InvocationHandler.class.getMethod("invokeDefault", Object.class, Method.class, Object[].class);
            } catch (NoSuchMethodException unavailable) {
                invokeDefault = null;
            }
            INVOKE_DEFAULT = invokeDefault;
            Method privateLookup = null;
            try {
                privateLookup = MethodHandles.class.getMethod("privateLookupIn", Class.class,
                        MethodHandles.Lookup.class);
            } catch (NoSuchMethodException unavailable) {
                privateLookup = null;
            }
            PRIVATE_LOOKUP_IN = privateLookup;
            Constructor<MethodHandles.Lookup> constructor = null;
            try {
                constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
                constructor.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                constructor = null;
            }
            LOOKUP_CONSTRUCTOR = constructor;
        }

        /** A handle invoking {@code method}'s default implementation (not dispatching to the proxy) on its receiver. */
        static MethodHandle special(Method method) throws ReflectiveOperationException {
            MethodHandle cached = HANDLES.get(method);
            if (cached != null) return cached;
            Class<?> iface = method.getDeclaringClass();
            Throwable failure = null;
            for (int mode = 0; mode < 2; mode++) {
                try {
                    MethodHandles.Lookup lookup;
                    if (mode == 0) {
                        if (PRIVATE_LOOKUP_IN == null) continue;
                        lookup = (MethodHandles.Lookup) PRIVATE_LOOKUP_IN.invoke(null, iface,
                                MethodHandles.lookup());
                    } else {
                        if (LOOKUP_CONSTRUCTOR == null) continue;
                        lookup = LOOKUP_CONSTRUCTOR.newInstance(iface, ALL_MODES);
                    }
                    if (lookup == null) continue;
                    MethodHandle handle = lookup.unreflectSpecial(method, iface);
                    MethodHandle previous = HANDLES.putIfAbsent(method, handle);
                    return previous != null ? previous : handle;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    failure = error instanceof InvocationTargetException target
                            ? target.getTargetException() : error;
                    if (failure instanceof Error fatal) throw fatal;
                }
            }
            if (failure != null) throw sneaky(failure);
            throw new UnsupportedOperationException("no private method handle lookup");
        }

        /** Cached public reflection entries preserve checked target failures across Supplier callback gates. */
        static Object invokeEntry(Method entry, Object receiver, Object[] arguments) {
            try {
                return entry.invoke(receiver, arguments);
            } catch (InvocationTargetException error) {
                throw sneaky(error.getTargetException());
            } catch (IllegalAccessException error) {
                throw sneaky(error);
            }
        }

        private static final class HandleCall {
            static final Method INVOKE_WITH_ARGUMENTS;

            static {
                try {
                    INVOKE_WITH_ARGUMENTS = MethodHandle.class.getMethod("invokeWithArguments", Object[].class);
                } catch (NoSuchMethodException error) {
                    throw new ExceptionInInitializerError(error);
                }
            }
        }
    }

    // ------------------------------------------------------------------ invocation

    /** Selects the best overload for {@code args}; returns null if none applies. */
    public <E extends Executable> E select(List<E> candidates, Object[] args) {
        return select(candidates, sigs(candidates), args);
    }

    /** {@link #select} among {@code candidates}, a list as long-lived as the {@link HostClassInfo} holding it. */
    <E extends Executable> E choose(List<E> candidates, Object[] args) {
        int index = selectIndex(overloads(candidates), args);
        return index < 0 ? null : candidates.get(index);
    }

    /** {@link #selectIndex} remembering the choice for arguments of the same kinds (up to {@link #MEMO_SIZE} combinations). */
    int selectIndex(Overloads overloads, Object[] args) {
        if (!overloads.memoizable || args.length > 15) return selectIndex(overloads.sigs, args);
        long key = args.length;
        for (Object argument : args) {
            int kind = kindOf(argument);
            if (kind < 0) return selectIndex(overloads.sigs, args);
            key = (key << 4) | kind;
        }
        for (int i = 0; i < overloads.size; i++) {
            if (overloads.keys[i] == key) return overloads.choices[i];
        }
        int chosen = selectIndex(overloads.sigs, args);
        if (overloads.size < MEMO_SIZE) {
            overloads.keys[overloads.size] = key;
            overloads.choices[overloads.size] = chosen;
            overloads.size++;
        }
        return chosen;
    }

    /**
     * What {@link #cost} looks at in {@code value}, as a code from 0 to 15: the same code means the same cost for
     * every parameter type (enum types aside). -1 where cost looks at more: the class of a host object, a proxy.
     */
    private int kindOf(Object value) {
        Object v = gate.normalizeGuest(value);
        if (v == Undefined.INSTANCE || v == Null.INSTANCE || v == null) return 0;
        if (v instanceof Boolean) return 1;
        if (v instanceof Double doubleValue) {
            double d = doubleValue;
            if (d != Math.rint(d) || Double.isInfinite(d)) return 2;
            if (d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE) return 3;
            if (d >= Short.MIN_VALUE && d <= Short.MAX_VALUE) return 4;
            if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return 5;
            if (Math.abs(d) <= 9007199254740991.0) return 6;
            return 7;
        }
        if (v instanceof CharSequence text) return text.length() == 1 ? 8 : 9;
        if (v instanceof BigInteger integer) return integer.bitLength() < 64 ? 10 : 11;
        if (v instanceof HostObject || v instanceof HostClassObject
                || v instanceof dev.mooner.neonjs.runtime.ProxyObject) return -1;
        if (v instanceof JSObject object) {
            if (object instanceof JSDate || object instanceof JSTemporalInstant) return 12;
            if (object.isCallable()) return 13;
            if (Ops.isArray(object)) return 14;
            return 15;
        }
        return -1;
    }

    /** {@link #select} with the {@link Sig}s of {@code candidates} at hand. */
    <E extends Executable> E select(List<E> candidates, Sig[] signatures, Object[] args) {
        int index = selectIndex(signatures, args);
        return index < 0 ? null : candidates.get(index);
    }

    /** The index in {@code signatures} of the overload {@link #select} chooses, or -1. */
    int selectIndex(Sig[] signatures, Object[] args) {
        int bestCost = IMPOSSIBLE;
        int bestIndex = -1;
        int[] tied = null;
        int tiedCount = 0;
        for (int index = 0; index < signatures.length; index++) {
            Sig sig = signatures[index];
            Class<?>[] parameters = sig.params;
            int total = 0;
            if (sig.varArgs) {
                if (args.length < parameters.length - 1) continue;
                for (int i = 0; i < parameters.length - 1; i++) {
                    int k = cost(args[i], parameters[i]);
                    if (k == IMPOSSIBLE) {
                        total = IMPOSSIBLE;
                        break;
                    }
                    total += k;
                }
                if (total == IMPOSSIBLE) continue;
                Class<?> component = parameters[parameters.length - 1].getComponentType();
                if (args.length == parameters.length
                        && cost(args[args.length - 1], parameters[parameters.length - 1]) != IMPOSSIBLE) {
                    total += cost(args[args.length - 1], parameters[parameters.length - 1]);
                } else {
                    for (int i = parameters.length - 1; i < args.length; i++) {
                        int k = cost(args[i], component);
                        if (k == IMPOSSIBLE) {
                            total = IMPOSSIBLE;
                            break;
                        }
                        total += k;
                    }
                }
                if (total == IMPOSSIBLE) continue;
                total += 10;
            } else {
                if (parameters.length != args.length) continue;
                for (int i = 0; i < parameters.length; i++) {
                    int k = cost(args[i], parameters[i]);
                    if (k == IMPOSSIBLE) {
                        total = IMPOSSIBLE;
                        break;
                    }
                    total += k;
                }
                if (total == IMPOSSIBLE) continue;
            }
            if (total < bestCost) {
                bestIndex = index;
                bestCost = total;
                tiedCount = 0;
            } else if (total == bestCost) {
                if (tied == null) tied = new int[signatures.length];
                if (tiedCount == 0) tied[tiedCount++] = bestIndex;
                tied[tiedCount++] = index;
            }
        }
        return tiedCount > 0 ? mostSpecific(signatures, tied, tiedCount) : bestIndex;
    }

    /**
     * The overload to call among tied ones of equal cost: one whose parameter types are each at least as specific as
     * every other's (Java's rule: {@code String} over {@code CharSequence} over {@code Object}, {@code int} over
     * {@code long}), else the first of the maximally specific ones in a fixed order.
     */
    private static int mostSpecific(Sig[] signatures, int[] tied, int count) {
        for (int i = 0; i < count; i++) {
            Sig a = signatures[tied[i]];
            boolean all = true;
            for (int j = 0; j < count; j++) {
                if (!asSpecific(a, signatures[tied[j]])) {
                    all = false;
                    break;
                }
            }
            if (all) return tied[i];
        }
        int first = -1;
        for (int i = 0; i < count; i++) {
            Sig a = signatures[tied[i]];
            boolean maximal = true;
            for (int j = 0; j < count; j++) {
                Sig b = signatures[tied[j]];
                if (asSpecific(b, a) && !asSpecific(a, b)) {
                    maximal = false;
                    break;
                }
            }
            if (maximal && (first < 0 || a.comesBefore(signatures[first]))) first = tied[i];
        }
        return first;
    }

    /** Whether each parameter type of {@code a} converts to the matching one of {@code b} (as specific or more). */
    private static boolean asSpecific(Sig a, Sig b) {
        Class<?>[] pa = a.params;
        Class<?>[] pb = b.params;
        if (pa.length != pb.length) return a == b;
        for (int i = 0; i < pa.length; i++) {
            if (!subtype(pa[i], pb[i])) return false;
        }
        return true;
    }

    private static boolean subtype(Class<?> s, Class<?> t) {
        if (s == t) return true;
        if (s.isPrimitive() && t.isPrimitive()) {
            java.util.Set<Class<?>> targets = PRIMITIVE_WIDENING.get(s);
            return targets != null && targets.contains(t);
        }
        if (s.isPrimitive()) {
            Class<?> boxed = boxed(s);
            return boxed != null && t.isAssignableFrom(boxed);
        }
        return !t.isPrimitive() && t.isAssignableFrom(s);
    }

    public Object[] convertArgs(Executable executable, Object[] args) {
        return convertArgs(sig(executable), args);
    }

    Object[] convertArgs(Sig sig, Object[] args) {
        Class<?>[] parameters = sig.params;
        if (!sig.varArgs) {
            Object[] out = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                out[i] = toHost(args[i], parameters[i], sig.generic(i));
            }
            return out;
        }
        int fixed = parameters.length - 1;
        Object[] out = new Object[parameters.length];
        for (int i = 0; i < fixed; i++) out[i] = toHost(args[i], parameters[i], sig.generic(i));
        if (args.length == parameters.length
                && cost(args[args.length - 1], parameters[parameters.length - 1]) != IMPOSSIBLE) {
            out[fixed] = toHost(args[args.length - 1], parameters[parameters.length - 1], sig.generic(fixed));
        } else {
            Class<?> component = parameters[parameters.length - 1].getComponentType();
            Object array = Array.newInstance(component, args.length - fixed);
            for (int i = fixed; i < args.length; i++) {
                Array.set(array, i - fixed, toHost(args[i], component));
            }
            out[fixed] = array;
        }
        return out;
    }

    /** Invokes a host method/constructor, mapping exceptions. */
    public Object invoke(Method method, Object target, Object[] args) {
        return invoke(method, sig(method), target, args);
    }

    /** {@link #invoke} with the {@link Sig} of {@code method} at hand. */
    Object invoke(Method method, Sig sig, Object target, Object[] args) {
        Object[] converted = convertArgs(sig, args);
        Object result;
        try {
            result = method.invoke(target, converted);
        } catch (InvocationTargetException error) {
            throw hostError(error.getTargetException());
        } catch (IllegalAccessException error) {
            throw JSException.typeError("Cannot access " + method.getName() + ": " + error.getMessage());
        } catch (IllegalArgumentException error) {
            // a receiver or an argument reflection does not accept: the call's fault, not the method's
            throw JSException.typeError("Cannot call " + method.getName() + ": " + error.getMessage());
        }
        if (sig.returnType == Void.TYPE) return Undefined.INSTANCE;
        return toJS(result);
    }

    public Object construct(Constructor<?> constructor, Object[] args) {
        Object[] converted = convertArgs(constructor, args);
        Object result;
        try {
            result = constructor.newInstance(converted);
        } catch (InvocationTargetException error) {
            throw hostError(error.getTargetException());
        } catch (IllegalAccessException error) {
            throw JSException.typeError("Cannot access constructor: " + error.getMessage());
        } catch (IllegalArgumentException error) {
            throw JSException.typeError("Cannot call constructor: " + error.getMessage());
        } catch (InstantiationException error) {
            throw sneaky(error);
        }
        return toJS(result);
    }

    /** {@code new (Java.type('int[]'))(n)}: a zero-initialized Java array; {@code len} is capped to protect the host heap. */
    public Object newArray(Class<?> component, Object length) {
        double size = Ops.toIntegerOrInfinity(length);
        if (size < 0 || size > MAX_ARRAY_LENGTH) {
            throw JSException.rangeError("Invalid Java array length: " + Ops.toDisplayString(length));
        }
        realm.agent.reserveAllocation((long) size * (component.isPrimitive() ? 8 : 4));
        Object array;
        try {
            array = Array.newInstance(component, (int) size);
        } catch (OutOfMemoryError failure) {
            throw JSException.rangeError("Java array allocation failed: length " + (int) size);
        }
        return toJS(array);
    }

    /** Converts a JS array-like (or iterable host collection) to a Java array / List of the given type. */
    public Object convertTo(Object input, Class<?> t) {
        Object v = gate.normalizeGuest(input);
        if (t.isArray() && v instanceof JSObject object && !(v instanceof HostObject)) {
            long length = Ops.lengthOfArrayLike(object);
            if (length > MAX_ARRAY_LENGTH) throw JSException.rangeError("Invalid Java array length: " + length);
            Class<?> component = t.getComponentType();
            Object array = Array.newInstance(component, (int) length);
            for (int i = 0; i < (int) length; i++) {
                Array.set(array, i, toHost(object.get(PK.fromIndex(i), object), component));
                if ((i & 1023) == 1023) realm.agent.checkInterrupt();
            }
            return array;
        }
        if ((t == List.class || t == ArrayList.class || t == java.util.Collection.class)
                && v instanceof JSObject object && !(v instanceof HostObject)) {
            long length = Ops.lengthOfArrayLike(object);
            if (length > MAX_ARRAY_LENGTH) throw JSException.rangeError("Invalid Java list length: " + length);
            ArrayList<Object> out = new ArrayList<>((int) length);
            for (int i = 0; i < (int) length; i++) {
                out.add(toObject(object.get(PK.fromIndex(i), object)));
                if ((i & 1023) == 1023) realm.agent.checkInterrupt();
            }
            return out;
        }
        return toHost(v, t);
    }

    public RuntimeException hostError(Throwable error) {
        if (error instanceof JSException || error instanceof TerminationException) return sneaky(error);
        if (error instanceof StackOverflowError || error instanceof OutOfMemoryError) return sneaky(error);
        return sneaky(new HostException(error));
    }

    private static String trimClassName(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && (Character.isWhitespace(name.charAt(start)) || Character.isSpaceChar(name.charAt(start)))) start++;
        while (end > start && (Character.isWhitespace(name.charAt(end - 1)) || Character.isSpaceChar(name.charAt(end - 1)))) end--;
        return name.substring(start, end);
    }

    public static Class<?> classForName(String name, ClassLoader loader) throws ClassNotFoundException {
        String candidate = trimClassName(name);
        int dimensions = 0;
        while (candidate.endsWith("[]")) {
            dimensions++;
            candidate = trimClassName(candidate.substring(0, candidate.length() - 2));
        }
        Class<?> type;
        switch (candidate) {
            case "boolean":
                type = Boolean.TYPE;
                break;
            case "byte":
                type = Byte.TYPE;
                break;
            case "char":
                type = Character.TYPE;
                break;
            case "short":
                type = Short.TYPE;
                break;
            case "int":
                type = Integer.TYPE;
                break;
            case "long":
                type = Long.TYPE;
                break;
            case "float":
                type = Float.TYPE;
                break;
            case "double":
                type = Double.TYPE;
                break;
            default:
                type = Class.forName(candidate, true, loader);
                break;
        }
        if (dimensions == 0 && type.isPrimitive()) throw new ClassNotFoundException(name + " is a primitive type");
        for (int i = 0; i < dimensions; i++) type = Array.newInstance(type, 0).getClass();
        return type;
    }

    private static boolean needsGeneric(Class<?> t) {
        return t.isInterface() || t == CompletableFuture.class;
    }
}
