/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.Op;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Value kinds of the type analysis that lets {@link JvmCompiler} keep numbers and booleans unboxed.
 *
 * <p>Soundness rules (docs/ARCHITECTURE.md, section JIT, "Unboxed numbers"):
 * <ul>
 *   <li>NUM is a JS number held as a JVM {@code double}; INT a JS number that is an int32 (never -0) held as a JVM
 *       {@code int}; BOOL a boolean held as a JVM {@code int}; ANY anything held as an Object. INT is a NUM: where
 *       they meet, the int is widened.</li>
 *   <li>A value is NUM only when every way of producing it yields a number: number literals, constants that are
 *       numbers (their kinds are part of the class identity, as classes are shared by code blocks with equal code),
 *       and operators that return numbers by definition.</li>
 *   <li>A value is INT only when it is an int32 by definition: integer literals and constants that are int32s (not
 *       -0), and the results of {@code &}, {@code |}, {@code ^}, {@code <<}, {@code >>} and {@code ~}.</li>
 *   <li>Registers are invisible outside their function (captured, eval-visible and mapped-argument bindings live in
 *       environment slots), so only stores and loads in the function itself define them.</li>
 *   <li>Nothing else is inferred, and no check (bounds, TDZ, interrupt, stack depth) is ever removed because of a
 *       type.</li>
 * </ul>
 */
final class JT {
    static final byte ANY = 0;
    static final byte NUM = 1;
    static final byte BOOL = 2;
    /** Registers only: still the initial {@code undefined}. */
    static final byte UNDEF = 3;
    static final byte INT = 4;

    static final int GENERIC = 0;
    /** Both operands NUM: inline JVM arithmetic or comparison. */
    static final int NN = 1;
    /** Left NUM, right ANY: a helper taking (double, Object). */
    static final int NA = 2;
    /** Left ANY, right NUM: a helper taking (Object, double). */
    static final int AN = 3;

    private JT() {
    }

    static boolean isNum(byte kind) {
        return kind == NUM || kind == INT;
    }

    static byte join(byte a, byte b) {
        return a == b ? a : isNum(a) && isNum(b) ? NUM : ANY;
    }

    static boolean isArith(int op) {
        return op == Op.SUB || op == Op.MUL || op == Op.DIV || op == Op.MOD || op == Op.EXP;
    }

    static boolean isBitwise(int op) {
        return op == Op.BAND || op == Op.BOR || op == Op.BXOR
                || op == Op.SHL || op == Op.SAR || op == Op.SHR;
    }

    static boolean isCompare(int op) {
        return op == Op.LT || op == Op.GT || op == Op.LE || op == Op.GE
                || op == Op.EQ || op == Op.NE || op == Op.SEQ || op == Op.SNE;
    }

    /**
     * How the binary operator {@code op} is compiled for operand kinds {@code a} (left) and {@code b} (right): N
     * stands for both number kinds; BOOL counts as ANY.
     */
    static int binaryClass(int op, byte a, byte b) {
        if (op != Op.ADD && !isArith(op) && !isBitwise(op) && !isCompare(op)) return GENERIC;
        boolean an = isNum(a);
        boolean bn = isNum(b);
        if (an && bn) return NN;
        if (an) return NA;
        if (bn) return AN;
        return GENERIC;
    }

    /**
     * For instructions compiled as Object code whose code consumes only the top operand stack values: how many (values
     * below them may stay unboxed). -1 for the others, before which every stack value is boxed.
     */
    static int pops(int op, int a) {
        switch (op) {
            case Op.PUSH_UNDEF:
            case Op.PUSH_NULL:
            case Op.PUSH_CONST:
            case Op.LOAD_THIS:
            case Op.LOAD_FUNCTION:
            case Op.LOAD_NEW_TARGET:
            case Op.LOAD_HOME:
            case Op.LOAD_ARG:
            case Op.LOAD_ENV:
            case Op.LOAD_ENV_TDZ:
            case Op.CHECK_ENV_TDZ:
            case Op.LOAD_IMPORT:
            case Op.LOAD_NAME:
            case Op.LOAD_NAME_TYPEOF:
            case Op.LOAD_GLOBAL:
            case Op.LOAD_GLOBAL_TYPEOF:
            case Op.LOAD_NAME_CALL:
            case Op.GET_ENV:
            case Op.CREATE_ARGUMENTS:
            case Op.CREATE_REST:
            case Op.NEW_OBJECT:
            case Op.NEW_ARRAY:
            case Op.MAKE_CLOSURE:
            case Op.TEMPLATE_OBJECT:
            case Op.NEW_REGEXP:
            case Op.IMPORT_META:
            case Op.THROW_ERROR:
            case Op.LOAD_REG_TDZ:
            case Op.CHECK_REG_TDZ:
                return 0;
            case Op.STORE_ENV:
            case Op.STORE_GLOBAL:
            case Op.STORE_NAME:
            case Op.STORE_NAME_VAR:
            case Op.INIT_NAME:
            case Op.INIT_GLOBAL_LEX:
            case Op.SET_ENV:
            case Op.PUSH_WITH:
            case Op.GET_PROP:
            case Op.TO_STRING:
            case Op.TYPEOF:
            case Op.TO_OBJECT:
            case Op.REQUIRE_COERCIBLE:
            case Op.TO_PROPERTY_KEY:
            case Op.RETURN:
            case Op.THROW:
            case Op.DELETE_PROP:
            case Op.GET_ITERATOR:
            case Op.CHECK_OBJECT:
            case Op.GET_PROTO_OF:
            case Op.GET_SUPER_BASE:
            case Op.NEG:
            case Op.BNOT:
            case Op.INC:
            case Op.DEC:
            case Op.TO_NUMERIC:
                return 1;
            case Op.PUT_PROP:
            case Op.GET_ELEM:
            case Op.CONCAT:
            case Op.ARRAY_PUSH:
            case Op.IN:
            case Op.INSTANCEOF:
            case Op.DELETE_ELEM:
            case Op.GET_PRIVATE:
            case Op.HAS_PRIVATE:
            case Op.ADD:
            case Op.SUB:
            case Op.MUL:
            case Op.DIV:
            case Op.MOD:
            case Op.EXP:
            case Op.SHL:
            case Op.SAR:
            case Op.SHR:
            case Op.BAND:
            case Op.BOR:
            case Op.BXOR:
            case Op.EQ:
            case Op.NE:
            case Op.SEQ:
            case Op.SNE:
            case Op.LT:
            case Op.GT:
            case Op.LE:
            case Op.GE:
                return 2;
            case Op.PUT_ELEM:
            case Op.PUT_PRIVATE:
            case Op.CALL_SPREAD:
                return 3;
            case Op.CALL:
            case Op.TAIL_CALL:
                return a + 2;
            case Op.NEW:
                return a + 1;
            default:
                return -1;
        }
    }

    /** Kind of the result of binary operator {@code op} compiled as {@code cls}; {@code intKind} is INT, or NUM. */
    static byte binaryResult(int op, int cls, byte intKind) {
        if (cls == GENERIC) return ANY;
        if (isCompare(op)) return BOOL;
        if (op == Op.ADD) return cls == NN ? NUM : ANY; // number + string is a concatenation
        if (isBitwise(op) && op != Op.SHR) return intKind;
        return NUM;
    }
}

/**
 * Forward data-flow analysis of a code block for {@link JvmCompiler}: the kind of every operand stack slot at every
 * instruction, the kind of every register, and which registers are kept as JVM {@code double} or {@code int} locals.
 * Instructions that are not reachable get no state (the generator emits nothing for them). Without {@code ints},
 * nothing is INT (integers are NUM, as before INT existed).
 */
final class TypeAnalysis {
    static final int[] PERM_DUP2 = {0, 1, 0, 1};
    static final int[] PERM_DUP3 = {0, 1, 2, 0, 1, 2};
    static final int[] PERM_SWAP = {1, 0};
    static final int[] PERM_ROT3 = {2, 0, 1};
    static final int[] PERM_ROT4 = {3, 0, 1, 2};

    private final JitInput input;
    final boolean ints;
    final boolean elems;
    private final byte[] argKinds;
    private final int[] code;
    /** Kind of the values that are int32s by definition: INT, or NUM without {@code ints}. */
    final byte intKind;
    /** Kinds of the operand stack before the instruction at each pc (null: unreachable). */
    final byte[][] stackIn;
    private final byte[][] regsIn;
    private final byte[] readKind;
    private final boolean[] forceAny;
    /** How each register is held: NUM a JVM {@code double} local, INT an {@code int} local, ANY an Object local. */
    final byte[] regStorage;

    TypeAnalysis(JitInput input, boolean ints, boolean elems) {
        this(input, ints, elems, null);
    }

    TypeAnalysis(JitInput input, boolean ints, boolean elems, byte[] argKinds) {
        this.input = input;
        this.ints = ints;
        this.elems = elems;
        this.argKinds = argKinds;
        this.code = input.code;
        this.intKind = ints ? JT.INT : JT.NUM;
        this.stackIn = new byte[code.length][];
        this.regsIn = new byte[code.length][];
        this.readKind = new byte[input.numRegs];
        Arrays.fill(readKind, (byte) -1);
        this.forceAny = new boolean[input.numRegs];
        this.regStorage = new byte[input.numRegs];
    }

    boolean reachable(int pc) {
        return pc < code.length && pc >= 0 && stackIn[pc] != null;
    }

    /** Kind of register {@code r} before the instruction at {@code pc} (reachable). */
    byte regKind(int pc, int r) {
        return regsIn[pc][r];
    }

    /** Kind of constant {@code k} when pushed. */
    byte constKind(int k) {
        switch (input.constKinds[k]) {
            case JitInput.CONST_INT:
                return intKind;
            case JitInput.CONST_NUMBER:
                return JT.NUM;
            default:
                return JT.ANY;
        }
    }

    /** Kind of the result of NEG, BNOT, INC, DEC or TO_NUMERIC on a value of kind {@code t}. */
    byte unaryResult(int op, byte t) {
        if (!JT.isNum(t)) return JT.ANY;
        if (op == Op.BNOT) return intKind;
        if (op == Op.TO_NUMERIC) return t;
        return JT.NUM; // -0 is -0, and ++ / -- can leave the int32 range
    }

    /**
     * Kind of the result of PUT_ELEM, which is the value stored: unboxed (the value's kind) when the key and the value
     * are numbers, unless {@code elems} is off.
     */
    byte putElemResult(byte key, byte value) {
        return elems && JT.isNum(key) && JT.isNum(value) ? value : JT.ANY;
    }

    /**
     * The effect on the stack kinds {@code s} of the instructions that neither read nor write registers (but LOAD_REG,
     * given the register's kind {@code regKind}) nor jump. False for every other instruction.
     */
    boolean stackStep(int op, int a, KindStack s, byte regKind) {
        switch (op) {
            case Op.PUSH_INT:
                s.push(intKind);
                break;
            case Op.PUSH_CONST:
                s.push(constKind(a));
                break;
            case Op.LOAD_REG:
                s.push(JT.isNum(regKind) ? regKind : JT.ANY);
                break;
            case Op.POP:
                s.pop();
                break;
            case Op.ADD:
            case Op.SUB:
            case Op.MUL:
            case Op.DIV:
            case Op.MOD:
            case Op.EXP:
            case Op.BAND:
            case Op.BOR:
            case Op.BXOR:
            case Op.SHL:
            case Op.SAR:
            case Op.SHR:
            case Op.LT:
            case Op.GT:
            case Op.LE:
            case Op.GE:
            case Op.EQ:
            case Op.NE:
            case Op.SEQ:
            case Op.SNE: {
                byte rb = s.pop();
                byte ra = s.pop();
                s.push(JT.binaryResult(op, JT.binaryClass(op, ra, rb), intKind));
                break;
            }
            case Op.NEG:
            case Op.BNOT:
            case Op.INC:
            case Op.DEC:
            case Op.TO_NUMERIC:
                s.push(unaryResult(op, s.pop()));
                break;
            case Op.TO_NUMBER:
                s.push(toNumberResult(s.pop())); // unary plus: a number, or a TypeError for BigInt
                break;
            case Op.PUT_ELEM: {
                byte v = s.pop();
                byte k = s.pop();
                s.pop();
                s.push(putElemResult(k, v));
                break;
            }
            default:
                return false;
        }
        return true;
    }

    /** Kind of the result of TO_NUMBER (unary plus) on a value of kind {@code t}. */
    byte toNumberResult(byte t) {
        if (t == JT.INT) return JT.INT;
        if (t == JT.BOOL) return intKind;
        return JT.NUM;
    }

    TypeAnalysis run() {
        // A handler starts with just the exception on the stack, as in the JVM, which empties the operand stack when it
        // catches; handlers whose code expects values below it (destructuring's iterator close) only throw. Code that
        // used such a value or merged back would be rejected below (underflow, or depths differing at a merge).
        byte[] entryRegs = new byte[input.numRegs];
        Arrays.fill(entryRegs, JT.UNDEF);
        if (input.paramRegs != null) {
            for (int i = 0; i < input.paramRegs.length; i++) {
                int r = input.paramRegs[i];
                if (r >= 0) {
                    entryRegs[r] = argKinds == null ? JT.ANY
                            : i < argKinds.length ? argKinds[i] : JT.UNDEF;
                }
            }
        }
        ArrayDeque<Integer> work = new ArrayDeque<>();
        merge(0, entryRegs, new byte[0], work);
        while (!work.isEmpty()) {
            int pc = work.removeFirst();
            step(pc, regsIn[pc].clone(), stackIn[pc], work);
        }
        for (int r = 0; r < input.numRegs; r++) {
            regStorage[r] = forceAny[r] || !JT.isNum(readKind[r]) ? JT.ANY : readKind[r];
        }
        return this;
    }

    private void merge(int pc, byte[] regs, byte[] stack, ArrayDeque<Integer> work) {
        if (pc >= code.length) throw new JitBailout("control flow leaves the code");
        byte[] old = stackIn[pc];
        if (old == null) {
            stackIn[pc] = stack.clone();
            regsIn[pc] = regs.clone();
            work.addLast(pc);
            return;
        }
        if (old.length != stack.length) throw new JitBailout("operand stack depth differs at " + pc);
        boolean changed = false;
        for (int k = 0; k < old.length; k++) {
            byte joined = JT.join(old[k], stack[k]);
            if (joined != old[k]) {
                old[k] = joined;
                changed = true;
            }
        }
        byte[] oldRegs = regsIn[pc];
        for (int k = 0; k < oldRegs.length; k++) {
            byte joined = JT.join(oldRegs[k], regs[k]);
            if (joined != oldRegs[k]) {
                oldRegs[k] = joined;
                changed = true;
            }
        }
        if (changed) work.addLast(pc);
    }

    private void read(int r, byte kind) {
        byte k = JT.isNum(kind) ? kind : JT.ANY;
        readKind[r] = readKind[r] < 0 ? k : JT.join(readKind[r], k);
    }

    private void step(int pc, byte[] regs, byte[] stackInPc, ArrayDeque<Integer> work) {
        // exception edges: any instruction in a protected range may throw with the registers it starts with
        int[] h = input.handlers;
        for (int i = 0; i < h.length; i += 4) {
            if (pc >= h[i] && pc < h[i + 1]) merge(h[i + 2], regs, new byte[] {JT.ANY}, work);
        }
        int op = code[pc];
        int len = Op.INSTANCE.length(code, pc);
        int a = Op.operands[op] >= 1 ? code[pc + 1] : 0;
        int b = Op.operands[op] >= 2 ? code[pc + 2] : 0;
        KindStack s = new KindStack();
        s.set(stackInPc);
        switch (op) {
            case Op.PUSH_TRUE:
            case Op.PUSH_FALSE:
                s.push(JT.BOOL);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.LOAD_REG:
                read(a, regs[a]);
                stackStep(op, a, s, regs[a]);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.STORE_REG: {
                byte k = s.pop();
                regs[a] = JT.isNum(k) ? k : JT.ANY;
                merge(pc + len, regs, s.toArray(), work);
                break;
            }
            case Op.DUP:
                s.push(s.peek(0));
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.DUP2:
                s.permute(2, PERM_DUP2);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.DUP3:
                s.permute(3, PERM_DUP3);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.SWAP:
                s.permute(2, PERM_SWAP);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.ROT3:
                s.permute(3, PERM_ROT3);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.ROT4:
                s.permute(4, PERM_ROT4);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.PUSH_INT:
            case Op.PUSH_CONST:
            case Op.POP:
            case Op.ADD:
            case Op.SUB:
            case Op.MUL:
            case Op.DIV:
            case Op.MOD:
            case Op.EXP:
            case Op.BAND:
            case Op.BOR:
            case Op.BXOR:
            case Op.SHL:
            case Op.SAR:
            case Op.SHR:
            case Op.LT:
            case Op.GT:
            case Op.LE:
            case Op.GE:
            case Op.EQ:
            case Op.NE:
            case Op.SEQ:
            case Op.SNE:
            case Op.NEG:
            case Op.BNOT:
            case Op.INC:
            case Op.DEC:
            case Op.TO_NUMERIC:
            case Op.TO_NUMBER:
            case Op.PUT_ELEM:
                stackStep(op, a, s, JT.ANY);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.NOT:
                s.pop();
                s.push(JT.BOOL);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.JUMP_IF_TRUE:
            case Op.JUMP_IF_FALSE:
                s.pop();
                merge(pc + len, regs, s.toArray(), work);
                merge(a, regs, s.toArray(), work);
                break;
            case Op.JUMP:
                merge(a, regs, s.toArray(), work);
                break;
            case Op.GET_ELEM:
                s.pop();
                s.pop();
                s.push(JT.ANY);
                merge(pc + len, regs, s.toArray(), work);
                break;
            case Op.RETURN:
            case Op.THROW:
            case Op.THROW_ERROR:
                break;
            default:
                generic(pc, op, len, a, b, regs, s, work);
                break;
        }
    }

    /** Any other instruction: compiled as before, after boxing every stack slot; its results are ANY. */
    private void generic(int pc, int op, int len, int a, int b, byte[] regs, KindStack s,
                         ArrayDeque<Integer> work) {
        switch (op) {
            case Op.LOAD_REG_TDZ:
            case Op.CHECK_REG_TDZ:
            case Op.ITER_STEP:
            case Op.ITER_STEP_U:
            case Op.ITER_REST:
            case Op.ITER_CLOSE:
            case Op.ITER_CLOSE_THROW:
            case Op.FOR_IN_NEXT:
                read(a, JT.ANY);
                forceAny[a] = true;
                break;
            case Op.INIT_THIS_REG:
                read(a, JT.ANY);
                forceAny[a] = true;
                regs[a] = JT.ANY;
                break;
            case Op.MAKE_METHOD:
                read(b, JT.ANY);
                forceAny[b] = true;
                break;
            case Op.JUMP_TABLE: {
                int r = code[pc + 1];
                read(r, JT.ANY);
                forceAny[r] = true;
                break;
            }
            default:
                break;
        }
        int pops = JT.pops(op, a);
        if (pops >= 0) {
            // only the top values are consumed: the kinds below are kept
            if (pops > s.size) throw new JitBailout("operand stack underflow at " + pc);
            int pushes = pops + effect(op, a, b);
            for (int i = 0; i < pops; i++) s.pop();
            for (int i = 0; i < pushes; i++) s.push(JT.ANY);
            if (op != Op.RETURN && op != Op.THROW && op != Op.THROW_ERROR) {
                merge(pc + len, regs, s.toArray(), work);
            }
            return;
        }
        int before = s.size;
        int depth = before + effect(op, a, b);
        if (depth < 0) throw new JitBailout("operand stack underflow at " + pc);
        byte[] all = new byte[depth];
        if (op == Op.JUMP_TABLE) {
            int n = code[pc + 2];
            for (int t = 0; t <= n; t++) merge(code[pc + 3 + t], regs, all, work);
        } else if (op == Op.ITER_STEP || op == Op.FOR_IN_NEXT) {
            // the value is pushed on the fall-through edge only
            merge(pc + len, regs, all, work);
            merge(code[pc + len - 1], regs, new byte[before], work);
        } else if (Op.INSTANCE.isJump(op)) {
            merge(pc + len, regs, all, work);
            merge(code[pc + len - 1], regs, all, work);
        } else {
            merge(pc + len, regs, all, work);
        }
    }

    /** Stack effect of an instruction (the emitter's rule, EmitterBase.effect). */
    private int effect(int op, int a, int b) {
        int e = Op.effects[op];
        if (e != Op.VAR) return e;
        switch (op) {
            case Op.CALL:
            case Op.TAIL_CALL:
            case Op.CALL_EVAL:
            case Op.SUPER_CALL:
            case Op.COPY_DATA_PROPS_EXCL:
                return -(a + 1);
            case Op.NEW:
                return -a;
            case Op.NEW_OBJECT_LITERAL:
                return 1 - b;
            case Op.MAKE_CLASS:
                return 2 - ((b & 1) != 0 ? 1 : 0) - ((b & 2) != 0 ? 1 : 0);
            case Op.DECLARE_GLOBALS:
            case Op.DECLARE_EVAL:
                return -b;
            default:
                throw new JitBailout("no stack effect for " + Op.names[op]);
        }
    }
}

/** A stack of value kinds (the operand stack of the analysis and of the code generator). */
final class KindStack {
    private byte[] a = new byte[16];
    int size;

    private void ensure(int n) {
        if (n > a.length) a = Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    void set(byte[] kinds) {
        ensure(kinds.length);
        System.arraycopy(kinds, 0, a, 0, kinds.length);
        size = kinds.length;
    }

    void push(byte b) {
        ensure(size + 1);
        a[size++] = b;
    }

    byte pop() {
        if (size == 0) throw new JitBailout("operand stack underflow");
        return a[--size];
    }

    byte peek(int depth) {
        if (depth >= size) throw new JitBailout("operand stack underflow");
        return a[size - 1 - depth];
    }

    byte get(int i) {
        return a[i];
    }

    void setKind(int i, byte k) {
        a[i] = k;
    }

    /** Replaces the top {@code n} kinds by {@code order} (indices into those n, 0 = deepest). */
    void permute(int n, int[] order) {
        if (n > size) throw new JitBailout("operand stack underflow");
        byte[] top = Arrays.copyOfRange(a, size - n, size);
        size -= n;
        for (int k : order) push(top[k]);
    }

    byte[] toArray() {
        return Arrays.copyOf(a, size);
    }

    boolean matches(byte[] kinds) {
        if (kinds.length != size) return false;
        for (int i = 0; i < size; i++) if (a[i] != kinds[i]) return false;
        return true;
    }

    boolean allAny(int n) {
        for (int i = size - n; i < size; i++) if (a[i] != JT.ANY) return false;
        return true;
    }

    void setAllAny(int depth) {
        ensure(depth);
        Arrays.fill(a, 0, depth, JT.ANY);
        size = depth;
    }
}
