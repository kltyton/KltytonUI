package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.vm.Frame;

/**
 * Entry point implemented by generated JavaScript code.
 * Derived from NeonJS and adapted to Java.
 */
public interface CompiledCode {
    Object run(Frame frame);
}
