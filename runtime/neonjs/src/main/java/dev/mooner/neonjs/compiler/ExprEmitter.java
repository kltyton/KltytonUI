/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.*;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.ObjectLiteralSite;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

enum BindMode {
    ASSIGN,
    INIT
}

/** Expression, assignment, class, pattern and generator emitter. */
abstract class ExprEmitter extends EmitterBase {

    ExprEmitter(FnInfo fi, Source source, ScopeAnalyzer analyzer) {
        super(fi, source, analyzer);
    }

    /** Compiles a nested function into a template. */
    public abstract CodeBlock compileNested(FunctionNode fn, String name, boolean fieldKeyDynamic);

    /** Emits a `return` of the value on top of the stack (through finally blocks / iterator closes). */
    public abstract void emitReturnValue();

    public CodeBlock compileNested(FunctionNode fn, String name) {
        return compileNested(fn, name, false);
    }

    boolean isAsyncGenerator() {
        return fi.isAsync && fi.isGenerator;
    }

    // ================================================================== entry points

    void expr(Node n) {
        if (n instanceof NumberLiteral literal) {
            pushConst(literal.value);
        } else if (n instanceof StringLiteral literal) {
            emit(Op.PUSH_CONST, constValue(literal.value));
        } else if (n instanceof BooleanLiteral literal) {
            emit(literal.value ? Op.PUSH_TRUE : Op.PUSH_FALSE);
        } else if (n instanceof NullLiteral) {
            emit(Op.PUSH_NULL);
        } else if (n instanceof BigIntLiteral literal) {
            emit(Op.PUSH_CONST, constValue(literal.value));
        } else if (n instanceof RegExpLiteral literal) {
            emit(Op.NEW_REGEXP, constValue(new RegExpSite(literal.pattern, literal.flags)));
        } else if (n instanceof Identifier identifier) {
            identLoad(identifier);
        } else if (n instanceof ThisExpression) {
            thisLoad(n);
        } else if (n instanceof TemplateLiteral literal) {
            template(literal);
        } else if (n instanceof TaggedTemplate tagged) {
            taggedTemplate(tagged);
        } else if (n instanceof ArrayLiteral literal) {
            arrayLiteral(literal);
        } else if (n instanceof ObjectLiteral literal) {
            objectLiteral(literal);
        } else if (n instanceof FunctionNode fn) {
            function(fn, null);
        } else if (n instanceof ClassNode cls) {
            classExpr(cls, null);
        } else if (n instanceof UnaryExpression unary) {
            unary(unary);
        } else if (n instanceof UpdateExpression update) {
            update(update, true);
        } else if (n instanceof BinaryExpression binary) {
            binary(binary);
        } else if (n instanceof LogicalExpression logical) {
            logical(logical);
        } else if (n instanceof PrivateInExpression privateIn) {
            privateNameLoad(privateIn.name);
            expr(privateIn.right);
            emit(Op.HAS_PRIVATE);
        } else if (n instanceof AssignmentExpression assignment) {
            assign(assignment, true);
        } else if (n instanceof ConditionalExpression conditional) {
            Label elseL = newLabel();
            Label end = newLabel();
            condJump(conditional.test, false, elseL);
            expr(conditional.consequent);
            emitJump(Op.JUMP, end);
            placeAfterJump(elseL);
            resetDepth(currentDepth());
            expr(conditional.alternate);
            place(end);
        } else if (n instanceof CallExpression call) {
            call(call);
        } else if (n instanceof NewExpression newExpr) {
            newExpr(newExpr);
        } else if (n instanceof MemberExpression member) {
            member(member);
        } else if (n instanceof ChainExpression chain) {
            chain(chain);
        } else if (n instanceof SequenceExpression sequence) {
            for (int i = 0; i < sequence.expressions.size(); i++) {
                expr(sequence.expressions.get(i));
                if (i < sequence.expressions.size() - 1) {
                    emit(Op.POP);
                }
            }
        } else if (n instanceof YieldExpression yield) {
            yieldExpr(yield);
        } else if (n instanceof AwaitExpression await) {
            expr(await.argument);
            emitAwait();
        } else if (n instanceof MetaProperty meta) {
            metaProperty(meta);
        } else if (n instanceof ImportCall importCall) {
            mark(n);
            expr(importCall.source);
            if (importCall.options != null) {
                expr(importCall.options);
            } else {
                emit(Op.PUSH_UNDEF);
            }
            if (importCall.phase == null) {
                emit(Op.DYNAMIC_IMPORT);
            } else {
                emit(Op.DYNAMIC_IMPORT_PHASE, constValue(importCall.phase));
            }
        } else if (n instanceof Super) {
            throw new CompileError("unexpected super");
        } else if (n instanceof SpreadElement) {
            throw new CompileError("unexpected spread");
        } else {
            throw new CompileError("unsupported expression " + n.getClass().getSimpleName());
        }
    }

    void exprDiscard(Node n) {
        if (n instanceof UpdateExpression update) {
            update(update, false);
        } else if (n instanceof AssignmentExpression assignment) {
            assign(assignment, false);
        } else {
            expr(n);
            emit(Op.POP);
        }
    }

    void emitAwait() {
        emit(Op.AWAIT);
    }

    /** IsAnonymousFunctionDefinition */
    boolean isAnonymousFn(Node n) {
        if (n instanceof FunctionNode fn) {
            return fn.id == null && fn.kind != FunctionKind.METHOD;
        }
        if (n instanceof ClassNode cls) {
            return cls.id == null;
        }
        return false;
    }

    /** Evaluates [n] applying NamedEvaluation when it is an anonymous function/class. */
    void exprNamed(Node n, FnName name) {
        if (name != null && isAnonymousFn(n)) {
            if (n instanceof FunctionNode fn) {
                function(fn, name);
            } else {
                classExpr((ClassNode) n, name);
            }
        } else {
            expr(n);
        }
    }

    // ================================================================== identifiers

    void identLoad(Identifier n) {
        if ("undefined".equals(n.name) && n.ref instanceof GlobalRef) {
            emit(Op.LOAD_GLOBAL, constValue("undefined"));
            return;
        }
        emitLoadRef(n.ref, n.name);
    }

    void thisLoad(Node n) {
        Object ref = n.ref;
        if (ref == null) {
            emit(Op.LOAD_THIS);
            return;
        }
        emitLoadRef(ref, "this");
    }

    void loadPseudo(Node n, String name) {
        Object ref = n.ref;
        if (ref == null) {
            switch (name) {
                case "this" -> emit(Op.LOAD_THIS);
                case "new.target" -> emit(Op.LOAD_NEW_TARGET);
                case "%home" -> emit(Op.LOAD_HOME);
                case "%fn" -> emit(Op.LOAD_FUNCTION);
                default -> {
                }
            }
            return;
        }
        emitLoadRef(ref, name);
    }

    void metaProperty(MetaProperty n) {
        if ("new".equals(n.meta)) {
            loadPseudo(n, "new.target");
        } else {
            emit(Op.IMPORT_META);
        }
    }

    void privateNameLoad(PrivateIdentifier p) {
        Object ref = p.ref;
        if (ref instanceof LocalRef local) {
            emitLoadBinding(local.binding, false);
        } else {
            emit(Op.LOAD_NAME, constValue("#" + p.name));
        }
    }

    // ================================================================== conditions

    /** Jumps to [target] if ToBoolean(n) == [jumpIf]. */
    void condJump(Node n, boolean jumpIf, Label target) {
        if (n instanceof UnaryExpression unary && "!".equals(unary.op)) {
            condJump(unary.argument, !jumpIf, target);
        } else if (n instanceof LogicalExpression logical && "&&".equals(logical.op)) {
            if (jumpIf) {
                Label skip = newLabel();
                condJump(logical.left, false, skip);
                condJump(logical.right, true, target);
                place(skip);
            } else {
                condJump(logical.left, false, target);
                condJump(logical.right, false, target);
            }
        } else if (n instanceof LogicalExpression logical && "||".equals(logical.op)) {
            if (jumpIf) {
                condJump(logical.left, true, target);
                condJump(logical.right, true, target);
            } else {
                Label skip = newLabel();
                condJump(logical.left, true, skip);
                condJump(logical.right, false, target);
                place(skip);
            }
        } else if (n instanceof BooleanLiteral literal) {
            if (literal.value == jumpIf) {
                emitJump(Op.JUMP, target);
            }
        } else {
            expr(n);
            emitJump(jumpIf ? Op.JUMP_IF_TRUE : Op.JUMP_IF_FALSE, target);
        }
    }

    // ================================================================== operators

    private void unary(UnaryExpression n) {
        switch (n.op) {
            case "typeof" -> {
                Node a = n.argument;
                if (a instanceof Identifier identifier) {
                    Object ref = identifier.ref;
                    if (ref instanceof LocalRef) {
                        emitLoadRef(ref, identifier.name);
                    } else {
                        emitLoadRef(ref, identifier.name, true);
                    }
                } else {
                    expr(a);
                }
                emit(Op.TYPEOF);
            }
            case "delete" -> deleteExpr(n.argument);
            case "void" -> {
                exprDiscard(n.argument);
                emit(Op.PUSH_UNDEF);
            }
            case "!" -> {
                expr(n.argument);
                emit(Op.NOT);
            }
            case "-" -> {
                Node a = n.argument;
                if (a instanceof NumberLiteral literal) {
                    pushConst(-literal.value);
                } else if (a instanceof BigIntLiteral literal) {
                    emit(Op.PUSH_CONST, constValue(literal.value.negate()));
                } else {
                    expr(a);
                    emit(Op.NEG);
                }
            }
            case "+" -> {
                expr(n.argument);
                emit(Op.TO_NUMBER);
            }
            case "~" -> {
                expr(n.argument);
                emit(Op.BNOT);
            }
            default -> throw new CompileError("unary " + n.op);
        }
    }

    private void deleteExpr(Node a) {
        if (a instanceof MemberExpression member) {
            if (member.obj instanceof Super) {
                // delete super.x : evaluate this (TDZ), key, then ReferenceError
                thisLoad((Node) member.ref);
                emit(Op.POP);
                if (member.computed) {
                    expr(member.property);
                    emit(Op.POP);
                }
                throwError(1, "Unsupported reference to 'super'");
                emit(Op.PUSH_UNDEF);
                return;
            }
            expr(member.obj);
            mark(member);
            if (member.computed) {
                expr(member.property);
                emit(Op.DELETE_ELEM);
            } else {
                emit(Op.DELETE_PROP, keyConst(((Identifier) member.property).name));
            }
        } else if (a instanceof ChainExpression chain) {
            // a short-circuited chain is not a reference: delete yields true
            chainWith(chain, Op.PUSH_TRUE, inner -> {
                if (inner instanceof MemberExpression member) {
                    memberObject(member);
                    if (member.computed) {
                        expr(member.property);
                        emit(Op.DELETE_ELEM);
                    } else {
                        emit(Op.DELETE_PROP, keyConst(((Identifier) member.property).name));
                    }
                } else {
                    expr(inner);
                    emit(Op.POP);
                    emit(Op.PUSH_TRUE);
                }
            });
        } else if (a instanceof Identifier identifier) {
            Object ref = identifier.ref;
            if (ref instanceof LocalRef) {
                emit(Op.PUSH_FALSE);
            } else {
                emit(Op.DELETE_NAME, constValue(identifier.name));
            }
        } else {
            exprDiscard(a);
            emit(Op.PUSH_TRUE);
        }
    }

    private void binary(BinaryExpression n) {
        expr(n.left);
        expr(n.right);
        mark(n);
        emit(binaryOpFor(n.op));
    }

    private static int binaryOpFor(String op) {
        return switch (op) {
            case "+" -> Op.ADD;
            case "-" -> Op.SUB;
            case "*" -> Op.MUL;
            case "/" -> Op.DIV;
            case "%" -> Op.MOD;
            case "**" -> Op.EXP;
            case "<<" -> Op.SHL;
            case ">>" -> Op.SAR;
            case ">>>" -> Op.SHR;
            case "&" -> Op.BAND;
            case "|" -> Op.BOR;
            case "^" -> Op.BXOR;
            case "==" -> Op.EQ;
            case "!=" -> Op.NE;
            case "===" -> Op.SEQ;
            case "!==" -> Op.SNE;
            case "<" -> Op.LT;
            case ">" -> Op.GT;
            case "<=" -> Op.LE;
            case ">=" -> Op.GE;
            case "instanceof" -> Op.INSTANCEOF;
            case "in" -> Op.IN;
            default -> throw new CompileError("binary " + op);
        };
    }

    private void logical(LogicalExpression n) {
        Label end = newLabel();
        expr(n.left);
        emit(Op.DUP);
        switch (n.op) {
            case "&&" -> emitJump(Op.JUMP_IF_FALSE, end);
            case "||" -> emitJump(Op.JUMP_IF_TRUE, end);
            case "??" -> emitJump(Op.JUMP_IF_NOT_NULLISH, end);
            default -> {
            }
        }
        emit(Op.POP);
        expr(n.right);
        place(end);
    }

    private static int binOpFor(String op) {
        return switch (op) {
            case "+=" -> Op.ADD;
            case "-=" -> Op.SUB;
            case "*=" -> Op.MUL;
            case "/=" -> Op.DIV;
            case "%=" -> Op.MOD;
            case "**=" -> Op.EXP;
            case "<<=" -> Op.SHL;
            case ">>=" -> Op.SAR;
            case ">>>=" -> Op.SHR;
            case "&=" -> Op.BAND;
            case "|=" -> Op.BOR;
            case "^=" -> Op.BXOR;
            default -> throw new CompileError("assign op " + op);
        };
    }

    // ================================================================== assignment

    /** True for global names declared by the code being compiled (always resolvable when assigned). */
    private boolean declaredGlobal(String name) {
        return analyzer.globalVarNames.contains(name) || analyzer.globalLexNames.containsKey(name);
    }

    /** Expressions whose evaluation cannot run user code (so cannot create the assigned global first). */
    private boolean isInert(Node n) {
        return n instanceof NumberLiteral || n instanceof StringLiteral || n instanceof BooleanLiteral
                || n instanceof NullLiteral || n instanceof BigIntLiteral || n instanceof FunctionNode
                || (n instanceof TemplateLiteral literal && literal.expressions.isEmpty());
    }

    /** Reference kinds for assignment targets. */
    private void assign(AssignmentExpression n, boolean keep) {
        Node t = n.target;
        String op = n.op;
        if ("=".equals(op)) {
            if (t instanceof Identifier identifier) {
                FnName nm = identifier.parenthesized ? null : new StaticName(identifier.name);
                if (identifier.ref instanceof DynamicRef
                        || (identifier.ref instanceof GlobalRef && isStrict() && !declaredGlobal(identifier.name)
                        && !isInert(n.value))) {
                    // the binding is resolved before evaluating the right-hand side
                    emit(Op.RESOLVE_NAME, constValue(identifier.name));
                    exprNamed(n.value, nm);
                    emit(Op.PUT_REF);
                    if (!keep) {
                        emit(Op.POP);
                    }
                    return;
                }
                exprNamed(n.value, nm);
                if (keep) {
                    emit(Op.DUP);
                }
                emitStoreRef(identifier.ref, identifier.name);
            } else if (t instanceof MemberExpression member) {
                if (member.obj instanceof Super) {
                    superRefPrefix(member);
                    expr(n.value);
                    emitPutSuper(keep);
                    return;
                }
                expr(member.obj);
                if (member.property instanceof PrivateIdentifier privateIdentifier) {
                    privateNameLoad(privateIdentifier);
                    expr(n.value);
                    mark(member);
                    emit(Op.PUT_PRIVATE);
                } else if (member.computed) {
                    expr(member.property);
                    expr(n.value);
                    mark(member);
                    emit(Op.PUT_ELEM);
                } else {
                    expr(n.value);
                    mark(member);
                    emit(Op.PUT_PROP, keyConst(((Identifier) member.property).name));
                }
                if (!keep) {
                    emit(Op.POP);
                }
            } else if (t instanceof ObjectPattern || t instanceof ArrayPattern) {
                expr(n.value);
                if (keep) {
                    emit(Op.DUP);
                }
                pattern(t, BindMode.ASSIGN);
            } else if (t instanceof CallExpression) {
                // Annex B: f() = v  -> evaluate call, then ReferenceError
                expr(t);
                emit(Op.POP);
                throwError(1, "Invalid left-hand side in assignment");
                if (keep) {
                    emit(Op.PUSH_UNDEF);
                }
            } else {
                throw new CompileError("bad assignment target " + t.getClass().getSimpleName());
            }
            return;
        }
        if ("&&=".equals(op) || "||=".equals(op) || "??=".equals(op)) {
            logicalAssign(n, keep);
            return;
        }
        int binop = binOpFor(op);
        if (t instanceof Identifier identifier) {
            if (identifier.ref instanceof DynamicRef) {
                emit(Op.RESOLVE_NAME, constValue(identifier.name));
                emit(Op.DUP);
                emit(Op.GET_REF);
                expr(n.value);
                mark(n);
                emit(binop);
                emit(Op.PUT_REF);
                if (!keep) {
                    emit(Op.POP);
                }
                return;
            }
            identLoad(identifier);
            expr(n.value);
            mark(n);
            emit(binop);
            if (keep) {
                emit(Op.DUP);
            }
            emitStoreRef(identifier.ref, identifier.name);
        } else if (t instanceof MemberExpression member) {
            if (member.obj instanceof Super) {
                superRefPrefix(member);                // this key base
                superGetFromPrefix();                  // this key base v
                expr(n.value);
                emit(binop);
                emitPutSuper(keep);
                return;
            }
            expr(member.obj);
            if (member.property instanceof PrivateIdentifier privateIdentifier) {
                privateNameLoad(privateIdentifier);    // o pn
                emit(Op.DUP2);                         // o pn o pn
                emit(Op.GET_PRIVATE);                  // o pn v
                expr(n.value);
                emit(binop);
                emit(Op.PUT_PRIVATE);
            } else if (member.computed) {
                expr(member.property);
                emit(Op.TO_KEY_FOR_BASE);              // o k  (converted after base check)
                emit(Op.DUP2);
                mark(member);
                emit(Op.GET_ELEM);
                expr(n.value);
                mark(n);
                emit(binop);
                emit(Op.PUT_ELEM);
            } else {
                int k = keyConst(((Identifier) member.property).name);
                emit(Op.DUP);
                mark(member);
                emit(Op.GET_PROP, k);
                expr(n.value);
                mark(n);
                emit(binop);
                emit(Op.PUT_PROP, k);
            }
            if (!keep) {
                emit(Op.POP);
            }
        } else if (t instanceof CallExpression) {
            expr(t);
            emit(Op.POP);
            throwError(1, "Invalid left-hand side in assignment");
            if (keep) {
                emit(Op.PUSH_UNDEF);
            }
        } else {
            throw new CompileError("bad compound target");
        }
    }

    private void logicalAssign(AssignmentExpression n, boolean keep) {
        Node t = n.target;
        Label end = newLabel();
        int jumpOp = switch (n.op) {
            case "&&=" -> Op.JUMP_IF_FALSE;
            case "||=" -> Op.JUMP_IF_TRUE;
            default -> Op.JUMP_IF_NOT_NULLISH;
        };
        if (t instanceof Identifier identifier) {
            if (identifier.ref instanceof DynamicRef) {
                emit(Op.RESOLVE_NAME, constValue(identifier.name));
                emit(Op.DUP);
                emit(Op.GET_REF);
                emit(Op.DUP);
                Label skip = newLabel();
                emitJump(jumpOp, skip);
                emit(Op.POP);
                exprNamed(n.value, identifier.parenthesized ? null : new StaticName(identifier.name));
                emit(Op.PUT_REF);
                emitJump(Op.JUMP, end);
                placeAfterJump(skip);
                emit(Op.SWAP);
                emit(Op.POP);
                place(end);
                if (!keep) {
                    emit(Op.POP);
                }
                return;
            }
            identLoad(identifier);
            emit(Op.DUP);
            emitJump(jumpOp, end);
            emit(Op.POP);
            exprNamed(n.value, identifier.parenthesized ? null : new StaticName(identifier.name));
            emit(Op.DUP);
            emitStoreRef(identifier.ref, identifier.name);
            place(end);
            if (!keep) {
                emit(Op.POP);
            }
        } else if (t instanceof MemberExpression member) {
            if (member.obj instanceof Super) {
                superRefPrefix(member);                // this key base
                superGetFromPrefix();                  // this key base v
                emit(Op.DUP);
                Label skip = newLabel();
                emitJump(jumpOp, skip);
                emit(Op.POP);
                exprNamed(n.value, null);
                emitPutSuper(true);                    // v
                Label done = newLabel();
                emitJump(Op.JUMP, done);
                placeAfterJump(skip);                  // this key base v
                int tmp = allocReg();
                emit(Op.STORE_REG, tmp);
                emit(Op.POP);
                emit(Op.POP);
                emit(Op.POP);
                emit(Op.LOAD_REG, tmp);
                freeReg(tmp);
                place(done);
                if (!keep) {
                    emit(Op.POP);
                }
                return;
            }
            expr(member.obj);
            Label skip = newLabel();
            if (member.property instanceof PrivateIdentifier privateIdentifier) {
                privateNameLoad(privateIdentifier);
                emit(Op.DUP2);
                emit(Op.GET_PRIVATE);                  // o pn v
                emit(Op.DUP);
                emitJump(jumpOp, skip);
                emit(Op.POP);
                exprNamed(n.value, null);
                emit(Op.PUT_PRIVATE);                  // v
            } else if (member.computed) {
                expr(member.property);
                emit(Op.TO_KEY_FOR_BASE);
                emit(Op.DUP2);
                emit(Op.GET_ELEM);                     // o k v
                emit(Op.DUP);
                emitJump(jumpOp, skip);
                emit(Op.POP);
                expr(n.value);
                emit(Op.PUT_ELEM);
            } else {
                int k = keyConst(((Identifier) member.property).name);
                emit(Op.DUP);
                emit(Op.GET_PROP, k);                  // o v
                emit(Op.DUP);
                Label skip1 = newLabel();
                emitJump(jumpOp, skip1);
                emit(Op.POP);
                expr(n.value);
                emit(Op.PUT_PROP, k);                  // v
                emitJump(Op.JUMP, end);
                placeAfterJump(skip1);                 // o v
                emit(Op.SWAP);
                emit(Op.POP);
                place(end);
                if (!keep) {
                    emit(Op.POP);
                }
                return;
            }
            emitJump(Op.JUMP, end);
            placeAfterJump(skip);                      // o k v
            emit(Op.ROT3);                             // v o k
            emit(Op.POP);
            emit(Op.POP);
            place(end);
            if (!keep) {
                emit(Op.POP);
            }
        } else {
            throw new CompileError("bad logical assignment target");
        }
    }

    private void update(UpdateExpression n, boolean keep) {
        Node t = n.argument;
        int op = "++".equals(n.op) ? Op.INC : Op.DEC;
        if (t instanceof Identifier identifier) {
            if (identifier.ref instanceof DynamicRef) {
                emit(Op.RESOLVE_NAME, constValue(identifier.name));
                emit(Op.DUP);
                emit(Op.GET_REF);
                emit(Op.TO_NUMERIC);
                if (n.prefix) {
                    emit(op);
                    emit(Op.PUT_REF);
                    if (!keep) {
                        emit(Op.POP);
                    }
                } else {
                    int tmp = allocReg();
                    emit(Op.DUP);
                    emit(Op.STORE_REG, tmp);
                    emit(op);
                    emit(Op.PUT_REF);
                    emit(Op.POP);
                    if (keep) {
                        emit(Op.LOAD_REG, tmp);
                    }
                    freeReg(tmp);
                }
                return;
            }
            identLoad(identifier);
            emit(Op.TO_NUMERIC);
            if (n.prefix) {
                emit(op);
                if (keep) {
                    emit(Op.DUP);
                }
            } else {
                if (keep) {
                    emit(Op.DUP);
                }
                emit(op);
            }
            emitStoreRef(identifier.ref, identifier.name);
        } else if (t instanceof MemberExpression member) {
            if (member.obj instanceof Super) {
                superRefPrefix(member);
                superGetFromPrefix();                  // this key base v
                emit(Op.TO_NUMERIC);
                if (n.prefix) {
                    emit(op);
                    emitPutSuper(keep);
                } else {
                    int tmp = allocReg();
                    emit(Op.DUP);
                    emit(Op.STORE_REG, tmp);
                    emit(op);
                    emitPutSuper(false);
                    if (keep) {
                        emit(Op.LOAD_REG, tmp);
                    }
                    freeReg(tmp);
                }
                return;
            }
            expr(member.obj);
            if (member.property instanceof PrivateIdentifier privateIdentifier) {
                privateNameLoad(privateIdentifier);    // o pn
                emit(Op.DUP2);
                emit(Op.GET_PRIVATE);                  // o pn v
                emit(Op.TO_NUMERIC);
                if (n.prefix) {
                    emit(op);
                    emit(Op.PUT_PRIVATE);              // v'
                    if (!keep) {
                        emit(Op.POP);
                    }
                } else {
                    int tmp = allocReg();
                    emit(Op.DUP);
                    emit(Op.STORE_REG, tmp);
                    emit(op);
                    emit(Op.PUT_PRIVATE);
                    emit(Op.POP);
                    if (keep) {
                        emit(Op.LOAD_REG, tmp);
                    }
                    freeReg(tmp);
                }
                return;
            }
            if (member.computed) {
                expr(member.property);
                emit(Op.TO_KEY_FOR_BASE);
                emit(Op.DUP2);
                mark(member);
                emit(Op.GET_ELEM);                     // o k v
            } else {
                emit(Op.DUP);
                mark(member);
                emit(Op.GET_PROP, keyConst(((Identifier) member.property).name)); // o v
            }
            emit(Op.TO_NUMERIC);
            if (n.prefix) {
                emit(op);
                if (member.computed) {
                    emit(Op.PUT_ELEM);
                } else {
                    emit(Op.PUT_PROP, keyConst(((Identifier) member.property).name));
                }
                if (!keep) {
                    emit(Op.POP);
                }
            } else {
                int tmp = allocReg();
                emit(Op.DUP);
                emit(Op.STORE_REG, tmp);
                emit(op);
                if (member.computed) {
                    emit(Op.PUT_ELEM);
                } else {
                    emit(Op.PUT_PROP, keyConst(((Identifier) member.property).name));
                }
                emit(Op.POP);
                if (keep) {
                    emit(Op.LOAD_REG, tmp);
                }
                freeReg(tmp);
            }
        } else if (t instanceof CallExpression) {
            expr(t);
            emit(Op.POP);
            throwError(1, "Invalid left-hand side expression in " + (n.prefix ? "prefix" : "postfix") + " operation");
            if (keep) {
                emit(Op.PUSH_UNDEF);
            }
        } else {
            throw new CompileError("bad update target");
        }
    }

    // ================================================================== super

    /** Pushes [this key base] for a super property reference. */
    void superRefPrefix(MemberExpression t) {
        thisLoad((Node) t.ref);
        if (t.computed) {
            expr(t.property);
        } else {
            emit(Op.PUSH_CONST, keyConst(((Identifier) t.property).name));
        }
        loadPseudo(t.obj, "%home");
        emit(Op.GET_SUPER_BASE);
    }

    /** (this key base -- this key base value): reads the super property keeping the reference. */
    void superGetFromPrefix() {
        // stack: this key base  -> need value = base.[[Get]](key, this)
        emit(Op.DUP3);                                 // this key base this key base
        emit(Op.GET_SUPER);                            // this key base v
    }

    /** (this key base v -- v?) */
    void emitPutSuper(boolean keep) {
        emit(Op.PUT_SUPER);
        if (!keep) {
            emit(Op.POP);
        }
    }

    // ================================================================== members

    void member(MemberExpression n) {
        if (n.obj instanceof Super) {
            superRefPrefix(n);
            mark(n);
            emit(Op.GET_SUPER);
            return;
        }
        expr(n.obj);
        if (n.optional) {
            optionalCheck();
        }
        memberGet(n);
    }

    /** With the object on the stack, performs the property get of [n]. */
    void memberGet(MemberExpression n) {
        Node p = n.property;
        if (p instanceof PrivateIdentifier privateIdentifier) {
            privateNameLoad(privateIdentifier);
            mark(n);
            emit(Op.GET_PRIVATE);
        } else if (n.computed) {
            expr(p);
            mark(n);
            emit(Op.GET_ELEM);
        } else {
            mark(n);
            emit(Op.GET_PROP, keyConst(((Identifier) p).name));
        }
    }

    // ================================================================== optional chains

    private HashMap<Integer, Label> chainNil = null;
    private int chainBase = 0;

    /** Evaluates an optional chain; a short-circuited chain produces undefined ([shortCircuitOp] pushes it). */
    private void chainWith(ChainExpression n, int shortCircuitOp, Consumer<Node> body) {
        HashMap<Integer, Label> savedNil = chainNil;
        int savedBase = chainBase;
        HashMap<Integer, Label> nil = new HashMap<>();
        chainNil = nil;
        chainBase = currentDepth();
        body.accept(n.expression);
        Label end = newLabel();
        emitJump(Op.JUMP, end);
        int resultDepth = currentDepth();
        for (Map.Entry<Integer, Label> entry : nil.entrySet()) {
            int d = entry.getKey();
            Label l = entry.getValue();
            resetDepth(d);
            place(l);
            for (int i = 0; i < d - chainBase; i++) {
                emit(Op.POP);
            }
            emit(shortCircuitOp);
            emitJump(Op.JUMP, end);
        }
        resetDepth(resultDepth);
        place(end);
        chainNil = savedNil;
        chainBase = savedBase;
    }

    private void chainWith(ChainExpression n, Consumer<Node> body) {
        chainWith(n, Op.PUSH_UNDEF, body);
    }

    /** Emits a nullish check for an optional link; value to test is on top of stack. */
    void optionalCheck() {
        HashMap<Integer, Label> nil = chainNil;
        if (nil == null) {
            throw new CompileError("optional outside chain");
        }
        emit(Op.DUP);
        int d = currentDepth() - 1;
        Label l = nil.get(d);
        if (l == null) {
            l = new Label();
            nil.put(d, l);
        }
        emitJump(Op.JUMP_IF_NULLISH, l);
        l.depth = d;
    }

    void chain(ChainExpression n) {
        chainWith(n, inner -> expr(inner));
    }

    /** Evaluates the object part of a member expression, handling optional links. */
    void memberObject(MemberExpression n) {
        expr(n.obj);
        if (n.optional) {
            optionalCheck();
        }
    }

    // ================================================================== calls

    /** Pushes [f, this] for a call's callee. */
    void callee(Node c) {
        if (c instanceof MemberExpression member) {
            if (member.obj instanceof Super) {
                thisLoad((Node) member.ref);
                emit(Op.DUP);
                if (member.computed) {
                    expr(member.property);
                } else {
                    emit(Op.PUSH_CONST, keyConst(((Identifier) member.property).name));
                }
                loadPseudo(member.obj, "%home");
                emit(Op.GET_SUPER_BASE);
                emit(Op.GET_SUPER);                    // this f
                emit(Op.SWAP);
                return;
            }
            expr(member.obj);
            if (member.optional) {
                optionalCheck();
            }
            emit(Op.DUP);
            memberGet(member);
            emit(Op.SWAP);
        } else if (c instanceof Identifier identifier) {
            Object ref = identifier.ref;
            if (ref instanceof DynamicRef) {
                emit(Op.LOAD_NAME_CALL, constValue(identifier.name));
            } else {
                identLoad(identifier);
                emit(Op.PUSH_UNDEF);
            }
        } else if (c instanceof ChainExpression chain) {
            // (a?.b)() : this is preserved
            Node inner = chain.expression;
            if (inner instanceof MemberExpression member && !(member.obj instanceof Super)) {
                HashMap<Integer, Label> savedNil = chainNil;
                int savedBase = chainBase;
                HashMap<Integer, Label> nil = new HashMap<>();
                chainNil = nil;
                chainBase = currentDepth();
                expr(member.obj);
                if (member.optional) {
                    optionalCheck();
                }
                emit(Op.DUP);
                memberGet(member);
                emit(Op.SWAP);
                Label end = newLabel();
                emitJump(Op.JUMP, end);
                int rd = currentDepth();
                for (Map.Entry<Integer, Label> entry : nil.entrySet()) {
                    int d = entry.getKey();
                    Label l = entry.getValue();
                    resetDepth(d);
                    place(l);
                    for (int i = 0; i < d - chainBase; i++) {
                        emit(Op.POP);
                    }
                    emit(Op.PUSH_UNDEF);
                    emit(Op.PUSH_UNDEF);
                    emitJump(Op.JUMP, end);
                }
                resetDepth(rd);
                place(end);
                chainNil = savedNil;
                chainBase = savedBase;
            } else {
                expr(c);
                emit(Op.PUSH_UNDEF);
            }
        } else {
            expr(c);
            emit(Op.PUSH_UNDEF);
        }
    }

    private boolean hasSpread(List<Node> args) {
        for (Node arg : args) {
            if (arg instanceof SpreadElement) {
                return true;
            }
        }
        return false;
    }

    /** Pushes arguments; returns argc, or -1 when a spread array was pushed instead. */
    int arguments(List<Node> args) {
        if (!hasSpread(args)) {
            for (Node a : args) {
                expr(a);
            }
            return args.size();
        }
        emit(Op.NEW_ARRAY);
        for (Node a : args) {
            if (a instanceof SpreadElement spread) {
                expr(spread.argument);
                emit(Op.ARRAY_SPREAD);
            } else {
                expr(a);
                emit(Op.ARRAY_PUSH);
            }
        }
        return -1;
    }

    // ================================================================== proper tail calls

    /** Call nodes in tail position of the expression being returned (identity set), or null. */
    private Set<Node> tailCalls = null;

    /** Number of enclosing `try` blocks (a call there is not in tail position: the handler must run). */
    protected int tryDepth = 0;

    /**
     * Whether a `return` here can perform proper tail calls (ECMA-262 IsInTailPosition): strict code in an ordinary
     * function body, not inside try / finally / iterator-closing / disposal regions.
     */
    private boolean tailCallsAllowed() {
        if (!isStrict() || fi.isTopLevel || fi.isGenerator || fi.isAsync || tryDepth > 0) {
            return false;
        }
        switch (fi.kind) {
            case NORMAL, ARROW, METHOD, GETTER, SETTER -> {
            }
            default -> {
                return false;
            }
        }
        Ctl c = ctl;
        while (c != null) {
            if (c instanceof FinallyCtl || c instanceof IterCtl) {
                return false;
            }
            c = c.parent;
        }
        return true;
    }

    private void collectTailCalls(Node e, Set<Node> out) {
        if (e instanceof CallExpression call) {
            if (!(call.callee instanceof Super) && !call.optional && !hasSpread(call.arguments)) {
                out.add(e);
            }
        } else if (e instanceof TaggedTemplate) {
            out.add(e);
        } else if (e instanceof ConditionalExpression conditional) {
            collectTailCalls(conditional.consequent, out);
            collectTailCalls(conditional.alternate, out);
        } else if (e instanceof LogicalExpression logical) {
            collectTailCalls(logical.right, out);
        } else if (e instanceof SequenceExpression sequence) {
            collectTailCalls(sequence.expressions.get(sequence.expressions.size() - 1), out);
        }
    }

    /** Evaluates the operand of a return (or a concise arrow body), marking calls in tail position. */
    void exprReturned(Node e) {
        if (!tailCallsAllowed()) {
            expr(e);
            return;
        }
        Set<Node> set = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        collectTailCalls(e, set);
        Set<Node> saved = tailCalls;
        tailCalls = set.isEmpty() ? null : set;
        try {
            expr(e);
        } finally {
            tailCalls = saved;
        }
    }

    private boolean isTail(Node n) {
        return tailCalls != null && tailCalls.contains(n);
    }

    void call(CallExpression n) {
        Node c = n.callee;
        if (c instanceof Super) {
            superCall(n);
            return;
        }
        boolean tail = isTail(n);
        if (c instanceof Identifier identifier && "eval".equals(identifier.name) && !n.optional) {
            callee(c);
            int argc = arguments(n.arguments);
            mark(n);
            markCallee(c);
            int flags = (isStrict() ? 1 : 0) | (tail ? 2 : 0);
            if (argc >= 0) {
                emit(Op.CALL_EVAL, argc, flags);
            } else {
                emit(Op.CALL_EVAL_SPREAD, flags);
            }
            return;
        }
        callee(c);
        if (n.optional) {
            // f?.() : check the function value (below this)
            emit(Op.SWAP);
            optionalCheck();
            emit(Op.SWAP);
        }
        int argc = arguments(n.arguments);
        mark(n);
        markCallee(c);
        if (argc >= 0) {
            emit(tail ? Op.TAIL_CALL : Op.CALL, argc);
        } else {
            emit(Op.CALL_SPREAD);
        }
    }

    private void superCall(CallExpression n) {
        // func = activeFunction.[[GetPrototypeOf]]() evaluated before arguments
        loadPseudo(n.callee, "%fn");
        emit(Op.GET_PROTO_OF);
        loadPseudo((Node) n.callee.scope, "new.target");
        int argc = arguments(n.arguments);
        mark(n);
        if (argc >= 0) {
            emit(Op.SUPER_CALL, argc);
        } else {
            emit(Op.SUPER_CALL_SPREAD);
        }
        // bind this
        emit(Op.DUP);
        Node th = (Node) n.ref;
        Object ref = th.ref;
        if (ref instanceof LocalRef local) {
            Binding b = local.binding;
            if (b.inEnv()) {
                emit(Op.INIT_THIS_ENV, hops(b.scope), b.slot);
            } else {
                emit(Op.INIT_THIS_REG, b.reg);
            }
        } else {
            emit(Op.INIT_THIS_NAME);
        }
        loadPseudo(n.callee, "%fn");
        emit(Op.INIT_INSTANCE);
    }

    private void newExpr(NewExpression n) {
        expr(n.callee);
        int argc = arguments(n.arguments);
        mark(n);
        markCallee(n.callee);
        if (argc >= 0) {
            emit(Op.NEW, argc);
        } else {
            emit(Op.NEW_SPREAD);
        }
    }

    // ================================================================== templates

    private void template(TemplateLiteral n) {
        emit(Op.PUSH_CONST, constValue(n.quasis.get(0).cooked != null ? n.quasis.get(0).cooked : ""));
        for (int i = 0; i < n.expressions.size(); i++) {
            expr(n.expressions.get(i));
            emit(Op.TO_STRING);
            emit(Op.CONCAT);
            String q = n.quasis.get(i + 1).cooked != null ? n.quasis.get(i + 1).cooked : "";
            if (!q.isEmpty()) {
                emit(Op.PUSH_CONST, constValue(q));
                emit(Op.CONCAT);
            }
        }
    }

    private void taggedTemplate(TaggedTemplate n) {
        callee(n.tag);
        TemplateLiteral q = n.quasi;
        String[] cooked = new String[q.quasis.size()];
        String[] raw = new String[q.quasis.size()];
        for (int i = 0; i < q.quasis.size(); i++) {
            cooked[i] = q.quasis.get(i).cooked;
            raw[i] = q.quasis.get(i).raw;
        }
        TemplateSite site = new TemplateSite(cooked, raw);
        emit(Op.TEMPLATE_OBJECT, constValue(site));
        for (Node e : q.expressions) {
            expr(e);
        }
        mark(n);
        markCallee(n.tag);
        emit(isTail(n) ? Op.TAIL_CALL : Op.CALL, q.expressions.size() + 1);
    }

    // ================================================================== literals

    private void arrayLiteral(ArrayLiteral n) {
        emit(Op.NEW_ARRAY);
        for (Node e : n.elements) {
            if (e == null) {
                emit(Op.ARRAY_HOLE);
            } else if (e instanceof SpreadElement spread) {
                expr(spread.argument);
                emit(Op.ARRAY_SPREAD);
            } else {
                expr(e);
                emit(Op.ARRAY_PUSH);
            }
        }
    }

    void propertyKey(Node key, boolean computed) {
        if (computed) {
            expr(key);
            emit(Op.TO_PROPERTY_KEY);
            return;
        }
        if (key instanceof Identifier identifier) {
            emit(Op.PUSH_CONST, keyConst(identifier.name));
        } else if (key instanceof StringLiteral literal) {
            emit(Op.PUSH_CONST, keyConst(literal.value));
        } else if (key instanceof NumberLiteral literal) {
            emit(Op.PUSH_CONST, constValue(PK.fromDouble(literal.value)));
        } else if (key instanceof BigIntLiteral literal) {
            emit(Op.PUSH_CONST, keyConst(literal.value.toString()));
        } else {
            throw new CompileError("bad key");
        }
    }

    String staticKeyName(Node key, boolean computed) {
        if (computed) {
            return null;
        }
        if (key instanceof Identifier identifier) {
            return identifier.name;
        }
        if (key instanceof StringLiteral literal) {
            return literal.value;
        }
        if (key instanceof NumberLiteral literal) {
            return NumberConv.toString(literal.value);
        }
        if (key instanceof BigIntLiteral literal) {
            return literal.value.toString();
        }
        if (key instanceof PrivateIdentifier privateIdentifier) {
            return "#" + privateIdentifier.name;
        }
        return null;
    }

    private void objectLiteral(ObjectLiteral n) {
        Object[] keys = literalKeys(n);
        if (keys != null) {
            // all `key: value` with distinct static keys: evaluate the values, then build the object in its final shape
            for (Node p0 : n.properties) {
                Property p = (Property) p0;
                exprNamed(p.value, new StaticName(staticKeyName(p.key, false)));
            }
            emit(Op.NEW_OBJECT_LITERAL, constValue(new ObjectLiteralSite(keys)), keys.length);
            return;
        }
        emit(Op.NEW_OBJECT);
        boolean needsHome = false;
        for (Node p : n.properties) {
            if (p instanceof Property property && (property.method || property.kind != PropKind.INIT)) {
                needsHome = true;
                break;
            }
        }
        int homeReg = -1;
        if (needsHome) {
            homeReg = allocReg();
            emit(Op.DUP);
            emit(Op.STORE_REG, homeReg);
        }
        for (Node p0 : n.properties) {
            if (p0 instanceof SpreadElement spread) {
                expr(spread.argument);
                emit(Op.COPY_DATA_PROPS);
                continue;
            }
            Property p = (Property) p0;
            Node v = p.value;
            if (p.kind == PropKind.INIT && !p.method && !p.computed && !p.shorthand && isProtoKey(p.key)) {
                expr(v);
                emit(Op.SET_PROTO);
                continue;
            }
            String name = staticKeyName(p.key, p.computed);
            if (p.kind != PropKind.INIT || p.method) {
                propertyKey(p.key, p.computed);
                FunctionNode fn = (FunctionNode) v;
                CodeBlock cb = compileNested(fn, name != null ? name : "");
                emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                switch (p.kind) {
                    case GET -> emit(Op.DEFINE_GETTER, 1);
                    case SET -> emit(Op.DEFINE_SETTER, 1);
                    default -> emit(Op.DEFINE_METHOD, 1);
                }
                continue;
            }
            if (!p.computed && name != null) {
                exprNamed(v, new StaticName(name));
                emit(Op.DEFINE_FIELD, keyConst(name));
            } else {
                propertyKey(p.key, true);
                if (isAnonymousFn(v)) {
                    if (v instanceof ClassNode cls) {
                        emit(Op.DUP);
                        classExpr(cls, DynamicName.INSTANCE);
                    } else {
                        exprNamed(v, null);
                        emit(Op.SET_FUNCTION_NAME, -1);
                    }
                } else {
                    expr(v);
                }
                emit(Op.DEFINE_FIELD_ELEM);
            }
        }
        if (homeReg >= 0) {
            freeReg(homeReg);
        }
    }

    /** The canonical keys of a literal eligible for NEW_OBJECT_LITERAL, else null. */
    private Object[] literalKeys(ObjectLiteral n) {
        List<Node> props = n.properties;
        if (props.isEmpty() || props.size() > PropertyMap.MAX_LAYOUT) {
            return null;
        }
        ArrayList<Object> keys = new ArrayList<>(props.size());
        for (Node p0 : props) {
            if (!(p0 instanceof Property p) || p.kind != PropKind.INIT || p.method || p.computed) {
                return null;
            }
            if (!p.shorthand && isProtoKey(p.key)) {
                return null;
            }
            String name = staticKeyName(p.key, false);
            if (name == null) {
                return null;
            }
            Object key = PK.fromString(name);
            if (keys.contains(key)) {
                return null;
            }
            keys.add(key);
        }
        return keys.toArray();
    }

    private boolean isProtoKey(Node k) {
        return (k instanceof Identifier identifier && "__proto__".equals(identifier.name))
                || (k instanceof StringLiteral literal && "__proto__".equals(literal.value));
    }

    // ================================================================== functions

    void function(FunctionNode fn, FnName name) {
        String staticName;
        if (fn.id != null) {
            staticName = fn.id.name;
        } else if (name instanceof StaticName staticNameValue) {
            staticName = staticNameValue.name;
        } else {
            staticName = "";
        }
        CodeBlock cb = compileNested(fn, staticName);
        emit(Op.MAKE_CLOSURE, constValue(cb));
    }

    // ================================================================== classes

    /** Compiles a class; [name]: DynamicName means the name is on top of the stack (consumed). */
    void classExpr(ClassNode cls, FnName name) {
        // all parts of a class are strict mode code, including what runs in the enclosing function: the heritage,
        // computed keys and decorators
        strictDepth++;
        try {
            if (usesClassDef(cls)) {
                classExprWithDef(cls, name);
            } else {
                plainClassExpr(cls, name);
            }
        } finally {
            strictDepth--;
        }
    }

    private static final class StaticEl {
        final boolean isBlock;
        final int keyReg;
        final int fnReg;

        StaticEl(boolean isBlock, int keyReg, int fnReg) {
            this.isBlock = isBlock;
            this.keyReg = keyReg;
            this.fnReg = fnReg;
        }
    }

    private void plainClassExpr(ClassNode cls, FnName name) {
        Scope cs = (Scope) cls.scope;
        boolean dynName = name instanceof DynamicName && cls.id == null;
        String className = cls.id != null ? cls.id.name : (name instanceof StaticName staticName ? staticName.name : "");
        int nameReg = -1;
        if (dynName) {
            nameReg = allocReg();
            emit(Op.STORE_REG, nameReg);
        }
        Scope outerScope = scope;
        enterScope(cs);
        // private names
        for (Binding b : cs.bindings.values()) {
            if (b.kind == BKind.PRIVATE) {
                emit(Op.NEW_PRIVATE_NAME, constValue(b.name));
                emitInitBinding(b);
            }
        }
        int flags = 0;
        if (cls.superClass != null) {
            flags = flags | 1;
            expr(cls.superClass);
        }
        if (dynName) {
            flags = flags | 2;
            emit(Op.LOAD_REG, nameReg);
        }
        // constructor template
        FunctionNode ctorFn = cls.constructor;
        CodeBlock ctorCb = ctorFn != null ? compileNested(ctorFn, className) : defaultConstructor(cls, className);
        if (ctorCb.sourceText() == null || true) {
            ctorCb.source = source;
            ctorCb.srcStart = cls.srcStart;
            ctorCb.srcEnd = cls.srcEnd;
        }
        mark(cls);
        emit(Op.MAKE_CLASS, constValue(ctorCb), flags); // proto ctor
        int rC = allocReg();
        int rP = allocReg();
        emit(Op.STORE_REG, rC);
        emit(Op.STORE_REG, rP);
        // static elements executed after definition: (kind, regs)
        ArrayList<StaticEl> statics = new ArrayList<>();
        ArrayList<int[]> staticPrivMethods = new ArrayList<>(); // pnReg? -> (kind, pnRegIdx, fnReg)
        for (Node el : cls.body) {
            if (el instanceof MethodDefinition method) {
                if (method.kind == MethodKind.CONSTRUCTOR) {
                    continue;
                }
                int homeReg = method.isStatic ? rC : rP;
                Node key = method.key;
                String fnName = staticKeyName(key, method.computed);
                CodeBlock cb = compileNested(method.value, fnName != null ? fnName : "");
                if (key instanceof PrivateIdentifier privateIdentifier) {
                    int kind = method.kind == MethodKind.GET ? 1 : method.kind == MethodKind.SET ? 2 : 0;
                    if (method.isStatic) {
                        int fnReg = allocReg();
                        emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                        emit(Op.STORE_REG, fnReg);
                        int pnReg = allocReg();
                        privateNameLoad(privateIdentifier);
                        emit(Op.STORE_REG, pnReg);
                        staticPrivMethods.add(new int[]{kind, pnReg, fnReg});
                    } else {
                        emit(Op.LOAD_REG, rC);
                        privateNameLoad(privateIdentifier);
                        emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                        emit(Op.ADD_PRIVATE_METHOD, kind);
                    }
                    continue;
                }
                emit(Op.LOAD_REG, homeReg);
                propertyKey(key, method.computed);
                emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                switch (method.kind) {
                    case GET -> emit(Op.DEFINE_GETTER, 0);
                    case SET -> emit(Op.DEFINE_SETTER, 0);
                    default -> emit(Op.DEFINE_METHOD, 0);
                }
                emit(Op.POP);
            } else if (el instanceof PropertyDefinition property) {
                Node key = property.key;
                int homeReg = property.isStatic ? rC : rP;
                // key
                int keyReg = allocReg();
                if (key instanceof PrivateIdentifier privateIdentifier) {
                    privateNameLoad(privateIdentifier);
                } else {
                    propertyKey(key, property.computed);
                }
                emit(Op.STORE_REG, keyReg);
                int fnReg = allocReg();
                FunctionNode init = property.initializer;
                if (init != null) {
                    String fnName = staticKeyName(key, property.computed);
                    CodeBlock cb = compileNested(init, fnName != null ? fnName : "", property.computed);
                    emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                } else {
                    emit(Op.PUSH_UNDEF);
                }
                emit(Op.STORE_REG, fnReg);
                if (property.isStatic) {
                    statics.add(new StaticEl(false, keyReg, fnReg));
                } else {
                    emit(Op.LOAD_REG, rC);
                    emit(Op.LOAD_REG, keyReg);
                    emit(Op.LOAD_REG, fnReg);
                    emit(Op.ADD_FIELD);
                    freeReg(keyReg);
                    freeReg(fnReg);
                }
            } else if (el instanceof StaticBlock staticBlock) {
                CodeBlock cb = compileNested(staticBlock.function, "");
                int fnReg = allocReg();
                emit(Op.MAKE_METHOD, constValue(cb), rC);
                emit(Op.STORE_REG, fnReg);
                statics.add(new StaticEl(true, -1, fnReg));
            }
        }
        // inner class binding
        if (cls.id != null) {
            Binding b = cs.bindings.get(cls.id.name);
            emit(Op.LOAD_REG, rC);
            emitInitBinding(b);
        }
        for (int[] triple : staticPrivMethods) {
            int kind = triple[0];
            int pnReg = triple[1];
            int fnReg = triple[2];
            emit(Op.LOAD_REG, rC);
            emit(Op.LOAD_REG, pnReg);
            emit(Op.LOAD_REG, fnReg);
            emit(Op.STATIC_PRIVATE_METHOD, kind);
            freeReg(pnReg);
            freeReg(fnReg);
        }
        for (StaticEl s : statics) {
            if (s.isBlock) {
                emit(Op.LOAD_REG, s.fnReg);
                emit(Op.LOAD_REG, rC);
                emit(Op.CALL, 0);
                emit(Op.POP);
            } else {
                emit(Op.LOAD_REG, rC);
                emit(Op.LOAD_REG, s.keyReg);
                emit(Op.LOAD_REG, s.fnReg);
                emit(Op.RUN_FIELD);
                freeReg(s.keyReg);
            }
            freeReg(s.fnReg);
        }
        emit(Op.LOAD_REG, rC);
        freeReg(rC);
        freeReg(rP);
        if (nameReg >= 0) {
            freeReg(nameReg);
        }
        exitScope(cs);
        scope = outerScope;
    }

    public abstract CodeBlock defaultConstructor(ClassNode cls, String name);

    /** Classes with decorators or `accessor` fields are built through a runtime class definition (vm/Decorators.kt). */
    private boolean usesClassDef(ClassNode cls) {
        if (!cls.decorators.isEmpty()) {
            return true;
        }
        for (Node el : cls.body) {
            if (el instanceof MethodDefinition method && !method.decorators.isEmpty()) {
                return true;
            }
            if (el instanceof PropertyDefinition property
                    && (!property.decorators.isEmpty() || property.isAccessor)) {
                return true;
            }
        }
        return false;
    }

    /** Pushes an array of evaluated decorators, or undefined when there are none. */
    private void decoratorArray(List<Node> decs) {
        if (decs.isEmpty()) {
            emit(Op.PUSH_UNDEF);
            return;
        }
        emit(Op.NEW_ARRAY);
        for (Node d : decs) {
            expr(d);
            emit(Op.ARRAY_PUSH);
        }
    }

    private void classExprWithDef(ClassNode cls, FnName name) {
        Scope cs = (Scope) cls.scope;
        boolean dynName = name instanceof DynamicName && cls.id == null;
        String className = cls.id != null ? cls.id.name : (name instanceof StaticName staticName ? staticName.name : "");
        int nameReg = -1;
        if (dynName) {
            nameReg = allocReg();
            emit(Op.STORE_REG, nameReg);
        }
        // class decorators are evaluated first, in the enclosing scope
        int classDecReg = -1;
        if (!cls.decorators.isEmpty()) {
            classDecReg = allocReg();
            decoratorArray(cls.decorators);
            emit(Op.STORE_REG, classDecReg);
        }
        Scope outerScope = scope;
        enterScope(cs);
        for (Binding b : cs.bindings.values()) {
            if (b.kind == BKind.PRIVATE) {
                emit(Op.NEW_PRIVATE_NAME, constValue(b.name));
                emitInitBinding(b);
            }
        }
        int flags = 0;
        if (cls.superClass != null) {
            flags = flags | 1;
            expr(cls.superClass);
        }
        if (dynName) {
            flags = flags | 2;
            emit(Op.LOAD_REG, nameReg);
        }
        FunctionNode ctorFn = cls.constructor;
        CodeBlock ctorCb = ctorFn != null ? compileNested(ctorFn, className) : defaultConstructor(cls, className);
        ctorCb.source = source;
        ctorCb.srcStart = cls.srcStart;
        ctorCb.srcEnd = cls.srcEnd;
        mark(cls);
        emit(Op.MAKE_CLASS, constValue(ctorCb), flags); // proto ctor
        int rC = allocReg();
        int rP = allocReg();
        emit(Op.STORE_REG, rC);
        emit(Op.STORE_REG, rP);
        emit(Op.LOAD_REG, rC);
        emit(Op.LOAD_REG, rP);
        emit(Op.CLASS_DEF_NEW);
        int rDef = allocReg();
        emit(Op.STORE_REG, rDef);
        for (Node el : cls.body) {
            if (el instanceof MethodDefinition method) {
                if (method.kind == MethodKind.CONSTRUCTOR) {
                    continue;
                }
                int homeReg = method.isStatic ? rC : rP;
                emit(Op.LOAD_REG, rDef);
                decoratorArray(method.decorators);
                Node key = method.key;
                if (key instanceof PrivateIdentifier privateIdentifier) {
                    privateNameLoad(privateIdentifier);
                } else {
                    propertyKey(key, method.computed);
                }
                String fnName = staticKeyName(key, method.computed);
                CodeBlock cb = compileNested(method.value, fnName != null ? fnName : "");
                emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                int kind = method.kind == MethodKind.GET ? 1 : method.kind == MethodKind.SET ? 2 : 0;
                emit(Op.CLASS_ELEMENT, kind | (method.isStatic ? 8 : 0));
            } else if (el instanceof PropertyDefinition property) {
                Node key = property.key;
                int homeReg = property.isStatic ? rC : rP;
                emit(Op.LOAD_REG, rDef);
                decoratorArray(property.decorators);
                if (key instanceof PrivateIdentifier privateIdentifier) {
                    privateNameLoad(privateIdentifier);
                } else {
                    propertyKey(key, property.computed);
                }
                FunctionNode init = property.initializer;
                if (init != null) {
                    String fnName = staticKeyName(key, property.computed);
                    CodeBlock cb = compileNested(init, fnName != null ? fnName : "", property.computed);
                    emit(Op.MAKE_METHOD, constValue(cb), homeReg);
                } else {
                    emit(Op.PUSH_UNDEF);
                }
                emit(Op.CLASS_ELEMENT, (property.isAccessor ? 4 : 3) | (property.isStatic ? 8 : 0));
            } else if (el instanceof StaticBlock staticBlock) {
                emit(Op.LOAD_REG, rDef);
                emit(Op.PUSH_UNDEF);
                emit(Op.PUSH_UNDEF);
                CodeBlock cb = compileNested(staticBlock.function, "");
                emit(Op.MAKE_METHOD, constValue(cb), rC);
                emit(Op.CLASS_ELEMENT, 5 | 8);
            }
        }
        emit(Op.LOAD_REG, rDef);
        emit(Op.CLASS_FINISH);
        emit(Op.LOAD_REG, rDef);
        if (classDecReg >= 0) {
            emit(Op.LOAD_REG, classDecReg);
        } else {
            emit(Op.PUSH_UNDEF);
        }
        emit(Op.CLASS_DECORATE);
        int rFinal = allocReg();
        emit(Op.STORE_REG, rFinal);
        if (cls.id != null) {
            Binding b = cs.bindings.get(cls.id.name);
            emit(Op.LOAD_REG, rFinal);
            emitInitBinding(b);
        }
        emit(Op.LOAD_REG, rDef);
        emit(Op.CLASS_STATIC_INIT);
        emit(Op.LOAD_REG, rFinal);
        freeReg(rFinal);
        freeReg(rDef);
        freeReg(rC);
        freeReg(rP);
        if (classDecReg >= 0) {
            freeReg(classDecReg);
        }
        if (nameReg >= 0) {
            freeReg(nameReg);
        }
        exitScope(cs);
        scope = outerScope;
    }

    // ================================================================== destructuring

    /** Binds the value on top of the stack (consumed) to [target]. */
    void pattern(Node target, BindMode mode) {
        if (target instanceof Identifier identifier) {
            bindIdentifier(identifier, mode);
        } else if (target instanceof MemberExpression member) {
            // value on stack; evaluate reference afterwards (only used for simple member targets of rest/elements)
            memberAssignFromStack(member);
        } else if (target instanceof AssignmentPattern assignmentPattern) {
            // value on stack; default if undefined
            Label skip = newLabel();
            emit(Op.DUP);
            emitJump(Op.JUMP_IF_NOT_UNDEFINED, skip);
            emit(Op.POP);
            exprNamed(assignmentPattern.right,
                    assignmentPattern.left instanceof Identifier identifier
                            ? new StaticName(identifier.name) : null);
            place(skip);
            pattern(assignmentPattern.left, mode);
        } else if (target instanceof ObjectPattern objectPattern) {
            objectPattern(objectPattern, mode);
        } else if (target instanceof ArrayPattern arrayPattern) {
            arrayPattern(arrayPattern, mode);
        } else if (target instanceof CallExpression) {
            emit(Op.POP);
            expr(target);
            emit(Op.POP);
            throwError(1, "Invalid destructuring assignment target");
        } else {
            throw new CompileError("bad pattern " + target.getClass().getSimpleName());
        }
    }

    void bindIdentifier(Identifier id, BindMode mode) {
        if (mode == BindMode.INIT) {
            emitInitDeclared(id);
        } else {
            emitStoreRef(id.ref, id.name);
        }
    }

    /** value on stack -> assign to member target (reference evaluated after value; used when order is unobservable). */
    private void memberAssignFromStack(MemberExpression t) {
        if (t.obj instanceof Super) {
            int tmp = allocReg();
            emit(Op.STORE_REG, tmp);
            superRefPrefix(t);
            emit(Op.LOAD_REG, tmp);
            freeReg(tmp);
            emitPutSuper(false);
            return;
        }
        int tmp = allocReg();
        emit(Op.STORE_REG, tmp);
        expr(t.obj);
        if (t.property instanceof PrivateIdentifier privateIdentifier) {
            privateNameLoad(privateIdentifier);
            emit(Op.LOAD_REG, tmp);
            emit(Op.PUT_PRIVATE);
        } else if (t.computed) {
            expr(t.property);
            emit(Op.LOAD_REG, tmp);
            emit(Op.PUT_ELEM);
        } else {
            emit(Op.LOAD_REG, tmp);
            emit(Op.PUT_PROP, keyConst(((Identifier) t.property).name));
        }
        emit(Op.POP);
        freeReg(tmp);
    }

    /**
     * Target evaluation-first protocol for assignment patterns: evaluates the reference part of a simple target and
     * returns a lambda that stores the value on top of the stack.
     */
    private Runnable prepareTarget(Node target, BindMode mode) {
        Node t = target instanceof AssignmentPattern assignmentPattern ? assignmentPattern.left : target;
        if (t instanceof Identifier identifier && identifier.ref instanceof DynamicRef
                && (mode == BindMode.ASSIGN || mode == BindMode.INIT)) {
            // ResolveBinding happens before the value is read (observable through `with` objects)
            emit(Op.RESOLVE_NAME, constValue(identifier.name));
            return () -> {
                emit(Op.PUT_REF);
                emit(Op.POP);
            };
        }
        if (t instanceof MemberExpression member && mode == BindMode.ASSIGN) {
            if (member.obj instanceof Super) {
                superRefPrefix(member);                // this key base
                return () -> emitPutSuper(false);
            }
            expr(member.obj);
            if (member.property instanceof PrivateIdentifier privateIdentifier) {
                privateNameLoad(privateIdentifier);
                return () -> {
                    emit(Op.PUT_PRIVATE);
                    emit(Op.POP);
                };
            } else if (member.computed) {
                expr(member.property);
                return () -> {
                    emit(Op.PUT_ELEM);
                    emit(Op.POP);
                };
            } else {
                int k = keyConst(((Identifier) member.property).name);
                return () -> {
                    emit(Op.PUT_PROP, k);
                    emit(Op.POP);
                };
            }
        }
        return () -> pattern(t, mode);
    }

    private void applyDefault(Node target) {
        if (target instanceof AssignmentPattern assignmentPattern) {
            Label skip = newLabel();
            emit(Op.DUP);
            emitJump(Op.JUMP_IF_NOT_UNDEFINED, skip);
            emit(Op.POP);
            exprNamed(assignmentPattern.right,
                    assignmentPattern.left instanceof Identifier identifier
                            ? new StaticName(identifier.name) : null);
            place(skip);
        }
    }

    private void objectPattern(ObjectPattern p, BindMode mode) {
        emit(Op.REQUIRE_COERCIBLE);
        int src = allocReg();
        emit(Op.STORE_REG, src);
        boolean hasRest = false;
        for (Node prop : p.properties) {
            if (prop instanceof RestElement) {
                hasRest = true;
                break;
            }
        }
        ArrayList<Integer> keyRegs = new ArrayList<>();
        for (Node prop0 : p.properties) {
            if (prop0 instanceof RestElement rest) {
                Runnable store = prepareTarget(rest.argument, mode);
                emit(Op.NEW_OBJECT);
                emit(Op.LOAD_REG, src);
                for (int kr : keyRegs) {
                    emit(Op.LOAD_REG, kr);
                }
                emit(Op.COPY_DATA_PROPS_EXCL, keyRegs.size());
                store.run();
                continue;
            }
            Property prop = (Property) prop0;
            // key evaluated first, then target reference, then GetV
            int keyReg = -1;
            if (prop.computed || hasRest) {
                propertyKey(prop.key, prop.computed);
                keyReg = allocReg();
                emit(Op.STORE_REG, keyReg);
                if (hasRest) {
                    keyRegs.add(keyReg);
                }
            }
            Runnable store = prepareTarget(prop.value, mode);
            emit(Op.LOAD_REG, src);
            if (keyReg >= 0) {
                emit(Op.LOAD_REG, keyReg);
                emit(Op.GET_ELEM);
            } else {
                String name = staticKeyName(prop.key, false);
                emit(Op.GET_PROP, keyConst(name));
            }
            applyDefault(prop.value);
            store.run();
            if (keyReg >= 0 && !hasRest) {
                freeReg(keyReg);
            }
        }
        for (int kr : keyRegs) {
            freeReg(kr);
        }
        freeReg(src);
    }

    private void arrayPattern(ArrayPattern p, BindMode mode) {
        emit(Op.GET_ITERATOR);
        int it = allocReg();
        emit(Op.STORE_REG, it);
        int start = pc;
        int d = currentDepth();
        Ctl savedCtl = ctl;
        ctl = new IterCtl(ctl, it, false);
        for (Node e : p.elements) {
            if (e == null) {
                emit(Op.ITER_STEP_U, it);
                emit(Op.POP);
                continue;
            }
            if (e instanceof RestElement rest) {
                Runnable store = prepareTarget(rest.argument, mode);
                emit(Op.ITER_REST, it);
                store.run();
                continue;
            }
            Runnable store = prepareTarget(e, mode);
            emit(Op.ITER_STEP_U, it);
            applyDefault(e);
            store.run();
        }
        int end = pc;
        ctl = savedCtl;
        // normal completion: close if not done
        emit(Op.ITER_CLOSE, it);
        Label after = newLabel();
        emitJump(Op.JUMP, after);
        int h = pc;
        resetDepth(d + 1);
        emit(Op.ITER_CLOSE_THROW, it);
        emit(Op.THROW);
        addHandler(start, end, h, d);
        placeAfterJump(after);
        resetDepth(d);
        freeReg(it);
    }

    // ================================================================== generators

    private void yieldExpr(YieldExpression n) {
        if (n.delegate) {
            yieldStar(n);
            return;
        }
        if (n.argument != null) {
            expr(n.argument);
        } else {
            emit(Op.PUSH_UNDEF);
        }
        if (isAsyncGenerator()) {
            emit(Op.AWAIT);
            emit(Op.YIELD);
            Label ret = newLabel();
            Label cont = newLabel();
            emitJump(Op.RESUME_DISPATCH, ret);
            emitJump(Op.JUMP, cont);
            placeAfterJump(ret);
            emit(Op.AWAIT);
            emitReturnValue();
            placeAfterJump(cont);
        } else {
            emit(Op.YIELD);
            Label ret = newLabel();
            Label cont = newLabel();
            emitJump(Op.RESUME_DISPATCH, ret);
            emitJump(Op.JUMP, cont);
            placeAfterJump(ret);
            emitReturnValue();
            placeAfterJump(cont);
        }
    }

    /** Patches the jump target (last operand) of the previous instruction; [depthAtTarget] is the stack depth there. */
    void patchJumpOperand(Label l, int depthAtTarget) {
        int at = pc - 1;
        if (l.pos >= 0) {
            code[at] = l.pos;
        } else {
            l.fixups.add(at);
        }
        if (l.depth < 0) {
            l.depth = depthAtTarget;
        }
    }

    private void yieldStar(YieldExpression n) {
        expr(n.argument);
        boolean async = isAsyncGenerator();
        emit(async ? Op.GET_ASYNC_ITERATOR : Op.GET_ITERATOR);
        int it = allocReg();
        emit(Op.STORE_REG, it);
        int mode = allocReg();
        pushInt(0);
        emit(Op.STORE_REG, mode);
        emit(Op.PUSH_UNDEF);
        Label loop = newLabel();
        Label done = newLabel();
        place(loop);
        if (!async) {
            emit(Op.YSTAR, it, mode, 0);
            patchJumpOperand(done, currentDepth());
            emit(Op.YIELD_RAW);
            emit(Op.RESUME_MODE);
            emit(Op.STORE_REG, mode);
            emitJump(Op.JUMP, loop);
        } else {
            emit(Op.YSTAR_ASYNC_CALL, it, mode, 0);
            patchJumpOperand(done, currentDepth());
            emitAwait();
            emit(Op.YSTAR_ASYNC_RESULT, it, mode, 0);
            patchJumpOperand(done, currentDepth());
            emit(Op.YIELD);
            emit(Op.RESUME_MODE);
            emit(Op.STORE_REG, mode);
            emit(Op.LOAD_REG, mode);
            pushInt(2);
            emit(Op.SEQ);
            emitJump(Op.JUMP_IF_FALSE, loop);
            emit(Op.AWAIT_CATCH, mode);
            emitJump(Op.JUMP, loop);
        }
        placeAfterJump(done);                          // value
        if (async) {
            Label notMissing = newLabel();
            emit(Op.LOAD_REG, mode);
            pushInt(3);
            emit(Op.SEQ);
            emitJump(Op.JUMP_IF_FALSE, notMissing);
            emit(Op.POP);
            Label skip = newLabel();
            emit(Op.ASYNC_ITER_CLOSE, it, 0, 0);
            patchJumpOperand(skip, currentDepth() - 1);
            emitAwait();
            emit(Op.CHECK_OBJECT, constValue("Iterator result is not an object"));
            emit(Op.POP);
            place(skip);
            throwError(0, "The iterator does not provide a 'throw' method");
            emit(Op.PUSH_UNDEF);
            place(notMissing);
        }
        Label normal = newLabel();
        emit(Op.LOAD_REG, mode);
        pushInt(2);
        emit(Op.SEQ);
        emitJump(Op.JUMP_IF_FALSE, normal);
        // return completion: in async generators the value is awaited (missing return method, or done result)
        if (async) {
            emitAwait();
        }
        emitReturnValue();
        placeAfterJump(normal);
        freeReg(it);
        freeReg(mode);
    }
}
