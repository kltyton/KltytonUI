package dev.mooner.neonjs.runtime;

import com.sun.management.ThreadMXBean;
import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.RegExpSite;
import dev.mooner.neonjs.compiler.Source;
import dev.mooner.neonjs.vm.Frame;
import dev.mooner.neonjs.vm.HostException;
import dev.mooner.neonjs.vm.JSClosure;
import dev.mooner.neonjs.vm.JSPromise;
import dev.mooner.neonjs.vm.Modules;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Context-owned execution and external-job state; derived from NeonJS and adapted to Java. */
public final class Agent {
    private static final System.Logger LOGGER = System.getLogger(Agent.class.getName());
    public static final ThreadLocal<Agent> current = new ThreadLocal<>();
    public static final long WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final ThreadMXBean THREAD_MX = threadMemoryBean();

    static {
        ClassLoader loader = Agent.class.getClassLoader();
        String[] names = {
            "dev.mooner.neonjs.runtime.JSException", "dev.mooner.neonjs.runtime.JSErrorObject",
            "dev.mooner.neonjs.runtime.ErrorKind", "dev.mooner.neonjs.runtime.TerminationException",
            "dev.mooner.neonjs.runtime.ExecutionTimeoutException", "dev.mooner.neonjs.runtime.ResourceLimitException",
            "dev.mooner.neonjs.runtime.InterruptedExecutionException", "dev.mooner.neonjs.runtime.PropertyDescriptor",
            "dev.mooner.neonjs.runtime.Ops", "dev.mooner.neonjs.runtime.NumberConv", "dev.mooner.neonjs.runtime.PK",
            "dev.mooner.neonjs.runtime.Shape", "dev.mooner.neonjs.runtime.PropCache", "dev.mooner.neonjs.runtime.GlobalCache",
            "dev.mooner.neonjs.vm.Interpreter", "dev.mooner.neonjs.vm.Rt", "dev.mooner.neonjs.vm.Frame",
            "dev.mooner.neonjs.vm.DerivedResult", "dev.mooner.neonjs.vm.HostException", "dev.mooner.neonjs.vm.Iteration",
            "dev.mooner.neonjs.vm.Promises", "dev.mooner.neonjs.vm.Generators", "dev.mooner.neonjs.vm.Disposal",
            "dev.mooner.neonjs.jit.JitRt", "dev.mooner.neonjs.builtins.ErrorBuiltins"
        };
        for (String name : names) {
            try {
                Class.forName(name, true, loader);
            } catch (ClassNotFoundException error) {
                LOGGER.log(System.Logger.Level.TRACE, "Optional error-path class is unavailable: " + name, error);
            }
        }
    }

    public final RuntimeConfig config;
    public Realm currentRealm;
    public int depth;
    public int maxDepth;
    public Frame topFrame;
    public boolean elemMiss;
    public final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
    public final HashMap<String, JSSymbol> symbolRegistry = new HashMap<>();
    public volatile boolean interruptRequested;
    public int callBudget = 1024;
    public long deadlineNanos;
    public long statementsLeft = Long.MAX_VALUE;
    public Consumer<Frame> debuggerHook;
    public BiFunction<Realm, RegExpSite, JSObject> regexpFactory = (realm, site) -> {
        throw JSException.syntaxError("RegExp not supported");
    };
    public ModuleLoaderHooks moduleLoader = new ModuleLoaderHooks() {
        @Override public JSObject importMeta(Frame frame) { return Modules.importMeta(frame); }
        @Override public Object dynamicImport(Frame frame, Object specifier, Object options) {
            return Modules.dynamicImport(frame, specifier, options);
        }
    };
    public BiFunction<HostException, Realm, Object> hostExceptionHandler;
    public final ArrayList<Object> keptAlive = new ArrayList<>();
    public BiConsumer<JSObject, Boolean> rejectionTracker;
    public UncaughtSink uncaught;
    public BiPredicate<RuntimeException, JSPromise> uncaughtInterceptor;
    public Random randomSource;
    public DoubleSupplier clock;

    private final LinkedHashSet<JSPromise> unhandledRejections = new LinkedHashSet<>();
    private long allocationBase;
    private final AtomicLong interrupts = new AtomicLong();
    private final ArrayDeque<Runnable> ticks = new ArrayDeque<>();
    private final ReentrantLock externalLock = new ReentrantLock();
    private final Condition externalPosted = externalLock.newCondition();
    private final ArrayDeque<Runnable> externalJobs = new ArrayDeque<>();
    private volatile int externalCount;
    private final Set<ExternalSource> externalSources = ConcurrentHashMap.newKeySet();
    private final Set<ExternalSource> idleSources = ConcurrentHashMap.newKeySet();
    private volatile boolean externalClosed;

    public Agent() {
        this(new RuntimeConfig());
    }

    public Agent(RuntimeConfig config) {
        this.config = config;
        maxDepth = config.maxCallDepth;
    }

    private static ThreadMXBean threadMemoryBean() {
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof ThreadMXBean memory)) return null;
            if (memory.isThreadAllocatedMemorySupported() && !memory.isThreadAllocatedMemoryEnabled()) {
                memory.setThreadAllocatedMemoryEnabled(true);
            }
            return memory;
        } catch (SecurityException | UnsupportedOperationException | LinkageError error) {
            LOGGER.log(System.Logger.Level.DEBUG, "Thread allocation accounting is unavailable", error);
            return null;
        }
    }

    public void trackRejection(JSPromise promise, boolean handled) {
        if (rejectionTracker != null) rejectionTracker.accept(promise, handled);
        if (uncaught == null && uncaughtInterceptor == null) return;
        if (handled) unhandledRejections.remove(promise);
        else unhandledRejections.add(promise);
    }

    public double nextRandom() {
        return randomSource == null ? ThreadLocalRandom.current().nextDouble() : randomSource.nextDouble();
    }

    public double currentTimeMillis() {
        return clock == null ? (double) System.currentTimeMillis() : clock.getAsDouble();
    }

    public void checkStringLength(long length) {
        if (length > config.maxStringLength) throw JSException.rangeError("Invalid string length");
        if (length > (1 << 20)) reserveAllocation(length * 2);
    }

    public void reserveAllocation(long bytes) {
        if (bytes < 65536 || config.maxAllocatedBytes <= 0 || THREAD_MX == null) return;
        if (threadAllocatedBytes() - allocationBase + bytes > config.maxAllocatedBytes) {
            throw new ResourceLimitException("Memory allocation limit exceeded");
        }
    }

    public Object hostExceptionToJS(HostException error, Realm realm) {
        BiFunction<HostException, Realm, Object> handler = hostExceptionHandler;
        if (handler != null) return handler.apply(error, realm);
        Throwable cause = error.getHostCause();
        return realm.newError(ErrorKind.ERROR, cause.getClass().getName() + ": " + cause.getMessage());
    }

    public void onDebugger(Frame frame) {
        if (debuggerHook != null) debuggerHook.accept(frame);
    }

    public void startLimits() {
        interruptRequested = false;
        restartLimits();
    }

    public void restartLimits() {
        deadlineNanos = config.maxExecutionMillis > 0
                ? System.nanoTime() + config.maxExecutionMillis * 1_000_000 : 0;
        statementsLeft = config.maxStatements > 0 ? config.maxStatements : Long.MAX_VALUE;
        if (config.maxAllocatedBytes > 0) allocationBase = threadAllocatedBytes();
    }

    public long[] saveLimits() {
        return new long[] {deadlineNanos, statementsLeft, allocationBase};
    }

    public void restoreLimits(long[] saved) {
        deadlineNanos = saved[0];
        statementsLeft = saved[1];
        allocationBase = saved[2];
    }

    public long getInterruptCount() {
        return interrupts.get();
    }

    public void requestInterrupt() {
        interruptRequested = true;
        interrupts.incrementAndGet();
    }

    @SuppressWarnings("deprecation")
    private long threadAllocatedBytes() {
        return THREAD_MX == null ? 0 : THREAD_MX.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public void checkInterrupt() {
        if (interruptRequested) throw new InterruptedExecutionException("Execution interrupted");
        if (deadlineNanos != 0 && System.nanoTime() > deadlineNanos) {
            throw new ExecutionTimeoutException("Execution time limit exceeded");
        }
        statementsLeft -= 4096;
        if (statementsLeft < 0) throw new ResourceLimitException("Instruction limit exceeded");
        if (config.maxAllocatedBytes > 0 && THREAD_MX != null
                && threadAllocatedBytes() - allocationBase > config.maxAllocatedBytes) {
            throw new ResourceLimitException("Memory allocation limit exceeded");
        }
    }

    public void checkWaitLimits() {
        if (interruptRequested) throw new InterruptedExecutionException("Execution interrupted");
        if (deadlineNanos != 0 && System.nanoTime() > deadlineNanos) {
            throw new ExecutionTimeoutException("Execution time limit exceeded");
        }
    }

    public void enqueueJob(Runnable job) {
        jobs.addLast(job);
    }

    public void enqueueTick(Runnable job) {
        ticks.addLast(job);
    }

    public boolean addExternalSource(ExternalSource source) {
        return addExternalSource(source, true);
    }

    public boolean addExternalSource(ExternalSource source, boolean keepsAlive) {
        if (externalClosed) return false;
        Set<ExternalSource> sources = keepsAlive ? externalSources : idleSources;
        sources.add(source);
        if (externalClosed) {
            sources.remove(source);
            return false;
        }
        return true;
    }

    public void setKeepsAlive(ExternalSource source, boolean keepsAlive) {
        if (externalClosed) return;
        if (keepsAlive) {
            if (idleSources.remove(source)) externalSources.add(source);
            return;
        }
        if (!externalSources.remove(source)) return;
        idleSources.add(source);
        if (externalSources.isEmpty()) {
            externalLock.lock();
            try {
                externalPosted.signalAll();
            } finally {
                externalLock.unlock();
            }
        }
    }

    public void removeExternalSource(ExternalSource source) {
        if (idleSources.remove(source)) return;
        if (!externalSources.remove(source) || !externalSources.isEmpty()) return;
        externalLock.lock();
        try {
            externalPosted.signalAll();
        } finally {
            externalLock.unlock();
        }
    }

    public int getExternalSourceCount() {
        return externalSources.size() + idleSources.size();
    }

    public void postExternalJob(Runnable job) {
        externalLock.lock();
        try {
            if (externalClosed) return;
            externalJobs.addLast(job);
            externalCount = externalJobs.size();
            externalPosted.signalAll();
        } finally {
            externalLock.unlock();
        }
    }

    public boolean hasPendingExternal() {
        return externalCount > 0 || !externalSources.isEmpty();
    }

    private Runnable pollExternal() {
        if (externalCount == 0) return null;
        externalLock.lock();
        try {
            Runnable job = externalJobs.pollFirst();
            externalCount = externalJobs.size();
            return job;
        } finally {
            externalLock.unlock();
        }
    }

    public boolean awaitExternal(long timeoutMillis) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(timeoutMillis, 0));
        while (true) {
            if (externalCount > 0) return true;
            if (!config.limitsPerTask) checkWaitLimits();
            else if (interruptRequested) throw new InterruptedExecutionException("Execution interrupted");
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return false;
            if (waitForExternal(Math.min(remaining, WAIT_SLICE_NANOS))) return true;
        }
    }

    public boolean waitForExternal(long nanos) {
        externalLock.lock();
        try {
            if (externalJobs.isEmpty() && !externalClosed && nanos > 0) externalPosted.awaitNanos(nanos);
            return !externalJobs.isEmpty();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedExecutionException("Execution interrupted");
        } finally {
            externalLock.unlock();
        }
    }

    public void closeExternal() {
        externalLock.lock();
        try {
            externalClosed = true;
            externalJobs.clear();
            externalCount = 0;
            externalPosted.signalAll();
        } finally {
            externalLock.unlock();
        }
        ArrayList<ExternalSource> sources = new ArrayList<>(externalSources);
        sources.addAll(new ArrayList<>(idleSources));
        for (ExternalSource source : sources) {
            externalSources.remove(source);
            idleSources.remove(source);
            try {
                source.cancel();
            } catch (RuntimeException error) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to cancel an external job source", error);
            }
        }
    }

    public void runJobs() {
        try {
            runMicrotasks();
            while (true) {
                Runnable task = pollExternal();
                if (task == null) return;
                if (config.limitsPerTask) restartLimits();
                if (interruptRequested) throw new InterruptedExecutionException("Execution interrupted");
                if (--callBudget < 0) {
                    callBudget = 1024;
                    checkInterrupt();
                }
                runJob(task);
                runMicrotasks();
            }
        } finally {
            keptAlive.clear();
        }
    }

    private void runMicrotasks() {
        while (true) {
            Runnable job;
            while ((job = ticks.pollFirst()) != null) {
                if (--callBudget < 0) {
                    callBudget = 1024;
                    checkInterrupt();
                }
                runJob(job);
            }
            if (jobs.isEmpty()) break;
            while ((job = jobs.pollFirst()) != null) {
                if (--callBudget < 0) {
                    callBudget = 1024;
                    checkInterrupt();
                }
                runJob(job);
            }
            if (ticks.isEmpty()) break;
        }
        keptAlive.clear();
        if (!unhandledRejections.isEmpty()) reportRejections();
    }

    private void runJob(Runnable job) {
        if (uncaught == null && uncaughtInterceptor == null) {
            job.run();
            return;
        }
        try {
            job.run();
        } catch (JSException | HostException error) {
            uncaughtException(error);
        }
    }

    private void uncaughtException(RuntimeException error) {
        if (uncaughtInterceptor != null && uncaughtInterceptor.test(error, null)) return;
        UncaughtSink sink = uncaught;
        if (sink == null) throw error;
        sink.exception(error);
    }

    private void reportRejections() {
        ArrayList<JSPromise> rejected = new ArrayList<>(unhandledRejections);
        unhandledRejections.clear();
        for (JSPromise promise : rejected) {
            if (promise.isHandled) continue;
            if (uncaughtInterceptor != null && uncaughtInterceptor.test(null, promise)) continue;
            if (uncaught != null) uncaught.rejection(promise);
        }
    }

    public String captureStack() {
        return captureStack(null, 50);
    }

    public String captureStack(JSObject skipThrough, int limit) {
        StringBuilder out = new StringBuilder();
        boolean skipping = skipThrough != null;
        Frame frame = topFrame;
        int count = 0;
        while (frame != null && count < limit) {
            JSClosure inlined = frame.inlineFn;
            if (inlined != null) {
                if (skipping) {
                    if (inlined == skipThrough) skipping = false;
                } else {
                    String name = inlined.debugName();
                    appendFrame(out, name.isEmpty() ? "<anonymous>" : name, inlined.code, frame.inlinePc);
                    if (++count >= limit) break;
                }
            }
            if (skipping) {
                if (frame.fn != null && frame.fn == skipThrough) skipping = false;
            } else {
                String name = frame.fn == null ? frame.code.name : frame.fn.debugName();
                if (frame.fn != null && name.isEmpty()) name = "<anonymous>";
                appendFrame(out, name, frame.code, frame.pc);
                count++;
            }
            frame = frame.parent;
        }
        int end = out.length();
        while (end > 0 && (Character.isWhitespace(out.charAt(end - 1))
                || Character.isSpaceChar(out.charAt(end - 1)))) end--;
        return out.substring(0, end);
    }

    private void appendFrame(StringBuilder out, String name, CodeBlock code, int pc) {
        int position = code.positionAt(pc);
        Source source = code.source;
        out.append("    at ").append(name);
        if (source != null && position >= 0) {
            Source.LineColumn location = source.lineCol(position);
            out.append(" (").append(source.name).append(':').append(location.line())
                    .append(':').append(location.column()).append(')');
        }
        out.append('\n');
    }

    public <R> R enter(Supplier<R> block) {
        Agent previous = current.get();
        current.set(this);
        try {
            return block.get();
        } finally {
            current.set(previous);
        }
    }

    public static Agent current() {
        Agent agent = current.get();
        if (agent == null) throw new IllegalStateException("No active NeonJS context on this thread");
        return agent;
    }

    public static Realm currentRealm() {
        Realm realm = current().currentRealm;
        if (realm == null) throw new IllegalStateException("No current realm");
        return realm;
    }

    public static Realm currentRealmOrNull() {
        Agent agent = current.get();
        return agent == null ? null : agent.currentRealm;
    }

    public interface UncaughtSink {
        void exception(RuntimeException error);
        void rejection(JSPromise promise);
    }

    @FunctionalInterface
    public interface ExternalSource {
        void cancel();
    }
}
