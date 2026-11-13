package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.FunctionKind;
import java.util.BitSet;
import java.util.Set;

/** Bytecode and metadata for one function; derived from NeonJS and adapted to Java. */
public final class CodeBlock {
    public static final int STRICT = 1;
    public static final int ARROW = 2;
    public static final int GENERATOR = 4;
    public static final int ASYNC = 8;
    public static final int CLASS_CTOR = 16;
    public static final int DERIVED = 32;
    public static final int METHOD = 64;
    public static final int USES_THIS = 128;
    public static final int DEFAULT_CTOR = 256;
    public static final int CONSTRUCTOR = 512;
    public static final int TOP_LEVEL = 1024;
    public static final int HAS_EVAL = 2048;
    public static final int NO_JIT = 4096;
    public static final int CTX_NEW_TARGET = 1;
    public static final int CTX_SUPER_PROP = 2;
    public static final int CTX_SUPER_CALL = 4;
    public static final int CTX_FIELD_INIT = 8;

    public final String name;
    public final FunctionKind kind;
    public int flags;
    public int[] code = new int[0];
    public Object[] constants = new Object[0];
    public int[] handlers = new int[0];
    public int numRegs;
    public int maxStack;
    public int length;
    public int formalCount;
    public int[] paramRegs;
    public int[] lineTable = new int[0];
    public int[] callSites = new int[0];
    public Source source;
    public int srcStart;
    public int srcEnd;
    public int completionReg = -1;
    public int[] mappedSlots;
    public CallFeedback[] callFeedback;
    public CodeBlock[] inlineTargets;
    public volatile Object compiled;
    public int invocationCount;
    public volatile boolean jitFailed;
    public volatile Object jitTask;
    public boolean childrenQueued;
    public int evalCtx;
    public Set<String> privateNames;
    public BitSet strictPcs;

    public CodeBlock(String name, FunctionKind kind) {
        this.name = name;
        this.kind = kind;
    }

    public boolean strictAt(int pc) {
        BitSet strict = strictPcs;
        return strict != null && strict.get(pc);
    }

    public boolean isGenerator() {
        return (flags & GENERATOR) != 0;
    }

    public boolean isAsync() {
        return (flags & ASYNC) != 0;
    }

    public boolean isClassConstructor() {
        return (flags & CLASS_CTOR) != 0;
    }

    public boolean isDerived() {
        return (flags & DERIVED) != 0;
    }

    public boolean isConstructor() {
        return (flags & CONSTRUCTOR) != 0;
    }

    public int positionAt(int pc) {
        int[] positions = lineTable;
        int best = -1;
        for (int i = 0; i < positions.length; i += 2) {
            if (positions[i] > pc) break;
            best = positions[i + 1];
        }
        return best;
    }

    public String sourceText() {
        Source current = source;
        if (current == null || srcEnd <= srcStart || srcEnd > current.text.length()) return null;
        return current.text.substring(srcStart, srcEnd);
    }

    public String disassemble() {
        StringBuilder out = new StringBuilder();
        out.append("function ").append(name).append(" regs=").append(numRegs)
                .append(" stack=").append(maxStack).append('\n');
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc];
            out.append(String.format("%5d  %-20s", pc, Op.names[op]));
            int size = Op.INSTANCE.length(code, pc);
            for (int i = 1; i < size; i++) out.append(' ').append(code[pc + i]);
            if (op == Op.PUSH_CONST || op == Op.GET_PROP || op == Op.PUT_PROP || op == Op.LOAD_GLOBAL
                    || op == Op.STORE_GLOBAL || op == Op.LOAD_NAME) {
                Object constant = constants[code[pc + 1]];
                if (constant instanceof CodeBlock function) {
                    out.append("   ; <fn ").append(function.name).append('>');
                } else {
                    String text = String.valueOf(constant);
                    out.append("   ; ").append(text, 0, Math.min(40, text.length()));
                }
            }
            out.append('\n');
            pc += size;
        }
        for (int i = 0; i < handlers.length; i += 4) {
            out.append("handler [").append(handlers[i]).append(", ").append(handlers[i + 1])
                    .append(") -> ").append(handlers[i + 2]).append(" depth ").append(handlers[i + 3]).append('\n');
        }
        return out.toString();
    }
}
