/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.Identifier;
import dev.mooner.neonjs.parser.Node;
import dev.mooner.neonjs.runtime.GlobalSite;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropSite;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Uninitialized;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;

/** Control-flow context used to compile break / continue / return. */
abstract class Ctl {
    final Ctl parent;

    Ctl(Ctl parent) {
        this.parent = parent;
    }
}

final class LoopCtl extends Ctl {
    final List<String> labels;
    final Label breakLabel;
    final Label continueLabel;
    final boolean isSwitch;

    LoopCtl(Ctl parent, List<String> labels, Label breakLabel, Label continueLabel, boolean isSwitch) {
        super(parent);
        this.labels = labels;
        this.breakLabel = breakLabel;
        this.continueLabel = continueLabel;
        this.isSwitch = isSwitch;
    }
}

final class LabelCtl extends Ctl {
    final List<String> labels;
    final Label breakLabel;

    LabelCtl(Ctl parent, List<String> labels, Label breakLabel) {
        super(parent);
        this.labels = labels;
        this.breakLabel = breakLabel;
    }
}

final class ScopeCtl extends Ctl {
    final Scope scope;

    ScopeCtl(Ctl parent, Scope scope) {
        super(parent);
        this.scope = scope;
    }
}

final class IterCtl extends Ctl {
    final int iterReg;
    final boolean isAsync;

    IterCtl(Ctl parent, int iterReg, boolean isAsync) {
        super(parent);
        this.iterReg = iterReg;
        this.isAsync = isAsync;
    }
}

final class FinallyCtl extends Ctl {
    final int kindReg;
    final int valueReg;
    final Label entry;
    /** Pending jumps through this finally: index -> (target kind, payload). */
    final ArrayList<PendingJump> pending = new ArrayList<>();

    FinallyCtl(Ctl parent, int kindReg, int valueReg, Label entry) {
        super(parent);
        this.kindReg = kindReg;
        this.valueReg = valueReg;
        this.entry = entry;
    }
}

final class PendingJump {
    final int kind;
    final Ctl target;
    final String label;
    final boolean isContinue;

    PendingJump(int kind, Ctl target, String label, boolean isContinue) {
        this.kind = kind;
        this.target = target;
        this.label = label;
        this.isContinue = isContinue;
    }
}

/** Static name used for NamedEvaluation of anonymous functions/classes. */
sealed abstract class FnName permits StaticName, DynamicName {
}

final class StaticName extends FnName {
    final String name;

    StaticName(String name) {
        this.name = name;
    }
}

final class DynamicName extends FnName {
    static final DynamicName INSTANCE = new DynamicName();

    private DynamicName() {
    }
}

/** Shared bytecode emitter state and helpers for expressions and statements. */
abstract class EmitterBase {
    static final Object UNINIT = Uninitialized.INSTANCE;

    final FnInfo fi;
    final Source source;
    final ScopeAnalyzer analyzer;

    // ------------------------------------------------------------------ code buffer
    protected int[] code = new int[256];
    protected int pc = 0;
    protected int depth = 0;
    protected int maxDepth = 0;
    protected final ArrayList<Object> constants = new ArrayList<>();
    private final HashMap<Object, Integer> constIndex = new HashMap<>();
    protected final ArrayList<int[]> handlers = new ArrayList<>();
    protected final ArrayList<Integer> lineTable = new ArrayList<>();
    protected final ArrayList<Integer> callSites = new ArrayList<>();
    private int lastPos = -1;
    protected int nextReg = 0;
    private int maxReg = 0;
    private final ArrayList<Integer> freeRegs = new ArrayList<>();

    /** Current compile-time scope (matches the runtime environment chain). */
    Scope scope;
    Ctl ctl;
    /** > 0 while emitting a class (heritage, computed keys, decorators): strict mode code even in sloppy functions. */
    protected int strictDepth = 0;
    /** Instructions of strict regions in sloppy code (CodeBlock.strictPcs). */
    protected BitSet strictPcs;

    /** Register holding the completion value (script/eval code), or -1. */
    int completionReg = -1;

    EmitterBase(FnInfo fi, Source source, ScopeAnalyzer analyzer) {
        this.fi = fi;
        this.source = source;
        this.analyzer = analyzer;
    }

    boolean isStrict() {
        return fi.strict || strictDepth > 0;
    }

    private void markStrict() {
        if (strictDepth > 0 && !fi.strict) {
            if (strictPcs == null) {
                strictPcs = new BitSet();
            }
            strictPcs.set(pc);
        }
    }

    void initRegs(int n) {
        nextReg = n;
        maxReg = n;
    }

    // ------------------------------------------------------------------ emission

    private void ensure(int n) {
        if (pc + n > code.length) {
            code = java.util.Arrays.copyOf(code, Math.max(code.length * 2, pc + n + 16));
        }
    }

    private void adjust(int delta) {
        depth += delta;
        if (depth < 0) {
            throw new CompileError("stack underflow at pc " + pc + " in "
                    + fi.node.getClass().getSimpleName());
        }
        if (depth > maxDepth) {
            maxDepth = depth;
        }
    }

    void emit(int op) {
        ensure(1);
        markStrict();
        code[pc++] = op;
        adjust(effect(op, 0, 0));
    }

    void emit(int op, int a) {
        ensure(2);
        markStrict();
        code[pc++] = op;
        // Named property / global accesses get their own inline-cache site. Every emitted instruction must get a
        // distinct site: load and store caches are filled under different conditions.
        code[pc++] = switch (op) {
            case Op.GET_PROP, Op.PUT_PROP -> {
                constants.add(new PropSite(constants.get(a)));
                yield constants.size() - 1;
            }
            case Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.STORE_GLOBAL -> {
                constants.add(new GlobalSite((String) constants.get(a)));
                yield constants.size() - 1;
            }
            default -> a;
        };
        adjust(effect(op, a, 0));
    }

    void emit(int op, int a, int b) {
        ensure(3);
        markStrict();
        code[pc++] = op;
        code[pc++] = a;
        code[pc++] = b;
        adjust(effect(op, a, b));
    }

    void emit(int op, int a, int b, int c) {
        ensure(4);
        markStrict();
        code[pc++] = op;
        code[pc++] = a;
        code[pc++] = b;
        code[pc++] = c;
        adjust(effect(op, a, b));
    }

    /** Stack effect of an instruction. */
    private int effect(int op, int a, int b) {
        int e = Op.effects[op];
        if (e != Op.VAR) {
            return e;
        }
        return switch (op) {
            case Op.CALL, Op.TAIL_CALL, Op.CALL_EVAL, Op.SUPER_CALL, Op.COPY_DATA_PROPS_EXCL -> -(a + 1);
            case Op.NEW -> -a;
            case Op.NEW_OBJECT_LITERAL -> 1 - b;
            case Op.MAKE_CLASS -> 2 - ((b & 1) != 0 ? 1 : 0) - ((b & 2) != 0 ? 1 : 0);
            case Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> -b;
            default -> throw new CompileError("no stack effect for " + Op.names[op]);
        };
    }

    void resetDepth(int d) {
        depth = d;
        if (d > maxDepth) {
            maxDepth = d;
        }
    }

    int currentDepth() {
        return depth;
    }

    // ------------------------------------------------------------------ labels & jumps

    Label newLabel() {
        return new Label();
    }

    void emitJump(int op, Label label) {
        emit(op, 0);
        int at = pc - 1;
        if (label.pos >= 0) {
            code[at] = label.pos;
        } else {
            label.fixups.add(at);
        }
        if (label.depth < 0) {
            label.depth = depth;
        }
    }

    void place(Label label) {
        label.pos = pc;
        for (int fixup : label.fixups) {
            code[fixup] = pc;
        }
        label.fixups.clear();
        if (label.depth >= 0) {
            depth = label.depth;
        } else {
            label.depth = depth;
        }
    }

    /** Places a label that is only reached by jumps (after an unconditional transfer). */
    void placeAfterJump(Label label) {
        if (label.depth >= 0) {
            depth = label.depth;
        }
        place(label);
    }

    void emitJumpTable(int reg, List<Label> targets, Label defaultLabel) {
        ensure(4 + targets.size());
        code[pc++] = Op.JUMP_TABLE;
        code[pc++] = reg;
        code[pc++] = targets.size();
        for (Label target : targets) {
            int at = pc++;
            if (target.pos >= 0) {
                code[at] = target.pos;
            } else {
                target.fixups.add(at);
            }
            if (target.depth < 0) {
                target.depth = depth;
            }
        }
        int at = pc++;
        if (defaultLabel.pos >= 0) {
            code[at] = defaultLabel.pos;
        } else {
            defaultLabel.fixups.add(at);
        }
        if (defaultLabel.depth < 0) {
            defaultLabel.depth = depth;
        }
    }

    // ------------------------------------------------------------------ constants & registers

    int constValue(Object value) {
        if (value instanceof CodeBlock || value instanceof ScopeInfo || value instanceof Object[]
                || value instanceof TemplateSite || value instanceof RegExpSite
                || value instanceof DeclInfo) {
            constants.add(value);
            return constants.size() - 1;
        }
        Object key = value instanceof Double ? new DoubleKey((Double) value) : value;
        Integer existing = constIndex.get(key);
        if (existing != null) {
            return existing;
        }
        constants.add(value);
        int index = constants.size() - 1;
        constIndex.put(key, index);
        return index;
    }

    /** Canonical property key constant for a property name. */
    int keyConst(String name) {
        return constValue(PK.fromString(name));
    }

    private static final class DoubleKey {
        private final long bits;

        DoubleKey(double value) {
            this.bits = Double.doubleToRawLongBits(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DoubleKey key && key.bits == bits;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(bits);
        }
    }

    int allocReg() {
        if (!freeRegs.isEmpty()) {
            return freeRegs.remove(freeRegs.size() - 1);
        }
        int reg = nextReg++;
        if (nextReg > maxReg) {
            maxReg = nextReg;
        }
        return reg;
    }

    void freeReg(int reg) {
        freeRegs.add(reg);
    }

    int totalRegs() {
        return maxReg;
    }

    void mark(Node node) {
        int pos = node.start;
        if (pos != lastPos) {
            lastPos = pos;
            lineTable.add(pc);
            lineTable.add(pos);
        }
    }

    /** Records the source range of {@code callee} for the call instruction emitted next. */
    void markCallee(Node callee) {
        int start = callee.start;
        int end = callee.end;
        if (callee.parenthesized) {
            // the node's range leaves out its parentheses: `(0, o.f)()` reads better with them
            String text = source.text;
            int s = start - 1;
            while (s >= 0 && Character.isWhitespace(text.charAt(s))) s--;
            int e = end;
            while (e < text.length() && Character.isWhitespace(text.charAt(e))) e++;
            if (s >= 0 && text.charAt(s) == '(' && e < text.length() && text.charAt(e) == ')') {
                start = s;
                end = e + 1;
            }
        }
        callSites.add(pc);
        callSites.add(start);
        callSites.add(end);
    }

    void addHandler(int start, int end, int handler, int stackDepth) {
        if (end > start) {
            handlers.add(new int[]{start, end, handler, stackDepth});
        }
    }

    // ------------------------------------------------------------------ helpers

    void pushInt(int i) {
        emit(Op.PUSH_INT, i);
    }

    void pushConst(Object value) {
        if (value == null || value == Undefined.INSTANCE) {
            emit(Op.PUSH_UNDEF);
        } else if (value instanceof Boolean bool) {
            emit(bool ? Op.PUSH_TRUE : Op.PUSH_FALSE);
        } else if (value instanceof Double number) {
            int i = number.intValue();
            // PUSH_INT only for the shared boxes of Ops.num (0..1023); other numbers are pre-boxed constants
            if ((double) i == number && !(number == 0.0 && 1.0 / number < 0)
                    && i >= 0 && i < 1024) {
                pushInt(i);
            } else {
                emit(Op.PUSH_CONST, constValue(number));
            }
        } else {
            emit(Op.PUSH_CONST, constValue(value));
        }
    }

    void throwError(int kind, String message) {
        emit(Op.THROW_ERROR, kind, constValue(message));
    }

    // ------------------------------------------------------------------ scopes at runtime

    /** Number of materialized environments between the current scope and {@code target} (exclusive). */
    int hops(Scope target) {
        int n = 0;
        Scope s = scope;
        while (s != null && s != target) {
            if (s.needsEnv) n++;
            s = s.parent;
        }
        if (s == null) {
            throw new CompileError("scope not on chain for hop computation");
        }
        return n;
    }

    void enterScope(Scope s) {
        scope = s;
        if (s.needsEnv) {
            emit(Op.PUSH_SCOPE, constValue(s.info));
            ctl = new ScopeCtl(ctl, s);
        }
        initTdzRegisters(s);
    }

    void initTdzRegisters(Scope s) {
        for (Binding binding : s.bindings.values()) {
            if (!binding.inEnv() && binding.needsTdz() && binding.reg >= 0) {
                emit(Op.PUSH_CONST, constValue(UNINIT));
                emit(Op.STORE_REG, binding.reg);
            }
        }
    }

    void exitScope(Scope s) {
        if (s.needsEnv) {
            emit(Op.POP_SCOPE);
            Ctl current = ctl;
            if (current instanceof ScopeCtl scopeCtl && scopeCtl.scope == s) {
                ctl = current.parent;
            }
        }
        scope = s.parent != null ? s.parent : s;
    }

    // ------------------------------------------------------------------ variable access

    /** Emits a load of a resolved reference. */
    void emitLoadRef(Object ref, String name) {
        emitLoadRef(ref, name, false);
    }

    void emitLoadRef(Object ref, String name, boolean forTypeof) {
        if (ref instanceof LocalRef local) {
            emitLoadBinding(local.binding, local.checkTdz);
        } else if (ref instanceof DynamicRef) {
            emit(forTypeof ? Op.LOAD_NAME_TYPEOF : Op.LOAD_NAME, constValue(name));
        } else if (ref instanceof GlobalRef || ref == null) {
            emit(forTypeof ? Op.LOAD_GLOBAL_TYPEOF : Op.LOAD_GLOBAL, constValue(name));
        } else {
            throw new CompileError("bad ref");
        }
    }

    void emitLoadBinding(Binding binding, boolean checkTdz) {
        if (binding.scope.fn != fi && !binding.inEnv()) {
            throw new CompileError("captured binding " + binding.name + " not in env");
        }
        if (!binding.inEnv()) {
            switch (binding.kind) {
                case THIS:
                    if (!binding.tdz) {
                        emit(Op.LOAD_THIS);
                        return;
                    }
                    break;
                case NEW_TARGET:
                    emit(Op.LOAD_NEW_TARGET);
                    return;
                case HOME:
                    emit(Op.LOAD_HOME);
                    return;
                case FN:
                    emit(Op.LOAD_FUNCTION);
                    return;
                case CALLEE:
                    emit(Op.LOAD_FUNCTION);
                    return;
                default:
                    break;
            }
            if (checkTdz) {
                emit(Op.LOAD_REG_TDZ, binding.reg, constValue(tdzName(binding)));
            } else {
                emit(Op.LOAD_REG, binding.reg);
            }
        } else {
            int h = hops(binding.scope);
            if (binding.kind == BKind.IMPORT) {
                emit(Op.LOAD_IMPORT, h, binding.slot);
            } else if (checkTdz) {
                emit(Op.LOAD_ENV_TDZ, h, binding.slot, constValue(tdzName(binding)));
            } else {
                emit(Op.LOAD_ENV, h, binding.slot);
            }
        }
    }

    private static String tdzName(Binding binding) {
        return binding.kind == BKind.THIS ? "this" : binding.name;
    }

    /** Store to a binding for initialization (no TDZ / const checks). Value is popped. */
    void emitInitBinding(Binding binding) {
        if (!binding.inEnv()) {
            if (binding.reg < 0) {
                emit(Op.POP);
                return;
            }
            emit(Op.STORE_REG, binding.reg);
        } else {
            emit(Op.STORE_ENV, hops(binding.scope), binding.slot);
        }
    }

    /** Emits an assignment to a resolved identifier reference (value popped). */
    void emitStoreRef(Object ref, String name) {
        if (ref instanceof LocalRef local) {
            Binding binding = local.binding;
            if (binding.kind.isConst() || binding.kind == BKind.CALLEE) {
                if (local.checkTdz || binding.needsTdz()) {
                    emitTdzCheck(binding, local.checkTdz);
                }
                if (binding.kind == BKind.CALLEE && !isStrict()) {
                    emit(Op.POP);
                    return;
                }
                emit(Op.POP);
                throwError(0, "Assignment to constant variable.");
                return;
            }
            if (local.checkTdz) {
                emitTdzCheck(binding, true);
            }
            emitInitBinding(binding);
        } else if (ref instanceof DynamicRef) {
            emit(Op.STORE_NAME, constValue(name));
        } else if (ref instanceof GlobalRef || ref == null) {
            emit(Op.STORE_GLOBAL, constValue(name));
        }
    }

    void emitTdzCheck(Binding binding, boolean check) {
        if (!check) {
            return;
        }
        if (!binding.inEnv()) {
            emit(Op.CHECK_REG_TDZ, binding.reg, constValue(tdzName(binding)));
        } else {
            emit(Op.CHECK_ENV_TDZ, hops(binding.scope), binding.slot, constValue(tdzName(binding)));
        }
    }

    /** Initializes a declared binding found by name in the current compile-time scope chain. */
    void emitInitDeclared(Identifier id) {
        Object ref = id.ref;
        if (ref instanceof LocalRef local) {
            emitInitBinding(local.binding);
        } else if (ref instanceof DynamicRef) {
            emit(Op.INIT_NAME, constValue(id.name));
        } else {
            emit(Op.INIT_GLOBAL_LEX, constValue(id.name));
        }
    }
}
