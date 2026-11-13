package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import java.util.List;

/**
 * Compiles a batch of code blocks, returning a null entry for any block that cannot be compiled.
 * Derived from NeonJS and adapted to Java.
 */
public interface JitBackend {
    List<CompiledCode> compile(List<CodeBlock> blocks);
}
