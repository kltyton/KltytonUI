/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.InterruptedExecutionException;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Promises;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.function.Supplier;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.runtime.Values.arg;

/**
 * The Atomics namespace object.
 *
 * <p>Atomic operations on a SharedArrayBuffer run inside the critical section of its {@link SharedDataBlock}, which
 * makes all atomics on a block sequentially consistent. Atomics.waitAsync promises are only resolved on their owning
 * agent's thread; other agents and the timer post jobs with {@link Agent#postExternalJob}.
 */
final class AtomicsBuiltins {
    /** Waiter Record in a SharedDataBlock waiter list; accessed while holding the block lock. */
    static abstract class Waiter {
        boolean notified;
    }

    /** Waiter Record of a blocked Atomics.wait call. */
    static final class SyncWaiter extends Waiter {
        final Condition cond;

        SyncWaiter(Condition condition) {
            this.cond = condition;
        }
    }

    /** Waiter Record of Atomics.waitAsync (its promise is of {@code realm}, owned by {@code agent}). */
    static final class AsyncWaiter extends Waiter implements Agent.ExternalSource {
        final Agent agent;
        final Realm realm;
        final JSPromise promise;
        final SharedDataBlock block;
        final int byteIndex;
        /** Pending timeout, if the timeout is finite. */
        volatile ScheduledFuture<?> timer;
        /** Job posted to {@code agent} by Atomics.notify from another agent. */
        final Runnable okJob = () -> resolve("ok");

        AsyncWaiter(Agent agent, Realm realm, JSPromise promise, SharedDataBlock block, int byteIndex) {
            this.agent = agent;
            this.realm = realm;
            this.promise = promise;
            this.block = block;
            this.byteIndex = byteIndex;
        }

        /** Resolves the promise; owner thread only. */
        void resolve(String result) {
            agent.removeExternalSource(this);
            realm.enter(() -> {
                Promises.resolvePromise(realm, promise, result);
                return null;
            });
        }

        /** The timeout job, run on the owner thread after the timer fired. */
        void onTimeout() {
            boolean removed;
            block.lock.lock();
            try {
                removed = unlink();
            } finally {
                block.lock.unlock();
            }
            if (removed) resolve("timed-out");
        }

        /** Removes this waiter from its waiter list (block lock held); false if it is no longer listed. */
        boolean unlink() {
            ArrayDeque<Waiter> list = block.waiters.get(byteIndex);
            if (list == null) return false;
            if (!list.remove(this)) return false;
            if (list.isEmpty()) block.waiters.remove(byteIndex);
            return true;
        }

        @Override
        public void cancel() {
            // unlink first: the lock orders this after a concurrent addAsyncWaiter, which sets timer under it
            block.lock.lock();
            try {
                unlink();
            } finally {
                block.lock.unlock();
            }
            ScheduledFuture<?> pending = timer;
            if (pending != null) pending.cancel(false);
        }
    }

    /** Timer task: only posts the timeout job to the owning agent (never touches guest state itself). */
    private static final class TimeoutTask implements Runnable {
        private final WeakReference<AsyncWaiter> reference;

        TimeoutTask(AsyncWaiter waiter) {
            reference = new WeakReference<>(waiter);
        }

        @Override
        public void run() {
            AsyncWaiter waiter = reference.get();
            if (waiter == null) return;
            waiter.agent.postExternalJob(waiter::onTimeout);
        }
    }

    /** Per-agent cap on pending Atomics.waitAsync waiters. */
    static final int MAX_ASYNC_WAITERS = 10_000;
    /** Finite timeouts above this many milliseconds (~31 years) never fire and get no timer. */
    private static final double MAX_TIMER_MILLIS = 1e12;

    private static final int OP_ADD = 0;
    private static final int OP_SUB = 1;
    private static final int OP_AND = 2;
    private static final int OP_OR = 3;
    private static final int OP_XOR = 4;
    private static final int OP_EXCHANGE = 5;

    /** Maximum slice of a blocking wait between interrupt / deadline checks. */
    private static final long WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static final boolean HAS_SPIN_WAIT = hasSpinWait();

    private AtomicsBuiltins() {
    }

    private static boolean hasSpinWait() {
        try {
            Thread.class.getMethod("onSpinWait");
            return true;
        } catch (NoSuchMethodException unavailable) {
            return false;
        }
    }

    private static void spinWait() {
        if (HAS_SPIN_WAIT) Thread.onSpinWait();
    }

    private static ScheduledThreadPoolExecutor timer() {
        return TimerHolder.TIMER;
    }

    private static final class TimerHolder {
        static final ScheduledThreadPoolExecutor TIMER = createTimer();
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(null, runnable, "neonjs-atomics-timer", 256L * 1024);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(10, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    static void install(Realm realm) {
        JSObject atomics = new JSObject(realm.getObjectPrototype());
        realm.intrinsics.put("%Atomics%", atomics);
        global(realm, "Atomics", atomics);
        String[] names = {"add", "and", "exchange", "or", "sub", "xor"};
        int[] operations = {OP_ADD, OP_AND, OP_EXCHANGE, OP_OR, OP_SUB, OP_XOR};
        for (int i = 0; i < names.length; i++) {
            final int operation = operations[i];
            method(atomics, realm, names[i], 3, (function, receiver, arguments, newTarget) -> {
                JSTypedArray array = validateIntegerTypedArray(arg(arguments, 0), false, true);
                int byteIndex = validateAtomicAccess(array, arg(arguments, 1));
                Object value = toIntegerValue(array.type, arg(arguments, 2));
                revalidate(array, byteIndex);
                long bits = rawBits(array.type, value);
                long old = atomically(array.buffer, () -> {
                    long current = rawLoad(array.buffer.data, byteIndex, array.type.size);
                    rawStore(array.buffer.data, byteIndex, array.type.size, apply(operation, current, bits));
                    return current;
                });
                return fromBits(array.type, old);
            });
        }
        method(atomics, realm, "compareExchange", 4, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validateIntegerTypedArray(arg(arguments, 0), false, true);
            int byteIndex = validateAtomicAccess(array, arg(arguments, 1));
            Object expected = toIntegerValue(array.type, arg(arguments, 2));
            Object replacement = toIntegerValue(array.type, arg(arguments, 3));
            revalidate(array, byteIndex);
            int size = array.type.size;
            long expectedBits = rawBits(array.type, expected);
            long replacementBits = rawBits(array.type, replacement);
            long mask = size == 8 ? -1L : (1L << (size * 8)) - 1;
            long old = atomically(array.buffer, () -> {
                long current = rawLoad(array.buffer.data, byteIndex, size);
                if (((current ^ expectedBits) & mask) == 0L) {
                    rawStore(array.buffer.data, byteIndex, size, replacementBits);
                }
                return current;
            });
            return fromBits(array.type, old);
        });
        method(atomics, realm, "isLockFree", 1, (function, receiver, arguments, newTarget) ->
                Ops.toIntegerOrInfinity(arg(arguments, 0)) == 4.0);
        method(atomics, realm, "load", 2, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validateIntegerTypedArray(arg(arguments, 0), false, false);
            int byteIndex = validateAtomicAccess(array, arg(arguments, 1));
            revalidate(array, byteIndex);
            return fromBits(array.type, atomically(array.buffer,
                    () -> rawLoad(array.buffer.data, byteIndex, array.type.size)));
        });
        method(atomics, realm, "store", 3, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validateIntegerTypedArray(arg(arguments, 0), false, true);
            int byteIndex = validateAtomicAccess(array, arg(arguments, 1));
            Object value = toIntegerValue(array.type, arg(arguments, 2));
            revalidate(array, byteIndex);
            long bits = rawBits(array.type, value);
            atomically(array.buffer, () -> {
                rawStore(array.buffer.data, byteIndex, array.type.size, bits);
                return 0L;
            });
            return value;
        });
        method(atomics, realm, "wait", 4, (function, receiver, arguments, newTarget) -> doWait(arguments));
        method(atomics, realm, "waitAsync", 4, (function, receiver, arguments, newTarget) ->
                doWaitAsync(function.realm, arguments));
        method(atomics, realm, "notify", 3, (function, receiver, arguments, newTarget) -> {
            JSTypedArray array = validateIntegerTypedArray(arg(arguments, 0), true, false);
            int byteIndex = validateAtomicAccess(array, arg(arguments, 1));
            Object countArgument = arg(arguments, 2);
            double count = countArgument == Undefined.INSTANCE
                    ? Double.POSITIVE_INFINITY
                    : Math.max(Ops.toIntegerOrInfinity(countArgument), 0.0);
            SharedDataBlock block = array.buffer.block;
            if (block == null) return 0.0;
            return (double) notify(block, byteIndex, count);
        });
        method(atomics, realm, "pause", 0, (function, receiver, arguments, newTarget) -> {
            Object n = arg(arguments, 0);
            if (n != Undefined.INSTANCE && !(n instanceof Double number && Ops.isIntegral(number))) {
                throw typeErr("Atomics.pause argument must be undefined or an integer");
            }
            int spins = n instanceof Double spinValue
                    ? (int) Math.min(Math.max(spinValue, 1.0), 1000.0) : 1;
            for (int i = 0; i < spins; i++) spinWait();
            return Undefined.INSTANCE;
        });
        value(atomics, JSSymbol.toStringTag, "Atomics", Attr.CONFIGURABLE);
    }

    // ------------------------------------------------------------------ validation

    private static JSTypedArray validateIntegerTypedArray(Object value, boolean waitable, boolean write) {
        JSTypedArray array = TypedArrayBuiltins.validate(value, "Atomics", write);
        ElementType type = array.type;
        if (waitable) {
            if (type != ElementType.INT32 && type != ElementType.BIGINT64) {
                throw typeErr("Atomics.wait / Atomics.notify require an Int32Array or BigInt64Array");
            }
        } else if (type == ElementType.UINT8C || type == ElementType.FLOAT16
                || type == ElementType.FLOAT32 || type == ElementType.FLOAT64) {
            throw typeErr("Atomics operations are not supported on " + type.ctorName());
        }
        return array;
    }

    /** ValidateAtomicAccess: returns the byte index in the buffer. */
    private static int validateAtomicAccess(JSTypedArray array, Object requestIndex) {
        int length = array.lengthOrOOB();
        long accessIndex = Ops.toIndex(requestIndex);
        if (accessIndex >= length) throw rangeErr("Atomics access index out of range");
        return ((int) accessIndex << array.type.shift) + array.byteOffset;
    }

    /** RevalidateAtomicAccess */
    private static void revalidate(JSTypedArray array, int byteIndex) {
        if (array.lengthOrOOB() < 0) {
            throw typeErr("Atomics: typed array is detached or out of bounds");
        }
        if (byteIndex >= array.buffer.byteLength()) throw rangeErr("Atomics access index out of range");
    }

    /** ToBigInt, or ToIntegerOrInfinity as a Number, of an operand. */
    private static Object toIntegerValue(ElementType type, Object value) {
        return type.isBigInt ? Ops.toBigInt(value) : Ops.toIntegerOrInfinity(value);
    }

    /** NumericToRawBytes as a bit pattern in the low bits of a Long. */
    private static long rawBits(ElementType type, Object value) {
        return type.isBigInt ? ((BigInteger) value).longValue()
                : (long) Ops.toInt32(((Double) value).doubleValue());
    }

    /** RawBytesToNumeric from a bit pattern. */
    private static Object fromBits(ElementType type, long bits) {
        switch (type) {
            case INT8:
                return (double) (byte) bits;
            case UINT8:
                return (double) (bits & 0xFF);
            case INT16:
                return (double) (short) bits;
            case UINT16:
                return (double) (bits & 0xFFFF);
            case INT32:
                return (double) (int) bits;
            case UINT32:
                return (double) (bits & 0xFFFFFFFFL);
            case BIGINT64:
                return BigInteger.valueOf(bits);
            case BIGUINT64:
                return BufferOps.bigFromBits(bits, true);
            default:
                throw new IllegalStateException("not an integer element type: " + type);
        }
    }

    // ------------------------------------------------------------------ atomic primitives

    /** Runs {@code body} inside the critical section of a shared buffer's data block. */
    private static <R> R atomically(JSArrayBuffer buffer, Supplier<R> body) {
        SharedDataBlock block = buffer.block;
        if (block == null) return body.get();
        block.lock.lock();
        try {
            return body.get();
        } finally {
            block.lock.unlock();
        }
    }

    /** Raw little-endian load of a {@code size}-byte integer (sign-extended). */
    private static long rawLoad(byte[] data, int index, int size) {
        switch (size) {
            case 1:
                return data[index];
            case 2:
                return BufferOps.getI16(data, index, true);
            case 4:
                return BufferOps.getI32(data, index, true);
            default:
                return BufferOps.getI64(data, index, true);
        }
    }

    private static void rawStore(byte[] data, int index, int size, long bits) {
        switch (size) {
            case 1:
                data[index] = (byte) bits;
                break;
            case 2:
                BufferOps.setI16(data, index, (short) bits, true);
                break;
            case 4:
                BufferOps.setI32(data, index, (int) bits, true);
                break;
            default:
                BufferOps.setI64(data, index, bits, true);
                break;
        }
    }

    private static long apply(int operation, long old, long value) {
        switch (operation) {
            case OP_ADD:
                return old + value;
            case OP_SUB:
                return old - value;
            case OP_AND:
                return old & value;
            case OP_OR:
                return old | value;
            case OP_XOR:
                return old ^ value;
            default:
                return value;
        }
    }

    // ------------------------------------------------------------------ wait / notify

    /** Validated arguments of DoWait: waiter list position, expected value and timeout t in ms. */
    private static final class WaitArgs {
        final SharedDataBlock block;
        final int byteIndex;
        final int size;
        final long value;
        final double timeoutMs;

        WaitArgs(SharedDataBlock block, int byteIndex, int size, long value, double timeoutMs) {
            this.block = block;
            this.byteIndex = byteIndex;
            this.size = size;
            this.value = value;
            this.timeoutMs = timeoutMs;
        }
    }

    /** Steps 1-9 of DoWait (validation and argument coercion, in spec order). */
    private static WaitArgs waitArgs(Object[] arguments, String method) {
        JSTypedArray array = validateIntegerTypedArray(arg(arguments, 0), true, false);
        SharedDataBlock block = array.buffer.block;
        if (block == null) throw typeErr(method + " requires a shared typed array");
        int byteIndex = validateAtomicAccess(array, arg(arguments, 1));
        boolean big = array.type == ElementType.BIGINT64;
        long value = big ? Ops.toBigInt(arg(arguments, 2)).longValue()
                : (long) Ops.toInt32(arg(arguments, 2));
        double q = Ops.toNumber(arg(arguments, 3));
        double timeoutMs = Double.isNaN(q) || q == Double.POSITIVE_INFINITY
                ? Double.POSITIVE_INFINITY : Math.max(q, 0.0);
        return new WaitArgs(block, byteIndex, array.type.size, value, timeoutMs);
    }

    /**
     * RemoveWaiters + NotifyWaiter for up to {@code count} waiters at {@code byteIndex}; returns the number woken.
     */
    private static int notify(SharedDataBlock block, int byteIndex, double count) {
        Agent self = Agent.current.get();
        ArrayList<AsyncWaiter> local = null;
        int n = 0;
        block.lock.lock();
        try {
            ArrayDeque<Waiter> list = block.waiters.get(byteIndex);
            if (list != null) {
                while (n < count && !list.isEmpty()) {
                    Waiter waiter = list.removeFirst();
                    waiter.notified = true;
                    if (waiter instanceof SyncWaiter sync) {
                        sync.cond.signal();
                    } else if (waiter instanceof AsyncWaiter async) {
                        ScheduledFuture<?> pending = async.timer;
                        if (pending != null) pending.cancel(false);
                        if (async.agent == self) {
                            if (local == null) local = new ArrayList<>();
                            local.add(async);
                        } else {
                            async.agent.postExternalJob(async.okJob);
                        }
                    }
                    n++;
                }
                if (list.isEmpty()) block.waiters.remove(byteIndex);
            }
        } finally {
            block.lock.unlock();
        }
        if (local != null) for (AsyncWaiter waiter : local) waiter.resolve("ok");
        return n;
    }

    /** DoWait(async, typedArray, index, value, timeout): returns the { async, value } result object. */
    private static JSObject doWaitAsync(Realm realm, Object[] arguments) {
        WaitArgs args = waitArgs(arguments, "Atomics.waitAsync");
        Agent agent = realm.agent;
        JSPromise promise = Promises.newPromise(realm);
        JSObject result = new JSObject(realm.getObjectPrototype());
        SharedDataBlock block = args.block;
        String immediate = null;
        boolean overLimit = false;
        block.lock.lock();
        try {
            if (rawLoad(block.data, args.byteIndex, args.size) != args.value) {
                immediate = "not-equal";
            } else if (args.timeoutMs == 0.0) {
                immediate = "timed-out";
            } else if (agent.getExternalSourceCount() >= MAX_ASYNC_WAITERS) {
                overLimit = true;
            } else {
                addAsyncWaiter(new AsyncWaiter(agent, realm, promise, block, args.byteIndex), args.timeoutMs);
            }
        } finally {
            block.lock.unlock();
        }
        if (overLimit) {
            throw rangeErr("Atomics.waitAsync: too many pending waiters (limit " + MAX_ASYNC_WAITERS + " per agent)");
        }
        result.createDataPropertyOrThrow("async", immediate == null);
        result.createDataPropertyOrThrow("value", immediate == null ? promise : immediate);
        return result;
    }

    /**
     * AddWaiter and, for a finite timeout, EnqueueAtomicsWaitAsyncTimeoutJob (block lock held). The waiter is registered
     * with its agent last, so a concurrent Agent.closeExternal either withdraws the fully linked waiter or makes the
     * registration fail (then it is unlinked again here).
     */
    private static void addAsyncWaiter(AsyncWaiter waiter, double timeoutMs) {
        blockWaiters(waiter.block, waiter.byteIndex).addLast(waiter);
        if (timeoutMs <= MAX_TIMER_MILLIS) {
            waiter.timer = timer().schedule(new TimeoutTask(waiter),
                    (long) (timeoutMs * 1e6), TimeUnit.NANOSECONDS);
        }
        if (!waiter.agent.addExternalSource(waiter)) {
            waiter.unlink();
            if (waiter.timer != null) waiter.timer.cancel(false);
            throw typeErr("Atomics.waitAsync: the agent is shutting down");
        }
    }

    private static ArrayDeque<Waiter> blockWaiters(SharedDataBlock block, int byteIndex) {
        return block.waiters.computeIfAbsent(byteIndex, key -> new ArrayDeque<>());
    }

    /** DoWait(sync, typedArray, index, value, timeout) */
    private static String doWait(Object[] arguments) {
        WaitArgs args = waitArgs(arguments, "Atomics.wait");
        SharedDataBlock block = args.block;
        int byteIndex = args.byteIndex;
        long value = args.value;
        double timeoutMs = args.timeoutMs;
        Agent agent = Agent.current();
        var lock = block.lock;
        lock.lock();
        try {
            long current = rawLoad(block.data, byteIndex, args.size);
            if (value != current) return "not-equal";
            SyncWaiter waiter = new SyncWaiter(lock.newCondition());
            blockWaiters(block, byteIndex).addLast(waiter);
            try {
                long deadline = timeoutMs == Double.POSITIVE_INFINITY
                        ? Long.MAX_VALUE
                        : System.nanoTime() + (long) Math.min(timeoutMs * 1e6, Long.MAX_VALUE / 4.0);
                while (!waiter.notified) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try {
                        waiter.cond.awaitNanos(Math.min(remaining, WAIT_SLICE_NANOS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedExecutionException("Execution interrupted");
                    }
                    if (!waiter.notified) agent.checkInterrupt();
                }
            } finally {
                if (!waiter.notified) {
                    ArrayDeque<Waiter> list = block.waiters.get(byteIndex);
                    if (list != null) {
                        list.remove(waiter);
                        if (list.isEmpty()) block.waiters.remove(byteIndex);
                    }
                }
            }
            return waiter.notified ? "ok" : "timed-out";
        } finally {
            lock.unlock();
        }
    }
}
