package dev.mooner.neonjs;

import dev.mooner.neonjs.interop.HostBridge;
import dev.mooner.neonjs.interop.HostClassObject;
import dev.mooner.neonjs.interop.HostObject;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSErrorObject;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.JSPromise;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Host-side handle to a JavaScript value, serialized through its owning context.
 * Derived from NeonJS (Apache License 2.0) and adapted to Java; context entry, exact numeric
 * conversion, and promise pumping behavior are retained for host callers.
 */
public final class NeonValue {
    private final NeonContext context;
    private final Object raw;

    public NeonValue(NeonContext context, Object raw) {
        this.context = Objects.requireNonNull(context, "context");
        this.raw = raw;
    }

    public NeonContext getContext() {
        return context;
    }

    public Object getRaw() {
        return raw;
    }

    public boolean isUndefined() {
        return raw == Undefined.INSTANCE || raw == null;
    }

    public boolean isNull() {
        return raw == Null.INSTANCE;
    }

    public boolean isNullish() {
        return isUndefined() || isNull();
    }

    public boolean isBoolean() {
        return raw instanceof Boolean;
    }

    public boolean isNumber() {
        return raw instanceof Double;
    }

    public boolean isString() {
        return raw instanceof CharSequence;
    }

    public boolean isBigInt() {
        return raw instanceof BigInteger;
    }

    public boolean isSymbol() {
        return raw instanceof JSSymbol;
    }

    public boolean isObject() {
        return raw instanceof JSObject;
    }

    public boolean isFunction() {
        return raw instanceof JSObject && ((JSObject) raw).isCallable();
    }

    public boolean isArray() {
        return raw instanceof JSArray;
    }

    public boolean isPromise() {
        return raw instanceof JSPromise;
    }

    public boolean isError() {
        return raw instanceof JSErrorObject;
    }

    public boolean isHostObject() {
        return raw instanceof HostObject;
    }

    public boolean isHostClass() {
        return raw instanceof HostClassObject;
    }

    public boolean asBoolean() {
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        throw new ClassCastException("not a boolean: " + typeOf());
    }

    public double asDouble() {
        if (raw instanceof Double) {
            return (Double) raw;
        }
        throw new ClassCastException("not a number: " + typeOf());
    }

    public int asInt() {
        double value = asDouble();
        int result = (int) value;
        if ((double) result != value) {
            throw new ArithmeticException("number " + value + " does not fit in an int");
        }
        return result;
    }

    /** A number, or a BigInt (host longs beyond 2^53 - 1 arrive as BigInts), that is exactly a long. */
    public long asLong() {
        if (raw instanceof BigInteger) {
            BigInteger value = (BigInteger) raw;
            if (value.bitLength() >= 64) {
                throw new ArithmeticException("bigint " + value + " does not fit in a long");
            }
            return value.longValue();
        }
        double value = asDouble();
        long result = (long) value;
        if ((double) result != value) {
            throw new ArithmeticException("number " + value + " does not fit in a long");
        }
        return result;
    }

    /** A JS string, or the text of a host CharSequence (a StringBuilder, Android's Spanned...). */
    public String asString() {
        CharSequence text = null;
        if (raw instanceof CharSequence) {
            text = (CharSequence) raw;
        } else if (raw instanceof HostObject && ((HostObject) raw).target instanceof CharSequence) {
            text = (CharSequence) ((HostObject) raw).target;
        }
        String value = text == null ? null : text.toString();
        if (value != null) {
            return value;
        }
        throw new ClassCastException("not a string: " + typeOf());
    }

    public BigInteger asBigInteger() {
        if (raw instanceof BigInteger) {
            return (BigInteger) raw;
        }
        throw new ClassCastException("not a bigint: " + typeOf());
    }

    @SuppressWarnings("unchecked")
    public <T> T asHostObject() {
        if (raw instanceof HostObject) {
            Object target = ((HostObject) raw).target;
            if (target != null) {
                return (T) target;
            }
        }
        throw new ClassCastException("not a host object: " + typeOf());
    }

    /** Converts to a host type (numbers, strings, collections, functional interfaces, host objects...). */
    @SuppressWarnings("unchecked")
    public <T> T as(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return call(() -> (T) bridge().toHost(raw, type, null));
    }

    /** Converts to the explicitly supplied host type. */
    public <T> T to(Class<T> type) {
        return as(type);
    }

    /** A JS Date or Temporal.Instant as an {@link Instant} (RangeError for an invalid Date). */
    public Instant asInstant() {
        return as(Instant.class);
    }

    public String typeOf() {
        return Ops.typeOf(raw);
    }

    private JSObject obj() {
        if (raw instanceof JSObject) {
            return (JSObject) raw;
        }
        throw new IllegalStateException("not an object: " + typeOf());
    }

    public NeonValue getMember(String key) {
        Objects.requireNonNull(key, "key");
        return call(() -> new NeonValue(context, Ops.getV(raw, PK.fromString(key))));
    }

    public void putMember(String key, Object value) {
        Objects.requireNonNull(key, "key");
        call(() -> {
            obj().setOrThrow(PK.fromString(key), bridge().toJS(value));
            return null;
        });
    }

    public boolean hasMember(String key) {
        Objects.requireNonNull(key, "key");
        return call(() -> raw instanceof JSObject && ((JSObject) raw).hasProperty(PK.fromString(key)));
    }

    public boolean removeMember(String key) {
        Objects.requireNonNull(key, "key");
        return call(() -> obj().delete(PK.fromString(key)));
    }

    /** Own enumerable string keys. */
    public Set<String> memberKeys() {
        return call(() -> {
            JSObject object = obj();
            Set<String> result = new LinkedHashSet<>();
            for (Object key : object.ownPropertyKeys()) {
                if (key instanceof JSSymbol) {
                    continue;
                }
                PropertyDescriptor descriptor = object.getOwnProperty(key);
                if (descriptor == null) {
                    continue;
                }
                if (descriptor.enumerable) {
                    result.add(PK.toStringKey(key));
                }
            }
            return result;
        });
    }

    public long getArraySize() {
        return call(() -> Ops.lengthOfArrayLike(obj()));
    }

    public NeonValue getElement(long index) {
        return call(() -> {
            JSObject object = obj();
            return new NeonValue(context, object.get(PK.fromIndex(index), object));
        });
    }

    public void setElement(long index, Object value) {
        call(() -> {
            obj().setOrThrow(PK.fromIndex(index), bridge().toJS(value));
            return null;
        });
    }

    /** Calls this function with {@code this} undefined. */
    public NeonValue call(Object... args) {
        Objects.requireNonNull(args, "args");
        return callWithThis(null, args);
    }

    public NeonValue callWithThis(Object thisArg, Object... args) {
        Objects.requireNonNull(args, "args");
        return call(() -> {
            HostBridge bridge = bridge();
            if (!(raw instanceof JSObject)) {
                throw JSException.typeError(Ops.describe(raw) + " is not a function");
            }
            JSObject function = (JSObject) raw;
            Object receiver = thisArg == null ? Undefined.INSTANCE : bridge.toJS(thisArg);
            Object[] converted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                converted[i] = bridge.toJS(args[i]);
            }
            return new NeonValue(context, Ops.call(function, receiver, converted));
        });
    }

    public NeonValue callMember(String name, Object... args) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(args, "args");
        return call(() -> {
            HostBridge bridge = bridge();
            Object key = PK.fromString(name);
            Object[] converted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                converted[i] = bridge.toJS(args[i]);
            }
            return new NeonValue(context, Ops.invoke(raw, key, converted));
        });
    }

    public NeonValue newInstance(Object... args) {
        Objects.requireNonNull(args, "args");
        return call(() -> {
            HostBridge bridge = bridge();
            Object[] converted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                converted[i] = bridge.toJS(args[i]);
            }
            return new NeonValue(context, Ops.construct(raw, converted, raw));
        });
    }

    /**
     * For promises: runs pending jobs until the promise settles and returns its value; throws
     * {@link JSException} if it is rejected. While it depends on external events (host futures,
     * Atomics.waitAsync) waits up to {@code timeoutMillis} for them; {@link IllegalStateException}
     * if it is still pending after that or once nothing more can settle it.
     */
    public NeonValue await(long timeoutMillis) {
        return call(() -> {
            if (!(raw instanceof JSPromise)) {
                return this;
            }
            JSPromise promise = (JSPromise) raw;
            pumpUntil(timeoutMillis, () -> promise.state != JSPromise.PENDING);
            if (promise.state == JSPromise.FULFILLED) {
                return new NeonValue(context, promise.result);
            }
            if (promise.state == JSPromise.REJECTED) {
                throw new JSException(promise.result);
            }
            throw new IllegalStateException("promise is still pending");
        });
    }

    public NeonValue await() {
        return await(Long.MAX_VALUE);
    }

    @Override
    public String toString() {
        try {
            return call(() -> {
                Object display = raw instanceof JSObject && !(raw instanceof HostObject)
                        ? Ops.toString(raw) : raw;
                return Ops.toDisplayString(display);
            });
        } catch (Exception ignored) {
            return "[" + typeOf() + "]";
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NeonValue)) {
            return false;
        }
        NeonValue value = (NeonValue) other;
        return value.context == context && Ops.sameValue(raw, value.raw);
    }

    @Override
    public int hashCode() {
        if (raw instanceof CharSequence) {
            return raw.toString().hashCode();
        }
        if (raw == null) {
            return 0;
        }
        return raw.hashCode();
    }

    private HostBridge bridge() {
        return context.getBridge();
    }

    private <T> T call(Supplier<T> block) {
        Objects.requireNonNull(block, "block");
        return context.call(block);
    }

    private boolean pumpUntil(long timeoutMillis, BooleanSupplier done) {
        return context.pumpUntil(timeoutMillis, done);
    }
}
