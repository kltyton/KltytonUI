package dev.mooner.neonjs.compiler;

/** Tracks the majority callee of one call site; derived from NeonJS and adapted to Java. */
public final class CallFeedback {
    public CodeBlock target;
    public int count;

    public void record(CodeBlock block) {
        if (target == block) {
            count++;
        } else if (count == 0) {
            target = block;
            count = 1;
        } else {
            count--;
        }
    }
}
