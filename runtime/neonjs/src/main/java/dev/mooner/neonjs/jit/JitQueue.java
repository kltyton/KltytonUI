package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.runtime.Agent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/** Shared background compiler batches; derived from NeonJS and adapted to Java. */
public final class JitQueue {
    public static final JitQueue INSTANCE = new JitQueue();
    public static final int THREADS = Integer.getInteger("neonjs.jit.threads",
            Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4)));
    public static final AtomicLong batches = new AtomicLong();
    public static final AtomicLong batchedBlocks = new AtomicLong();
    public static final AtomicLong takenOver = new AtomicLong();
    private static final int BATCH = Math.max(1, Integer.getInteger("neonjs.jit.batch", 32));
    private static final int MAX_PENDING = Integer.getInteger("neonjs.jit.maxPending", 4096);
    private static final LinkedBlockingQueue<JitTask> QUEUE = new LinkedBlockingQueue<>();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicReferenceFieldUpdater<CodeBlock, Object> TASK =
            AtomicReferenceFieldUpdater.newUpdater(CodeBlock.class, Object.class, "jitTask");
    private static final System.Logger LOGGER = System.getLogger(JitQueue.class.getName());
    private static volatile boolean started;

    private JitQueue() {
    }

    public static boolean submit(CodeBlock block, JitBackend backend, Agent owner) {
        if (THREADS <= 0) return false;
        if (PENDING.incrementAndGet() > MAX_PENDING) {
            PENDING.decrementAndGet();
            return false;
        }
        JitTask task = new JitTask(block, backend, owner, false);
        if (!TASK.compareAndSet(block, null, task)) {
            PENDING.decrementAndGet();
            return true;
        }
        if (block.compiled != null || block.jitFailed) {
            withdraw(task);
            return true;
        }
        startWorkers();
        QUEUE.add(task);
        return true;
    }

    public static boolean attach(JitTask task) {
        return TASK.compareAndSet(task.cb, null, task);
    }

    public static boolean claim(JitTask task) {
        if (!task.state.compareAndSet(JitTask.QUEUED, JitTask.RUNNING)) return false;
        PENDING.decrementAndGet();
        takenOver.incrementAndGet();
        return true;
    }

    public static void run(List<JitTask> tasks) {
        try {
            Map<JitBackend, List<JitTask>> groups = new LinkedHashMap<>();
            for (JitTask task : tasks) groups.computeIfAbsent(task.backend, key -> new ArrayList<>()).add(task);
            for (Map.Entry<JitBackend, List<JitTask>> group : groups.entrySet()) {
                List<JitTask> pending = group.getValue();
                List<CompiledCode> code = List.of();
                try {
                    List<CodeBlock> blocks = new ArrayList<>(pending.size());
                    for (JitTask task : pending) blocks.add(task.cb);
                    code = group.getKey().compile(blocks);
                } catch (Exception error) {
                    LOGGER.log(System.Logger.Level.WARNING, "A JIT backend failed to compile its batch", error);
                } finally {
                    for (int i = 0; i < pending.size(); i++) install(pending.get(i), i < code.size() ? code.get(i) : null);
                }
            }
        } finally {
            for (JitTask task : tasks) if (task.cb.jitTask == task) install(task, null);
        }
    }

    private static void install(JitTask task, CompiledCode code) {
        CodeBlock block = task.cb;
        if (code != null) {
            if (block.compiled == null) block.compiled = code;
        } else if (block.compiled == null) {
            block.jitFailed = true;
        }
        TASK.compareAndSet(block, task, null);
        task.finish();
    }

    private static void withdraw(JitTask task) {
        if (!task.state.compareAndSet(JitTask.QUEUED, JitTask.CANCELLED)) return;
        PENDING.decrementAndGet();
        TASK.compareAndSet(task.cb, task, null);
        task.finish();
    }

    public static void cancel(Agent owner) {
        var iterator = QUEUE.iterator();
        while (iterator.hasNext()) {
            JitTask task = iterator.next();
            if (task.owner == owner) {
                iterator.remove();
                withdraw(task);
            }
        }
    }

    private static void startWorkers() {
        if (started) return;
        synchronized (INSTANCE) {
            if (started) return;
            for (int i = 0; i < THREADS; i++) {
                Thread worker = new Thread(JitQueue::work, "neonjs-jit-" + i);
                worker.setDaemon(true);
                worker.setPriority(Thread.NORM_PRIORITY - 1);
                worker.start();
            }
            started = true;
        }
    }

    private static void work() {
        ArrayList<JitTask> batch = new ArrayList<>(BATCH);
        ArrayList<JitTask> claimed = new ArrayList<>(BATCH);
        while (true) {
            batch.clear();
            claimed.clear();
            try {
                batch.add(QUEUE.take());
            } catch (InterruptedException error) {
                continue;
            }
            QUEUE.drainTo(batch, BATCH - 1);
            for (JitTask task : batch) {
                if (task.state.compareAndSet(JitTask.QUEUED, JitTask.RUNNING)) claimed.add(task);
            }
            if (claimed.isEmpty()) continue;
            PENDING.addAndGet(-claimed.size());
            batches.incrementAndGet();
            batchedBlocks.addAndGet(claimed.size());
            run(new ArrayList<>(claimed));
        }
    }
}
