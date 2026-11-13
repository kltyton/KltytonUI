/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.AssignmentPattern;
import dev.mooner.neonjs.parser.BlockStatement;
import dev.mooner.neonjs.parser.BreakStatement;
import dev.mooner.neonjs.parser.CatchClause;
import dev.mooner.neonjs.parser.ClassDeclaration;
import dev.mooner.neonjs.parser.ClassNode;
import dev.mooner.neonjs.parser.ContinueStatement;
import dev.mooner.neonjs.parser.DebuggerStatement;
import dev.mooner.neonjs.parser.DoWhileStatement;
import dev.mooner.neonjs.parser.EmptyStatement;
import dev.mooner.neonjs.parser.ExportAllDeclaration;
import dev.mooner.neonjs.parser.ExportDefaultDeclaration;
import dev.mooner.neonjs.parser.ExportNamedDeclaration;
import dev.mooner.neonjs.parser.ExpressionStatement;
import dev.mooner.neonjs.parser.ForInStatement;
import dev.mooner.neonjs.parser.ForOfStatement;
import dev.mooner.neonjs.parser.ForStatement;
import dev.mooner.neonjs.parser.FunctionDeclaration;
import dev.mooner.neonjs.parser.FunctionKind;
import dev.mooner.neonjs.parser.FunctionNode;
import dev.mooner.neonjs.parser.Identifier;
import dev.mooner.neonjs.parser.IfStatement;
import dev.mooner.neonjs.parser.ImportDeclaration;
import dev.mooner.neonjs.parser.LabeledStatement;
import dev.mooner.neonjs.parser.Node;
import dev.mooner.neonjs.parser.Program;
import dev.mooner.neonjs.parser.RestElement;
import dev.mooner.neonjs.parser.ReturnStatement;
import dev.mooner.neonjs.parser.SwitchCase;
import dev.mooner.neonjs.parser.SwitchStatement;
import dev.mooner.neonjs.parser.ThrowStatement;
import dev.mooner.neonjs.parser.TryStatement;
import dev.mooner.neonjs.parser.VarKind;
import dev.mooner.neonjs.parser.VariableDeclaration;
import dev.mooner.neonjs.parser.VariableDeclarator;
import dev.mooner.neonjs.parser.WhileStatement;
import dev.mooner.neonjs.parser.WithStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Statement, control-flow, function and program emitter. */
final class Emitter extends ExprEmitter {

    private int finallyKinds = 2;

    Emitter(FnInfo fi, Source source, ScopeAnalyzer analyzer) {
        super(fi, source, analyzer);
    }

    @Override
    public CodeBlock compileNested(FunctionNode fn, String name, boolean fieldKeyDynamic) {
        Emitter sub = new Emitter(((Scope) fn.scope).fn, source, analyzer);
        return sub.compileFunction(fn, name, fieldKeyDynamic);
    }

    @Override
    public CodeBlock defaultConstructor(ClassNode cls, String name) {
        CodeBlock cb = new CodeBlock(name,
                cls.superClass != null ? FunctionKind.DERIVED_CONSTRUCTOR : FunctionKind.CLASS_CONSTRUCTOR);
        cb.flags = CodeBlock.STRICT | CodeBlock.CLASS_CTOR | CodeBlock.DEFAULT_CTOR | CodeBlock.CONSTRUCTOR
                | CodeBlock.METHOD | (cls.superClass != null ? CodeBlock.DERIVED : 0);
        cb.code = new int[]{Op.PUSH_UNDEF, Op.RETURN};
        cb.maxStack = 1;
        cb.source = source;
        cb.srcStart = cls.srcStart;
        cb.srcEnd = cls.srcEnd;
        return cb;
    }

    // ================================================================== finishing

    private void finishBlock(CodeBlock cb) {
        cb.code = Arrays.copyOf(code, pc);
        cb.constants = constants.toArray();
        int[] hs = new int[handlers.size() * 4];
        // inner (later-added for nested?) handlers: sort by range size so innermost first
        ArrayList<int[]> sorted = new ArrayList<>(handlers);
        sorted.sort((a, b) -> {
            int bySize = Integer.compare(a[1] - a[0], b[1] - b[0]);
            if (bySize != 0) {
                return bySize;
            }
            return Integer.compare(a[0], b[0]);
        });
        for (int i = 0; i < sorted.size(); i++) {
            System.arraycopy(sorted.get(i), 0, hs, i * 4, 4);
        }
        cb.handlers = hs;
        cb.numRegs = totalRegs();
        cb.maxStack = maxDepth + 2;
        cb.lineTable = toIntArray(lineTable);
        cb.callSites = toIntArray(callSites);
        if (cb.source == null) {
            cb.source = source;
        }
        cb.completionReg = completionReg;
        // only regions with an instruction that behaves differently in strict code matter (and keep the block from
        // being JIT-compiled): a plain `class C {}` in sloppy code needs none
        if (strictPcs != null) {
            for (int i = strictPcs.nextSetBit(0); i >= 0; i = strictPcs.nextSetBit(i + 1)) {
                if (isStrictSensitive(code[i])) {
                    cb.strictPcs = strictPcs;
                    break;
                }
            }
        }
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    /** Instructions whose semantics depend on the strictness of the code (the `strict` argument at run time). */
    private boolean isStrictSensitive(int op) {
        switch (op) {
            case Op.PUT_PROP, Op.PUT_ELEM, Op.STORE_GLOBAL, Op.LOAD_NAME, Op.LOAD_NAME_TYPEOF,
                    Op.LOAD_NAME_CALL, Op.STORE_NAME, Op.DELETE_PROP, Op.DELETE_ELEM, Op.PUT_SUPER,
                    Op.RESOLVE_NAME, Op.GET_REF, Op.PUT_REF -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ================================================================== programs

    CodeBlock compileProgram(Program prog, CodeMode mode) {
        FunctionKind kind = FunctionKind.NORMAL;
        CodeBlock cb = new CodeBlock(mode == CodeMode.SCRIPT ? "<script>" : "<eval>", kind);
        cb.flags = CodeBlock.TOP_LEVEL | (prog.strict ? CodeBlock.STRICT : 0);
        cb.source = source;
        cb.srcStart = 0;
        cb.srcEnd = prog.end;
        initRegs(fi.numRegs);
        Scope root = (Scope) prog.scope;
        scope = root;
        completionReg = allocReg();
        emit(Op.PUSH_UNDEF);
        emit(Op.STORE_REG, completionReg);
        if (mode == CodeMode.SCRIPT) {
            declareGlobals(prog, false);
            enterTopScope(root);
        } else if (mode == CodeMode.EVAL_DIRECT || mode == CodeMode.EVAL_INDIRECT) {
            if (!prog.strict) {
                enterTopScope(root);
                declareGlobals(prog, true);
            } else {
                enterTopScope(root);
                hoistFunctions(prog.body, root);
            }
        }
        for (Node st : prog.body) {
            stmt(st);
        }
        emit(Op.LOAD_REG, completionReg);
        emit(Op.RETURN);
        finishBlock(cb);
        return cb;
    }

    /** Module function hoisting, run during InitializeEnvironment (frame env = module env). */
    CodeBlock compileModuleInit(Program prog) {
        CodeBlock cb = new CodeBlock("<module-init>", FunctionKind.NORMAL);
        cb.flags = CodeBlock.TOP_LEVEL | CodeBlock.STRICT;
        cb.source = source;
        initRegs(fi.numRegs);
        scope = (Scope) prog.scope;
        hoistFunctions(prog.body, scope);
        emit(Op.PUSH_UNDEF);
        emit(Op.RETURN);
        finishBlock(cb);
        return cb;
    }

    /** Module body; async when the module uses top-level await. */
    CodeBlock compileModuleBody(Program prog, boolean async) {
        CodeBlock cb = new CodeBlock("<module>", FunctionKind.NORMAL);
        cb.flags = CodeBlock.TOP_LEVEL | CodeBlock.STRICT | (async ? CodeBlock.ASYNC : 0);
        cb.source = source;
        cb.srcStart = 0;
        cb.srcEnd = prog.end;
        initRegs(fi.numRegs);
        scope = (Scope) prog.scope;
        statements(prog.body);
        emit(Op.PUSH_UNDEF);
        emit(Op.RETURN);
        finishBlock(cb);
        return cb;
    }

    private void enterTopScope(Scope root) {
        if (root.needsEnv) {
            emit(Op.PUSH_SCOPE, constValue(root.info));
        }
        initTdzRegisters(root);
        if (root.needsEnv) {
            ctl = new ScopeCtl(ctl, root);
        }
    }

    private void declareGlobals(Program prog, boolean isEval) {
        ScopeAnalyzer an = analyzer;
        // function declarations: last declaration of a name wins
        LinkedHashMap<String, FunctionDeclaration> fnByName = new LinkedHashMap<>();
        for (FunctionDeclaration f : an.globalFunctions) {
            String name = f.function.id.name;
            fnByName.remove(name);
            fnByName.put(name, f);
        }
        ArrayList<CodeBlock> templates = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (var entry : fnByName.entrySet()) {
            String name = entry.getKey();
            FunctionDeclaration f = entry.getValue();
            CodeBlock cb = compileNested(f.function, name, false);
            emit(Op.MAKE_CLOSURE, constValue(cb));
            templates.add(cb);
            names.add(name);
        }
        String[] lexNames = an.globalLexNames.keySet().toArray(new String[0]);
        boolean[] lexConst = new boolean[lexNames.length];
        for (int i = 0; i < lexNames.length; i++) {
            lexConst[i] = an.globalLexNames.get(lexNames[i]) == Boolean.TRUE;
        }
        ArrayList<String> varNames = new ArrayList<>();
        for (String name : an.globalVarNames) {
            if (!fnByName.containsKey(name)) {
                varNames.add(name);
            }
        }
        DeclInfo info = new DeclInfo(
                varNames.toArray(new String[0]),
                names.toArray(new String[0]),
                isEval ? new String[0] : lexNames,
                isEval ? new boolean[0] : lexConst,
                an.annexBGlobalNames.toArray(new String[0]),
                prog.strict);
        emit(isEval ? Op.DECLARE_EVAL : Op.DECLARE_GLOBALS, constValue(info), templates.size());
    }

    /** Hoists function declarations of a statement list into their (already declared) bindings. */
    private void hoistFunctions(List<Node> body, Scope s) {
        HashSet<String> seen = new HashSet<>();
        ArrayList<FunctionDeclaration> decls = new ArrayList<>();
        for (Node st0 : body) {
            Node st = st0;
            while (st instanceof LabeledStatement labeled) {
                st = labeled.body;
            }
            if (st instanceof ExportNamedDeclaration named) {
                st = named.declaration;
                if (st == null) {
                    continue;
                }
            }
            if (st instanceof ExportDefaultDeclaration dflt) {
                st = dflt.declaration;
            }
            if (st instanceof FunctionDeclaration declaration) {
                decls.add(declaration);
            }
        }
        // last declaration wins: initialize in order (later overwrite earlier)
        for (FunctionDeclaration d : decls) {
            String name = d.function.id != null ? d.function.id.name : "*default*";
            Binding b = s.bindings.get(name);
            if (b == null) {
                b = fi.varScope.bindings.get(name);
            }
            if (b == null) {
                continue;
            }
            CodeBlock cb = compileNested(d.function, d.function.id == null ? "default" : name, false);
            emit(Op.MAKE_CLOSURE, constValue(cb));
            emitInitBinding(b);
            seen.add(name);
        }
    }

    // ================================================================== functions

    public CodeBlock compileFunction(FunctionNode fn, String name, boolean fieldKeyDynamic) {
        FnInfo f = fi;
        CodeBlock cb = new CodeBlock(name, fn.kind);
        int flags = 0;
        if (fn.strict) {
            flags |= CodeBlock.STRICT;
        }
        if (fn.kind == FunctionKind.ARROW) {
            flags |= CodeBlock.ARROW;
        }
        if (fn.isGenerator) {
            flags |= CodeBlock.GENERATOR;
        }
        if (fn.isAsync) {
            flags |= CodeBlock.ASYNC;
        }
        switch (fn.kind) {
            case CLASS_CONSTRUCTOR -> flags |= CodeBlock.CLASS_CTOR | CodeBlock.CONSTRUCTOR | CodeBlock.METHOD;
            case DERIVED_CONSTRUCTOR -> flags |= CodeBlock.CLASS_CTOR | CodeBlock.DERIVED
                    | CodeBlock.CONSTRUCTOR | CodeBlock.METHOD;
            case METHOD, GETTER, SETTER, CLASS_FIELD_INIT, STATIC_BLOCK -> flags |= CodeBlock.METHOD;
            case NORMAL -> {
                if (!fn.isAsync && !fn.isGenerator) {
                    flags |= CodeBlock.CONSTRUCTOR;
                }
            }
            default -> {
            }
        }
        if (f.usesThis) {
            flags |= CodeBlock.USES_THIS;
        }
        if (f.hasDirectEval) {
            flags |= CodeBlock.HAS_EVAL;
        }
        cb.flags = flags;
        if (f.hasDirectEval) {
            computeEvalContext(cb);
        }
        cb.source = source;
        cb.srcStart = fn.srcStart;
        cb.srcEnd = fn.srcEnd;
        cb.formalCount = fn.params.size();
        int len = 0;
        for (Node p : fn.params) {
            if (p instanceof AssignmentPattern || p instanceof RestElement) {
                break;
            }
            len++;
        }
        cb.length = len;
        initRegs(f.numRegs);
        Scope outer = (f.calleeScope != null ? f.calleeScope : f.scope).parent;
        if (outer == null) {
            throw new CompileError("function without outer scope");
        }
        scope = outer;
        mark(fn);
        // callee scope
        Scope cs = f.calleeScope;
        if (cs != null) {
            enterScope(cs);
            Binding b = cs.bindings.values().iterator().next();
            if (b.inEnv()) {
                emit(Op.LOAD_FUNCTION);
                emit(Op.STORE_ENV, 0, b.slot);
            }
        }
        if (f.paramEvalScope != null) {
            enterScope(f.paramEvalScope);
        }
        Scope fs = f.scope;
        enterScope(fs);
        // pseudo bindings
        Binding thisBinding = f.thisBinding;
        if (thisBinding != null) {
            if (!thisBinding.tdz && thisBinding.inEnv()) {
                emit(Op.LOAD_THIS);
                emitInitBinding(thisBinding);
            }
        }
        Binding newTargetBinding = f.newTargetBinding;
        if (newTargetBinding != null && newTargetBinding.inEnv()) {
            emit(Op.LOAD_NEW_TARGET);
            emitInitBinding(newTargetBinding);
        }
        Binding homeBinding = f.homeBinding;
        if (homeBinding != null && homeBinding.inEnv()) {
            emit(Op.LOAD_HOME);
            emitInitBinding(homeBinding);
        }
        Binding fnBinding = f.fnBinding;
        if (fnBinding != null && fnBinding.inEnv()) {
            emit(Op.LOAD_FUNCTION);
            emitInitBinding(fnBinding);
        }
        // arguments object
        Binding ab = f.argumentsBinding;
        if (ab != null && f.usesArguments) {
            if (f.mappedArguments) {
                int[] slots = new int[fn.params.size()];
                for (int i = 0; i < slots.length; i++) {
                    Identifier id = (Identifier) fn.params.get(i);
                    slots[i] = fs.bindings.get(id.name).slot;
                }
                cb.mappedSlots = slots;
                emit(Op.CREATE_ARGUMENTS, 1);
            } else {
                emit(Op.CREATE_ARGUMENTS, 0);
            }
            emitInitBinding(ab);
        }
        // parameters
        boolean simple = true;
        for (Node p : fn.params) {
            if (!(p instanceof Identifier)) {
                simple = false;
                break;
            }
        }
        if (simple && !f.hasParamExpressions) {
            int[] regs = new int[fn.params.size()];
            Arrays.fill(regs, -1);
            boolean anyReg = false;
            for (int i = 0; i < fn.params.size(); i++) {
                Identifier p = (Identifier) fn.params.get(i);
                Binding b = fs.bindings.get(p.name);
                if (b.kind != BKind.PARAM) {
                    continue;
                }
                if (b.inEnv()) {
                    // last duplicate wins: emit in order
                    emit(Op.LOAD_ARG, i);
                    emitInitBinding(b);
                } else {
                    regs[i] = b.reg;
                    anyReg = true;
                }
            }
            if (anyReg) {
                // duplicates: only the last occurrence of a register receives the argument
                HashSet<Integer> seenRegs = new HashSet<>();
                for (int i = regs.length - 1; i >= 0; i--) {
                    if (regs[i] >= 0 && !seenRegs.add(regs[i])) {
                        regs[i] = -1;
                    }
                }
                cb.paramRegs = regs;
            }
        } else {
            for (int i = 0; i < fn.params.size(); i++) {
                Node p = fn.params.get(i);
                if (p instanceof RestElement rest) {
                    emit(Op.CREATE_REST, i);
                    pattern(rest.argument, BindMode.INIT);
                } else {
                    emit(Op.LOAD_ARG, i);
                    pattern(p, BindMode.INIT);
                }
            }
        }
        // body var scope (separate when parameters have expressions)
        Scope vs = f.varScope;
        if (vs != fs) {
            enterScope(vs);
            for (Binding b : vs.bindings.values()) {
                if (b.kind == BKind.VAR) {
                    Binding pb = fs.bindings.get(b.name);
                    if (pb != null && (pb.kind == BKind.PARAM || pb.kind == BKind.ARGUMENTS
                            || pb.isArgumentsObject)) {
                        emitLoadBinding(pb, false);
                        emitInitBinding(b);
                    }
                }
            }
        }
        Node body = fn.body;
        if (body instanceof BlockStatement block) {
            hoistFunctions(block.body, vs);
            if (fn.isGenerator) {
                emit(Op.GENERATOR_INIT);
            }
            statements(block.body);
            implicitReturn(fn);
        } else {
            if (fn.isGenerator) {
                emit(Op.GENERATOR_INIT);
            }
            // expression body (arrow or class field initializer)
            if (fn.kind == FunctionKind.CLASS_FIELD_INIT) {
                if (fieldKeyDynamic && isAnonymousFn(body)) {
                    exprNamed(body, null);
                    emit(Op.LOAD_ARG, 0);
                    emit(Op.SWAP);
                    emit(Op.SET_FUNCTION_NAME, -1);
                    emit(Op.SWAP);
                    emit(Op.POP);
                } else if (isAnonymousFn(body)) {
                    exprNamed(body, new StaticName(name));
                } else {
                    expr(body);
                }
            } else {
                exprReturned(body);
            }
            emit(Op.RETURN);
        }
        finishBlock(cb);
        return cb;
    }

    private void computeEvalContext(CodeBlock cb) {
        FnInfo g = fi;
        while (g != null && g.isArrow) {
            g = g.parent;
        }
        int ctx = 0;
        if (g != null && !g.isTopLevel) {
            ctx |= CodeBlock.CTX_NEW_TARGET;
            switch (g.kind) {
                case METHOD, GETTER, SETTER, CLASS_CONSTRUCTOR, STATIC_BLOCK ->
                        ctx |= CodeBlock.CTX_SUPER_PROP;
                case DERIVED_CONSTRUCTOR -> ctx |= CodeBlock.CTX_SUPER_PROP | CodeBlock.CTX_SUPER_CALL;
                case CLASS_FIELD_INIT -> ctx |= CodeBlock.CTX_SUPER_PROP | CodeBlock.CTX_FIELD_INIT;
                default -> {
                }
            }
        }
        cb.evalCtx = ctx;
        HashSet<String> names = new HashSet<>();
        Scope s = fi.scope;
        while (s != null) {
            for (Binding b : s.bindings.values()) {
                if (b.kind == BKind.PRIVATE) {
                    names.add(b.name.substring(1));
                }
            }
            s = s.parent;
        }
        if (!names.isEmpty()) {
            cb.privateNames = names;
        }
    }

    private void implicitReturn(FunctionNode fn) {
        if (fn.kind == FunctionKind.DERIVED_CONSTRUCTOR) {
            emit(Op.PUSH_UNDEF);
            emitReturnValue();
            return;
        }
        emit(Op.PUSH_UNDEF);
        emit(Op.RETURN);
    }

    // ================================================================== control flow

    @Override
    public void emitReturnValue() {
        Ctl c = ctl;
        while (c != null) {
            if (c instanceof FinallyCtl finallyCtl) {
                emit(Op.STORE_REG, finallyCtl.valueReg);
                int k = finallyKinds++;
                finallyCtl.pending.add(new PendingJump(k, null, null, false));
                pushInt(k);
                emit(Op.STORE_REG, finallyCtl.kindReg);
                emitJump(Op.JUMP, finallyCtl.entry);
                return;
            } else if (c instanceof IterCtl iterCtl) {
                int tmp = allocReg();
                emit(Op.STORE_REG, tmp);
                emitIterClose(iterCtl);
                emit(Op.LOAD_REG, tmp);
                freeReg(tmp);
            }
            c = c.parent;
        }
        if (fi.kind == FunctionKind.DERIVED_CONSTRUCTOR) {
            Binding tb = fi.thisBinding;
            emitLoadBinding(tb, false);
            emit(Op.DERIVED_RETURN);
        }
        emit(Op.RETURN);
    }

    private void emitIterClose(IterCtl c) {
        if (c.isAsync) {
            Label skip = newLabel();
            emit(Op.ASYNC_ITER_CLOSE, c.iterReg, 0, 0);
            fixAsyncCloseJump(skip);
            emitAwait();
            emit(Op.CHECK_OBJECT, constValue("Iterator result is not an object"));
            emit(Op.POP);
            place(skip);
        } else {
            emit(Op.ITER_CLOSE, c.iterReg);
        }
    }

    /** Patches the jump operand of the just-emitted ASYNC_ITER_CLOSE (operand 2) to [l]. */
    private void fixAsyncCloseJump(Label l) {
        int at = pc - 1;
        if (l.pos >= 0) {
            code[at] = l.pos;
        } else {
            l.fixups.add(at);
        }
        if (l.depth < 0) {
            l.depth = currentDepth() - 1;
        }
    }

    /** Emits a break/continue jump through the control stack. */
    private void emitJumpTo(Ctl target, boolean isContinue) {
        Ctl c = ctl;
        while (c != null && c != target) {
            if (c instanceof ScopeCtl) {
                emit(Op.POP_SCOPE);
            } else if (c instanceof IterCtl iterCtl) {
                if (!(isContinue && c.parent == target)) {
                    emitIterClose(iterCtl);
                }
            } else if (c instanceof FinallyCtl finallyCtl) {
                int k = finallyKinds++;
                finallyCtl.pending.add(new PendingJump(k, target, null, isContinue));
                pushInt(k);
                emit(Op.STORE_REG, finallyCtl.kindReg);
                emitJump(Op.JUMP, finallyCtl.entry);
                return;
            }
            c = c.parent;
        }
        if (c == null) {
            throw new CompileError("jump target not found");
        }
        Label l;
        if (isContinue) {
            l = ((LoopCtl) target).continueLabel;
        } else if (target instanceof LoopCtl loopCtl) {
            l = loopCtl.breakLabel;
        } else if (target instanceof LabelCtl labelCtl) {
            l = labelCtl.breakLabel;
        } else {
            throw new CompileError("bad target");
        }
        emitJump(Op.JUMP, l);
    }

    private Ctl findBreakTarget(String label) {
        Ctl c = ctl;
        while (c != null) {
            if (label == null) {
                if (c instanceof LoopCtl) {
                    return c;
                }
            } else {
                if (c instanceof LoopCtl loopCtl && loopCtl.labels.contains(label)) {
                    return c;
                }
                if (c instanceof LabelCtl labelCtl && labelCtl.labels.contains(label)) {
                    return c;
                }
            }
            c = c.parent;
        }
        throw new CompileError("break target not found: " + label);
    }

    private Ctl findContinueTarget(String label) {
        Ctl c = ctl;
        while (c != null) {
            if (c instanceof LoopCtl loopCtl && !loopCtl.isSwitch
                    && (label == null || loopCtl.labels.contains(label))) {
                return c;
            }
            c = c.parent;
        }
        throw new CompileError("continue target not found: " + label);
    }

    // ================================================================== statements

    private List<String> pendingLabels = Collections.emptyList();

    private List<String> takeLabels() {
        List<String> l = pendingLabels;
        pendingLabels = Collections.emptyList();
        return l;
    }

    private void setCompletionUndefined() {
        if (completionReg >= 0) {
            emit(Op.PUSH_UNDEF);
            emit(Op.STORE_REG, completionReg);
        }
    }

    void stmt(Node n) {
        if (!(n instanceof LabeledStatement) && !(n instanceof ForStatement) && !(n instanceof ForInStatement)
                && !(n instanceof ForOfStatement) && !(n instanceof WhileStatement)
                && !(n instanceof DoWhileStatement) && !pendingLabels.isEmpty()) {
            // labeled non-loop statement
            List<String> labels = takeLabels();
            Label brk = newLabel();
            ctl = new LabelCtl(ctl, labels, brk);
            stmt(n);
            ctl = ctl.parent;
            place(brk);
            return;
        }
        if (n instanceof ExpressionStatement expressionStatement) {
            mark(n);
            expr(expressionStatement.expression);
            if (completionReg >= 0) {
                emit(Op.STORE_REG, completionReg);
            } else {
                emit(Op.POP);
            }
        } else if (n instanceof VariableDeclaration declaration) {
            varDecl(declaration);
        } else if (n instanceof FunctionDeclaration declaration) {
            // hoisted; Annex B copy for block-level functions
            Scope s = scope;
            String name = declaration.function.id.name;
            Binding b = s.bindings.get(name);
            if (b != null && b.kind == BKind.BLOCK_FUNCTION) {
                Object av = b.annexBVar;
                if (av != null) {
                    emitLoadBinding(b, false);
                    if (av instanceof Binding annexBinding) {
                        // var binding may be in an outer scope of the same function
                        emitInitBinding(annexBinding);
                    } else {
                        emit(Op.STORE_NAME_VAR, constValue((String) av));
                    }
                }
            }
        } else if (n instanceof ClassDeclaration declaration) {
            mark(n);
            classExpr(declaration.cls, null);
            Identifier id = declaration.cls.id;
            Object ref = id.ref;
            if (ref == null) {
                // resolve declaration binding in the current scope
                Binding b = scope.bindings.get(id.name);
                if (b != null) {
                    emitInitBinding(b);
                } else {
                    emit(Op.INIT_GLOBAL_LEX, constValue(id.name));
                }
            } else {
                emitInitBindingFor(id);
            }
        } else if (n instanceof BlockStatement blockStatement) {
            block(blockStatement);
        } else if (n instanceof EmptyStatement) {
            // nothing
        } else if (n instanceof DebuggerStatement) {
            emit(Op.DEBUGGER);
        } else if (n instanceof IfStatement ifStatement) {
            mark(n);
            setCompletionUndefined();
            Label elseL = newLabel();
            condJump(ifStatement.test, false, elseL);
            subStmt(ifStatement.consequent);
            if (ifStatement.alternate != null) {
                Label end = newLabel();
                emitJump(Op.JUMP, end);
                placeAfterJump(elseL);
                subStmt(ifStatement.alternate);
                place(end);
            } else {
                place(elseL);
            }
        } else if (n instanceof ReturnStatement returnStatement) {
            mark(n);
            if (returnStatement.argument != null) {
                exprReturned(returnStatement.argument);
                if (fi.isAsync && fi.isGenerator) {
                    emitAwait();
                }
            } else {
                emit(Op.PUSH_UNDEF);
            }
            emitReturnValue();
        } else if (n instanceof ThrowStatement throwStatement) {
            mark(n);
            expr(throwStatement.argument);
            emit(Op.THROW);
        } else if (n instanceof BreakStatement breakStatement) {
            emitJumpTo(findBreakTarget(breakStatement.label), false);
        } else if (n instanceof ContinueStatement continueStatement) {
            emitJumpTo(findContinueTarget(continueStatement.label), true);
        } else if (n instanceof LabeledStatement labeledStatement) {
            ArrayList<String> labels = new ArrayList<>(pendingLabels);
            labels.add(labeledStatement.label);
            pendingLabels = labels;
            Node body = labeledStatement.body;
            if (body instanceof FunctionDeclaration) {
                pendingLabels = Collections.emptyList();
                stmt(body);
                return;
            }
            stmt(body);
            pendingLabels = Collections.emptyList();
        } else if (n instanceof WhileStatement whileStatement) {
            whileStmt(whileStatement);
        } else if (n instanceof DoWhileStatement doWhileStatement) {
            doWhile(doWhileStatement);
        } else if (n instanceof ForStatement forStatement) {
            forStmt(forStatement);
        } else if (n instanceof ForInStatement forInStatement) {
            forIn(forInStatement);
        } else if (n instanceof ForOfStatement forOfStatement) {
            forOf(forOfStatement);
        } else if (n instanceof SwitchStatement switchStatement) {
            switchStmt(switchStatement);
        } else if (n instanceof TryStatement tryStatement) {
            tryStmt(tryStatement);
        } else if (n instanceof WithStatement withStatement) {
            mark(n);
            setCompletionUndefined();
            expr(withStatement.obj);
            emit(Op.TO_OBJECT);
            emit(Op.PUSH_WITH);
            Scope ws = (Scope) withStatement.scope;
            scope = ws;
            ctl = new ScopeCtl(ctl, ws);
            subStmt(withStatement.body);
            ctl = ctl.parent;
            emit(Op.POP_SCOPE);
            scope = ws.parent;
        } else if (n instanceof ImportDeclaration || n instanceof ExportAllDeclaration) {
            // nothing
        } else if (n instanceof ExportNamedDeclaration named) {
            if (named.declaration != null) {
                stmt(named.declaration);
            }
        } else if (n instanceof ExportDefaultDeclaration defaultDeclaration) {
            Node d = defaultDeclaration.declaration;
            if (d instanceof FunctionDeclaration) {
                // nothing
            } else if (d instanceof ClassDeclaration classDeclaration) {
                classExpr(classDeclaration.cls, new StaticName("default"));
                String name = classDeclaration.cls.id != null ? classDeclaration.cls.id.name : "*default*";
                Binding b = scope.bindings.get(name);
                if (b == null) {
                    throw new CompileError("default export binding");
                }
                emitInitBinding(b);
            } else {
                exprNamed(d, new StaticName("default"));
                Binding b = scope.bindings.get("*default*");
                if (b == null) {
                    throw new CompileError("default export binding");
                }
                emitInitBinding(b);
            }
        } else {
            throw new CompileError("unsupported statement " + n.getClass().getSimpleName());
        }
    }

    private void emitInitBindingFor(Identifier id) {
        Object ref = id.ref;
        if (ref instanceof LocalRef local) {
            emitInitBinding(local.binding);
        } else if (ref instanceof DynamicRef) {
            emit(Op.INIT_NAME, constValue(id.name));
        } else {
            emit(Op.INIT_GLOBAL_LEX, constValue(id.name));
        }
    }

    /** Statement in single-statement position (may have an Annex B function scope). */
    private void subStmt(Node n) {
        Object s = n.scope;
        if ((n instanceof FunctionDeclaration || n instanceof LabeledStatement)
                && s instanceof Scope subScope && subScope.kind == ScopeKind.BLOCK) {
            enterScope(subScope);
            Node inner = n;
            while (inner instanceof LabeledStatement labeled) {
                inner = labeled.body;
            }
            hoistBlockFunctions(Collections.singletonList(inner), subScope);
            stmt(inner);
            exitScope(subScope);
            return;
        }
        stmt(n);
    }

    private void block(BlockStatement n) {
        Scope s = n.scope instanceof Scope scopeValue ? scopeValue : null;
        if (s == null || s.kind != ScopeKind.BLOCK) {
            for (Node st : n.body) {
                stmt(st);
            }
            return;
        }
        enterScope(s);
        hoistBlockFunctions(n.body, s);
        statements(n.body);
        exitScope(s);
    }

    /** Emits a statement list, wrapping it in a disposal region when it declares `using` / `await using`. */
    private void statements(List<Node> list) {
        int u = usingKind(list);
        if (u == 0) {
            for (Node st : list) {
                stmt(st);
            }
        } else {
            withDisposal(u == 2, () -> {
                for (Node st : list) {
                    stmt(st);
                }
            });
        }
    }

    /** 0: no `using` declarations directly in [list]; 1: only `using`; 2: some `await using`. */
    private int usingKind(List<Node> list) {
        int r = 0;
        for (Node st : list) {
            if (st instanceof VariableDeclaration declaration) {
                if (declaration.kind == VarKind.AWAIT_USING) {
                    return 2;
                }
                if (declaration.kind == VarKind.USING) {
                    r = 1;
                }
            }
        }
        return r;
    }

    /** Capability registers of the enclosing disposal regions (innermost last). */
    private final ArrayList<Integer> disposeCaps = new ArrayList<>();

    /**
     * Runs [body] in a region that performs DisposeResources on its capability when left by any completion (normal,
     * break/continue/return through the finally machinery, or throw), then continues that completion unless disposal
     * threw.
     */
    private void withDisposal(boolean async, Runnable body) {
        int capReg = allocReg();
        emit(Op.DISPOSE_NEW);
        emit(Op.STORE_REG, capReg);
        int kindReg = allocReg();
        int valueReg = allocReg();
        int envReg = allocReg();
        emit(Op.GET_ENV);
        emit(Op.STORE_REG, envReg);
        int d0 = currentDepth();
        Label entry = newLabel();
        FinallyCtl fctl = new FinallyCtl(ctl, kindReg, valueReg, entry);
        ctl = fctl;
        Scope savedScope = scope;
        disposeCaps.add(capReg);
        int start = pc;
        body.run();
        int end = pc;
        disposeCaps.remove(disposeCaps.size() - 1);
        ctl = fctl.parent;
        emit(Op.PUSH_UNDEF);
        emit(Op.STORE_REG, valueReg);
        pushInt(0);
        emit(Op.STORE_REG, kindReg);
        emitJump(Op.JUMP, entry);
        int hpc = pc;
        resetDepth(d0 + 1);
        addHandler(start, end, hpc, d0);
        emit(Op.STORE_REG, valueReg);
        emit(Op.LOAD_REG, envReg);
        emit(Op.SET_ENV);
        pushInt(1);
        emit(Op.STORE_REG, kindReg);
        place(entry);
        scope = savedScope;
        emit(Op.LOAD_REG, capReg);
        emit(Op.LOAD_REG, kindReg);
        emit(Op.LOAD_REG, valueReg);
        if (!async) {
            emit(Op.DISPOSE_SYNC);
        } else {
            int ad = allocReg();
            int mode = allocReg();
            emit(Op.DISPOSE_BEGIN);
            emit(Op.STORE_REG, ad);
            Label loop = newLabel();
            Label done = newLabel();
            place(loop);
            emit(Op.LOAD_REG, ad);
            emit(Op.DISPOSE_STEP);
            emitJump(Op.JUMP_IF_FALSE, done);
            pushInt(0);
            emit(Op.STORE_REG, mode);
            emit(Op.AWAIT_CATCH, mode);
            emit(Op.LOAD_REG, ad);
            emit(Op.LOAD_REG, mode);
            emit(Op.DISPOSE_AWAITED);
            emitJump(Op.JUMP, loop);
            placeAfterJump(done);
            emit(Op.POP);
            emit(Op.LOAD_REG, ad);
            emit(Op.DISPOSE_END);
            freeReg(mode);
            freeReg(ad);
        }
        // continue the original completion
        Label after = newLabel();
        Label rethrow = newLabel();
        int maxKind = 1;
        if (!fctl.pending.isEmpty()) {
            maxKind = fctl.pending.get(0).kind;
            for (PendingJump pending : fctl.pending) {
                if (pending.kind > maxKind) {
                    maxKind = pending.kind;
                }
            }
        }
        ArrayList<Label> table = new ArrayList<>();
        for (int i = 0; i <= maxKind; i++) {
            table.add(after);
        }
        if (maxKind >= 1) {
            table.set(1, rethrow);
        }
        ArrayList<PendingJump> stubs = new ArrayList<>();
        ArrayList<Label> stubLabels = new ArrayList<>();
        for (PendingJump pending : fctl.pending) {
            Label l = newLabel();
            table.set(pending.kind, l);
            stubs.add(pending);
            stubLabels.add(l);
        }
        emitJumpTable(kindReg, table, after);
        placeAfterJump(rethrow);
        emit(Op.LOAD_REG, valueReg);
        emit(Op.THROW);
        for (int i = 0; i < stubs.size(); i++) {
            PendingJump p = stubs.get(i);
            Label l = stubLabels.get(i);
            placeAfterJump(l);
            if (p.target == null) {
                emit(Op.LOAD_REG, valueReg);
                emitReturnValue();
            } else {
                emitJumpTo(p.target, p.isContinue);
            }
        }
        placeAfterJump(after);
        freeReg(envReg);
        freeReg(valueReg);
        freeReg(kindReg);
        freeReg(capReg);
    }

    /** Value on the stack is the initializer of a `using` binding: registers it (keeping the value). */
    private void emitAddDisposable(boolean async) {
        emit(Op.DUP);
        if (disposeCaps.isEmpty()) {
            throw new CompileError("using declaration outside a disposal scope");
        }
        emit(Op.LOAD_REG, disposeCaps.get(disposeCaps.size() - 1));
        emit(Op.ADD_DISPOSABLE, async ? 1 : 0);
    }

    private void hoistBlockFunctions(List<Node> body, Scope s) {
        for (Node st0 : body) {
            Node st = st0;
            while (st instanceof LabeledStatement labeled) {
                st = labeled.body;
            }
            if (st instanceof FunctionDeclaration declaration) {
                String name = declaration.function.id.name;
                Binding b = s.bindings.get(name);
                if (b == null) {
                    continue;
                }
                if (b.kind != BKind.BLOCK_FUNCTION) {
                    continue;
                }
                CodeBlock cb = compileNested(declaration.function, name, false);
                emit(Op.MAKE_CLOSURE, constValue(cb));
                emitInitBinding(b);
            }
        }
    }

    private void varDecl(VariableDeclaration n) {
        mark(n);
        if (n.kind == VarKind.USING || n.kind == VarKind.AWAIT_USING) {
            for (VariableDeclarator d : n.declarations) {
                exprNamed(d.init, new StaticName(((Identifier) d.id).name));
                emitAddDisposable(n.kind == VarKind.AWAIT_USING);
                pattern(d.id, BindMode.INIT);
            }
            return;
        }
        for (VariableDeclarator d : n.declarations) {
            Node init = d.init;
            if (n.kind == VarKind.VAR) {
                if (init == null) {
                    continue;
                }
                Node id = d.id;
                if (id instanceof Identifier identifier && identifier.ref instanceof DynamicRef) {
                    emit(Op.RESOLVE_NAME, constValue(identifier.name));
                    exprNamed(init, new StaticName(identifier.name));
                    emit(Op.PUT_REF);
                    emit(Op.POP);
                    continue;
                }
                exprNamed(init, id instanceof Identifier identifier ? new StaticName(identifier.name) : null);
                pattern(id, BindMode.ASSIGN);
            } else {
                if (init == null) {
                    emit(Op.PUSH_UNDEF);
                } else {
                    Node id = d.id;
                    exprNamed(init, id instanceof Identifier identifier ? new StaticName(identifier.name) : null);
                }
                pattern(d.id, BindMode.INIT);
            }
        }
    }

    // ------------------------------------------------------------------ loops

    private void whileStmt(WhileStatement n) {
        mark(n);
        setCompletionUndefined();
        List<String> labels = takeLabels();
        Label top = newLabel();
        Label brk = newLabel();
        place(top);
        condJump(n.test, false, brk);
        ctl = new LoopCtl(ctl, labels, brk, top, false);
        subStmt(n.body);
        ctl = ctl.parent;
        emitJump(Op.JUMP, top);
        placeAfterJump(brk);
    }

    private void doWhile(DoWhileStatement n) {
        mark(n);
        setCompletionUndefined();
        List<String> labels = takeLabels();
        Label top = newLabel();
        Label cont = newLabel();
        Label brk = newLabel();
        place(top);
        ctl = new LoopCtl(ctl, labels, brk, cont, false);
        subStmt(n.body);
        ctl = ctl.parent;
        place(cont);
        condJump(n.test, true, top);
        place(brk);
    }

    private void forStmt(ForStatement n) {
        mark(n);
        setCompletionUndefined();
        List<String> labels = takeLabels();
        Scope fs = n.scope instanceof Scope scopeValue ? scopeValue : null;
        if (fs != null) {
            enterScope(fs);
        }
        Node init = n.init;
        if (init instanceof VariableDeclaration declaration
                && (declaration.kind == VarKind.USING || declaration.kind == VarKind.AWAIT_USING)) {
            // the loop environment's resources are disposed when the whole statement completes
            withDisposal(declaration.kind == VarKind.AWAIT_USING, () -> {
                varDecl(declaration);
                forLoopRest(n, fs, labels);
            });
        } else {
            if (init != null) {
                if (init instanceof VariableDeclaration declaration) {
                    varDecl(declaration);
                } else {
                    exprDiscard(init);
                }
            }
            forLoopRest(n, fs, labels);
        }
        if (fs != null) {
            exitScope(fs);
        }
    }

    private void forLoopRest(ForStatement n, Scope fs, List<String> labels) {
        boolean perIter = fs != null && fs.perIteration && fs.needsEnv;
        if (perIter) {
            emit(Op.COPY_SCOPE);
        }
        Label top = newLabel();
        Label cont = newLabel();
        Label brk = newLabel();
        place(top);
        if (n.test != null) {
            condJump(n.test, false, brk);
        }
        ctl = new LoopCtl(ctl, labels, brk, cont, false);
        subStmt(n.body);
        ctl = ctl.parent;
        place(cont);
        if (perIter) {
            emit(Op.COPY_SCOPE);
        }
        if (n.update != null) {
            exprDiscard(n.update);
        }
        emitJump(Op.JUMP, top);
        placeAfterJump(brk);
    }

    private void forIn(ForInStatement n) {
        mark(n);
        setCompletionUndefined();
        List<String> labels = takeLabels();
        Node left = n.left;
        // Annex B: for (var x = init in obj)
        if (left instanceof VariableDeclaration declaration && declaration.kind == VarKind.VAR
                && declaration.declarations.get(0).init != null) {
            VariableDeclarator d = declaration.declarations.get(0);
            exprNamed(d.init, new StaticName(((Identifier) d.id).name));
            pattern(d.id, BindMode.ASSIGN);
        }
        Scope tdz = n.right.scope instanceof Scope scopeValue ? scopeValue : null;
        if (tdz != null) {
            enterScope(tdz);
        }
        expr(n.right);
        if (tdz != null) {
            exitScope(tdz);
        }
        Label brk = newLabel();
        // null/undefined -> no iteration
        emit(Op.DUP);
        Label nullish = newLabel();
        emitJump(Op.JUMP_IF_NULLISH, nullish);
        emit(Op.FOR_IN_START);
        int it = allocReg();
        emit(Op.STORE_REG, it);
        Label top = newLabel();
        place(top);
        emit(Op.FOR_IN_NEXT, it, 0);
        patchLastJump(brk);
        Scope iterScope = n.scope instanceof Scope scopeValue ? scopeValue : null;
        ctl = new LoopCtl(ctl, labels, brk, top, false);
        bindLoopTarget(left, iterScope);
        subStmt(n.body);
        if (iterScope != null && left instanceof VariableDeclaration declaration
                && declaration.kind != VarKind.VAR) {
            exitScope(iterScope);
        }
        ctl = ctl.parent;
        emitJump(Op.JUMP, top);
        placeAfterJump(nullish);
        emit(Op.POP);
        place(brk);
        freeReg(it);
    }

    /** Patches the last operand of the previously emitted instruction to jump to [l]. */
    private void patchLastJump(Label l) {
        int at = pc - 1;
        if (l.pos >= 0) {
            code[at] = l.pos;
        } else {
            l.fixups.add(at);
        }
        if (l.depth < 0) {
            l.depth = currentDepth() - 1;
        }
    }

    /** Binds the value on top of the stack to a for-in/of left side, entering the per-iteration scope. */
    private void bindLoopTarget(Node left, Scope iterScope) {
        if (left instanceof VariableDeclaration declaration) {
            if (declaration.kind == VarKind.VAR) {
                pattern(declaration.declarations.get(0).id, BindMode.ASSIGN);
            } else {
                if (iterScope != null) {
                    // enterScope pushes ScopeCtl so break/continue pop the env
                    enterScope(iterScope);
                }
                pattern(declaration.declarations.get(0).id, BindMode.INIT);
            }
        } else {
            pattern(left, BindMode.ASSIGN);
        }
    }

    private void forOf(ForOfStatement n) {
        mark(n);
        setCompletionUndefined();
        List<String> labels = takeLabels();
        Scope tdz = n.right.scope instanceof Scope scopeValue ? scopeValue : null;
        if (tdz != null) {
            enterScope(tdz);
        }
        expr(n.right);
        if (tdz != null) {
            exitScope(tdz);
        }
        boolean isAsync = n.isAwait;
        emit(isAsync ? Op.GET_ASYNC_ITERATOR : Op.GET_ITERATOR);
        int it = allocReg();
        emit(Op.STORE_REG, it);
        Label top = newLabel();
        Label brk = newLabel();
        place(top);
        if (isAsync) {
            emit(Op.ITER_NEXT_CALL, it);
            emitAwait();
            emit(Op.ITER_RESULT_STEP, it, 0);
            patchLastJump(brk);
        } else {
            emit(Op.ITER_STEP, it, 0);
            patchLastJump(brk);
        }
        int d = currentDepth() - 1;
        int start = pc;
        Scope iterScope = n.scope instanceof Scope scopeValue ? scopeValue : null;
        LoopCtl loopCtl = new LoopCtl(ctl, labels, brk, top, false);
        IterCtl icl = new IterCtl(loopCtl, it, isAsync);
        ctl = icl;
        Node left = n.left;
        if (left instanceof VariableDeclaration declaration
                && (declaration.kind == VarKind.USING || declaration.kind == VarKind.AWAIT_USING)) {
            // each iteration's resources are disposed at the end of that iteration
            if (iterScope != null) {
                enterScope(iterScope);
            }
            int tmp = allocReg();
            emit(Op.STORE_REG, tmp);
            withDisposal(declaration.kind == VarKind.AWAIT_USING, () -> {
                emit(Op.LOAD_REG, tmp);
                emitAddDisposable(declaration.kind == VarKind.AWAIT_USING);
                pattern(declaration.declarations.get(0).id, BindMode.INIT);
                subStmt(n.body);
            });
            freeReg(tmp);
            if (iterScope != null) {
                exitScope(iterScope);
            }
        } else {
            bindLoopTarget(left, iterScope);
            subStmt(n.body);
            if (iterScope != null && left instanceof VariableDeclaration declaration
                    && declaration.kind != VarKind.VAR) {
                exitScope(iterScope);
            }
        }
        ctl = loopCtl.parent;
        emitJump(Op.JUMP, top);
        int end = pc;
        // abrupt throw completion: close the iterator (ignoring errors) and rethrow
        int h = pc;
        resetDepth(d + 1);
        if (isAsync) {
            int excReg = allocReg();
            emit(Op.STORE_REG, excReg);
            Label skip = newLabel();
            emit(Op.ASYNC_ITER_CLOSE, it, 1, 0);
            fixAsyncCloseJump(skip);
            emit(Op.AWAIT_IGNORE);
            emit(Op.POP);
            place(skip);
            emit(Op.LOAD_REG, excReg);
            emit(Op.THROW);
            freeReg(excReg);
        } else {
            emit(Op.ITER_CLOSE_THROW, it);
            emit(Op.THROW);
        }
        addHandler(start, end, h, d);
        resetDepth(d);
        placeAfterJump(brk);
        freeReg(it);
    }

    private void switchStmt(SwitchStatement n) {
        mark(n);
        setCompletionUndefined();
        List<String> labels = takeLabels();
        expr(n.discriminant);
        int tmp = allocReg();
        emit(Op.STORE_REG, tmp);
        Scope s = (Scope) n.scope;
        enterScope(s);
        ArrayList<Node> all = new ArrayList<>();
        for (SwitchCase c : n.cases) {
            all.addAll(c.consequent);
        }
        hoistBlockFunctions(all, s);
        ArrayList<Label> caseLabels = new ArrayList<>();
        for (int i = 0; i < n.cases.size(); i++) {
            caseLabels.add(newLabel());
        }
        int defaultIdx = -1;
        for (int i = 0; i < n.cases.size(); i++) {
            SwitchCase c = n.cases.get(i);
            if (c.test == null) {
                defaultIdx = i;
                continue;
            }
            emit(Op.LOAD_REG, tmp);
            expr(c.test);
            emit(Op.SEQ);
            emitJump(Op.JUMP_IF_TRUE, caseLabels.get(i));
        }
        Label brk = newLabel();
        emitJump(Op.JUMP, defaultIdx >= 0 ? caseLabels.get(defaultIdx) : brk);
        ctl = new LoopCtl(ctl, labels, brk, null, true);
        for (int i = 0; i < n.cases.size(); i++) {
            placeAfterJumpOrFall(caseLabels.get(i));
            for (Node st : n.cases.get(i).consequent) {
                stmt(st);
            }
        }
        ctl = ctl.parent;
        place(brk);
        exitScope(s);
        freeReg(tmp);
    }

    private void placeAfterJumpOrFall(Label l) {
        place(l);
    }

    // ------------------------------------------------------------------ try

    private void tryStmt(TryStatement n) {
        mark(n);
        setCompletionUndefined();
        if (n.finalizer != null) {
            tryFinally(n);
        } else {
            tryCatch(n.block, n.handler);
        }
    }

    private void tryCatch(BlockStatement blockNode, CatchClause h) {
        int envReg = allocReg();
        emit(Op.GET_ENV);
        emit(Op.STORE_REG, envReg);
        Scope savedScope = scope;
        int start = pc;
        tryDepth++;
        block(blockNode);
        tryDepth--;
        int end = pc;
        Label after = newLabel();
        emitJump(Op.JUMP, after);
        int hpc = pc;
        resetDepth(1);
        addHandler(start, end, hpc, 0);
        emit(Op.LOAD_REG, envReg);
        emit(Op.SET_ENV);
        // UpdateEmpty(C, undefined): the try block's partial completion value does not survive a throw
        if (completionReg >= 0) {
            emit(Op.PUSH_UNDEF);
            emit(Op.STORE_REG, completionReg);
        }
        scope = savedScope;
        Scope cs = (Scope) h.scope;
        enterScope(cs);
        if (h.param != null) {
            pattern(h.param, BindMode.INIT);
        } else {
            emit(Op.POP);
        }
        block(h.body);
        exitScope(cs);
        place(after);
        freeReg(envReg);
    }

    private void tryFinally(TryStatement n) {
        int kindReg = allocReg();
        int valueReg = allocReg();
        int envReg = allocReg();
        emit(Op.GET_ENV);
        emit(Op.STORE_REG, envReg);
        Label entry = newLabel();
        FinallyCtl fctl = new FinallyCtl(ctl, kindReg, valueReg, entry);
        ctl = fctl;
        Scope savedScope = scope;
        int start = pc;
        if (n.handler != null) {
            tryCatch(n.block, n.handler);
        } else {
            block(n.block);
        }
        int end = pc;
        ctl = fctl.parent;
        pushInt(0);
        emit(Op.STORE_REG, kindReg);
        emitJump(Op.JUMP, entry);
        int hpc = pc;
        resetDepth(1);
        addHandler(start, end, hpc, 0);
        emit(Op.STORE_REG, valueReg);
        emit(Op.LOAD_REG, envReg);
        emit(Op.SET_ENV);
        pushInt(1);
        emit(Op.STORE_REG, kindReg);
        place(entry);
        scope = savedScope;
        int savedCompletion = -1;
        if (completionReg >= 0) {
            savedCompletion = allocReg();
            emit(Op.LOAD_REG, completionReg);
            emit(Op.STORE_REG, savedCompletion);
            // an abrupt finally completes with its own (possibly empty -> undefined) value
            emit(Op.PUSH_UNDEF);
            emit(Op.STORE_REG, completionReg);
        }
        block(n.finalizer);
        if (savedCompletion >= 0) {
            emit(Op.LOAD_REG, savedCompletion);
            emit(Op.STORE_REG, completionReg);
            freeReg(savedCompletion);
        }
        // dispatch
        Label after = newLabel();
        Label rethrow = newLabel();
        ArrayList<PendingJump> stubs = new ArrayList<>();
        ArrayList<Label> stubLabels = new ArrayList<>();
        // map kinds compactly: build table from 0..maxKind
        int maxKind = 1;
        if (!fctl.pending.isEmpty()) {
            maxKind = fctl.pending.get(0).kind;
            for (PendingJump pending : fctl.pending) {
                if (pending.kind > maxKind) {
                    maxKind = pending.kind;
                }
            }
        }
        ArrayList<Label> table = new ArrayList<>();
        for (int i = 0; i <= maxKind; i++) {
            table.add(after);
        }
        table.set(0, after);
        if (maxKind >= 1) {
            table.set(1, rethrow);
        }
        for (PendingJump pending : fctl.pending) {
            Label l = newLabel();
            table.set(pending.kind, l);
            stubs.add(pending);
            stubLabels.add(l);
        }
        emitJumpTable(kindReg, table, after);
        placeAfterJump(rethrow);
        emit(Op.LOAD_REG, valueReg);
        emit(Op.THROW);
        for (int i = 0; i < stubs.size(); i++) {
            PendingJump p = stubs.get(i);
            Label l = stubLabels.get(i);
            placeAfterJump(l);
            if (p.target == null) {
                emit(Op.LOAD_REG, valueReg);
                emitReturnValue();
            } else {
                emitJumpTo(p.target, p.isContinue);
            }
        }
        placeAfterJump(after);
        freeReg(envReg);
        freeReg(valueReg);
        freeReg(kindReg);
    }
}
