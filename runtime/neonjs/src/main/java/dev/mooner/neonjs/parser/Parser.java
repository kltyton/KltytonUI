/*
 * Copyright 2026 mooner1022
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from the NeonJS Kotlin parser sources to pure Java 17.
 */

package dev.mooner.neonjs.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ECMAScript parser producing an ESTree-like AST and reporting early errors as {@link JSSyntaxError}.
 */
public final class Parser {
    private Parser() {
    }

    /** Parses a Script or Module. */
    public static Program parse(String src) {
        return parse(src, new ParseOptions());
    }

    /** Parses a Script or Module. */
    public static Program parse(String src, ParseOptions options) {
        return new ParserImpl(src, options).parseTopLevel();
    }

    /**
     * The result of parsing the parts of a dynamic function: the function node and the synthesized source text used
     * for toString.
     */
    public record DynamicFunction(FunctionNode function, String source) {
        public FunctionNode getFunction() {
            return function;
        }

        public String getSource() {
            return source;
        }
    }

    /**
     * Parses the parts of a dynamic function (Function / GeneratorFunction / AsyncFunction constructors).
     * Returns the function node and the synthesized source text used for toString.
     */
    public static DynamicFunction parseDynamicFunction(
            String params, String body, boolean isAsync, boolean isGenerator
    ) {
        return parseDynamicFunction(params, body, isAsync, isGenerator, null);
    }

    public static DynamicFunction parseDynamicFunction(
            String params, String body, boolean isAsync, boolean isGenerator, String sourceName
    ) {
        String prefix;
        if (isAsync && isGenerator) {
            prefix = "async function*";
        } else if (isAsync) {
            prefix = "async function";
        } else if (isGenerator) {
            prefix = "function*";
        } else {
            prefix = "function";
        }
        String full = prefix + " anonymous(" + params + "\n) {\n" + body + "\n}";
        // Parameters and body must each be well-formed on their own.
        new ParserImpl("(" + params + "\n)", optionsWithSourceName(sourceName))
                .checkStandaloneParams(isAsync, isGenerator);
        new ParserImpl(body, optionsWithSourceName(sourceName))
                .checkStandaloneBody(isAsync, isGenerator);
        FunctionNode fn = new ParserImpl(full, optionsWithSourceName(sourceName))
                .parseSingleFunction(isAsync, isGenerator);
        fn.name = "anonymous";
        return new DynamicFunction(fn, full);
    }

    private static ParseOptions optionsWithSourceName(String sourceName) {
        return new ParseOptions(
                false, false, false, false, false, false, true, false,
                Collections.emptySet(), sourceName, false, true
        );
    }
}

final class ParserImpl extends ExpressionParser {

    private final Map<String, Integer> undefinedExports = new LinkedHashMap<>();
    private final Set<String> exportedNames = new HashSet<>();

    private int switchCaseDepth = -1;

    ParserImpl(String src, ParseOptions options) {
        super(src, options);
    }

    // ============================================================ entry points

    Program parseTopLevel() {
        enterScope(SCOPE_TOP);
        next();
        List<Node> body = parseStatementListTop();
        if (inModule) {
            resolvePendingExports();
            for (Map.Entry<String, Integer> entry : undefinedExports.entrySet()) {
                raise(entry.getValue(), "Export '" + entry.getKey() + "' is not defined");
            }
        }
        Program prog = new Program(body, inModule);
        prog.start = 0;
        prog.end = src.length();
        prog.line = 1;
        prog.col = 0;
        prog.strict = strict;
        prog.source = src;
        exitScope();
        return prog;
    }

    private List<Node> parseStatementListTop() {
        List<Node> body = new ArrayList<>();
        boolean allowDirectives = true;
        int firstOctal = -1;
        while (type() != T.EOF) {
            Node stmt = parseStatement(null, true);
            if (allowDirectives) {
                StringLiteral d = directiveOf(stmt);
                if (d != null) {
                    if (d.hasLegacyOctal && firstOctal < 0) {
                        firstOctal = d.start;
                    }
                    if (d.raw.length() == 12 && d.raw.substring(1, 11).equals("use strict")) {
                        if (firstOctal >= 0) {
                            raise(firstOctal, "Octal escape sequences are not allowed in strict mode");
                        }
                        strict = true;
                    }
                } else {
                    allowDirectives = false;
                }
            }
            body.add(stmt);
        }
        return body;
    }

    private StringLiteral directiveOf(Node stmt) {
        if (stmt instanceof ExpressionStatement statement) {
            Node e = statement.expression;
            if (e instanceof StringLiteral literal && !literal.parenthesized) {
                statement.directive = literal.raw.substring(1, literal.raw.length() - 1);
                return literal;
            }
        }
        return null;
    }

    void checkStandaloneParams(boolean isAsync, boolean isGenerator) {
        enterScope(SCOPE_TOP);
        enterScope(functionFlags(isAsync, isGenerator));
        next();
        expect(T.LPAREN);
        parseBindingList(T.RPAREN, false, true);
        if (type() != T.EOF) {
            unexpected();
        }
    }

    void checkStandaloneBody(boolean isAsync, boolean isGenerator) {
        enterScope(SCOPE_TOP);
        enterScope(functionFlags(isAsync, isGenerator));
        next();
        parseBlockBody(false, true, null);
    }

    FunctionNode parseSingleFunction(boolean isAsync, boolean isGenerator) {
        enterScope(SCOPE_TOP);
        next();
        SP p = sp();
        if (isAsync) {
            expectContextual("async");
        }
        expectKw("function");
        if (isGenerator) {
            expect(T.STAR);
        }
        if (!isContextual("anonymous")) {
            unexpected();
        }
        next();
        FunctionNode fn = parseFunctionRest(p, null, isAsync, isGenerator, FunctionKind.NORMAL, false);
        if (type() != T.EOF) {
            unexpected();
        }
        return fn;
    }

    // ============================================================ statements

    @Override
    List<Node> parseBlockBody(boolean endsWithBrace, boolean directives, Consumer<StringLiteral> onDirective) {
        List<Node> body = new ArrayList<>();
        boolean allowDirectives = directives;
        int firstOctal = -1;
        T end = endsWithBrace ? T.RBRACE : T.EOF;
        while (type() != end) {
            if (type() == T.EOF) {
                unexpected();
            }
            Node stmt = parseStatement(null, false);
            if (allowDirectives) {
                StringLiteral d = directiveOf(stmt);
                if (d != null) {
                    if (d.hasLegacyOctal && firstOctal < 0) {
                        firstOctal = d.start;
                    }
                    if (onDirective != null) {
                        onDirective.accept(d);
                    }
                    if (d.raw.length() == 12 && d.raw.substring(1, 11).equals("use strict") && firstOctal >= 0) {
                        raise(firstOctal, "Octal escape sequences are not allowed in strict mode");
                    }
                } else {
                    allowDirectives = false;
                }
            }
            body.add(stmt);
        }
        if (endsWithBrace) {
            next();
        }
        return body;
    }

    @Override
    BlockStatement parseBlock(boolean newScope) {
        SP p = sp();
        expect(T.LBRACE);
        if (newScope) {
            enterScope(0);
        }
        List<Node> body = parseBlockBody(true, false, null);
        if (newScope) {
            exitScope();
        }
        return fin(new BlockStatement(body), p);
    }

    /** Is the current {@code let} the start of a lexical declaration? */
    private boolean isLet(String context) {
        return isContextual("let") && lookahead(() -> {
            if (type() == T.LBRACKET) {
                return true;
            }
            if (context != null) {
                return false;
            }
            if (type() == T.LBRACE) {
                return true;
            }
            if (type() == T.NAME) {
                String v = (String) lex.value;
                // `let` followed by an identifier on the next line is still a declaration, except `let \n let`?
                return lex.escaped || (!v.equals("in") && !v.equals("instanceof"));
            }
            return false;
        });
    }

    private boolean isAsyncFunction() {
        return isContextual("async") && lookahead(() -> isKw("function") && !lex.nlBefore);
    }

    private boolean isUsing(boolean isFor) {
        return isContextual("using") && lookahead(() -> {
            if (lex.nlBefore || type() != T.NAME) {
                return false;
            }
            String v = (String) lex.value;
            if (isFor && v.equals("of") && !lex.escaped) {
                // `for (using of ...)`: `using of = x` is a declaration only when followed by '='
                return lookahead(() -> type() == T.ASSIGN);
            }
            return !(v.equals("in") || v.equals("instanceof")) || lex.escaped;
        });
    }

    private boolean isAwaitUsing() {
        return isContextual("await") && inAsync() && lookahead(() -> {
            if (lex.nlBefore || !isContextual("using")) {
                return false;
            }
            return lookahead(() -> !lex.nlBefore && type() == T.NAME);
        });
    }

    Node parseStatement(String context) {
        return parseStatement(context, false);
    }

    Node parseStatement(String context, boolean topLevel) {
        SP p = sp();
        if (type() == T.LBRACE) {
            return parseBlock(true);
        }
        if (type() == T.AT) {
            // `@dec class C {}` or `@dec export [default] class C {}`
            if (context != null) {
                unexpected();
            }
            pendingDecorators = parseDecorators();
            boolean exp = isKw("export");
            if (!isKw("class") && !exp) {
                unexpected();
            }
            if (exp && lookahead(() -> (isKw("default") && lookahead(() -> type() == T.AT)) || type() == T.AT)) {
                raise(p.start, "Decorators cannot appear both before and after 'export'");
            }
            Node st = parseStatement(context, topLevel);
            if (pendingDecorators != null) {
                raise(p.start, "Decorators must be followed by a class declaration");
            }
            return st;
        }
        if (type() == T.SEMI) {
            next();
            return fin(new EmptyStatement(), p);
        }
        if (type() == T.NAME && !lex.escaped) {
            if (isLet(context)) {
                if (context != null) {
                    raise(p.start, "Lexical declaration cannot appear in a single-statement context");
                }
                next();
                VariableDeclaration decl = parseVar(p, false, VarKind.LET);
                semicolon();
                return fin(decl, p);
            }
            String name = (String) lex.value;
            switch (name) {
                case "break":
                case "continue":
                    return parseBreakContinue(p);
                case "debugger":
                    next();
                    semicolon();
                    return fin(new DebuggerStatement(), p);
                case "do":
                    return parseDoWhile(p);
                case "for":
                    return parseFor(p);
                case "function":
                    if (context != null && (strict || (!context.equals("if") && !context.equals("label")))) {
                        raise(p.start, strict
                                ? "In strict mode code, functions can only be declared at top level or inside a block"
                                : "Function declarations are not allowed in this context");
                    }
                    next();
                    return parseFunctionStatement(p, false, context);
                case "class":
                    if (context != null) {
                        unexpected();
                    }
                    ClassNode cls = parseClass(true, false);
                    return fin(new ClassDeclaration(cls), p);
                case "if":
                    return parseIf(p);
                case "return":
                    return parseReturn(p);
                case "switch":
                    return parseSwitch(p);
                case "throw":
                    return parseThrow(p);
                case "try":
                    return parseTry(p);
                case "const":
                case "var": {
                    VarKind kind = name.equals("var") ? VarKind.VAR : VarKind.CONST;
                    if (context != null && kind != VarKind.VAR) {
                        raise(p.start, "Lexical declaration cannot appear in a single-statement context");
                    }
                    next();
                    VariableDeclaration decl = parseVar(p, false, kind);
                    semicolon();
                    return fin(decl, p);
                }
                case "while":
                    return parseWhile(p);
                case "with":
                    return parseWith(p);
                case "import": {
                    T nt = lookahead(() -> type());
                    if (nt != T.LPAREN && nt != T.DOT) {
                        if (!topLevel || !inModule) {
                            raise(p.start, !inModule
                                    ? "Cannot use import statement outside a module"
                                    : "'import' and 'export' may only appear at the top level");
                        }
                        return parseImport(p);
                    }
                    break;
                }
                case "export":
                    if (!topLevel || !inModule) {
                        raise(p.start, !inModule
                                ? "Unexpected token 'export'"
                                : "'import' and 'export' may only appear at the top level");
                    }
                    return parseExport(p);
                case "async":
                    if (isAsyncFunction()) {
                        if (context != null) {
                            raise(p.start, "Async functions can only be declared at the top level or inside a block");
                        }
                        next();
                        next();
                        return parseFunctionStatement(p, true, context);
                    }
                    break;
                case "using":
                    if (isUsing(false)) {
                        if (context != null) {
                            raise(p.start, "Lexical declaration cannot appear in a single-statement context");
                        }
                        if (scopeStack.size() == 1 && !inModule) {
                            raise(p.start, "Using declaration cannot appear in the top level of a script");
                        }
                        if (isInSwitchCaseDirectly()) {
                            raise(p.start, "Using declaration cannot appear directly in a switch case");
                        }
                        next();
                        VariableDeclaration decl = parseVar(p, false, VarKind.USING);
                        semicolon();
                        return fin(decl, p);
                    }
                    break;
                case "await":
                    if (isAwaitUsing()) {
                        if (context != null) {
                            raise(p.start, "Lexical declaration cannot appear in a single-statement context");
                        }
                        if (scopeStack.size() == 1 && !inModule) {
                            raise(p.start, "Using declaration cannot appear in the top level of a script");
                        }
                        if (isInSwitchCaseDirectly()) {
                            raise(p.start, "Using declaration cannot appear directly in a switch case");
                        }
                        next();
                        next();
                        VariableDeclaration decl = parseVar(p, false, VarKind.AWAIT_USING);
                        semicolon();
                        return fin(decl, p);
                    }
                    break;
                default:
                    break;
            }
        }
        T startType = type();
        Node expr = parseExpression(false, null);
        if (startType == T.NAME && expr instanceof Identifier id && !id.parenthesized && type() == T.COLON) {
            next();
            return parseLabeled(p, id, context);
        }
        semicolon();
        return fin(new ExpressionStatement(expr), p);
    }

    private boolean isInSwitchCaseDirectly() {
        return switchCaseDepth == scopeStack.size();
    }

    private Node parseFunctionStatement(SP p, boolean isAsync, String context) {
        boolean hanging = context != null;
        if (type() == T.STAR && hanging) {
            unexpected();
        }
        boolean isGenerator = eat(T.STAR);
        if (hanging && isAsync) {
            unexpected();
        }
        Identifier id = parseBindingIdent();
        if (strict && (id.name.equals("eval") || id.name.equals("arguments"))) {
            raise(id.start, "Unexpected eval or arguments in strict mode");
        }
        // Annex B: function in if-statement body behaves as if in its own block; label bodies are declarations
        if (context == null || !context.contains("if")) {
            Bind bind;
            if (strict || isGenerator || isAsync) {
                bind = treatFunctionsAsVarInScope(currentScope()) ? Bind.VAR : Bind.LEXICAL;
            } else {
                bind = Bind.FUNCTION;
            }
            declareName(id.name, bind, id.start);
        }
        FunctionNode fn = parseFunctionRest(p, id, isAsync, isGenerator, FunctionKind.NORMAL, true);
        return fin(new FunctionDeclaration(fn), p);
    }

    private Node parseBreakContinue(SP p) {
        boolean isBreak = lex.value.equals("break");
        next();
        String label = null;
        if (!eat(T.SEMI) && !canInsertSemicolon()) {
            if (type() != T.NAME) {
                unexpected();
            }
            int lp = lex.start;
            label = parseLabelIdent();
            semicolon();
            boolean found = false;
            for (int i = labels.size() - 1; i >= 0; i--) {
                Label l = labels.get(i);
                if (Objects.equals(l.name, label)) {
                    if (!isBreak && l.kind != 1) {
                        raise(lp, "Illegal continue statement: '" + label + "' does not denote an iteration statement");
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                raise(lp, "Undefined label '" + label + "'");
            }
        } else {
            boolean found = false;
            for (int i = labels.size() - 1; i >= 0; i--) {
                Label l = labels.get(i);
                if (l.name == null && (l.kind == 1 || (isBreak && l.kind == 2))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                raise(p.start, isBreak
                        ? "Illegal break statement"
                        : "Illegal continue statement: no surrounding iteration statement");
            }
        }
        Node result = isBreak ? new BreakStatement(label) : new ContinueStatement(label);
        return fin(result, p);
    }

    private String parseLabelIdent() {
        int pos = lex.start;
        String name = (String) lex.value;
        if (lex.escaped && keywords.contains(name)) {
            raise(pos, "Keyword must not contain escaped characters");
        }
        checkUnreserved(name, pos);
        next();
        return name;
    }

    private void pushLoopLabel() {
        labels.add(new Label(null, 1, lex.start));
    }

    private Node parseDoWhile(SP p) {
        next();
        pushLoopLabel();
        Node body = parseStatement("do");
        labels.remove(labels.size() - 1);
        expectKw("while");
        Node test = parseParenExpression();
        eat(T.SEMI);
        return fin(new DoWhileStatement(body, test), p);
    }

    private Node parseWhile(SP p) {
        next();
        Node test = parseParenExpression();
        pushLoopLabel();
        Node body = parseStatement("while");
        labels.remove(labels.size() - 1);
        return fin(new WhileStatement(test, body), p);
    }

    private Node parseParenExpression() {
        expect(T.LPAREN);
        Node e = parseExpression(false, null);
        expect(T.RPAREN);
        return e;
    }

    private Node parseIf(SP p) {
        next();
        Node test = parseParenExpression();
        Node cons = parseStatement("if");
        Node alt = eatKw("else") ? parseStatement("if") : null;
        return fin(new IfStatement(test, cons, alt), p);
    }

    private Node parseReturn(SP p) {
        PScope vs = currentVarScope();
        boolean inFn = (vs.flags & SCOPE_FUNCTION) != 0 && !vs.inClassFieldInit;
        if (!inFn && !((vs.flags & SCOPE_TOP) != 0 && options.allowReturnOutsideFunction)) {
            raise(p.start, "Illegal return statement");
        }
        next();
        Node arg = null;
        if (!eat(T.SEMI) && !canInsertSemicolon()) {
            arg = parseExpression(false, null);
            semicolon();
        }
        return fin(new ReturnStatement(arg), p);
    }

    private Node parseThrow(SP p) {
        next();
        if (lex.nlBefore) {
            raise(lex.prevEnd, "Illegal newline after throw");
        }
        Node arg = parseExpression(false, null);
        semicolon();
        return fin(new ThrowStatement(arg), p);
    }

    private Node parseWith(SP p) {
        if (strict) {
            raise(p.start, "Strict mode code may not include a with statement");
        }
        next();
        Node obj = parseParenExpression();
        Node body = parseStatement("with");
        return fin(new WithStatement(obj, body), p);
    }

    private Node parseSwitch(SP p) {
        next();
        Node disc = parseParenExpression();
        List<SwitchCase> cases = new ArrayList<>();
        expect(T.LBRACE);
        labels.add(new Label(null, 2, lex.start));
        enterScope(0);
        int oldSwitchDepth = switchCaseDepth;
        switchCaseDepth = scopeStack.size();
        boolean sawDefault = false;

        final class SwitchState {
            Node test;
            ArrayList<Node> stmts;
            SP p;
        }
        final SwitchState cur = new SwitchState();
        Runnable flush = () -> {
            if (cur.stmts != null) {
                cases.add(fin(new SwitchCase(cur.test, cur.stmts), cur.p));
            }
        };
        while (type() != T.RBRACE) {
            if (isKw("case") || isKw("default")) {
                boolean isCase = isKw("case");
                flush.run();
                cur.p = sp();
                next();
                if (isCase) {
                    cur.test = parseExpression(false, null);
                } else {
                    if (sawDefault) {
                        raise(lex.prevEnd, "More than one default clause in switch statement");
                    }
                    sawDefault = true;
                    cur.test = null;
                }
                expect(T.COLON);
                cur.stmts = new ArrayList<>();
            } else {
                if (cur.stmts == null) {
                    unexpected();
                }
                cur.stmts.add(parseStatement(null, false));
            }
        }
        flush.run();
        next();
        switchCaseDepth = oldSwitchDepth;
        exitScope();
        labels.remove(labels.size() - 1);
        return fin(new SwitchStatement(disc, cases), p);
    }

    private Node parseTry(SP p) {
        next();
        BlockStatement block = parseBlock(true);
        CatchClause handler = null;
        if (isKw("catch")) {
            SP cp = sp();
            next();
            Node param = null;
            if (eat(T.LPAREN)) {
                param = parseBindingAtom();
                boolean simple = param instanceof Identifier;
                enterScope(simple ? SCOPE_SIMPLE_CATCH : 0);
                if (simple) {
                    checkLValSimple(param, Bind.SIMPLE_CATCH);
                } else {
                    checkLValPattern(param, Bind.LEXICAL, false);
                }
                expect(T.RPAREN);
            } else {
                enterScope(0);
            }
            BlockStatement body = parseBlock(false);
            exitScope();
            handler = fin(new CatchClause(param, body), cp);
        }
        BlockStatement finalizer = eatKw("finally") ? parseBlock(true) : null;
        if (handler == null && finalizer == null) {
            raise(lex.start, "Missing catch or finally after try");
        }
        return fin(new TryStatement(block, handler, finalizer), p);
    }

    private Node parseLabeled(SP p, Identifier label, String context) {
        String name = label.name;
        if (name.equals("await") && (inAsync() || inModule)) {
            raise(label.start, "Cannot use 'await' as a label");
        }
        if (name.equals("yield") && (inGenerator() || strict)) {
            raise(label.start, "Cannot use 'yield' as a label");
        }
        for (Label l : labels) {
            if (Objects.equals(l.name, name)) {
                raise(label.start, "Label '" + name + "' has already been declared");
            }
        }
        int kind = (isKw("for") || isKw("while") || isKw("do")) ? 1 : (isKw("switch") ? 2 : 0);
        for (int i = labels.size() - 1; i >= 0; i--) {
            Label l = labels.get(i);
            if (l.statementStart == p.start) {
                l.statementStart = lex.start;
                l.kind = kind;
            } else {
                break;
            }
        }
        labels.add(new Label(name, kind, lex.start));
        String newContext = context == null
                ? "label"
                : (!context.contains("label") ? context + "label" : context);
        Node body = parseStatement(newContext);
        labels.remove(labels.size() - 1);
        return fin(new LabeledStatement(name, body), p);
    }

    private VariableDeclaration parseVar(SP p, boolean isFor, VarKind kind) {
        List<VariableDeclarator> decls = new ArrayList<>();
        while (true) {
            SP dp = sp();
            Node id;
            if (kind == VarKind.USING || kind == VarKind.AWAIT_USING) {
                if (type() != T.NAME) {
                    unexpected();
                }
                id = parseBindingIdent();
            } else {
                id = parseBindingAtom();
            }
            Bind bind = kind == VarKind.VAR ? Bind.VAR : Bind.LEXICAL;
            if (kind != VarKind.VAR && id instanceof Identifier identifier && identifier.name.equals("let")) {
                raise(id.start, "let is disallowed as a lexically bound name");
            }
            checkLValPattern(id, bind, false);
            Node init = null;
            if (eat(T.ASSIGN)) {
                init = parseMaybeAssign(isFor, null);
            } else {
                boolean forInOf = isFor && (isKw("in") || isContextual("of"));
                if (kind == VarKind.CONST && !forInOf) {
                    raise(lex.start, "Missing initializer in const declaration");
                }
                if ((kind == VarKind.USING || kind == VarKind.AWAIT_USING)
                        && !(isFor && isContextual("of"))) {
                    raise(lex.start, "Missing initializer in using declaration");
                }
                if (!(id instanceof Identifier) && !forInOf) {
                    raise(lex.start, "Missing initializer in destructuring declaration");
                }
            }
            decls.add(fin(new VariableDeclarator(id, init), dp));
            if (!eat(T.COMMA)) {
                break;
            }
        }
        return fin(new VariableDeclaration(kind, decls), p);
    }

    private Node parseFor(SP p) {
        next();
        boolean isAwait = false;
        if (isContextual("await")) {
            if (!inAsync()) {
                raise(lex.start, "for await is only valid in async functions and the top level bodies of modules");
            }
            isAwait = true;
            next();
        }
        pushLoopLabel();
        enterScope(0);
        expect(T.LPAREN);
        if (type() == T.SEMI) {
            if (isAwait) {
                unexpected();
            }
            return parseForRest(p, null);
        }
        boolean isLetDecl = isLet(null);
        if (isKw("var") || isKw("const") || isLetDecl) {
            SP dp = sp();
            VarKind kind = isLetDecl ? VarKind.LET : (lex.value.equals("var") ? VarKind.VAR : VarKind.CONST);
            next();
            VariableDeclaration init = parseVar(dp, true, kind);
            return parseForAfterInit(p, init, isAwait);
        }
        VarKind usingKind = null;
        if (isUsing(true)) {
            usingKind = VarKind.USING;
        } else if (isContextual("await") && inAsync()
                && lookahead(() -> isContextual("using") && !lex.nlBefore
                && lookahead(() -> type() == T.NAME && !lex.nlBefore))) {
            usingKind = VarKind.AWAIT_USING;
        }
        if (usingKind != null) {
            SP dp = sp();
            if (usingKind == VarKind.AWAIT_USING) {
                next();
            }
            next();
            VariableDeclaration init = parseVar(dp, true, usingKind);
            if (isKw("in")) {
                raise(lex.start, "using declarations are not allowed in for-in");
            }
            return parseForAfterInit(p, init, isAwait);
        }
        boolean startsWithLet = isContextual("let");
        boolean containsEsc = lex.escaped;
        DestructuringErrors refErrors = new DestructuringErrors();
        int initStart = lex.start;
        Node init = isAwait ? parseExprSubscripts(refErrors, true) : parseExpression(true, refErrors);
        boolean isForOf = isContextual("of");
        if (isKw("in") || isForOf) {
            if (isAwait && isKw("in")) {
                unexpected();
            }
            if (isForOf && !isAwait && init.start == initStart && !containsEsc
                    && init instanceof Identifier identifier && identifier.name.equals("async")
                    && !identifier.parenthesized) {
                raise(init.start, "The left-hand side of a for-of loop may not be 'async'.");
            }
            if (startsWithLet && isForOf) {
                raise(init.start, "The left-hand side of a for-of loop may not start with 'let'.");
            }
            if (!strict && init instanceof CallExpression && !init.parenthesized) {
                // Annex B: runtime ReferenceError
                return parseForInOf(p, init, isAwait);
            }
            Node target = toAssignable(init, false, refErrors);
            checkLValPattern(target, Bind.NONE, false);
            return parseForInOf(p, target, isAwait);
        } else {
            checkExpressionErrors(refErrors, true);
        }
        if (isAwait) {
            unexpected();
        }
        return parseForRest(p, init);
    }

    private Node parseForAfterInit(SP p, VariableDeclaration init, boolean isAwait) {
        if ((isKw("in") || isContextual("of")) && init.declarations.size() == 1) {
            if (isKw("in") && isAwait) {
                unexpected();
            }
            return parseForInOf(p, init, isAwait);
        }
        if (isAwait) {
            unexpected();
        }
        for (VariableDeclarator d : init.declarations) {
            if (d.init == null && (init.kind == VarKind.CONST
                    || init.kind == VarKind.USING || init.kind == VarKind.AWAIT_USING)) {
                raise(d.end, "Missing initializer in declaration");
            }
            if (d.init == null && !(d.id instanceof Identifier)) {
                raise(d.end, "Missing initializer in destructuring declaration");
            }
        }
        return parseForRest(p, init);
    }

    private Node parseForRest(SP p, Node init) {
        expect(T.SEMI);
        Node test = type() == T.SEMI ? null : parseExpression(false, null);
        expect(T.SEMI);
        Node update = type() == T.RPAREN ? null : parseExpression(false, null);
        expect(T.RPAREN);
        Node body = parseStatement("for");
        exitScope();
        labels.remove(labels.size() - 1);
        return fin(new ForStatement(init, test, update, body), p);
    }

    private Node parseForInOf(SP p, Node left, boolean isAwait) {
        boolean isIn = isKw("in");
        next();
        if (left instanceof VariableDeclaration declaration && declaration.declarations.get(0).init != null) {
            VariableDeclarator d = declaration.declarations.get(0);
            if (!isIn || strict || declaration.kind != VarKind.VAR || !(d.id instanceof Identifier)) {
                raise(left.start, (isIn ? "for-in" : "for-of")
                        + " loop variable declaration may not have an initializer");
            }
        }

        Node right = isIn ? parseExpression(false, null) : parseMaybeAssign(false, null);
        expect(T.RPAREN);
        Node body = parseStatement("for");
        exitScope();
        labels.remove(labels.size() - 1);
        if (isIn) {
            return fin(new ForInStatement(left, right, body), p);
        }
        return fin(new ForOfStatement(left, right, body, isAwait), p);
    }

    // ============================================================ modules

    private record ModuleExportName(String name, boolean isString) {
    }

    private ModuleExportName parseModuleExportName() {
        if (type() == T.STRING) {
            String s = (String) lex.value;
            if (!isWellFormed(s)) {
                raise(lex.start, "An export name cannot include a lone surrogate");
            }
            next();
            return new ModuleExportName(s, true);
        }
        if (type() != T.NAME) {
            unexpected();
        }
        String n = (String) lex.value;
        next();
        return new ModuleExportName(n, false);
    }

    private boolean isWellFormed(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i += 2;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                return false;
            }
            i++;
        }
        return true;
    }

    private String parseModuleSource() {
        if (type() != T.STRING) {
            unexpected();
        }
        String s = (String) lex.value;
        next();
        return s;
    }

    private List<ImportAttribute> parseWithClause() {
        if (!isKw("with")) {
            return Collections.emptyList();
        }
        next();
        expect(T.LBRACE);
        List<ImportAttribute> attrs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean first = true;
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA);
                if (eat(T.RBRACE)) {
                    break;
                }
            }
            first = false;
            SP ap = sp();
            String key;
            switch (type()) {
                case STRING:
                    key = (String) lex.value;
                    break;
                case NAME:
                    key = (String) lex.value;
                    break;
                default:
                    throw unexpected();
            }
            next();
            if (!seen.add(key)) {
                raise(ap.start, "Duplicate import attribute '" + key + "'");
            }
            expect(T.COLON);
            if (type() != T.STRING) {
                unexpected();
            }
            String v = (String) lex.value;
            next();
            attrs.add(fin(new ImportAttribute(key, v), ap));
        }
        return attrs;
    }

    private Node parseImport(SP p) {
        next();
        List<ImportSpecifier> specs = new ArrayList<>();
        String phase = null;
        String source;
        if (type() == T.STRING) {
            source = parseModuleSource();
        } else {
            if ((isContextual("source") || isContextual("defer"))
                    && lookahead(() -> (type() == T.NAME && !isContextual("from"))
                    || type() == T.STAR
                    || (isContextual("from") && lookahead(() -> isContextual("from"))))) {
                phase = (String) lex.value;
                next();
            }
            if (type() == T.NAME) {
                SP ip = sp();
                Identifier local = parseBindingIdent();
                checkLValSimple(local, Bind.LEXICAL);
                specs.add(fin(new ImportSpecifier("default", local), ip));
                if (phase == null) {
                    boolean hasMore = eat(T.COMMA);
                    if (hasMore) {
                        parseImportSpecifiersRest(specs);
                    }
                }
            } else {
                parseImportSpecifiersRest(specs);
            }
            expectContextual("from");
            source = parseModuleSource();
        }
        List<ImportAttribute> attrs = parseWithClause();
        semicolon();
        ImportDeclaration node = fin(new ImportDeclaration(specs, source, attrs), p);
        node.phase = phase;
        return node;
    }

    private void parseImportSpecifiersRest(List<ImportSpecifier> specs) {
        if (type() == T.STAR) {
            SP ip = sp();
            next();
            expectContextual("as");
            Identifier local = parseBindingIdent();
            checkLValSimple(local, Bind.LEXICAL);
            specs.add(fin(new ImportSpecifier(ModuleNames.NAMESPACE, local), ip));
            return;
        }
        expect(T.LBRACE);
        boolean first = true;
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA);
                if (eat(T.RBRACE)) {
                    break;
                }
            }
            first = false;
            SP ip = sp();
            int namePos = lex.start;
            boolean nameEscaped = lex.escaped;
            ModuleExportName importedName = parseModuleExportName();
            String imported = importedName.name();
            boolean isString = importedName.isString();
            Identifier local;
            if (eatContextual("as")) {
                local = parseBindingIdent();
            } else {
                if (isString) {
                    raise(namePos, "String import names must be followed by 'as'");
                }
                if (nameEscaped && keywords.contains(imported)) {
                    raise(namePos, "Keyword must not contain escaped characters");
                }
                checkUnreserved(imported, namePos, true);
                local = new Identifier(imported);
                local.start = namePos;
                local.end = lex.prevEnd;
                local.line = ip.line;
                local.col = ip.col;
            }
            checkLValSimple(local, Bind.LEXICAL);
            specs.add(fin(new ImportSpecifier(imported, local), ip));
        }
    }

    private void addExport(String name, int pos) {
        if (!exportedNames.add(name)) {
            raise(pos, "Duplicate export of '" + name + "'");
        }
    }

    private boolean declaredAtTop(String name) {
        PScope top = scopeStack.get(0);
        return top.vars.contains(name) || top.lexical.contains(name) || top.functions.contains(name);
    }

    private Node parseExport(SP p) {
        next();
        if (type() == T.STAR) {
            next();
            String exported = null;
            if (eatContextual("as")) {
                int ep = lex.start;
                exported = parseModuleExportName().name();
                addExport(exported, ep);
            }
            expectContextual("from");
            String source = parseModuleSource();
            List<ImportAttribute> attrs = parseWithClause();
            semicolon();
            return fin(new ExportAllDeclaration(exported, source, attrs), p);
        }
        if (isKw("default")) {
            addExport("default", lex.start);
            next();
            SP dp = sp();
            if (type() == T.AT) {
                pendingDecorators = parseDecorators();
                if (!isKw("class")) {
                    unexpected();
                }
            }
            if (isKw("function") || isAsyncFunction()) {
                boolean isAsync = isContextual("async");
                if (isAsync) {
                    next();
                }
                next();
                boolean isGenerator = eat(T.STAR);
                Identifier id = null;
                if (type() == T.NAME) {
                    id = parseBindingIdent();
                    declareName(id.name, Bind.FUNCTION, id.start);
                }
                FunctionNode fn = parseFunctionRest(dp, id, isAsync, isGenerator, FunctionKind.NORMAL, true);
                return fin(new ExportDefaultDeclaration(fin(new FunctionDeclaration(fn), dp)), p);
            }
            if (isKw("class")) {
                ClassNode cls = parseClass(true, true);
                return fin(new ExportDefaultDeclaration(fin(new ClassDeclaration(cls), dp)), p);
            }
            Node expr = parseMaybeAssign(false, null);
            semicolon();
            return fin(new ExportDefaultDeclaration(expr), p);
        }
        if (type() == T.AT) {
            pendingDecorators = parseDecorators();
            if (!isKw("class")) {
                unexpected();
            }
        }
        if (type() == T.NAME && !lex.escaped && (lex.value.equals("var")
                || lex.value.equals("let") || lex.value.equals("const")
                || lex.value.equals("function") || lex.value.equals("class")
                || isAsyncFunction()
                || (lex.value.equals("using") && isUsing(false))
                || (lex.value.equals("await") && isAwaitUsing()))) {
            Node decl = parseStatement(null, false);
            List<Identifier> names = new ArrayList<>();
            if (decl instanceof VariableDeclaration declaration) {
                if (declaration.kind == VarKind.USING || declaration.kind == VarKind.AWAIT_USING) {
                    raise(declaration.start, "Using declarations cannot be exported");
                }
                for (VariableDeclarator d : declaration.declarations) {
                    collectBoundIdentifiers(d.id, names);
                }
            } else if (decl instanceof FunctionDeclaration functionDeclaration) {
                names.add(functionDeclaration.function.id);
            } else if (decl instanceof ClassDeclaration classDeclaration) {
                names.add(classDeclaration.cls.id);
            }
            for (Identifier n : names) {
                addExport(n.name, n.start);
            }
            return fin(new ExportNamedDeclaration(decl, new ArrayList<>(), null, new ArrayList<>()), p);
        }
        // export { ... } [from "..."]
        expect(T.LBRACE);
        List<ExportSpecifier> specs = new ArrayList<>();
        List<LocalCheck> localChecks = new ArrayList<>();
        boolean first = true;
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA);
                if (eat(T.RBRACE)) {
                    break;
                }
            }
            first = false;
            SP sp0 = sp();
            boolean localEscaped = lex.escaped;
            ModuleExportName localName = parseModuleExportName();
            String local = localName.name();
            boolean localIsString = localName.isString();
            String exported = local;
            if (eatContextual("as")) {
                exported = parseModuleExportName().name();
            }
            addExport(exported, sp0.start);
            localChecks.add(new LocalCheck(local, sp0.start, localIsString || localEscaped));
            specs.add(fin(new ExportSpecifier(local, exported, localIsString), sp0));
        }
        String source = null;
        List<ImportAttribute> attrs = Collections.emptyList();
        if (eatContextual("from")) {
            source = parseModuleSource();
            attrs = parseWithClause();
        } else {
            for (LocalCheck check : localChecks) {
                boolean specLocalIsString = false;
                for (ExportSpecifier spec : specs) {
                    if (spec.start == check.pos()) {
                        specLocalIsString = spec.localIsString;
                        break;
                    }
                }
                if (specLocalIsString) {
                    raise(check.pos(), "A string literal cannot be used as an exported binding without 'from'");
                }
                if (keywords.contains(check.local()) || strictReserved.contains(check.local())
                        || check.local().equals("await")) {
                    raise(check.pos(), "Unexpected reserved word '" + check.local() + "'");
                }
                if (!declaredAtTop(check.local())) {
                    undefinedExports.putIfAbsent(check.local(), check.pos());
                }
            }
        }
        semicolon();
        return fin(new ExportNamedDeclaration(null, specs, source, attrs), p);
    }

    private record LocalCheck(String local, int pos, boolean escapedOrString) {
    }

    /** After the whole module is parsed, names declared later still satisfy pending exports. */
    private void resolvePendingExports() {
        Iterator<String> it = undefinedExports.keySet().iterator();
        while (it.hasNext()) {
            if (declaredAtTop(it.next())) {
                it.remove();
            }
        }
    }
}
