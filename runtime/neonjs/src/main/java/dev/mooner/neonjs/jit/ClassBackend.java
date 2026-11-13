package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import java.util.List;

/**
 * Compiles blocks to JVM classes using the selected code definer.
 * Derived from NeonJS and adapted to Java.
 */
public final class ClassBackend implements JitBackend {
    private final CodeDefiner definer;

    public ClassBackend(CodeDefiner definer) {
        this.definer = definer;
    }

    public CodeDefiner getDefiner() {
        return definer;
    }

    @Override
    public List<CompiledCode> compile(List<CodeBlock> blocks) {
        return JvmCompiler.INSTANCE.compileAll(blocks, definer);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClassBackend backend && backend.definer == definer;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(definer);
    }
}
