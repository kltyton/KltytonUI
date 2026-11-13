package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CallFeedback;
import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Op;
import java.util.ArrayList;
import java.util.List;

/**
 * Selects guarded, nonrecursive call sites for JIT inlining.
 * Derived from NeonJS and adapted to Java.
 */
public final class Inlining {
    public static final Inlining INSTANCE = new Inlining();
    public static final boolean ENABLED = !"false".equals(System.getProperty("neonjs.jit.inline"));
    public static final int MAX_CALLEE_CODE = 96;
    public static final int MAX_SITES = 8;
    public static final int MIN_COUNT = Integer.getInteger("neonjs.jit.inlineMinCount", 16);

    private static final boolean[] ALLOWED = new boolean[256];

    static {
        int[] allowed = {
            Op.NOP, Op.PUSH_UNDEF, Op.PUSH_NULL, Op.PUSH_TRUE, Op.PUSH_FALSE, Op.PUSH_CONST, Op.PUSH_INT,
            Op.POP, Op.DUP, Op.DUP2, Op.SWAP, Op.ROT3,
            Op.LOAD_REG, Op.STORE_REG, Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ,
            Op.LOAD_ENV, Op.STORE_ENV, Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ,
            Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.STORE_GLOBAL,
            Op.GET_PROP, Op.PUT_PROP, Op.GET_ELEM, Op.PUT_ELEM,
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.SHL, Op.SAR, Op.SHR, Op.BAND, Op.BOR, Op.BXOR,
            Op.EQ, Op.NE, Op.SEQ, Op.SNE, Op.LT, Op.GT, Op.LE, Op.GE, Op.INSTANCEOF, Op.IN,
            Op.NEG, Op.TO_NUMBER, Op.NOT, Op.BNOT, Op.TYPEOF, Op.TO_NUMERIC, Op.INC, Op.DEC, Op.TO_STRING, Op.CONCAT,
            Op.JUMP, Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE, Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH,
            Op.JUMP_IF_UNDEFINED, Op.JUMP_IF_NOT_UNDEFINED,
            Op.RETURN, Op.CALL, Op.NEW, Op.THROW, Op.THROW_ERROR,
            Op.NEW_OBJECT, Op.NEW_ARRAY, Op.ARRAY_PUSH, Op.ARRAY_HOLE, Op.NEW_OBJECT_LITERAL
        };
        for (int op : allowed) ALLOWED[op] = true;
    }

    private Inlining() {
    }

    public boolean eligible(CodeBlock caller, CodeBlock callee) {
        int flags = callee.flags;
        if ((flags & (CodeBlock.GENERATOR | CodeBlock.ASYNC | CodeBlock.CLASS_CTOR
                | CodeBlock.DERIVED | CodeBlock.NO_JIT)) != 0) return false;
        if ((flags & CodeBlock.STRICT) != (caller.flags & CodeBlock.STRICT)) return false;
        if (callee.strictPcs != null || callee.handlers.length != 0) return false;
        int[] code = callee.code;
        if (code.length > MAX_CALLEE_CODE) return false;
        if (callee.paramRegs != null) {
            for (int register : callee.paramRegs) if (register < 0) return false;
        }
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc];
            if (op < 0 || op >= ALLOWED.length || !ALLOWED[op]) return false;
            pc += Op.INSTANCE.length(code, pc);
        }
        return true;
    }

    public List<Site> plan(CodeBlock block) {
        if (!ENABLED || block.callFeedback == null) return List.of();
        CallFeedback[] feedback = block.callFeedback;
        int[] code = block.code;
        List<Site> sites = new ArrayList<>();
        int pc = 0;
        while (pc < code.length && sites.size() < MAX_SITES) {
            if (code[pc] == Op.CALL && pc < feedback.length) {
                CallFeedback entry = feedback[pc];
                CodeBlock target = entry == null ? null : entry.target;
                if (target != null && entry.count >= MIN_COUNT && eligible(block, target)) {
                    sites.add(new Site(pc, target));
                }
            }
            pc += Op.INSTANCE.length(code, pc);
        }
        return sites;
    }

    public record Site(int pc, CodeBlock target) {
    }
}
