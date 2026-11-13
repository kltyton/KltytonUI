package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.runtime.Agent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-owner compilation task; derived from NeonJS and adapted to Java. */
public final class JitTask {
    static final int QUEUED = 0;
    static final int RUNNING = 1;
    static final int CANCELLED = 2;
    public final CodeBlock cb;
    public final JitBackend backend;
    public final Agent owner;
    final AtomicInteger state;
    private final CountDownLatch done = new CountDownLatch(1);

    public JitTask(CodeBlock block, JitBackend backend, Agent owner, boolean claimed) {
        cb = block;
        this.backend = backend;
        this.owner = owner;
        state = new AtomicInteger(claimed ? RUNNING : QUEUED);
    }

    void finish() {
        done.countDown();
    }

    public boolean await(long millis) throws InterruptedException {
        return done.await(millis, TimeUnit.MILLISECONDS);
    }
}
