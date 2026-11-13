package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.RuntimeConfig;
import java.util.List;

/** Interpreter-to-JIT tiering policy; derived from NeonJS and adapted to Java. */
public final class Jit {
    public static final int MODE_INTERPRETER = 0;
    public static final int MODE_COMPILED = 1;
    public static final int MODE_ADAPTIVE = 2;

    private Jit() {
    }

    public static CompiledCode prepare(CodeBlock block, Agent agent) {
        Object compiled = block.compiled;
        if (compiled != null) {
            if (!block.childrenQueued) queueChildren(block, agent);
            return (CompiledCode) compiled;
        }
        if (block.jitFailed) return null;
        RuntimeConfig config = agent.config;
        if (config.executionMode == MODE_INTERPRETER) return null;
        if (config.executionMode == MODE_ADAPTIVE) {
            if (++block.invocationCount < config.jitThreshold || block.jitTask != null) return null;
            if (config.backgroundJit && compilable(block) && JitQueue.submit(block, backend(agent), agent)) return null;
            return compileNow(block, agent);
        }
        CompiledCode result = compileNow(block, agent);
        queueChildren(block, agent);
        return result;
    }

    public static void prefetch(CodeBlock block, Agent agent) {
        RuntimeConfig config = agent.config;
        if (config.executionMode == MODE_COMPILED && config.backgroundJit && compilable(block)) {
            JitQueue.submit(block, backend(agent), agent);
        }
    }

    public static JitBackend backend(Agent agent) {
        return new ClassBackend(agent.config.codeDefiner == null ? CodeDefiners.getDefault() : agent.config.codeDefiner);
    }

    private static void queueChildren(CodeBlock block, Agent agent) {
        block.childrenQueued = true;
        RuntimeConfig config = agent.config;
        if (config.executionMode != MODE_COMPILED || !config.backgroundJit) return;
        JitBackend backend = null;
        for (Object constant : block.constants) {
            if (constant instanceof CodeBlock child && compilable(child)) {
                if (backend == null) backend = backend(agent);
                JitQueue.submit(child, backend, agent);
            }
        }
    }

    private static boolean compilable(CodeBlock block) {
        return block.compiled == null && !block.jitFailed && block.jitTask == null
                && block.strictPcs == null && JvmCompiler.INSTANCE.canCompile(block);
    }

    private static CompiledCode compileNow(CodeBlock block, Agent agent) {
        if (block.strictPcs != null || !JvmCompiler.INSTANCE.canCompile(block)) {
            block.jitFailed = true;
            return null;
        }
        while (true) {
            Object compiled = block.compiled;
            if (compiled != null) return (CompiledCode) compiled;
            if (block.jitFailed) return null;
            JitTask task = (JitTask) block.jitTask;
            if (task == null) {
                JitTask owned = new JitTask(block, backend(agent), agent, true);
                if (JitQueue.attach(owned)) JitQueue.run(List.of(owned));
            } else if (JitQueue.claim(task)) {
                JitQueue.run(List.of(task));
            } else {
                try {
                    while (!task.await(20)) if (agent.interruptRequested) return null;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }
}
