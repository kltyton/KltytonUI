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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Binding kinds used for early-error redeclaration checks. */
enum Bind { NONE, VAR, LEXICAL, FUNCTION, SIMPLE_CATCH, OUTSIDE }

final class PScope {
    final int flags;
    final Set<String> vars = new HashSet<>();
    final List<String> lexical = new ArrayList<>();
    final Set<String> functions = new HashSet<>();
    /** First name declared via simple catch param (for Annex B var redeclaration). */
    String catchParam;
    boolean inClassFieldInit;

    PScope(int flags) {
        this.flags = flags;
    }
}

final class Label {
    final String name;
    int kind;
    int statementStart;

    Label(String name, int kind, int statementStart) {
        this.name = name;
        this.kind = kind;
        this.statementStart = statementStart;
    }
}

final class PrivateNameScope {
    final PrivateNameScope parent;
    /** name -> kind string: "field", "method", "get", "set", "accessor"; with static prefix "s". */
    final HashMap<String, String> declared = new HashMap<>();
    final List<PrivateIdentifier> used = new ArrayList<>();

    PrivateNameScope(PrivateNameScope parent) {
        this.parent = parent;
    }
}

/** Destructuring error tracking for cover grammars. Positions are -1 when unset. */
final class DestructuringErrors {
    int shorthandAssign = -1;
    int trailingComma = -1;
    int doubleProto = -1;
}

/** Shared parser state and helpers. */
abstract class ParserBase {
    static final int SCOPE_TOP = 1;
    static final int SCOPE_FUNCTION = 2;
    static final int SCOPE_ASYNC = 4;
    static final int SCOPE_GENERATOR = 8;
    static final int SCOPE_ARROW = 16;
    static final int SCOPE_SIMPLE_CATCH = 32;
    static final int SCOPE_SUPER = 64;
    static final int SCOPE_DIRECT_SUPER = 128;
    static final int SCOPE_CLASS_STATIC_BLOCK = 256;
    static final int SCOPE_CLASS_FIELD_INIT = 512;
    static final int SCOPE_VAR = SCOPE_TOP | SCOPE_FUNCTION | SCOPE_CLASS_STATIC_BLOCK;

    final String src;
    final ParseOptions options;
    final Lexer lex;
    final boolean inModule;
    boolean strict;

    final List<PScope> scopeStack = new ArrayList<>();
    List<Label> labels = new ArrayList<>();
    PrivateNameScope privateNames;

    /** Positions of yield/await expressions and 'await' identifiers (for arrow param validation). */
    int yieldPos = -1;
    int awaitPos = -1;
    int awaitIdentPos = -1;

    /** Set when a direct eval call is parsed inside the current function. */
    final List<FunctionInfo> functionStack = new ArrayList<>();

    static final class FunctionInfo {
        final Node node;
        boolean hasDirectEval;
        boolean usesArguments;
        boolean usesThis;
        boolean usesSuperProperty;
        boolean usesSuperCall;
        boolean usesNewTarget;
        boolean containsEval;

        FunctionInfo(Node node) {
            this.node = node;
        }
    }

    ParserBase(String src, ParseOptions options) {
        this.src = src;
        this.options = options;
        this.lex = new Lexer(src, options.sourceName, options.isModule);
        this.inModule = options.isModule;
        this.strict = options.strict || options.isModule;
    }

    T type() {
        return lex.type;
    }

    T getType() {
        return lex.type;
    }

    Object value() {
        return lex.value;
    }

    Object getValue() {
        return lex.value;
    }

    void next() {
        lex.next();
    }

    JSSyntaxError raise(int pos, String msg) {
        lex.error(msg, pos);
        throw new AssertionError("unreachable");
    }

    JSSyntaxError unexpected(int pos) {
        if (lex.type == T.EOF && pos == lex.start) {
            return raise(pos, "Unexpected end of input");
        }
        String tokText;
        switch (lex.type) {
            case NAME:
                tokText = "identifier '" + lex.value + "'";
                break;
            case STRING:
                tokText = "string";
                break;
            case NUM:
                tokText = "number";
                break;
            case EOF:
                tokText = "end of input";
                break;
            default:
                tokText = "token '" + lex.type.text + "'";
                break;
        }
        return raise(pos, "Unexpected " + tokText);
    }

    JSSyntaxError unexpected() {
        return unexpected(lex.start);
    }

    boolean eat(T t) {
        if (lex.type == t) {
            next();
            return true;
        }
        return false;
    }

    void expect(T t) {
        if (!eat(t)) {
            if (lex.type == T.EOF) {
                raise(lex.start, "Unexpected end of input");
            }
            raise(lex.start, "Expected '" + t.text + "' but found " + describeToken());
        }
    }

    String describeToken() {
        switch (lex.type) {
            case NAME:
                return "'" + lex.value + "'";
            case EOF:
                return "end of input";
            case STRING:
                return "string";
            case NUM:
                return "number";
            default:
                return "'" + lex.type.text + "'";
        }
    }

    /** Is the current token the (unescaped) contextual keyword {@code name}? */
    boolean isContextual(String name) {
        return lex.type == T.NAME && name.equals(lex.value) && !lex.escaped;
    }

    boolean eatContextual(String name) {
        if (isContextual(name)) {
            next();
            return true;
        }
        return false;
    }

    void expectContextual(String name) {
        if (!eatContextual(name)) {
            unexpected();
        }
    }

    /** Current token is a keyword {@code kw} (identifiers with escapes are never keywords). */
    boolean isKw(String kw) {
        return lex.type == T.NAME && kw.equals(lex.value) && !lex.escaped;
    }

    boolean eatKw(String kw) {
        if (isKw(kw)) {
            next();
            return true;
        }
        return false;
    }

    void expectKw(String kw) {
        if (!eatKw(kw)) {
            raise(lex.start, "Expected '" + kw + "' but found " + describeToken());
        }
    }

    boolean canInsertSemicolon() {
        return lex.type == T.EOF || lex.type == T.RBRACE || lex.nlBefore;
    }

    void semicolon() {
        if (!eat(T.SEMI) && !canInsertSemicolon()) {
            unexpected();
        }
    }

    boolean isLineTerminatorBefore() {
        return lex.nlBefore;
    }

    /** Peek at the next token type and value without consuming. */
    <R> R lookahead(Supplier<R> f) {
        LexState s = lex.save();
        try {
            next();
            return f.get();
        } finally {
            lex.restore(s);
        }
    }

    <N extends Node> N finish(N node, int start, int line, int col) {
        node.start = start;
        node.end = lex.prevEnd;
        node.line = line;
        node.col = col;
        return node;
    }

    <N extends Node> N finishAt(N node, Node startNode) {
        node.start = startNode.start;
        node.end = lex.prevEnd;
        node.line = startNode.line;
        node.col = startNode.col;
        return node;
    }

    // ------------------------------------------------------------ scopes

    void enterScope(int flags) {
        scopeStack.add(new PScope(flags));
    }

    void exitScope() {
        scopeStack.remove(scopeStack.size() - 1);
    }

    PScope currentScope() {
        return scopeStack.get(scopeStack.size() - 1);
    }

    PScope getCurrentScope() {
        return currentScope();
    }

    PScope currentVarScope() {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            PScope s = scopeStack.get(i);
            if ((s.flags & SCOPE_VAR) != 0) {
                return s;
            }
        }
        throw new IllegalStateException();
    }

    /** Nearest scope that provides {@code this} (non-arrow function, class field init, static block or top). */
    PScope currentThisScope() {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            PScope s = scopeStack.get(i);
            if ((s.flags & SCOPE_VAR) != 0 && (s.flags & SCOPE_ARROW) == 0) {
                return s;
            }
        }
        throw new IllegalStateException();
    }

    boolean inFunction() {
        return (currentVarScope().flags & SCOPE_FUNCTION) != 0
                || (!scopeStack.isEmpty() && functionStack.size() > 1);
    }

    boolean getInFunction() {
        return inFunction();
    }

    boolean isInFunction() {
        return inFunction();
    }

    boolean inGenerator() {
        PScope s = currentVarScope();
        return (s.flags & SCOPE_GENERATOR) != 0 && !s.inClassFieldInit;
    }

    boolean getInGenerator() {
        return inGenerator();
    }

    boolean isInGenerator() {
        return inGenerator();
    }

    boolean inAsync() {
        PScope s = currentVarScope();
        if (s.inClassFieldInit) {
            return false;
        }
        if ((s.flags & SCOPE_TOP) != 0) {
            return inModule || options.topLevelAwait;
        }
        return (s.flags & SCOPE_ASYNC) != 0;
    }

    boolean getInAsync() {
        return inAsync();
    }

    boolean isInAsync() {
        return inAsync();
    }

    /**
     * True when {@code await} is reserved here as an identifier (async function, module, static block). A class
     * field initializer is parsed with [~Await] whatever its context, so {@code await} is an identifier there in
     * scripts.
     */
    boolean awaitReserved() {
        if (inModule) {
            return true;
        }
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            PScope s = scopeStack.get(i);
            if ((s.flags & SCOPE_CLASS_STATIC_BLOCK) != 0) {
                return true;
            }
            if ((s.flags & SCOPE_VAR) != 0) {
                if (s.inClassFieldInit) {
                    return false;
                }
                if ((s.flags & SCOPE_ASYNC) != 0) {
                    return true;
                }
                if ((s.flags & SCOPE_TOP) != 0) {
                    return options.topLevelAwait;
                }
                return false;
            }
        }
        return false;
    }

    boolean getAwaitReserved() {
        return awaitReserved();
    }

    boolean isAwaitReserved() {
        return awaitReserved();
    }

    boolean inStaticBlock() {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            PScope s = scopeStack.get(i);
            if ((s.flags & SCOPE_CLASS_STATIC_BLOCK) != 0) {
                return true;
            }
            if ((s.flags & SCOPE_VAR) != 0) {
                return false;
            }
        }
        return false;
    }

    boolean getInStaticBlock() {
        return inStaticBlock();
    }

    boolean isInStaticBlock() {
        return inStaticBlock();
    }

    boolean inClassFieldInit() {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            PScope s = scopeStack.get(i);
            if (s.inClassFieldInit) {
                return true;
            }
            if ((s.flags & SCOPE_VAR) != 0 && (s.flags & SCOPE_ARROW) == 0) {
                return false;
            }
        }
        return false;
    }

    boolean getInClassFieldInit() {
        return inClassFieldInit();
    }

    boolean isInClassFieldInit() {
        return inClassFieldInit();
    }

    boolean allowSuperProperty() {
        PScope s = currentThisScope();
        if (s.inClassFieldInit) {
            return true;
        }
        if ((s.flags & SCOPE_TOP) != 0) {
            return options.allowSuperProperty;
        }
        return (s.flags & SCOPE_SUPER) != 0 || (s.flags & SCOPE_CLASS_STATIC_BLOCK) != 0;
    }

    boolean getAllowSuperProperty() {
        return allowSuperProperty();
    }

    boolean isAllowSuperProperty() {
        return allowSuperProperty();
    }

    boolean allowSuperCall() {
        PScope s = currentThisScope();
        if (s.inClassFieldInit) {
            return false;
        }
        if ((s.flags & SCOPE_TOP) != 0) {
            return options.allowSuperCall;
        }
        return (s.flags & SCOPE_DIRECT_SUPER) != 0;
    }

    boolean getAllowSuperCall() {
        return allowSuperCall();
    }

    boolean isAllowSuperCall() {
        return allowSuperCall();
    }

    boolean allowNewTarget() {
        PScope s = currentThisScope();
        if ((s.flags & SCOPE_TOP) != 0) {
            return options.allowNewTarget;
        }
        return true;
    }

    boolean getAllowNewTarget() {
        return allowNewTarget();
    }

    boolean isAllowNewTarget() {
        return allowNewTarget();
    }

    boolean allowArguments() {
        for (int i = scopeStack.size() - 1; i >= 0; i--) {
            PScope s = scopeStack.get(i);
            if (s.inClassFieldInit) {
                return false;
            }
            if ((s.flags & SCOPE_CLASS_STATIC_BLOCK) != 0) {
                return false;
            }
            if ((s.flags & SCOPE_VAR) != 0 && (s.flags & SCOPE_ARROW) == 0) {
                if ((s.flags & SCOPE_TOP) != 0) {
                    return options.allowArguments && !options.inClassFieldInit;
                }
                return true;
            }
        }
        return true;
    }

    boolean getAllowArguments() {
        return allowArguments();
    }

    boolean isAllowArguments() {
        return allowArguments();
    }

    boolean treatFunctionsAsVarInScope(PScope s) {
        return (s.flags & SCOPE_FUNCTION) != 0
                || (!inModule && (s.flags & SCOPE_TOP) != 0)
                || (s.flags & SCOPE_CLASS_STATIC_BLOCK) != 0;
    }

    void declareName(String name, Bind bind, int pos) {
        declareName(name, bind, pos, false);
    }

    void declareName(String name, Bind bind, int pos, boolean fromForOf) {
        boolean redeclared = false;
        switch (bind) {
            case LEXICAL: {
                PScope s = currentScope();
                redeclared = s.lexical.contains(name) || s.functions.contains(name) || s.vars.contains(name);
                s.lexical.add(name);
                break;
            }
            case SIMPLE_CATCH: {
                PScope s = currentScope();
                s.lexical.add(name);
                s.catchParam = name;
                break;
            }
            case FUNCTION: {
                PScope s = currentScope();
                if (treatFunctionsAsVarInScope(s)) {
                    redeclared = s.lexical.contains(name);
                } else {
                    redeclared = s.lexical.contains(name) || s.vars.contains(name);
                }
                s.functions.add(name);
                break;
            }
            case VAR: {
                for (int i = scopeStack.size() - 1; i >= 0; i--) {
                    PScope s = scopeStack.get(i);
                    if ((s.lexical.contains(name)
                            && !((s.flags & SCOPE_SIMPLE_CATCH) != 0 && name.equals(s.catchParam)))
                            || (!treatFunctionsAsVarInScope(s) && s.functions.contains(name))) {
                        redeclared = true;
                        break;
                    }
                    s.vars.add(name);
                    if ((s.flags & SCOPE_VAR) != 0) {
                        break;
                    }
                }
                break;
            }
            default:
                break;
        }
        if (redeclared) {
            raise(pos, "Identifier '" + name + "' has already been declared");
        }
    }

    // ------------------------------------------------------------ reserved words

    static final Set<String> keywords = Set.of(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
            "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof",
            "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void",
            "while", "with"
    );

    static final Set<String> strictReserved = Set.of(
            "implements", "interface", "let", "package", "private", "protected", "public", "static", "yield"
    );

    boolean isReservedWord(String name) {
        return keywords.contains(name);
    }

    /** Validates an identifier used as IdentifierReference / BindingIdentifier / LabelIdentifier. */
    void checkUnreserved(String name, int pos) {
        checkUnreserved(name, pos, false);
    }

    void checkUnreserved(String name, int pos, boolean isBinding) {
        if (inGenerator() && "yield".equals(name)) {
            raise(pos, "Cannot use 'yield' as identifier inside a generator");
        }
        if ("await".equals(name)) {
            if (inAsync() || awaitReserved()) {
                raise(pos, "Cannot use 'await' as identifier inside an async function or module");
            }
        }
        if ("arguments".equals(name) && !isBinding) {
            if (!allowArguments()) {
                raise(pos, "'arguments' is not allowed in class field initializer or static initialization block");
            }
        }
        if (inStaticBlock() && ("arguments".equals(name) || "await".equals(name))) {
            raise(pos, "Cannot use '" + name + "' in class static initialization block");
        }
        if (keywords.contains(name)) {
            raise(pos, "Unexpected reserved word '" + name + "'");
        }
        if (strict && strictReserved.contains(name)) {
            raise(pos, "Unexpected strict mode reserved word '" + name + "'");
        }
    }

    // ------------------------------------------------------------ labels

    ArrayList<Label> withFreshLabels() {
        @SuppressWarnings("unchecked")
        ArrayList<Label> old = (ArrayList<Label>) labels;
        labels = new ArrayList<>();
        return old;
    }

    // ------------------------------------------------------------ function info

    FunctionInfo currentFunctionInfo() {
        return functionStack.get(functionStack.size() - 1);
    }

    FunctionInfo getCurrentFunctionInfo() {
        return currentFunctionInfo();
    }

    /** The nearest non-arrow function info (where {@code this}/{@code arguments} usage is recorded). */
    FunctionInfo thisFunctionInfo() {
        return functionStack.get(functionStack.size() - 1);
    }
}
