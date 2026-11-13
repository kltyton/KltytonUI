/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.ArrayLiteral;
import dev.mooner.neonjs.parser.ArrayPattern;
import dev.mooner.neonjs.parser.AssignmentExpression;
import dev.mooner.neonjs.parser.AssignmentPattern;
import dev.mooner.neonjs.parser.AwaitExpression;
import dev.mooner.neonjs.parser.BigIntLiteral;
import dev.mooner.neonjs.parser.BinaryExpression;
import dev.mooner.neonjs.parser.BlockStatement;
import dev.mooner.neonjs.parser.BooleanLiteral;
import dev.mooner.neonjs.parser.BreakStatement;
import dev.mooner.neonjs.parser.CallExpression;
import dev.mooner.neonjs.parser.CatchClause;
import dev.mooner.neonjs.parser.ChainExpression;
import dev.mooner.neonjs.parser.ClassDeclaration;
import dev.mooner.neonjs.parser.ClassNode;
import dev.mooner.neonjs.parser.ConditionalExpression;
import dev.mooner.neonjs.parser.ContinueStatement;
import dev.mooner.neonjs.parser.DebuggerStatement;
import dev.mooner.neonjs.parser.DoWhileStatement;
import dev.mooner.neonjs.parser.EmptyStatement;
import dev.mooner.neonjs.parser.ExportAllDeclaration;
import dev.mooner.neonjs.parser.ExportDefaultDeclaration;
import dev.mooner.neonjs.parser.ExportNamedDeclaration;
import dev.mooner.neonjs.parser.ExportSpecifier;
import dev.mooner.neonjs.parser.ExpressionStatement;
import dev.mooner.neonjs.parser.ForInStatement;
import dev.mooner.neonjs.parser.ForOfStatement;
import dev.mooner.neonjs.parser.ForStatement;
import dev.mooner.neonjs.parser.FunctionDeclaration;
import dev.mooner.neonjs.parser.FunctionKind;
import dev.mooner.neonjs.parser.FunctionNode;
import dev.mooner.neonjs.parser.Identifier;
import dev.mooner.neonjs.parser.IfStatement;
import dev.mooner.neonjs.parser.ImportCall;
import dev.mooner.neonjs.parser.ImportDeclaration;
import dev.mooner.neonjs.parser.LabeledStatement;
import dev.mooner.neonjs.parser.LogicalExpression;
import dev.mooner.neonjs.parser.MemberExpression;
import dev.mooner.neonjs.parser.MetaProperty;
import dev.mooner.neonjs.parser.MethodDefinition;
import dev.mooner.neonjs.parser.MethodKind;
import dev.mooner.neonjs.parser.NewExpression;
import dev.mooner.neonjs.parser.Node;
import dev.mooner.neonjs.parser.NullLiteral;
import dev.mooner.neonjs.parser.NumberLiteral;
import dev.mooner.neonjs.parser.ObjectLiteral;
import dev.mooner.neonjs.parser.ObjectPattern;
import dev.mooner.neonjs.parser.PrivateIdentifier;
import dev.mooner.neonjs.parser.PrivateInExpression;
import dev.mooner.neonjs.parser.Program;
import dev.mooner.neonjs.parser.Property;
import dev.mooner.neonjs.parser.PropertyDefinition;
import dev.mooner.neonjs.parser.RegExpLiteral;
import dev.mooner.neonjs.parser.RestElement;
import dev.mooner.neonjs.parser.ReturnStatement;
import dev.mooner.neonjs.parser.SequenceExpression;
import dev.mooner.neonjs.parser.SpreadElement;
import dev.mooner.neonjs.parser.StaticBlock;
import dev.mooner.neonjs.parser.StringLiteral;
import dev.mooner.neonjs.parser.Super;
import dev.mooner.neonjs.parser.SwitchCase;
import dev.mooner.neonjs.parser.SwitchStatement;
import dev.mooner.neonjs.parser.TaggedTemplate;
import dev.mooner.neonjs.parser.TemplateLiteral;
import dev.mooner.neonjs.parser.ThisExpression;
import dev.mooner.neonjs.parser.ThrowStatement;
import dev.mooner.neonjs.parser.TryStatement;
import dev.mooner.neonjs.parser.UnaryExpression;
import dev.mooner.neonjs.parser.UpdateExpression;
import dev.mooner.neonjs.parser.VarKind;
import dev.mooner.neonjs.parser.VariableDeclaration;
import dev.mooner.neonjs.parser.VariableDeclarator;
import dev.mooner.neonjs.parser.WhileStatement;
import dev.mooner.neonjs.parser.WithStatement;
import dev.mooner.neonjs.parser.YieldExpression;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the scope tree, declares bindings (with hoisting and Annex B semantics), resolves
 * identifier references and allocates registers / environment slots.
 *
 * <p>Derived from NeonJS and adapted to Java. Kotlin collection helpers are expanded to explicit
 * JDK operations so every original analysis branch is retained.
 */
public final class ScopeAnalyzer {
    public final CodeMode mode;
    public final boolean evalStrict;
    public final boolean evalInFunction;

    private static final class RefSite {
        final Node node;
        final String name;
        final Scope scope;
        final int pos;

        RefSite(Node node, String name, Scope scope, int pos) {
            this.node = node;
            this.name = name;
            this.scope = scope;
            this.pos = pos;
        }
    }

    private static final class BlockFunction {
        final FunctionDeclaration declaration;
        final Scope scope;
        final Binding binding;

        BlockFunction(FunctionDeclaration declaration, Scope scope, Binding binding) {
            this.declaration = declaration;
            this.scope = scope;
            this.binding = binding;
        }
    }

    private final ArrayList<RefSite> refs = new ArrayList<>();
    private final ArrayList<FnInfo> allFunctions = new ArrayList<>();
    private final ArrayList<BlockFunction> blockFunctions = new ArrayList<>();
    private final ArrayList<Scope> evalScopes = new ArrayList<>();

    public FnInfo root;
    /** Global code: names declared at the top level. */
    public final LinkedHashSet<String> globalVarNames = new LinkedHashSet<>();
    public final ArrayList<FunctionDeclaration> globalFunctions = new ArrayList<>();
    public final LinkedHashMap<String, Boolean> globalLexNames = new LinkedHashMap<>();
    /** Annex B function names hoisted to the global/eval var scope. */
    public final LinkedHashSet<String> annexBGlobalNames = new LinkedHashSet<>();

    private Scope cur;
    private FnInfo curFn;
    private final HashSet<Scope> catchPatternScopes = new HashSet<>();
    private final HashMap<FnInfo, ArrayList<Scope>> scopesByFn = new HashMap<>();

    public ScopeAnalyzer(CodeMode mode, boolean evalStrict, boolean evalInFunction) {
        this.mode = mode;
        this.evalStrict = evalStrict;
        this.evalInFunction = evalInFunction;
    }

    public ScopeAnalyzer(CodeMode mode) {
        this(mode, false, false);
    }

    public ScopeAnalyzer(CodeMode mode, boolean evalStrict) {
        this(mode, evalStrict, false);
    }

    public FnInfo analyze(Program program) {
        FnInfo info = new FnInfo(program, null);
        info.isTopLevel = true;
        info.strict = program.getStrict();
        root = info;
        curFn = info;
        allFunctions.add(info);
        ScopeKind kind;
        if (mode == CodeMode.SCRIPT) {
            kind = ScopeKind.GLOBAL;
        } else if (mode == CodeMode.MODULE) {
            kind = ScopeKind.MODULE;
        } else {
            kind = ScopeKind.EVAL;
        }
        Scope scope = new Scope(kind, null, info);
        info.scope = scope;
        info.varScope = scope;
        program.scope = scope;
        cur = scope;
        if (mode == CodeMode.MODULE) {
            scope.allCaptured = true;
        }
        hoistDeclarations(program.getBody(), scope, true);
        for (Node statement : program.getBody()) {
            visitStatement(statement);
        }
        finish();
        return info;
    }

    /** Analyze a function created by the Function constructor (global scope parent). */
    public FnInfo analyzeFunction(FunctionNode function) {
        FnInfo info = new FnInfo(function, null);
        info.isTopLevel = true;
        root = info;
        curFn = info;
        allFunctions.add(info);
        Scope scope = new Scope(ScopeKind.GLOBAL, null, info);
        info.scope = scope;
        info.varScope = scope;
        cur = scope;
        visitFunction(function);
        finish();
        return info;
    }

    private boolean isSloppyEval() {
        return (mode == CodeMode.EVAL_DIRECT || mode == CodeMode.EVAL_INDIRECT) && !evalStrict;
    }

    // ================================================================== declarations

    /** True when declarations in this var scope are global object properties (script / sloppy eval). */
    private boolean varsAreDynamic(Scope scope) {
        return scope.kind == ScopeKind.GLOBAL
                || (scope.kind == ScopeKind.EVAL && isSloppyEval());
    }

    private void declareVar(String name, Scope varScope) {
        if (varsAreDynamic(varScope)) {
            globalVarNames.add(name);
            return;
        }
        Binding existing = varScope.bindings.get(name);
        if (existing == null) {
            varScope.declare(name, BKind.VAR);
        }
    }

    /**
     * Hoists var declarations (recursively, not into nested functions) and top-level function
     * declarations of a function body / program into {@code varScope}; lexical declarations of the
     * statement list into {@code scope}.
     */
    private void hoistDeclarations(List<Node> body, Scope scope, boolean topLevel) {
        Scope varScope = scope.fn.varScope;
        for (Node statement : body) {
            collectVars(statement, varScope);
        }
        declareLexical(body, scope, topLevel);
    }

    private void collectVars(Node node, Scope varScope) {
        if (node == null) {
            return;
        }
        if (node instanceof VariableDeclaration declaration) {
            if (declaration.getKind() == VarKind.VAR) {
                for (VariableDeclarator declarator : declaration.getDeclarations()) {
                    for (Identifier identifier : boundNames(declarator.getId())) {
                        declareVar(identifier.getName(), varScope);
                    }
                }
            }
        } else if (node instanceof BlockStatement block) {
            for (Node statement : block.getBody()) {
                collectVars(statement, varScope);
            }
        } else if (node instanceof IfStatement statement) {
            collectVars(statement.getConsequent(), varScope);
            collectVars(statement.getAlternate(), varScope);
        } else if (node instanceof ForStatement statement) {
            collectVars(statement.getInit(), varScope);
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof ForInStatement statement) {
            collectVars(statement.getLeft(), varScope);
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof ForOfStatement statement) {
            collectVars(statement.getLeft(), varScope);
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof WhileStatement statement) {
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof DoWhileStatement statement) {
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof LabeledStatement statement) {
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof TryStatement statement) {
            collectVars(statement.getBlock(), varScope);
            if (statement.getHandler() != null) {
                collectVars(statement.getHandler().getBody(), varScope);
            }
            collectVars(statement.getFinalizer(), varScope);
        } else if (node instanceof SwitchStatement statement) {
            for (SwitchCase switchCase : statement.getCases()) {
                for (Node child : switchCase.getConsequent()) {
                    collectVars(child, varScope);
                }
            }
        } else if (node instanceof WithStatement statement) {
            collectVars(statement.getBody(), varScope);
        } else if (node instanceof ExportNamedDeclaration declaration) {
            collectVars(declaration.getDeclaration(), varScope);
        }
    }

    /** Declares lexically scoped declarations of a statement list (let/const/class, block functions). */
    private void declareLexical(List<Node> body, Scope scope, boolean topLevel) {
        boolean isFnTop = topLevel;
        for (Node original : body) {
            Node statement = original;
            while (statement instanceof LabeledStatement labeled) {
                statement = labeled.getBody();
            }
            if (statement instanceof ExportNamedDeclaration declaration) {
                statement = declaration.getDeclaration();
                if (statement == null) {
                    continue;
                }
            }
            if (statement instanceof ExportDefaultDeclaration declaration) {
                Node node = declaration.getDeclaration();
                if (node instanceof FunctionDeclaration function) {
                    Identifier id = function.getFunction().getId();
                    declareFunctionDecl(function, scope, isFnTop,
                            id == null ? "*default*" : id.getName());
                } else if (node instanceof ClassDeclaration cls) {
                    Identifier id = cls.getCls().getId();
                    String name = id == null ? "*default*" : id.getName();
                    Binding binding = scope.declare(name, BKind.CLASS);
                    binding.declEnd = node.getEnd();
                } else {
                    Binding binding = scope.declare("*default*", BKind.CONST);
                    binding.declEnd = statement.getEnd();
                }
                continue;
            }
            if (statement instanceof VariableDeclaration declaration) {
                if (declaration.getKind() != VarKind.VAR) {
                    for (VariableDeclarator declarator : declaration.getDeclarations()) {
                        for (Identifier id : boundNames(declarator.getId())) {
                            if (scope.kind == ScopeKind.GLOBAL) {
                                globalLexNames.put(id.getName(), declaration.getKind() == VarKind.CONST);
                            } else {
                                BKind kind;
                                if (declaration.getKind() == VarKind.CONST) {
                                    kind = BKind.CONST;
                                } else if (declaration.getKind() == VarKind.USING
                                        || declaration.getKind() == VarKind.AWAIT_USING) {
                                    kind = BKind.USING;
                                } else {
                                    kind = BKind.LET;
                                }
                                Binding binding = scope.declare(id.getName(), kind);
                                binding.declEnd = declarator.getEnd();
                            }
                        }
                    }
                }
            } else if (statement instanceof ClassDeclaration cls) {
                String name = cls.getCls().getId().getName();
                if (scope.kind == ScopeKind.GLOBAL) {
                    globalLexNames.put(name, false);
                } else {
                    Binding binding = scope.declare(name, BKind.CLASS);
                    binding.declEnd = statement.getEnd();
                }
            } else if (statement instanceof FunctionDeclaration function) {
                declareFunctionDecl(function, scope, isFnTop,
                        function.getFunction().getId().getName());
            } else if (statement instanceof ImportDeclaration declaration) {
                for (dev.mooner.neonjs.parser.ImportSpecifier specifier : declaration.getSpecifiers()) {
                    Binding binding = scope.declare(specifier.getLocal().getName(), BKind.IMPORT);
                    binding.captured = true;
                }
            }
        }
    }

    private void declareFunctionDecl(FunctionDeclaration declaration, Scope scope,
                                     boolean isFnTop, String name) {
        if (isFnTop) {
            // function-level (or global/eval/module top-level) declaration: var-scoped
            Scope varScope = scope.fn.varScope;
            if (varScope.kind == ScopeKind.GLOBAL
                    || (varScope.kind == ScopeKind.EVAL && isSloppyEval())) {
                globalFunctions.add(declaration);
                globalVarNames.add(name);
            } else if (varScope.kind == ScopeKind.MODULE) {
                scope.declare(name, BKind.FUNCTION);
            } else {
                Binding binding = varScope.bindings.get(name);
                if (binding == null || binding.kind != BKind.PARAM || true) {
                    if (binding == null) {
                        varScope.declare(name, BKind.FUNCTION);
                    } else if (binding.kind == BKind.VAR || binding.kind == BKind.FUNCTION) {
                        binding.kind = BKind.FUNCTION;
                    } else if (binding.kind == BKind.PARAM) {
                        varScope.declare(name, BKind.FUNCTION);
                    }
                }
            }
        } else {
            Binding binding = scope.declare(name, BKind.BLOCK_FUNCTION);
            FunctionNode function = declaration.getFunction();
            if (!function.getStrict() && !scope.fn.strict && !function.isAsync()
                    && !function.isGenerator()) {
                blockFunctions.add(new BlockFunction(declaration, scope, binding));
            }
        }
    }

    private List<Identifier> boundNames(Node node) {
        ArrayList<Identifier> out = new ArrayList<>();
        collectBound(node, out);
        return out;
    }

    private void collectBound(Node node, List<Identifier> out) {
        if (node == null) {
            return;
        }
        if (node instanceof Identifier identifier) {
            out.add(identifier);
        } else if (node instanceof AssignmentPattern pattern) {
            collectBound(pattern.getLeft(), out);
        } else if (node instanceof RestElement element) {
            collectBound(element.getArgument(), out);
        } else if (node instanceof ArrayPattern pattern) {
            for (Node element : pattern.getElements()) {
                collectBound(element, out);
            }
        } else if (node instanceof ObjectPattern pattern) {
            for (Node property : pattern.getProperties()) {
                if (property instanceof Property value) {
                    collectBound(value.getValue(), out);
                } else {
                    collectBound(property, out);
                }
            }
        } else if (node instanceof VariableDeclaration declaration) {
            for (VariableDeclarator declarator : declaration.getDeclarations()) {
                collectBound(declarator.getId(), out);
            }
        }
    }

    // ================================================================== scopes

    private Scope push(ScopeKind kind, Node node) {
        Scope scope = new Scope(kind, cur, curFn);
        if (node != null) {
            node.scope = scope;
        }
        cur = scope;
        register(scope);
        return scope;
    }

    private void pop(Scope scope) {
        cur = scope.parent;
    }

    private void ref(Node node, String name) {
        refs.add(new RefSite(node, name, cur, node.getStart()));
    }

    // ================================================================== statements

    private void visitStatement(Node node) {
        if (node == null) {
            return;
        }
        if (node instanceof ExpressionStatement statement) {
            visitExpr(statement.getExpression());
        } else if (node instanceof VariableDeclaration declaration) {
            for (VariableDeclarator declarator : declaration.getDeclarations()) {
                if (declaration.getKind() == VarKind.VAR) {
                    visitPattern(declarator.getId(), false);
                } else {
                    visitPattern(declarator.getId(), true);
                }
                visitExpr(declarator.getInit());
            }
        } else if (node instanceof FunctionDeclaration declaration) {
            visitFunction(declaration.getFunction());
        } else if (node instanceof ClassDeclaration declaration) {
            visitClass(declaration.getCls());
        } else if (node instanceof BlockStatement block) {
            visitBlock(block, block.getBody());
        } else if (node instanceof EmptyStatement || node instanceof DebuggerStatement) {
            // nothing to visit
        } else if (node instanceof IfStatement statement) {
            visitExpr(statement.getTest());
            visitSubStatement(statement.getConsequent());
            visitSubStatement(statement.getAlternate());
        } else if (node instanceof ForStatement statement) {
            Node init = statement.getInit();
            if (init instanceof VariableDeclaration declaration && declaration.getKind() != VarKind.VAR) {
                Scope scope = push(ScopeKind.FOR, node);
                for (VariableDeclarator declarator : declaration.getDeclarations()) {
                    for (Identifier id : boundNames(declarator.getId())) {
                        BKind kind;
                        if (declaration.getKind() == VarKind.CONST) {
                            kind = BKind.CONST;
                        } else if (declaration.getKind() == VarKind.USING
                                || declaration.getKind() == VarKind.AWAIT_USING) {
                            kind = BKind.USING;
                        } else {
                            kind = BKind.LET;
                        }
                        Binding binding = scope.declare(id.getName(), kind);
                        binding.declEnd = declarator.getEnd();
                    }
                }
                scope.perIteration = declaration.getKind() == VarKind.LET;
                visitStatement(init);
                visitExpr(statement.getTest());
                visitExpr(statement.getUpdate());
                visitSubStatement(statement.getBody());
                pop(scope);
            } else {
                if (init instanceof VariableDeclaration) {
                    visitStatement(init);
                } else {
                    visitExpr(init);
                }
                visitExpr(statement.getTest());
                visitExpr(statement.getUpdate());
                visitSubStatement(statement.getBody());
            }
        } else if (node instanceof ForInStatement statement) {
            visitForInOf(statement, statement.getLeft(), statement.getRight(), statement.getBody());
        } else if (node instanceof ForOfStatement statement) {
            visitForInOf(statement, statement.getLeft(), statement.getRight(), statement.getBody());
        } else if (node instanceof WhileStatement statement) {
            visitExpr(statement.getTest());
            visitSubStatement(statement.getBody());
        } else if (node instanceof DoWhileStatement statement) {
            visitSubStatement(statement.getBody());
            visitExpr(statement.getTest());
        } else if (node instanceof ReturnStatement statement) {
            visitExpr(statement.getArgument());
        } else if (node instanceof ThrowStatement statement) {
            visitExpr(statement.getArgument());
        } else if (node instanceof BreakStatement || node instanceof ContinueStatement) {
            // nothing to visit
        } else if (node instanceof LabeledStatement statement) {
            visitStatementInList(statement.getBody());
        } else if (node instanceof TryStatement statement) {
            visitStatement(statement.getBlock());
            CatchClause handler = statement.getHandler();
            if (handler != null) {
                Scope scope = push(ScopeKind.CATCH, handler);
                Node param = handler.getParam();
                if (param != null && !(param instanceof Identifier)) {
                    catchPatternScopes.add(scope);
                }
                if (param != null) {
                    for (Identifier id : boundNames(param)) {
                        scope.declare(id.getName(), BKind.CATCH);
                    }
                    visitPattern(param, true);
                }
                visitBlock(handler.getBody(), handler.getBody().getBody());
                pop(scope);
            }
            if (statement.getFinalizer() != null) {
                visitStatement(statement.getFinalizer());
            }
        } else if (node instanceof SwitchStatement statement) {
            visitExpr(statement.getDiscriminant());
            Scope scope = push(ScopeKind.SWITCH, node);
            ArrayList<Node> all = new ArrayList<>();
            for (SwitchCase switchCase : statement.getCases()) {
                all.addAll(switchCase.getConsequent());
            }
            declareLexical(all, scope, false);
            for (SwitchCase switchCase : statement.getCases()) {
                visitExpr(switchCase.getTest());
                for (Node child : switchCase.getConsequent()) {
                    visitStatementInList(child);
                }
            }
            pop(scope);
        } else if (node instanceof WithStatement statement) {
            visitExpr(statement.getObj());
            Scope scope = push(ScopeKind.WITH, node);
            scope.needsEnv = true;
            visitSubStatement(statement.getBody());
            pop(scope);
        } else if (node instanceof ImportDeclaration) {
            // nothing to visit
        } else if (node instanceof ExportNamedDeclaration declaration) {
            if (declaration.getDeclaration() != null) {
                visitStatement(declaration.getDeclaration());
            } else if (declaration.getSource() == null) {
                for (ExportSpecifier specifier : declaration.getSpecifiers()) {
                    Identifier id = new Identifier(specifier.getLocal());
                    id.setStart(specifier.getStart());
                    id.setEnd(specifier.getEnd());
                    specifier.ref = id;
                    ref(id, specifier.getLocal());
                }
            }
        } else if (node instanceof ExportDefaultDeclaration declaration) {
            Node child = declaration.getDeclaration();
            if (child instanceof FunctionDeclaration function) {
                visitFunction(function.getFunction());
            } else if (child instanceof ClassDeclaration cls) {
                visitClass(cls.getCls());
            } else {
                visitExpr(child);
            }
        } else if (node instanceof ExportAllDeclaration) {
            // nothing to visit
        } else {
            throw new IllegalStateException("Unknown statement " + node.getClass().getSimpleName());
        }
    }

    /** Statement in a statement list (labels may wrap declarations). */
    private void visitStatementInList(Node node) {
        visitStatement(node);
    }

    /** Statement in a single-statement position (if/loop bodies): sloppy function declarations get a block scope. */
    private void visitSubStatement(Node node) {
        if (node instanceof FunctionDeclaration declaration) {
            // Annex B: if (x) function f(){}  behaves like  if (x) { function f(){} }
            Scope scope = push(ScopeKind.BLOCK, node);
            declareLexical(List.of(node), scope, false);
            visitFunction(declaration.getFunction());
            pop(scope);
            return;
        }
        if (node instanceof LabeledStatement) {
            Node inner = node;
            while (inner instanceof LabeledStatement labeled) {
                inner = labeled.getBody();
            }
            if (inner instanceof FunctionDeclaration declaration) {
                Scope scope = push(ScopeKind.BLOCK, node);
                declareLexical(List.of(inner), scope, false);
                visitFunction(declaration.getFunction());
                pop(scope);
                return;
            }
        }
        visitStatement(node);
    }

    private void visitBlock(Node node, List<Node> body) {
        Scope scope = push(ScopeKind.BLOCK, node);
        declareLexical(body, scope, false);
        for (Node statement : body) {
            visitStatementInList(statement);
        }
        pop(scope);
    }

    private void visitForInOf(Node node, Node left, Node right, Node body) {
        if (left instanceof VariableDeclaration declaration && declaration.getKind() != VarKind.VAR) {
            // TDZ scope for the RHS expression with the same names
            Scope tdz = push(ScopeKind.FOR, right);
            for (VariableDeclarator declarator : declaration.getDeclarations()) {
                for (Identifier id : boundNames(declarator.getId())) {
                    tdz.declare(id.getName(), BKind.LET);
                }
            }
            visitExpr(right);
            pop(tdz);
            Scope scope = push(ScopeKind.FOR, node);
            BKind kind;
            if (declaration.getKind() == VarKind.CONST) {
                kind = BKind.CONST;
            } else if (declaration.getKind() == VarKind.USING
                    || declaration.getKind() == VarKind.AWAIT_USING) {
                kind = BKind.USING;
            } else {
                kind = BKind.LET;
            }
            for (VariableDeclarator declarator : declaration.getDeclarations()) {
                for (Identifier id : boundNames(declarator.getId())) {
                    Binding binding = scope.declare(id.getName(), kind);
                    binding.declEnd = left.getEnd();
                }
            }
            visitPattern(declaration.getDeclarations().get(0).getId(), true);
            visitSubStatement(body);
            pop(scope);
        } else {
            if (left instanceof VariableDeclaration declaration) {
                visitPattern(declaration.getDeclarations().get(0).getId(), false);
                visitExpr(declaration.getDeclarations().get(0).getInit());
            } else {
                visitPattern(left, false);
            }
            visitExpr(right);
            visitSubStatement(body);
        }
    }

    // ================================================================== patterns

    /** Visits a binding/assignment pattern. Declaration identifiers are recorded as references to resolve. */
    private void visitPattern(Node node, boolean isDeclaration) {
        if (node == null) {
            return;
        }
        if (node instanceof Identifier identifier) {
            ref(identifier, identifier.getName());
        } else if (node instanceof MemberExpression) {
            visitExpr(node);
        } else if (node instanceof AssignmentPattern pattern) {
            visitPattern(pattern.getLeft(), isDeclaration);
            visitExpr(pattern.getRight());
        } else if (node instanceof RestElement element) {
            visitPattern(element.getArgument(), isDeclaration);
        } else if (node instanceof ArrayPattern pattern) {
            for (Node element : pattern.getElements()) {
                visitPattern(element, isDeclaration);
            }
        } else if (node instanceof ObjectPattern pattern) {
            for (Node property : pattern.getProperties()) {
                if (property instanceof Property value) {
                    if (value.getComputed()) {
                        visitExpr(value.getKey());
                    }
                    visitPattern(value.getValue(), isDeclaration);
                } else {
                    visitPattern(property, isDeclaration);
                }
            }
        } else if (node instanceof CallExpression) {
            visitExpr(node);
        } else {
            visitExpr(node);
        }
    }

    // ================================================================== expressions

    private void visitExpr(Node node) {
        if (node == null) {
            return;
        }
        if (node instanceof Identifier identifier) {
            ref(identifier, identifier.getName());
        } else if (node instanceof NumberLiteral || node instanceof StringLiteral
                || node instanceof BigIntLiteral || node instanceof BooleanLiteral
                || node instanceof NullLiteral || node instanceof RegExpLiteral) {
            // no references
        } else if (node instanceof TemplateLiteral template) {
            for (Node expression : template.getExpressions()) {
                visitExpr(expression);
            }
        } else if (node instanceof TaggedTemplate tagged) {
            visitExpr(tagged.getTag());
            for (Node expression : tagged.getQuasi().getExpressions()) {
                visitExpr(expression);
            }
        } else if (node instanceof ThisExpression) {
            ref(node, "this");
        } else if (node instanceof Super) {
            ref(node, "%home");
        } else if (node instanceof ArrayLiteral array) {
            for (Node element : array.getElements()) {
                visitExpr(element);
            }
        } else if (node instanceof ObjectLiteral literal) {
            for (Node property : literal.getProperties()) {
                if (property instanceof Property value) {
                    if (value.getComputed()) {
                        visitExpr(value.getKey());
                    }
                    Node child = value.getValue();
                    if (child instanceof FunctionNode function) {
                        visitFunction(function);
                    } else {
                        visitExpr(child);
                    }
                } else {
                    visitExpr(property);
                }
            }
        } else if (node instanceof SpreadElement element) {
            visitExpr(element.getArgument());
        } else if (node instanceof UnaryExpression expression) {
            visitExpr(expression.getArgument());
        } else if (node instanceof UpdateExpression expression) {
            visitExpr(expression.getArgument());
        } else if (node instanceof BinaryExpression expression) {
            visitExpr(expression.getLeft());
            visitExpr(expression.getRight());
        } else if (node instanceof LogicalExpression expression) {
            visitExpr(expression.getLeft());
            visitExpr(expression.getRight());
        } else if (node instanceof PrivateInExpression expression) {
            ref(expression.getName(), "#" + expression.getName().getName());
            visitExpr(expression.getRight());
        } else if (node instanceof AssignmentExpression expression) {
            Node target = expression.getTarget();
            if (target instanceof ObjectPattern || target instanceof ArrayPattern) {
                visitPattern(target, false);
            } else {
                visitExpr(target);
            }
            visitExpr(expression.getValue());
        } else if (node instanceof ConditionalExpression expression) {
            visitExpr(expression.getTest());
            visitExpr(expression.getConsequent());
            visitExpr(expression.getAlternate());
        } else if (node instanceof CallExpression call) {
            Node callee = call.getCallee();
            if (callee instanceof Super) {
                ref(callee, "%fn");
                MetaProperty newTarget = new MetaProperty("new", "target");
                newTarget.setStart(callee.getStart());
                callee.scope = newTarget;
                ref(newTarget, "new.target");
                ThisExpression thisExpression = new ThisExpression();
                thisExpression.setStart(callee.getStart());
                call.ref = thisExpression;
                ref(thisExpression, "this");
            } else {
                visitExpr(callee);
            }
            for (Node argument : call.getArguments()) {
                visitExpr(argument);
            }
            if (callee instanceof Identifier identifier && identifier.getName().equals("eval")
                    && !call.getOptional()) {
                markDirectEval();
            }
        } else if (node instanceof NewExpression expression) {
            visitExpr(expression.getCallee());
            for (Node argument : expression.getArguments()) {
                visitExpr(argument);
            }
        } else if (node instanceof MemberExpression expression) {
            Node object = expression.getObj();
            if (object instanceof Super) {
                ref(object, "%home");
                ThisExpression thisExpression = new ThisExpression();
                thisExpression.setStart(object.getStart());
                expression.ref = thisExpression;
                ref(thisExpression, "this");
            } else {
                visitExpr(object);
            }
            Node property = expression.getProperty();
            if (expression.getComputed()) {
                visitExpr(property);
            } else if (property instanceof PrivateIdentifier identifier) {
                ref(property, "#" + identifier.getName());
            }
        } else if (node instanceof ChainExpression expression) {
            visitExpr(expression.getExpression());
        } else if (node instanceof SequenceExpression expression) {
            for (Node child : expression.getExpressions()) {
                visitExpr(child);
            }
        } else if (node instanceof YieldExpression expression) {
            visitExpr(expression.getArgument());
        } else if (node instanceof AwaitExpression expression) {
            visitExpr(expression.getArgument());
        } else if (node instanceof MetaProperty property) {
            if (property.getMeta().equals("new")) {
                ref(property, "new.target");
            }
        } else if (node instanceof ImportCall call) {
            visitExpr(call.getSource());
            visitExpr(call.getOptions());
        } else if (node instanceof FunctionNode function) {
            visitFunction(function);
        } else if (node instanceof ClassNode cls) {
            visitClass(cls);
        } else if (node instanceof ObjectPattern || node instanceof ArrayPattern) {
            visitPattern(node, false);
        } else if (node instanceof AssignmentPattern) {
            visitPattern(node, false);
        } else {
            throw new IllegalStateException("Unknown expression " + node.getClass().getSimpleName());
        }
    }

    private void markDirectEval() {
        cur.hasEval = true;
        evalScopes.add(cur);
        curFn.hasDirectEval = true;
        FnInfo function = curFn;
        while (function != null) {
            function.containsEval = true;
            function = function.parent;
        }
        Scope varScope = curFn.varScope;
        // global / sloppy-eval code: eval vars go to the global object or the caller's var environment
        if (!curFn.strict && varScope.kind != ScopeKind.GLOBAL
                && !(varScope.kind == ScopeKind.EVAL && isSloppyEval())) {
            varScope.evalVarTarget = true;
            varScope.needsEnv = true;
        }
    }

    // ================================================================== functions

    private void visitFunction(FunctionNode function) {
        Scope outerScope = cur;
        FnInfo outerFn = curFn;
        FnInfo info = new FnInfo(function, outerFn);
        allFunctions.add(info);
        info.isArrow = function.getKind() == FunctionKind.ARROW;
        info.strict = function.getStrict();
        info.isAsync = function.isAsync();
        info.isGenerator = function.isGenerator();
        info.kind = function.getKind();
        curFn = info;
        // callee scope for named function expressions
        Identifier id = function.getId();
        if (id != null && !function.isDeclaration()) {
            Scope callee = new Scope(ScopeKind.CALLEE, cur, info);
            callee.declare(id.getName(), BKind.CALLEE);
            info.calleeScope = callee;
            cur = callee;
        }
        boolean hasParamExpr = false;
        for (Node param : function.getParams()) {
            if (!(param instanceof Identifier)) {
                hasParamExpr = true;
                break;
            }
        }
        if (hasParamExpr) {
            for (Node param : function.getParams()) {
                if (containsExpression(param)) {
                    info.hasParamExpressions = true;
                    break;
                }
            }
        }
        Scope parameterEval = null;
        if (info.hasParamExpressions && !function.getStrict()) {
            boolean directEval = false;
            for (Node param : function.getParams()) {
                if (containsDirectEval(param)) {
                    directEval = true;
                    break;
                }
            }
            if (directEval) {
                parameterEval = new Scope(ScopeKind.BLOCK, cur, info);
                parameterEval.evalVarTarget = true;
                parameterEval.needsEnv = true;
                info.paramEvalScope = parameterEval;
                register(parameterEval);
                cur = parameterEval;
            }
        }
        Scope functionScope = new Scope(ScopeKind.FUNCTION, cur, info);
        function.scope = functionScope;
        info.scope = functionScope;
        info.varScope = parameterEval != null ? parameterEval : functionScope;
        cur = functionScope;
        if (function.getKind() == FunctionKind.DERIVED_CONSTRUCTOR) {
            ensureThisBinding(info);
        }
        // parameters
        for (Node param : function.getParams()) {
            for (Identifier identifier : boundNames(param)) {
                if (functionScope.bindings.get(identifier.getName()) == null) {
                    Binding binding = functionScope.declare(identifier.getName(), BKind.PARAM);
                    if (info.hasParamExpressions) {
                        binding.tdz = true;
                    }
                }
            }
        }
        if (info.hasParamExpressions) {
            for (Node param : function.getParams()) {
                visitPattern(param, true);
            }
            Scope bodyScope = new Scope(ScopeKind.BODY, functionScope, info);
            info.varScope = bodyScope;
            cur = bodyScope;
            if (function.getBody() instanceof BlockStatement block) {
                block.scope = bodyScope;
            }
        } else {
            for (Node param : function.getParams()) {
                visitPattern(param, true);
            }
            info.varScope = functionScope;
        }
        Node body = function.getBody();
        if (body instanceof BlockStatement block) {
            hoistDeclarations(block.getBody(), cur, true);
            for (Node statement : block.getBody()) {
                visitStatementInList(statement);
            }
        } else {
            visitExpr(body);
        }
        cur = outerScope;
        curFn = outerFn;
    }

    /** True if the subtree contains a direct eval call (not inside nested functions). */
    private boolean containsDirectEval(Node node) {
        boolean[] found = {false};
        walkDirectEval(node, found);
        return found[0];
    }

    private void walkDirectEval(Object value, boolean[] found) {
        if (found[0] || value == null) {
            return;
        }
        if (value instanceof FunctionNode || value instanceof ClassNode) {
            return;
        }
        if (value instanceof CallExpression call) {
            Node callee = call.getCallee();
            if (callee instanceof Identifier identifier && identifier.getName().equals("eval")
                    && !call.getOptional()) {
                found[0] = true;
                return;
            }
            walkDirectEval(callee, found);
            for (Node argument : call.getArguments()) {
                walkDirectEval(argument, found);
            }
            return;
        }
        if (value instanceof Node node) {
            for (Field field : node.getClass().getDeclaredFields()) {
                if (field.getName().equals("scope") || field.getName().equals("ref")) {
                    continue;
                }
                field.setAccessible(true);
                Object child;
                try {
                    child = field.get(node);
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException("Cannot read AST field " + field.getName(), error);
                }
                if (child instanceof Node nested) {
                    walkDirectEval(nested, found);
                } else if (child instanceof List<?> list) {
                    for (Object element : list) {
                        walkDirectEval(element, found);
                    }
                }
            }
        }
    }

    private boolean containsExpression(Node node) {
        if (node == null) {
            return false;
        }
        if (node instanceof Identifier) {
            return false;
        }
        if (node instanceof AssignmentPattern) {
            return true;
        }
        if (node instanceof RestElement element) {
            return containsExpression(element.getArgument());
        }
        if (node instanceof ArrayPattern pattern) {
            for (Node element : pattern.getElements()) {
                if (containsExpression(element)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof ObjectPattern pattern) {
            for (Node property : pattern.getProperties()) {
                if (property instanceof Property value) {
                    if (value.getComputed() || containsExpression(value.getValue())) {
                        return true;
                    }
                } else if (containsExpression(property)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void visitClass(ClassNode cls) {
        // class decorators are evaluated outside the class scope
        for (Node decorator : cls.getDecorators()) {
            visitExpr(decorator);
        }
        Scope scope = push(ScopeKind.CLASS, cls);
        Identifier id = cls.getId();
        if (id != null) {
            Binding binding = scope.declare(id.getName(), BKind.CLASS_INNER);
            binding.declEnd = cls.getEnd();
        }
        // private names
        for (Node element : cls.getBody()) {
            Node key = null;
            if (element instanceof MethodDefinition method) {
                key = method.getKey();
            } else if (element instanceof PropertyDefinition property) {
                key = property.getKey();
            }
            if (key instanceof PrivateIdentifier privateName) {
                String name = "#" + privateName.getName();
                Binding binding = scope.bindings.get(name);
                if (binding == null) {
                    binding = scope.declare(name, BKind.PRIVATE);
                    binding.captured = true;
                    if (element instanceof MethodDefinition method) {
                        binding.privateStatic = method.isStatic();
                    } else if (element instanceof PropertyDefinition property) {
                        binding.privateStatic = property.isStatic();
                    } else {
                        binding.privateStatic = false;
                    }
                }
                String kind;
                if (element instanceof MethodDefinition method) {
                    if (method.getKind() == MethodKind.GET) {
                        kind = "get";
                    } else if (method.getKind() == MethodKind.SET) {
                        kind = "set";
                    } else {
                        kind = "method";
                    }
                } else if (element instanceof PropertyDefinition property) {
                    kind = property.isAccessor() ? "accessor" : "field";
                } else {
                    kind = "field";
                }
                if (binding.privateKind == null) {
                    binding.privateKind = kind;
                } else if ((binding.privateKind.equals("get") && kind.equals("set"))
                        || (binding.privateKind.equals("set") && kind.equals("get"))) {
                    binding.privateKind = "getset";
                } else {
                    binding.privateKind = kind;
                }
            }
        }
        visitExpr(cls.getSuperClass());
        for (Node element : cls.getBody()) {
            if (element instanceof MethodDefinition method) {
                for (Node decorator : method.getDecorators()) {
                    visitExpr(decorator);
                }
                if (method.getComputed()) {
                    visitExpr(method.getKey());
                }
                method.getValue().setClassNode(cls);
                visitFunction(method.getValue());
            } else if (element instanceof PropertyDefinition property) {
                for (Node decorator : property.getDecorators()) {
                    visitExpr(decorator);
                }
                if (property.getComputed()) {
                    visitExpr(property.getKey());
                }
                if (property.getInitializer() != null) {
                    visitFunction(property.getInitializer());
                }
            } else if (element instanceof StaticBlock block) {
                visitFunction(block.getFunction());
            }
        }
        pop(scope);
    }

    // ================================================================== resolution

    private void finish() {
        resolveAnnexB();
        // eval visibility: all bindings in scopes enclosing a direct eval must be env-allocated
        for (Scope evalScope : evalScopes) {
            Scope scope = evalScope;
            while (scope != null) {
                scope.allCaptured = true;
                scope = scope.parent;
            }
            // eval code may use this / arguments / new.target / super of the enclosing function
            FnInfo function = evalScope.fn;
            while (function != null && function.isArrow) {
                function = function.parent;
            }
            if (function != null && !function.isTopLevel) {
                ensureThisBinding(function).captured = true;
                ensureFnBindings(function);
                if (function.kind != FunctionKind.CLASS_FIELD_INIT
                        && function.kind != FunctionKind.STATIC_BLOCK) {
                    ensureArguments(function, true);
                }
            }
        }
        for (RefSite ref : refs) {
            resolve(ref);
        }
        for (FnInfo function : allFunctions) {
            allocate(function);
        }
    }

    private Binding ensureThisBinding(FnInfo function) {
        Binding binding = function.thisBinding;
        if (binding == null) {
            binding = new Binding("this", BKind.THIS, function.scope);
            function.scope.bindings.put("this", binding);
            function.thisBinding = binding;
            binding.tdz = function.kind == FunctionKind.DERIVED_CONSTRUCTOR;
        }
        return binding;
    }

    private void ensureFnBindings(FnInfo function) {
        if (function.newTargetBinding == null) {
            Binding binding = new Binding("new.target", BKind.NEW_TARGET, function.scope);
            function.scope.bindings.put("new.target", binding);
            function.newTargetBinding = binding;
            binding.captured = true;
        }
        if (function.homeBinding == null) {
            Binding binding = new Binding("%home", BKind.HOME, function.scope);
            function.scope.bindings.put("%home", binding);
            function.homeBinding = binding;
            binding.captured = true;
        }
        if (function.fnBinding == null) {
            Binding binding = new Binding("%fn", BKind.FN, function.scope);
            function.scope.bindings.put("%fn", binding);
            function.fnBinding = binding;
            binding.captured = true;
        }
    }

    private Binding ensureArguments(FnInfo function, boolean capture) {
        Binding existing = function.scope.bindings.get("arguments");
        if (existing != null) {
            if (existing.kind == BKind.ARGUMENTS) {
                if (capture) {
                    existing.captured = true;
                }
                return existing;
            }
            if (existing.kind == BKind.VAR) {
                existing.isArgumentsObject = true;
                function.argumentsBinding = existing;
                function.usesArguments = true;
                if (capture) {
                    existing.captured = true;
                }
                return existing;
            }
            return existing;
        }
        Binding binding = new Binding("arguments", BKind.ARGUMENTS, function.scope);
        function.scope.bindings.put("arguments", binding);
        binding.isArgumentsObject = true;
        function.argumentsBinding = binding;
        function.usesArguments = true;
        if (capture) {
            binding.captured = true;
        }
        return binding;
    }

    private void resolve(RefSite ref) {
        String name = ref.name;
        if (name.equals("this") || name.equals("new.target") || name.equals("%home")
                || name.equals("%fn")) {
            resolvePseudo(ref);
            return;
        }
        Scope scope = ref.scope;
        boolean crossed = false;
        boolean dynamic = false;
        FnInfo refFn = ref.scope.fn;
        while (scope != null) {
            if (scope.kind == ScopeKind.WITH) {
                dynamic = true;
            }
            Binding binding = scope.bindings.get(name);
            if (name.equals("arguments") && !scope.fn.isArrow && !scope.fn.isTopLevel
                    && scope.fn.kind != FunctionKind.CLASS_FIELD_INIT
                    && scope.fn.kind != FunctionKind.STATIC_BLOCK) {
                if (scope.kind == ScopeKind.FUNCTION) {
                    binding = ensureArguments(scope.fn, false);
                } else if (scope.kind == ScopeKind.BODY && binding != null
                        && binding.kind == BKind.VAR) {
                    ensureArguments(scope.fn, false);
                }
            }
            if (binding != null) {
                if (crossed || dynamic) {
                    binding.captured = true;
                }
                if (scope.allCaptured) {
                    binding.captured = true;
                }
                if (dynamic) {
                    ref.node.ref = new DynamicRef(name);
                    return;
                }
                boolean checkTdz = binding.needsTdz()
                        && !(binding.scope.fn == refFn && binding.declEnd >= 0
                        && ref.pos >= binding.declEnd && binding.scope.kind != ScopeKind.SWITCH
                        && !binding.tdz);
                ref.node.ref = new LocalRef(binding, checkTdz);
                return;
            }
            if (scope.evalVarTarget) {
                dynamic = true;
            }
            if (scope.kind == ScopeKind.FUNCTION && scope.parent != null
                    && scope.parent.fn != scope.fn) {
                crossed = true;
            }
            if (scope.kind == ScopeKind.CALLEE) {
                crossed = true;
            }
            if (scope.kind == ScopeKind.FUNCTION && scope.parent == null) {
                crossed = true;
            }
            scope = scope.parent;
        }
        // unresolved
        if (dynamic || mode == CodeMode.EVAL_DIRECT) {
            ref.node.ref = new DynamicRef(name);
        } else {
            ref.node.ref = new GlobalRef(name);
        }
    }

    private void resolvePseudo(RefSite ref) {
        FnInfo function = ref.scope.fn;
        boolean crossed = false;
        while (function != null && function.isArrow) {
            function = function.parent;
            crossed = true;
        }
        if (function == null || function.isTopLevel) {
            // global / module / eval top level
            ref.node.ref = mode == CodeMode.EVAL_DIRECT ? new DynamicRef(ref.name) : null;
            if (function != null && ref.name.equals("this")) {
                function.usesThis = true;
            }
            return;
        }
        Binding binding;
        if (ref.name.equals("this")) {
            function.usesThis = true;
            binding = ensureThisBinding(function);
        } else if (ref.name.equals("new.target")) {
            Binding current = function.newTargetBinding;
            if (current == null) {
                current = new Binding("new.target", BKind.NEW_TARGET, function.scope);
                function.scope.bindings.put("new.target", current);
                function.newTargetBinding = current;
            }
            binding = current;
        } else if (ref.name.equals("%home")) {
            Binding current = function.homeBinding;
            if (current == null) {
                current = new Binding("%home", BKind.HOME, function.scope);
                function.scope.bindings.put("%home", current);
                function.homeBinding = current;
            }
            binding = current;
        } else {
            Binding current = function.fnBinding;
            if (current == null) {
                current = new Binding("%fn", BKind.FN, function.scope);
                function.scope.bindings.put("%fn", current);
                function.fnBinding = current;
            }
            binding = current;
        }
        if (crossed) {
            binding.captured = true;
        }
        if (function.scope.allCaptured) {
            binding.captured = true;
        }
        ref.node.ref = new LocalRef(binding, binding.tdz);
    }

    /** Annex B.3.3: block function declarations also create a var binding when no conflicts exist. */
    private void resolveAnnexB() {
        for (BlockFunction entry : blockFunctions) {
            String name = entry.declaration.getFunction().getId().getName();
            FnInfo function = entry.scope.fn;
            Scope varScope = function.varScope;
            // would `var name` conflict with a lexical declaration in an enclosing scope (excluding the block itself)?
            Scope scope = entry.scope.parent;
            boolean conflict = false;
            while (scope != null) {
                Binding binding = scope.bindings.get(name);
                if (binding != null && (binding.kind.isLexical()
                        || binding.kind == BKind.BLOCK_FUNCTION) && scope != varScope) {
                    conflict = true;
                    break;
                }
                if (binding != null && scope == varScope && binding.kind.isLexical()) {
                    conflict = true;
                    break;
                }
                if (scope.kind == ScopeKind.CATCH && binding != null && binding.kind == BKind.CATCH) {
                    // simple catch parameter: var redeclaration allowed (B.3.4), pattern params conflict
                    boolean pattern = findCatchParamIsPattern(scope);
                    if (pattern) {
                        conflict = true;
                        break;
                    }
                }
                if (scope == varScope) {
                    break;
                }
                scope = scope.parent;
            }
            if (conflict) {
                continue;
            }
            if (varScope.kind == ScopeKind.GLOBAL
                    || (varScope.kind == ScopeKind.EVAL && isSloppyEval())) {
                if (globalLexNames.containsKey(name)) {
                    continue;
                }
                annexBGlobalNames.add(name);
                globalVarNames.add(name);
                entry.binding.annexBVar = name;
                continue;
            }
            // parameter names are not overridden
            Scope functionScope = function.scope;
            Binding parameterBinding = functionScope.bindings.get(name);
            if (parameterBinding != null && parameterBinding.kind == BKind.PARAM) {
                continue;
            }
            if (name.equals("arguments") && !function.isArrow && !function.isTopLevel) {
                // "arguments" is not hoisted if it would shadow the arguments object (it is then one of the
                // parameterNames of FunctionDeclarationInstantiation)
                Binding existing = varScope.bindings.get(name);
                if (existing == null) {
                    continue;
                }
            }
            Binding varBinding = varScope.bindings.get(name);
            if (varBinding == null) {
                varBinding = varScope.declare(name, BKind.VAR);
            }
            if (varBinding.kind.isLexical()) {
                continue;
            }
            entry.binding.annexBVar = varBinding;
        }
    }

    private boolean findCatchParamIsPattern(Scope scope) {
        return catchPatternScopes.contains(scope);
    }

    // ================================================================== allocation

    private void allocate(FnInfo function) {
        // mapped arguments: parameters must be env-allocated so the arguments object can alias them
        if (function.usesArguments && !function.strict && !function.isArrow
                && function.node instanceof FunctionNode node && node.getHasSimpleParams()) {
            function.mappedArguments = true;
            for (Binding binding : function.scope.bindings.values()) {
                if (binding.kind == BKind.PARAM) {
                    binding.captured = true;
                }
            }
        }
        ArrayList<Scope> scopes = new ArrayList<>();
        collectScopes(function, scopes);
        int[] reg = {0};
        for (Scope scope : scopes) {
            allocScope(scope, reg);
        }
        function.numRegs = reg[0];
    }

    private void allocScope(Scope scope, int[] reg) {
        if (scope.allCaptured || scope.kind == ScopeKind.MODULE) {
            for (Binding binding : scope.bindings.values()) {
                binding.captured = true;
            }
        }
        int slot = 0;
        for (Binding binding : scope.bindings.values()) {
            if (binding.kind == BKind.PRIVATE) {
                binding.captured = true;
            }
            if (binding.captured) {
                binding.slot = slot++;
            } else {
                binding.reg = reg[0]++;
            }
        }
        scope.envSize = slot;
        if (slot > 0) {
            scope.needsEnv = true;
        }
        if (scope.kind == ScopeKind.WITH || scope.kind == ScopeKind.MODULE) {
            scope.needsEnv = true;
        }
        if (scope.needsEnv) {
            scope.info = ScopeInfo.from(scope);
        }
    }

    /** Collects scopes belonging to function {@code function} (not nested functions) in creation order. */
    private void collectScopes(FnInfo function, List<Scope> out) {
        LinkedHashSet<Scope> all = new LinkedHashSet<>();
        if (function.calleeScope != null) {
            all.add(function.calleeScope);
        }
        if (function.paramEvalScope != null) {
            all.add(function.paramEvalScope);
        }
        all.add(function.scope);
        all.add(function.varScope);
        ArrayList<Scope> registered = scopesByFn.get(function);
        if (registered != null) {
            all.addAll(registered);
        }
        out.addAll(all);
    }

    /** Registers every created scope by function (called through {@link #push}). */
    private void register(Scope scope) {
        scopesByFn.computeIfAbsent(scope.fn, ignored -> new ArrayList<>()).add(scope);
    }
}
