/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/** Shared expression, pattern, function and class parsing. */
abstract class ExpressionParser extends ParserBase {
    static final class SP {
        final int start;
        final int line;
        final int col;

        SP(int start, int line, int col) {
            this.start = start;
            this.line = line;
            this.col = col;
        }
    }

    private static final class EmptyHole extends Node {
    }

    private static final EmptyHole EMPTY_HOLE = new EmptyHole();

    ExpressionParser(String src, ParseOptions options) {
        super(src, options);
    }

    SP sp() {
        return new SP(lex.start, lex.tokLine, lex.tokCol);
    }

    <N extends Node> N fin(N node, SP p) {
        node.start = p.start;
        node.line = p.line;
        node.col = p.col;
        node.end = lex.prevEnd;
        return node;
    }

    abstract List<Node> parseBlockBody(boolean endsWithBrace, boolean directives, Consumer<StringLiteral> onDirective);

    abstract BlockStatement parseBlock(boolean newScope);

    BlockStatement parseBlock() {
        return parseBlock(true);
    }

    // ============================================================ expressions

    Node parseExpression() {
        return parseExpression(false, null);
    }

    Node parseExpression(boolean noIn, DestructuringErrors refErrors) {
        SP p = sp();
        Node expr = parseMaybeAssign(noIn, refErrors);
        if (type() == T.COMMA) {
            ArrayList<Node> list = new ArrayList<>();
            list.add(expr);
            while (eat(T.COMMA)) {
                list.add(parseMaybeAssign(noIn, refErrors));
            }
            return fin(new SequenceExpression(list), p);
        }
        return expr;
    }

    void checkStrictOctal() {
        if (strict && lex.legacyOctalPos >= 0) {
            raise(lex.legacyOctalPos, type() == T.STRING
                    ? "Octal escape sequences are not allowed in strict mode"
                    : "Octal literals are not allowed in strict mode");
        }
    }

    Node parseMaybeAssign() {
        return parseMaybeAssign(false, null);
    }

    Node parseMaybeAssign(boolean noIn) {
        return parseMaybeAssign(noIn, null);
    }

    Node parseMaybeAssign(boolean noIn, DestructuringErrors refErrors0) {
        if (isContextual("yield")) {
            if (inGenerator()) {
                return parseYield(noIn);
            }
        }
        boolean ownErrors = false;
        DestructuringErrors refErrors = refErrors0;
        int oldTrailingComma = -1;
        int oldDoubleProto = -1;
        if (refErrors != null) {
            oldTrailingComma = refErrors.trailingComma;
            oldDoubleProto = refErrors.doubleProto;
            refErrors.trailingComma = -1;
            refErrors.doubleProto = -1;
        } else {
            refErrors = new DestructuringErrors();
            ownErrors = true;
        }
        SP p = sp();
        Node left = parseMaybeConditional(noIn, refErrors);
        if (type().isAssign()) {
            String op = type().text;
            boolean annexBCall = !strict && left instanceof CallExpression && !left.parenthesized
                    && type() != T.AND_ASSIGN && type() != T.OR_ASSIGN && type() != T.NULLISH_ASSIGN;
            if (type() == T.ASSIGN && !annexBCall) {
                left = toAssignable(left, false, refErrors);
            }
            if (!ownErrors) {
                refErrors.trailingComma = -1;
                refErrors.doubleProto = -1;
            }
            if (refErrors.shorthandAssign >= left.start) {
                refErrors.shorthandAssign = -1;
            }
            if (annexBCall) {
                // Annex B: f() = x throws ReferenceError at runtime in sloppy mode
            } else if (type() == T.ASSIGN) {
                checkLValPattern(left);
            } else {
                checkLValSimple(left);
                if (!(left instanceof Identifier) && !(left instanceof MemberExpression)) {
                    raise(left.start, "Invalid left-hand side in assignment");
                }
            }
            next();
            Node right = parseMaybeAssign(noIn);
            if (oldDoubleProto > -1) {
                refErrors.doubleProto = oldDoubleProto;
            }
            return fin(new AssignmentExpression(op, left, right), p);
        } else {
            if (ownErrors) {
                checkExpressionErrors(refErrors, true);
            }
        }
        if (oldTrailingComma > -1) {
            refErrors.trailingComma = oldTrailingComma;
        }
        if (oldDoubleProto > -1 && refErrors.doubleProto < 0) {
            refErrors.doubleProto = oldDoubleProto;
        }
        return left;
    }

    boolean checkExpressionErrors(DestructuringErrors refErrors, boolean andThrow) {
        if (refErrors == null) {
            return false;
        }
        int sa = refErrors.shorthandAssign;
        int dp = refErrors.doubleProto;
        if (!andThrow) {
            return sa >= 0 || dp >= 0;
        }
        if (sa >= 0) {
            raise(sa, "Shorthand property assignments are valid only in destructuring patterns");
        }
        if (dp >= 0) {
            raise(dp, "Redefinition of __proto__ property");
        }
        return false;
    }

    void checkPatternErrors(DestructuringErrors refErrors) {
        if (refErrors == null) {
            return;
        }
        if (refErrors.trailingComma > -1) {
            raise(refErrors.trailingComma, "Comma is not permitted after the rest element");
        }
    }

    Node parseMaybeConditional(boolean noIn, DestructuringErrors refErrors) {
        SP p = sp();
        Node expr = parseExprOps(noIn, refErrors);
        if (checkExpressionErrors(refErrors, false)) {
            return expr;
        }
        if (isBareArrow(expr)) {
            return expr;
        }
        if (eat(T.QUESTION)) {
            Node cons = parseMaybeAssign(false);
            expect(T.COLON);
            Node alt = parseMaybeAssign(noIn);
            return fin(new ConditionalExpression(expr, cons, alt), p);
        }
        return expr;
    }

    Node parseExprOps(boolean noIn, DestructuringErrors refErrors) {
        SP p = sp();
        Node expr = parseMaybeUnary(refErrors, false, noIn);
        if (checkExpressionErrors(refErrors, false)) {
            return expr;
        }
        if (isBareArrow(expr)) {
            return expr;
        }
        return parseExprOp(expr, p, -1, noIn);
    }

    private int binaryPrec(T t, boolean noIn) {
        switch (t) {
            case NULLISH: return 1;
            case OR: return 1;
            case AND: return 2;
            case BAR: return 3;
            case CARET: return 4;
            case AMP: return 5;
            case EQ: case NE: case SEQ: case SNE: return 6;
            case LT: case GT: case LE: case GE: return 7;
            case SHL: case SAR: case SHR: return 8;
            case PLUS: case MINUS: return 9;
            case STAR: case SLASH: case PERCENT: return 10;
            case NAME:
                if (lex.escaped) return -1;
                String value = (String) lex.value;
                if ("instanceof".equals(value)) return 7;
                if ("in".equals(value)) return noIn ? -1 : 7;
                return -1;
            default: return -1;
        }
    }

    Node parseExprOp(Node left, SP leftP, int minPrec, boolean noIn) {
        if (left instanceof PrivateIdentifier && !(isKw("in") && !noIn)) {
            throw unexpected();
        }
        int prec = binaryPrec(type(), noIn);
        if (prec >= 0 && prec > minPrec) {
            boolean logical = type() == T.OR || type() == T.AND;
            boolean coalesce = type() == T.NULLISH;
            String op = type() == T.NAME ? (String) lex.value : type().text;
            if (left instanceof PrivateIdentifier && !"in".equals(op)) {
                throw unexpected();
            }
            next();
            SP rp = sp();
            Node rightStart = parseMaybeUnary(null, false, noIn);
            if (isBareArrow(rightStart)) {
                raise(rightStart.start, "Malformed arrow function parameter list");
            }
            Node right = parseExprOp(rightStart, rp, coalesce ? 2 : prec, noIn);
            if (right instanceof PrivateIdentifier) {
                raise(right.start, "Private identifier can only be left side of binary expression");
            }
            Node node;
            if (left instanceof PrivateIdentifier) {
                node = fin(new PrivateInExpression((PrivateIdentifier) left, right), leftP);
            } else if (logical || coalesce) {
                node = fin(new LogicalExpression(op, left, right), leftP);
            } else {
                node = fin(new BinaryExpression(op, left, right), leftP);
            }
            if ((logical && type() == T.NULLISH)
                    || (coalesce && (type() == T.OR || type() == T.AND))) {
                raise(lex.start,
                        "Logical expressions and coalesce expressions cannot be mixed. Wrap either by parentheses");
            }
            return parseExprOp(node, leftP, minPrec, noIn);
        }
        if (left instanceof PrivateIdentifier) {
            throw unexpected(left.start);
        }
        return left;
    }

    Node parseMaybeUnary(DestructuringErrors refErrors, boolean sawUnary0, boolean noIn) {
        return parseMaybeUnary(refErrors, sawUnary0, noIn, false);
    }

    Node parseMaybeUnary(DestructuringErrors refErrors, boolean sawUnary0, boolean noIn,
                        boolean updateOperand) {
        SP p = sp();
        boolean sawUnary = sawUnary0;
        Node expr;
        if (isContextual("await") && inAsync()) {
            expr = parseAwait(noIn);
            sawUnary = true;
        } else if (type() == T.INC || type() == T.DEC) {
            String op = type().text;
            next();
            Node arg = parseMaybeUnary(null, true, noIn, true);
            checkLValSimple(arg);
            if (!(arg instanceof Identifier) && !(arg instanceof MemberExpression)
                    && !(arg instanceof CallExpression && !strict && !arg.parenthesized)) {
                raise(arg.start, "Invalid left-hand side expression in prefix operation");
            }
            expr = fin(new UpdateExpression(op, true, arg), p);
        } else if (type() == T.BANG || type() == T.TILDE || type() == T.PLUS || type() == T.MINUS
                || (type() == T.NAME && !lex.escaped
                && ("typeof".equals(lex.value) || "void".equals(lex.value) || "delete".equals(lex.value)))) {
            String op = type() == T.NAME ? (String) lex.value : type().text;
            next();
            Node arg = parseMaybeUnary(null, true, noIn);
            checkExpressionErrors(refErrors, true);
            if ("delete".equals(op)) {
                if (strict && unparen(arg) instanceof Identifier) {
                    raise(p.start, "Deleting local variable in strict mode");
                }
                if (isPrivateFieldAccess(arg)) {
                    raise(p.start, "Private fields can not be deleted");
                }
            }
            expr = fin(new UnaryExpression(op, arg), p);
            sawUnary = true;
        } else if (!sawUnary && type() == T.PRIVATE_NAME) {
            if (privateNames == null && options.privateNames.isEmpty()) {
                throw unexpected();
            }
            PrivateIdentifier pid = parsePrivateIdent();
            if (!isKw("in") || noIn) {
                throw unexpected();
            }
            expr = pid;
        } else {
            Node e = parseExprSubscripts(refErrors, noIn);
            if (checkExpressionErrors(refErrors, false)) {
                return e;
            }
            while ((type() == T.INC || type() == T.DEC) && !lex.nlBefore) {
                checkLValSimple(e);
                if (!(e instanceof Identifier) && !(e instanceof MemberExpression)
                        && !(e instanceof CallExpression && !strict && !e.parenthesized)) {
                    raise(e.start, "Invalid left-hand side expression in postfix operation");
                }
                String op = type().text;
                next();
                e = fin(new UpdateExpression(op, false, e), p);
            }
            expr = e;
        }
        if (isBareArrow(expr) || updateOperand) {
            return expr;
        }
        if (type() == T.STARSTAR) {
            if (sawUnary) {
                throw unexpected();
            }
            next();
            Node right = parseMaybeUnary(null, false, noIn);
            if (right instanceof PrivateIdentifier) {
                throw unexpected(right.start);
            }
            return fin(new BinaryExpression("**", expr, right), p);
        }
        return expr;
    }

    private Node unparen(Node n) {
        return n;
    }

    boolean isBareArrow(Node n) {
        return n instanceof FunctionNode && ((FunctionNode) n).kind == FunctionKind.ARROW
                && !n.parenthesized;
    }

    private boolean isPrivateFieldAccess(Node n) {
        if (n instanceof MemberExpression && ((MemberExpression) n).property instanceof PrivateIdentifier) {
            return true;
        }
        if (n instanceof ChainExpression) {
            return isPrivateFieldAccess(((ChainExpression) n).expression);
        }
        return false;
    }

    Node parseExprSubscripts(DestructuringErrors refErrors, boolean noIn) {
        SP p = sp();
        Node expr = parseExprAtom(refErrors, noIn);
        if (isBareArrow(expr)) {
            return expr;
        }
        Node result = parseSubscripts(expr, p, false, noIn);
        if (refErrors != null && result != expr) {
            if (refErrors.shorthandAssign >= expr.start) {
                raise(refErrors.shorthandAssign, "Invalid shorthand property initializer");
            }
            if (refErrors.doubleProto >= expr.start) {
                raise(refErrors.doubleProto, "Redefinition of __proto__ property");
            }
        }
        if (refErrors != null && result instanceof MemberExpression) {
            if (refErrors.trailingComma >= result.start) {
                refErrors.trailingComma = -1;
            }
        }
        return result;
    }

    Node parseSubscripts(Node base0, SP p, boolean noCalls, boolean noIn) {
        Node base = base0;
        boolean maybeAsyncArrow = base instanceof Identifier && "async".equals(((Identifier) base).name)
                && lex.prevEnd == base.end && !canInsertSemicolon() && base.end - base.start == 5
                && !base.parenthesized;
        boolean optionalChained = false;
        while (true) {
            boolean optional = type() == T.QDOT;
            if (optional) {
                if (noCalls) {
                    raise(lex.start, "Optional chaining cannot appear in the callee of new expressions");
                }
                next();
                optionalChained = true;
            }
            if (type() == T.LBRACKET) {
                next();
                Node prop = parseExpression();
                expect(T.RBRACKET);
                base = fin(new MemberExpression(base, prop, true, optional), p);
            } else if (optional && type() != T.LPAREN && type() != T.TEMPLATE) {
                base = fin(new MemberExpression(base, parsePropertyAccessName(), false, true), p);
            } else if (!optional && eat(T.DOT)) {
                if (base instanceof Super && type() == T.PRIVATE_NAME) {
                    raise(lex.start, "Unexpected private field access on super");
                }
                base = fin(new MemberExpression(base, parsePropertyAccessName(), false, false), p);
            } else if (!noCalls && type() == T.LPAREN) {
                DestructuringErrors refErrors = new DestructuringErrors();
                int oldYield = yieldPos;
                int oldAwait = awaitPos;
                int oldAwaitIdent = awaitIdentPos;
                yieldPos = -1;
                awaitPos = -1;
                awaitIdentPos = -1;
                next();
                List<Node> args = parseExprList(T.RPAREN, true, false, refErrors);
                if (maybeAsyncArrow && !optional && !optionalChained && type() == T.ARROW
                        && !lex.nlBefore) {
                    checkPatternErrors(refErrors);
                    checkYieldAwaitInDefaultParams();
                    if (awaitIdentPos > -1) {
                        raise(awaitIdentPos, "Cannot use 'await' as identifier inside an async function");
                    }
                    yieldPos = oldYield;
                    awaitPos = oldAwait;
                    awaitIdentPos = oldAwaitIdent;
                    next();
                    return parseArrowExpression(p, new ArrayList<>(args), true, noIn);
                }
                checkExpressionErrors(refErrors, true);
                if (oldYield > -1) yieldPos = oldYield;
                if (oldAwait > -1) awaitPos = oldAwait;
                if (oldAwaitIdent > -1) awaitIdentPos = oldAwaitIdent;
                ArrayList<Node> callArgs = new ArrayList<>();
                for (Node arg : args) {
                    callArgs.add(arg);
                }
                base = fin(new CallExpression(base, callArgs, optional), p);
            } else if (type() == T.TEMPLATE) {
                if (optional || optionalChained) {
                    raise(lex.start,
                            "Optional chaining cannot appear in the tag of tagged template expressions");
                }
                TemplateLiteral quasi = parseTemplate(true);
                base = fin(new TaggedTemplate(base, quasi), p);
            } else {
                if (optional) {
                    throw unexpected();
                }
                break;
            }
        }
        if (optionalChained) {
            base = fin(new ChainExpression(base), p);
        }
        return base;
    }

    Node parsePropertyAccessName() {
        if (type() == T.PRIVATE_NAME) {
            return parsePrivateIdent();
        }
        if (type() != T.NAME) {
            throw unexpected();
        }
        SP p = sp();
        String name = (String) lex.value;
        next();
        return fin(new Identifier(name), p);
    }

    PrivateIdentifier parsePrivateIdent() {
        SP p = sp();
        String name = (String) lex.value;
        next();
        PrivateIdentifier node = fin(new PrivateIdentifier(name), p);
        PrivateNameScope pn = privateNames;
        if (pn != null) {
            pn.used.add(node);
        } else if (!options.privateNames.contains(name)) {
            raise(node.start, "Private field '#" + name + "' must be declared in an enclosing class");
        }
        return node;
    }

    Node parseExprAtom(DestructuringErrors refErrors, boolean noIn) {
        SP p = sp();
        if (type() == T.AT) {
            pendingDecorators = parseDecorators();
            if (!isKw("class")) {
                throw unexpected();
            }
            return parseClass(false, false);
        }
        switch (type()) {
            case NAME: {
                String name = (String) lex.value;
                if (!lex.escaped) {
                    switch (name) {
                        case "this":
                            next();
                            return fin(new ThisExpression(), p);
                        case "null":
                            next();
                            return fin(new NullLiteral(), p);
                        case "true":
                            next();
                            return fin(new BooleanLiteral(true), p);
                        case "false":
                            next();
                            return fin(new BooleanLiteral(false), p);
                        case "function":
                            next();
                            return parseFunctionExpression(p, false);
                        case "class":
                            return parseClass(false, false);
                        case "new":
                            return parseNew();
                        case "super":
                            return parseSuper();
                        case "import":
                            return parseImportExpr();
                        case "async": {
                            LexState la = lex.save();
                            next();
                            if (isKw("function") && !lex.nlBefore) {
                                next();
                                return parseFunctionExpression(p, true);
                            }
                            if (type() == T.NAME && !lex.nlBefore) {
                                SP idP = sp();
                                String idName = (String) lex.value;
                                boolean esc = lex.escaped;
                                LexState la2 = lex.save();
                                next();
                                if (type() == T.ARROW && !lex.nlBefore) {
                                    if (esc && isReservedWord(idName)) {
                                        raise(idP.start, "Keyword must not contain escaped characters");
                                    }
                                    if ("await".equals(idName)) {
                                        raise(idP.start,
                                                "Cannot use 'await' as identifier inside an async function");
                                    }
                                    Identifier id = fin(new Identifier(idName), idP);
                                    id.end = la2.end;
                                    next();
                                    ArrayList<Node> params = new ArrayList<>();
                                    params.add(id);
                                    return parseArrowExpression(p, params, true, noIn);
                                }
                                lex.restore(la);
                            } else {
                                lex.restore(la);
                            }
                            break;
                        }
                        default:
                            break;
                    }
                }
                if (lex.escaped && keywords.contains(name) && !"await".equals(name)
                        && !"yield".equals(name)) {
                    raise(lex.start, "Keyword must not contain escaped characters");
                }
                Identifier id = parseIdentReference();
                if (type() == T.ARROW && !canInsertSemicolon()) {
                    next();
                    ArrayList<Node> params = new ArrayList<>();
                    params.add(id);
                    return parseArrowExpression(p, params, false, noIn);
                }
                return id;
            }
            case NUM: {
                checkStrictOctal();
                double v = (Double) lex.value;
                next();
                return fin(new NumberLiteral(v), p);
            }
            case BIGINT: {
                BigInteger v = (BigInteger) lex.value;
                next();
                return fin(new BigIntLiteral(v), p);
            }
            case STRING: {
                checkStrictOctal();
                StringLiteral node = new StringLiteral((String) lex.value);
                node.raw = src.substring(lex.start, lex.end);
                node.hasLegacyOctal = lex.legacyOctalPos >= 0;
                next();
                return fin(node, p);
            }
            case SLASH:
            case SLASH_ASSIGN: {
                lex.rescanRegExp();
                String pattern = (String) lex.value;
                String flags = lex.regexFlags;
                next();
                RegExpLiteral node = fin(new RegExpLiteral(pattern, flags), p);
                validateRegExp(node);
                return node;
            }
            case LPAREN:
                return parseParenAndDistinguishExpression(noIn);
            case LBRACKET: {
                next();
                List<Node> elements = parseExprList(T.RBRACKET, true, true, refErrors);
                return fin(new ArrayLiteral(new ArrayList<>(elements)), p);
            }
            case LBRACE:
                return parseObj(false, refErrors);
            case TEMPLATE:
                return parseTemplate(false);
            case PRIVATE_NAME:
            default:
                throw unexpected();
        }
    }

    void validateRegExp(RegExpLiteral node) {
        String err = RegExpSyntaxValidator.validate(node.pattern, node.flags);
        if (err != null) {
            raise(node.start, "Invalid regular expression: /" + node.pattern + "/" + node.flags + ": " + err);
        }
    }

    Identifier parseIdentReference() {
        SP p = sp();
        if (!(lex.value instanceof String)) {
            throw unexpected();
        }
        String name = (String) lex.value;
        checkUnreserved(name, p.start);
        if ("await".equals(name) && awaitIdentPos < 0) {
            awaitIdentPos = p.start;
        }
        next();
        return fin(new Identifier(name), p);
    }

    /** Parses a binding identifier (declarations, params). Validation happens in checkLVal. */
    Identifier parseBindingIdent() {
        if (type() != T.NAME) {
            throw unexpected();
        }
        SP p = sp();
        String name = (String) lex.value;
        if (lex.escaped && keywords.contains(name)) {
            raise(p.start, "Keyword must not contain escaped characters");
        }
        checkUnreserved(name, p.start, true);
        if ("await".equals(name) && awaitIdentPos < 0) {
            awaitIdentPos = p.start;
        }
        next();
        return fin(new Identifier(name), p);
    }

    Node parseSuper() {
        SP p = sp();
        next();
        switch (type()) {
            case LPAREN:
                if (!allowSuperCall()) {
                    raise(p.start, "'super' keyword unexpected here");
                }
                break;
            case DOT:
            case LBRACKET:
                if (!allowSuperProperty()) {
                    raise(p.start, "'super' keyword unexpected here");
                }
                break;
            default:
                raise(p.start, "'super' keyword unexpected here");
        }
        return fin(new Super(), p);
    }

    Node parseNew() {
        SP p = sp();
        next();
        if (type() == T.DOT) {
            next();
            if (!isContextual("target")) {
                if (type() == T.NAME && "target".equals(lex.value)) {
                    raise(lex.start, "'new.target' must not contain escaped characters");
                }
                raise(lex.start, "The only valid meta property for new is 'new.target'");
            }
            next();
            if (!allowNewTarget()) {
                raise(p.start,
                        "'new.target' can only be used in functions and class static block");
            }
            return fin(new MetaProperty("new", "target"), p);
        }
        if (isKw("import") && lookahead(() -> type() == T.LPAREN
                || (type() == T.DOT && lookahead(() -> isContextual("source") || isContextual("defer"))))) {
            raise(lex.start, "Cannot use new with import()");
        }
        SP cp = sp();
        Node callee = parseSubscripts(parseExprAtom(null, false), cp, true, false);
        List<Node> args;
        if (eat(T.LPAREN)) {
            args = new ArrayList<>();
            for (Node arg : parseExprList(T.RPAREN, true, false, null)) {
                args.add(arg);
            }
        } else {
            args = Collections.emptyList();
        }
        return fin(new NewExpression(callee, args), p);
    }

    Node parseImportExpr() {
        SP p = sp();
        next();
        if (type() == T.DOT) {
            next();
            if (isContextual("meta")) {
                if (!inModule) {
                    raise(p.start, "Cannot use 'import.meta' outside a module");
                }
                next();
                return fin(new MetaProperty("import", "meta"), p);
            }
            if (isContextual("source") || isContextual("defer")) {
                String phase = (String) lex.value;
                next();
                if (type() != T.LPAREN) {
                    throw unexpected();
                }
                return parseImportCall(p, phase);
            }
            if (type() == T.NAME && "meta".equals(lex.value)) {
                raise(lex.start, "'import.meta' must not contain escaped characters");
            }
            throw unexpected();
        }
        if (type() == T.LPAREN) {
            return parseImportCall(p, null);
        }
        throw unexpected(p.start);
    }

    private Node parseImportCall(SP p, String phase) {
        expect(T.LPAREN);
        if (type() == T.ELLIPSIS) {
            throw unexpected();
        }
        Node source = parseMaybeAssign(false);
        Node opts = null;
        if (eat(T.COMMA)) {
            if (type() != T.RPAREN) {
                if (type() == T.ELLIPSIS) {
                    throw unexpected();
                }
                opts = parseMaybeAssign(false);
                eat(T.COMMA);
            }
        }
        expect(T.RPAREN);
        return fin(new ImportCall(source, opts, phase), p);
    }

    /** Parses comma separated expressions until {@code close}. Allows holes for arrays. */
    List<Node> parseExprList(T close, boolean allowTrailingComma, boolean allowEmpty,
                             DestructuringErrors refErrors) {
        ArrayList<Node> elts = new ArrayList<>();
        boolean first = true;
        while (!eat(close)) {
            if (!first) {
                expect(T.COMMA);
                if (allowTrailingComma && type() == close) {
                    next();
                    break;
                }
            } else {
                first = false;
            }
            if (allowEmpty && type() == T.COMMA) {
                elts.add(null);
            } else if (type() == T.ELLIPSIS) {
                SP sp = sp();
                next();
                Node arg = parseMaybeAssign(false, refErrors);
                elts.add(fin(new SpreadElement(arg), sp));
                if (refErrors != null && type() == T.COMMA && refErrors.trailingComma < 0) {
                    refErrors.trailingComma = lex.start;
                }
            } else {
                elts.add(parseMaybeAssign(false, refErrors));
            }
        }
        return elts;
    }

    void checkYieldAwaitInDefaultParams() {
        if (yieldPos > -1 && (awaitPos < 0 || yieldPos < awaitPos)) {
            raise(yieldPos, "Yield expression cannot be a default value");
        }
        if (awaitPos > -1) {
            raise(awaitPos, "Await expression cannot be a default value");
        }
    }

    Node parseParenAndDistinguishExpression(boolean noIn) {
        SP p = sp();
        next();
        SP innerP = sp();
        ArrayList<Node> exprList = new ArrayList<>();
        boolean first = true;
        boolean lastIsComma = false;
        DestructuringErrors refErrors = new DestructuringErrors();
        int oldYield = yieldPos;
        int oldAwait = awaitPos;
        yieldPos = -1;
        awaitPos = -1;
        int spreadStart = -1;
        while (type() != T.RPAREN) {
            if (first) {
                first = false;
            } else {
                expect(T.COMMA);
            }
            if (type() == T.RPAREN) {
                lastIsComma = true;
                break;
            } else if (type() == T.ELLIPSIS) {
                spreadStart = lex.start;
                SP rp = sp();
                next();
                Node arg = parseBindingAtom();
                if (type() == T.ASSIGN) {
                    raise(lex.start, "Rest parameter may not have a default initializer");
                }
                exprList.add(fin(new RestElement(arg), rp));
                if (type() == T.COMMA) {
                    raise(lex.start, "Comma is not permitted after the rest element");
                }
                break;
            } else {
                exprList.add(parseMaybeAssign(false, refErrors));
            }
        }
        int innerEnd = lex.prevEnd;
        expect(T.RPAREN);
        if (type() == T.ARROW && !canInsertSemicolon()) {
            checkPatternErrors(refErrors);
            checkYieldAwaitInDefaultParams();
            yieldPos = oldYield;
            awaitPos = oldAwait;
            next();
            return parseArrowExpression(p, new ArrayList<>(exprList), false, noIn);
        }
        if (exprList.isEmpty() || lastIsComma) {
            throw unexpected(lex.prevEnd - 1);
        }
        if (spreadStart > -1) {
            throw unexpected(spreadStart);
        }
        checkExpressionErrors(refErrors, true);
        if (oldYield > -1) yieldPos = oldYield;
        if (oldAwait > -1) awaitPos = oldAwait;
        Node v;
        if (exprList.size() > 1) {
            SequenceExpression seq = new SequenceExpression(exprList);
            seq.start = innerP.start;
            seq.line = innerP.line;
            seq.col = innerP.col;
            seq.end = innerEnd;
            v = seq;
        } else {
            v = exprList.get(0);
        }
        v.parenthesized = true;
        return v;
    }

    // ============================================================ templates

    TemplateLiteral parseTemplate(boolean isTagged) {
        SP p = sp();
        ArrayList<TemplateElement> quasis = new ArrayList<>();
        ArrayList<Node> exprs = new ArrayList<>();
        while (true) {
            if (type() != T.TEMPLATE) {
                throw unexpected();
            }
            if (!isTagged && lex.cooked == null) {
                raise(lex.invalidEscapePos, "Invalid escape sequence in template");
            }
            TemplateElement el = new TemplateElement(lex.cooked, lex.raw);
            el.start = lex.start;
            el.end = lex.end;
            el.line = lex.tokLine;
            el.col = lex.tokCol;
            quasis.add(el);
            boolean tail = lex.templateTail;
            next();
            if (tail) {
                break;
            }
            exprs.add(parseExpression());
            if (type() != T.RBRACE) {
                throw unexpected();
            }
            lex.rescanTemplateContinuation();
        }
        return fin(new TemplateLiteral(quasis, exprs), p);
    }

    // ============================================================ yield / await

    Node parseYield(boolean noIn) {
        SP p = sp();
        if (yieldPos < 0) yieldPos = lex.start;
        next();
        if (type() == T.SEMI || canInsertSemicolon() || (type() != T.STAR && !startsExpr())) {
            return fin(new YieldExpression(null, false), p);
        }
        boolean delegate = eat(T.STAR);
        Node arg = parseMaybeAssign(noIn);
        return fin(new YieldExpression(arg, delegate), p);
    }

    private boolean startsExpr() {
        switch (type()) {
            case NAME:
                return !("in".equals(lex.value) || "instanceof".equals(lex.value)
                        || "of".equals(lex.value)) || lex.escaped;
            case NUM: case BIGINT: case STRING: case TEMPLATE: case REGEXP: case LPAREN:
            case LBRACKET: case LBRACE: case PLUS: case MINUS: case BANG: case TILDE:
            case INC: case DEC: case SLASH: case SLASH_ASSIGN: case PRIVATE_NAME:
            case LT: case AT:
                return true;
            default:
                return false;
        }
    }

    Node parseAwait(boolean noIn) {
        SP p = sp();
        if (awaitPos < 0) awaitPos = lex.start;
        next();
        Node arg = parseMaybeUnary(null, true, noIn);
        return fin(new AwaitExpression(arg), p);
    }

    // ============================================================ object literals

    Node parseObj(boolean isPattern, DestructuringErrors refErrors) {
        SP p = sp();
        next();
        ArrayList<Node> props = new ArrayList<>();
        boolean first = true;
        boolean hasProto = false;
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA);
                if (eat(T.RBRACE)) {
                    break;
                }
            } else {
                first = false;
            }
            Node prop = parseObjProperty(isPattern, refErrors);
            if (!isPattern && prop instanceof Property property && !property.computed
                    && !property.shorthand && !property.method && property.kind == PropKind.INIT) {
                Node k = property.key;
                String kn = k instanceof Identifier ? ((Identifier) k).name
                        : k instanceof StringLiteral ? ((StringLiteral) k).value : null;
                if ("__proto__".equals(kn)) {
                    if (hasProto) {
                        if (refErrors != null) {
                            if (refErrors.doubleProto < 0) {
                                refErrors.doubleProto = k.start;
                            }
                        } else {
                            raise(k.start, "Redefinition of __proto__ property");
                        }
                    }
                    hasProto = true;
                }
            }
            props.add(prop);
        }
        return isPattern ? fin(new ObjectPattern(props), p) : fin(new ObjectLiteral(props), p);
    }

    Node parseObjProperty(boolean isPattern, DestructuringErrors refErrors) {
        SP p = sp();
        if (type() == T.ELLIPSIS) {
            next();
            if (isPattern) {
                Identifier arg = parseBindingIdent();
                if (type() == T.COMMA) {
                    raise(lex.start, "Comma is not permitted after the rest element");
                }
                return fin(new RestElement(arg), p);
            }
            Node arg = parseMaybeAssign(false, refErrors);
            if (type() == T.COMMA && refErrors != null && refErrors.trailingComma < 0) {
                refErrors.trailingComma = lex.start;
            }
            return fin(new SpreadElement(arg), p);
        }
        boolean isAsync = false;
        boolean isGenerator = false;
        PropKind kind = PropKind.INIT;
        if (!isPattern) {
            isGenerator = eat(T.STAR);
        }
        boolean containsEsc = lex.escaped;
        KeyInfo keyInfo = parsePropertyName();
        if (!isPattern && !containsEsc && !isGenerator && !keyInfo.computed
                && keyInfo.key instanceof Identifier && isAsyncProp((Identifier) keyInfo.key)) {
            isAsync = true;
            isGenerator = eat(T.STAR);
            keyInfo = parsePropertyName();
        } else if (!isPattern && !containsEsc && !isGenerator && !keyInfo.computed
                && keyInfo.key instanceof Identifier
                && ("get".equals(((Identifier) keyInfo.key).name)
                || "set".equals(((Identifier) keyInfo.key).name))
                && type() != T.COMMA && type() != T.RBRACE && type() != T.COLON
                && type() != T.LPAREN && type() != T.ASSIGN) {
            kind = "get".equals(((Identifier) keyInfo.key).name) ? PropKind.GET : PropKind.SET;
            keyInfo = parsePropertyName();
        }
        return parsePropertyValue(p, keyInfo, isPattern, isGenerator, isAsync, kind, refErrors, containsEsc);
    }

    private boolean isAsyncProp(Identifier key) {
        return "async".equals(key.name) && !lex.nlBefore
                && (type() == T.NAME || type() == T.NUM || type() == T.STRING
                || type() == T.BIGINT || type() == T.LBRACKET || type() == T.STAR
                || type() == T.PRIVATE_NAME);
    }

    static final class KeyInfo {
        final Node key;
        final boolean computed;

        KeyInfo(Node key, boolean computed) {
            this.key = key;
            this.computed = computed;
        }
    }

    KeyInfo parsePropertyName() {
        return parsePropertyName(false);
    }

    KeyInfo parsePropertyName(boolean allowPrivate) {
        SP p = sp();
        switch (type()) {
            case LBRACKET: {
                next();
                Node k = parseMaybeAssign(false);
                expect(T.RBRACKET);
                return new KeyInfo(k, true);
            }
            case NUM: {
                checkStrictOctal();
                double v = (Double) lex.value;
                next();
                return new KeyInfo(fin(new NumberLiteral(v), p), false);
            }
            case BIGINT: {
                BigInteger v = (BigInteger) lex.value;
                next();
                return new KeyInfo(fin(new BigIntLiteral(v), p), false);
            }
            case STRING: {
                checkStrictOctal();
                StringLiteral s = new StringLiteral((String) lex.value);
                s.raw = src.substring(lex.start, lex.end);
                next();
                return new KeyInfo(fin(s, p), false);
            }
            case NAME: {
                String n = (String) lex.value;
                next();
                return new KeyInfo(fin(new Identifier(n), p), false);
            }
            case PRIVATE_NAME: {
                if (!allowPrivate) {
                    throw unexpected();
                }
                String n = (String) lex.value;
                next();
                return new KeyInfo(fin(new PrivateIdentifier(n), p), false);
            }
            default:
                throw unexpected();
        }
    }

    private Node parsePropertyValue(SP p, KeyInfo keyInfo, boolean isPattern,
                                    boolean isGenerator, boolean isAsync, PropKind kind,
                                    DestructuringErrors refErrors, boolean containsEsc) {
        Node key = keyInfo.key;
        if ((isGenerator || isAsync) && type() == T.COLON) {
            throw unexpected();
        }
        if (eat(T.COLON)) {
            if (kind != PropKind.INIT) {
                throw unexpected(lex.prevEnd - 1);
            }
            Node value = isPattern ? parseMaybeDefault() : parseMaybeAssign(false, refErrors);
            return fin(new Property(key, value, PropKind.INIT, keyInfo.computed, false, false), p);
        }
        if (type() == T.LPAREN) {
            if (isPattern) {
                throw unexpected();
            }
            FunctionKind fk;
            if (kind == PropKind.GET) {
                fk = FunctionKind.GETTER;
            } else if (kind == PropKind.SET) {
                fk = FunctionKind.SETTER;
            } else {
                fk = FunctionKind.METHOD;
            }
            FunctionNode fn = parseMethod(isGenerator, isAsync, fk, false, p);
            if (kind == PropKind.GET && !fn.params.isEmpty()) {
                raise(fn.start, "Getter must not have any formal parameters");
            }
            if (kind == PropKind.SET && (fn.params.size() != 1 || fn.params.get(0) instanceof RestElement)) {
                raise(fn.start, "Setter must have exactly one formal parameter");
            }
            return fin(new Property(key, fn, kind, keyInfo.computed, false, kind == PropKind.INIT), p);
        }
        if (kind != PropKind.INIT || isGenerator || isAsync) {
            throw unexpected();
        }
        if (!keyInfo.computed && key instanceof Identifier) {
            // shorthand
            String name = ((Identifier) key).name;
            if (containsEsc && keywords.contains(name)) {
                raise(key.start, "Keyword must not contain escaped characters");
            }
            checkUnreserved(name, key.start);
            if ("await".equals(name) && awaitIdentPos < 0) {
                awaitIdentPos = key.start;
            }
            Identifier id = new Identifier(name);
            id.start = key.start;
            id.end = key.end;
            id.line = key.line;
            id.col = key.col;
            if (isPattern) {
                Node v = parseMaybeDefaultFrom(id);
                return fin(new Property(key, v, PropKind.INIT, false, true, false), p);
            }
            if (type() == T.ASSIGN && refErrors != null) {
                if (refErrors.shorthandAssign < 0) {
                    refErrors.shorthandAssign = lex.start;
                }
                next();
                Node right = parseMaybeAssign(false);
                AssignmentPattern ap = new AssignmentPattern(id, right);
                ap.start = id.start;
                ap.line = id.line;
                ap.col = id.col;
                ap.end = lex.prevEnd;
                return fin(new Property(key, ap, PropKind.INIT, false, true, false), p);
            }
            return fin(new Property(key, id, PropKind.INIT, false, true, false), p);
        }
        throw unexpected();
    }

    // ============================================================ functions

    int functionFlags(boolean isAsync, boolean isGenerator) {
        return SCOPE_FUNCTION | (isAsync ? SCOPE_ASYNC : 0) | (isGenerator ? SCOPE_GENERATOR : 0);
    }

    FunctionNode parseFunctionExpression(SP p, boolean isAsync) {
        boolean isGenerator = eat(T.STAR);
        Identifier id = null;
        if (type() == T.NAME) {
            SP ip = sp();
            String name = (String) lex.value;
            if (lex.escaped && keywords.contains(name)) {
                raise(ip.start, "Keyword must not contain escaped characters");
            }
            if (isGenerator && "yield".equals(name)) {
                raise(ip.start, "Cannot use 'yield' as function name here");
            }
            if (isAsync && "await".equals(name)) {
                raise(ip.start, "Cannot use 'await' as function name here");
            }
            if (keywords.contains(name)) {
                throw unexpected();
            }
            if (strict && strictReserved.contains(name)) {
                raise(ip.start, "Unexpected strict mode reserved word '" + name + "'");
            }
            if (strict && ("eval".equals(name) || "arguments".equals(name))) {
                raise(ip.start, "Unexpected eval or arguments in strict mode");
            }
            if (!isAsync && "await".equals(name) && inModule) {
                raise(ip.start, "Cannot use 'await' as identifier");
            }
            next();
            id = fin(new Identifier(name), ip);
        }
        return parseFunctionRest(p, id, isAsync, isGenerator, FunctionKind.NORMAL, false);
    }

    /** Parses params and body. Current token must be '('. */
    FunctionNode parseFunctionRest(SP p, Identifier id, boolean isAsync, boolean isGenerator,
                                   FunctionKind kind, boolean isStatement) {
        int oldYield = yieldPos;
        int oldAwait = awaitPos;
        int oldAwaitIdent = awaitIdentPos;
        yieldPos = -1;
        awaitPos = -1;
        awaitIdentPos = -1;
        enterScope(functionFlags(isAsync, isGenerator)
                | ((kind == FunctionKind.METHOD || kind == FunctionKind.GETTER
                || kind == FunctionKind.SETTER || kind == FunctionKind.CLASS_CONSTRUCTOR)
                ? SCOPE_SUPER : 0)
                | (kind == FunctionKind.DERIVED_CONSTRUCTOR ? SCOPE_SUPER | SCOPE_DIRECT_SUPER : 0));
        int srcStart = p.start;
        expect(T.LPAREN);
        List<Node> params = parseBindingList(T.RPAREN, false, true);
        checkYieldAwaitInDefaultParams();
        FunctionNode fn = new FunctionNode(id, params, new EmptyStatement(), kind, isAsync, isGenerator);
        fn.isDeclaration = isStatement;
        parseFunctionBody(fn, false, kind != FunctionKind.NORMAL, false);
        yieldPos = oldYield;
        awaitPos = oldAwait;
        awaitIdentPos = oldAwaitIdent;
        fin(fn, p);
        fn.srcStart = srcStart;
        fn.srcEnd = lex.prevEnd;
        if (id != null) fn.name = id.name;
        return fn;
    }

    FunctionNode parseMethod(boolean isGenerator, boolean isAsync, FunctionKind kind,
                             boolean isStatic, SP nameStart) {
        SP p = sp();
        int oldYield = yieldPos;
        int oldAwait = awaitPos;
        int oldAwaitIdent = awaitIdentPos;
        yieldPos = -1;
        awaitPos = -1;
        awaitIdentPos = -1;
        int flags = functionFlags(isAsync, isGenerator) | SCOPE_SUPER;
        if (kind == FunctionKind.DERIVED_CONSTRUCTOR) {
            flags |= SCOPE_DIRECT_SUPER;
        }
        enterScope(flags);
        expect(T.LPAREN);
        List<Node> params = parseBindingList(T.RPAREN, false, true);
        checkYieldAwaitInDefaultParams();
        FunctionNode fn = new FunctionNode(null, params, new EmptyStatement(), kind, isAsync, isGenerator);
        fn.isStatic = isStatic;
        parseFunctionBody(fn, false, true, false);
        yieldPos = oldYield;
        awaitPos = oldAwait;
        awaitIdentPos = oldAwaitIdent;
        fin(fn, p);
        fn.srcStart = nameStart != null ? nameStart.start : p.start;
        fn.srcEnd = lex.prevEnd;
        return fn;
    }

    FunctionNode parseArrowExpression(SP p, List<Node> params0, boolean isAsync, boolean noIn) {
        int oldYield = yieldPos;
        int oldAwait = awaitPos;
        int oldAwaitIdent = awaitIdentPos;
        enterScope(functionFlags(isAsync, false) | SCOPE_ARROW);
        ArrayList<Node> params = new ArrayList<>();
        for (Node e : params0) {
            if (e == null) {
                throw unexpected();
            }
            params.add(toAssignable(e, true, null));
        }
        yieldPos = -1;
        awaitPos = -1;
        awaitIdentPos = -1;
        FunctionNode fn = new FunctionNode(null, params, new EmptyStatement(),
                FunctionKind.ARROW, isAsync, false);
        parseFunctionBody(fn, true, false, noIn);
        yieldPos = oldYield;
        awaitPos = oldAwait;
        awaitIdentPos = oldAwaitIdent;
        fin(fn, p);
        fn.srcStart = p.start;
        fn.srcEnd = lex.prevEnd;
        return fn;
    }

    boolean isSimpleParamList(List<Node> params) {
        for (Node param : params) {
            if (!(param instanceof Identifier)) {
                return false;
            }
        }
        return true;
    }

    /** Parses the function body; enterScope must have been called (this exits it). */
    void parseFunctionBody(FunctionNode fn, boolean isArrow, boolean isMethod, boolean noIn) {
        boolean isExpression = isArrow && type() != T.LBRACE;
        boolean oldStrict = strict;
        fn.hasSimpleParams = isSimpleParamList(fn.params);
        ArrayList<Identifier> paramNames = new ArrayList<>();
        for (Node prm : fn.params) {
            collectBoundIdentifiers(prm, paramNames);
        }
        for (Identifier id : paramNames) {
            declareName(id.name, Bind.VAR, id.start);
        }
        if (isExpression) {
            fn.body = parseMaybeAssign(noIn);
            fn.isExpressionBody = true;
            checkParams(fn, false);
        } else {
            ArrayList<Label> oldLabels = withFreshLabels();
            SP bp = sp();
            expect(T.LBRACE);
            List<Node> body = parseBlockBody(true, true, directive -> {
                if (directive.raw.length() == 12
                        && "use strict".equals(directive.raw.substring(1, 11))) {
                    if (!fn.hasSimpleParams) {
                        raise(directive.start,
                                "Illegal 'use strict' directive in function with non-simple parameter list");
                    }
                    strict = true;
                }
            });
            fn.body = fin(new BlockStatement(body), bp);
            labels = oldLabels;
            boolean allowDup = !strict && !isArrow && !isMethod && fn.hasSimpleParams
                    && fn.kind == FunctionKind.NORMAL;
            checkParams(fn, allowDup);
            if (strict && fn.id != null && fn.kind == FunctionKind.NORMAL) {
                String n = fn.id.name;
                if ("eval".equals(n) || "arguments".equals(n)) {
                    raise(fn.id.start, "Unexpected eval or arguments in strict mode");
                }
                if (strictReserved.contains(n) && !oldStrict) {
                    raise(fn.id.start, "Unexpected strict mode reserved word '" + n + "'");
                }
            }
        }
        fn.strict = strict;
        strict = oldStrict;
        exitScope();
    }

    void checkParams(FunctionNode fn, boolean allowDuplicates) {
        HashSet<String> seen = new HashSet<>();
        ArrayList<Identifier> ids = new ArrayList<>();
        for (Node prm : fn.params) {
            collectBoundIdentifiers(prm, ids);
        }
        for (Identifier id : ids) {
            String n = id.name;
            if (strict) {
                if ("eval".equals(n) || "arguments".equals(n)) {
                    raise(id.start, "Unexpected eval or arguments in strict mode");
                }
                if (strictReserved.contains(n)) {
                    raise(id.start, "Unexpected strict mode reserved word '" + n + "'");
                }
            }
            if (!seen.add(n) && !allowDuplicates) {
                raise(id.start, "Duplicate parameter name not allowed in this context");
            }
        }
    }

    void collectBoundIdentifiers(Node n, List<Identifier> out) {
        if (n == null) {
            return;
        }
        if (n instanceof Identifier) {
            out.add((Identifier) n);
        } else if (n instanceof AssignmentPattern) {
            collectBoundIdentifiers(((AssignmentPattern) n).left, out);
        } else if (n instanceof RestElement) {
            collectBoundIdentifiers(((RestElement) n).argument, out);
        } else if (n instanceof ArrayPattern) {
            for (Node element : ((ArrayPattern) n).elements) {
                collectBoundIdentifiers(element, out);
            }
        } else if (n instanceof ObjectPattern) {
            for (Node property : ((ObjectPattern) n).properties) {
                if (property instanceof Property) {
                    collectBoundIdentifiers(((Property) property).value, out);
                } else {
                    collectBoundIdentifiers(property, out);
                }
            }
        }
    }

    // ============================================================ binding patterns

    Node parseBindingAtom() {
        SP p = sp();
        if (type() == T.LBRACKET) {
            next();
            List<Node> elts = parseBindingList(T.RBRACKET, true, true);
            for (int i = 0; i < elts.size(); i++) {
                if (elts.get(i) instanceof EmptyHole) {
                    elts.set(i, null);
                }
            }
            return fin(new ArrayPattern(new ArrayList<>(elts)), p);
        }
        if (type() == T.LBRACE) {
            return parseObj(true, null);
        }
        return parseBindingIdent();
    }

    List<Node> parseBindingList(T close, boolean allowEmpty, boolean allowTrailingComma) {
        ArrayList<Node> elts = new ArrayList<>();
        boolean first = true;
        while (!eat(close)) {
            if (first) {
                first = false;
            } else {
                expect(T.COMMA);
            }
            if (allowEmpty && type() == T.COMMA) {
                elts.add(EMPTY_HOLE);
            } else if (allowTrailingComma && type() == close) {
                next();
                break;
            } else if (type() == T.ELLIPSIS) {
                SP p = sp();
                next();
                Node arg = parseBindingAtom();
                if (type() == T.ASSIGN) {
                    raise(lex.start, "Rest element may not have a default initializer");
                }
                elts.add(fin(new RestElement(arg), p));
                if (type() == T.COMMA) {
                    raise(lex.start, "Comma is not permitted after the rest element");
                }
                expect(close);
                break;
            } else {
                elts.add(parseMaybeDefault());
            }
        }
        return elts;
    }

    Node parseMaybeDefault() {
        Node left = parseBindingAtom();
        return parseMaybeDefaultFrom(left);
    }

    Node parseMaybeDefaultFrom(Node left) {
        if (!eat(T.ASSIGN)) {
            return left;
        }
        Node right = parseMaybeAssign(false);
        AssignmentPattern ap = new AssignmentPattern(left, right);
        ap.start = left.start;
        ap.line = left.line;
        ap.col = left.col;
        ap.end = lex.prevEnd;
        return ap;
    }

    // ============================================================ assignable conversion

    /** Converts an expression into an assignment/binding pattern. */
    Node toAssignable(Node node, boolean isBinding, DestructuringErrors refErrors) {
        if (node instanceof Identifier) {
            if (node.parenthesized && isBinding) {
                raise(node.start, "Invalid destructuring assignment target");
            }
            if (inAsync() && "await".equals(((Identifier) node).name)) {
                raise(node.start, "Cannot use 'await' as identifier inside an async function");
            }
            return node;
        }
        if (node instanceof ObjectPattern || node instanceof ArrayPattern
                || node instanceof AssignmentPattern || node instanceof RestElement) {
            return node;
        }
        if (node instanceof ObjectLiteral) {
            if (node.parenthesized) {
                raise(node.start, "Invalid destructuring assignment target");
            }
            if (refErrors != null) {
                checkPatternErrors(refErrors);
            }
            ObjectLiteral literal = (ObjectLiteral) node;
            ArrayList<Node> props = new ArrayList<>();
            for (int i = 0; i < literal.properties.size(); i++) {
                Node prop = literal.properties.get(i);
                if (prop instanceof SpreadElement) {
                    SpreadElement spread = (SpreadElement) prop;
                    Node arg = spread.argument;
                    if (i != literal.properties.size() - 1) {
                        raise(prop.start, "Rest element must be last element");
                    }
                    if (!(arg instanceof Identifier) && (isBinding || !(arg instanceof MemberExpression))) {
                        raise(arg.start, "Invalid rest element");
                    }
                    if (arg.parenthesized && isBinding) {
                        raise(arg.start, "Invalid rest element");
                    }
                    RestElement r = new RestElement(toAssignable(arg, isBinding, null));
                    r.start = prop.start;
                    r.end = prop.end;
                    r.line = prop.line;
                    r.col = prop.col;
                    props.add(r);
                } else if (prop instanceof Property) {
                    Property property = (Property) prop;
                    if (property.kind != PropKind.INIT || property.method) {
                        raise(property.key.start, "Object pattern can't contain getter or setter");
                    }
                    property.value = toAssignable(property.value, isBinding, null);
                    props.add(property);
                } else {
                    props.add(prop);
                }
            }
            ObjectPattern pat = new ObjectPattern(props);
            pat.start = node.start;
            pat.end = node.end;
            pat.line = node.line;
            pat.col = node.col;
            return pat;
        }
        if (node instanceof ArrayLiteral) {
            if (node.parenthesized) {
                raise(node.start, "Invalid destructuring assignment target");
            }
            if (refErrors != null) {
                checkPatternErrors(refErrors);
            }
            ArrayLiteral literal = (ArrayLiteral) node;
            ArrayList<Node> elts = new ArrayList<>();
            for (int i = 0; i < literal.elements.size(); i++) {
                Node e = literal.elements.get(i);
                if (e instanceof SpreadElement) {
                    SpreadElement spread = (SpreadElement) e;
                    if (i != literal.elements.size() - 1) {
                        raise(e.start, "Rest element must be last element");
                    }
                    if (isBinding && spread.argument instanceof AssignmentExpression) {
                        raise(spread.argument.start, "Rest elements cannot have a default value");
                    }
                    Node arg = spread.argument;
                    if (arg instanceof AssignmentExpression) {
                        raise(arg.start, "Rest elements cannot have a default value");
                    }
                    RestElement r = new RestElement(toAssignable(arg, isBinding, null));
                    r.start = e.start;
                    r.end = e.end;
                    r.line = e.line;
                    r.col = e.col;
                    elts.add(r);
                } else {
                    elts.add(e == null ? null : toAssignable(e, isBinding, null));
                }
            }
            ArrayPattern pat = new ArrayPattern(elts);
            pat.start = node.start;
            pat.end = node.end;
            pat.line = node.line;
            pat.col = node.col;
            return pat;
        }
        if (node instanceof SpreadElement) {
            SpreadElement spread = (SpreadElement) node;
            if (spread.argument instanceof AssignmentExpression) {
                raise(spread.argument.start, "Rest elements cannot have a default value");
            }
            RestElement r = new RestElement(toAssignable(spread.argument, isBinding, null));
            r.start = node.start;
            r.end = node.end;
            r.line = node.line;
            r.col = node.col;
            return r;
        }
        if (node instanceof AssignmentExpression) {
            AssignmentExpression assignment = (AssignmentExpression) node;
            if (!"=".equals(assignment.op)) {
                raise(assignment.target.end,
                        "Only '=' operator can be used for specifying default value.");
            }
            if (node.parenthesized) {
                raise(node.start, "Invalid destructuring assignment target");
            }
            AssignmentPattern ap = new AssignmentPattern(
                    toAssignable(assignment.target, isBinding, null), assignment.value);
            ap.start = node.start;
            ap.end = node.end;
            ap.line = node.line;
            ap.col = node.col;
            return ap;
        }
        if (node instanceof MemberExpression) {
            if (!isBinding) {
                if (((MemberExpression) node).optional) {
                    raise(node.start, "Invalid destructuring assignment target");
                }
                return node;
            }
            throw raise(node.start, "Invalid destructuring assignment target");
        }
        if (node instanceof ChainExpression) {
            throw raise(node.start, "Optional chaining cannot appear in left-hand side");
        }
        throw raise(node.start, isBinding
                ? "Invalid destructuring assignment target" : "Invalid left-hand side in assignment");
    }

    /** Validates simple assignment target / binding identifier. {@code bind} declares the name when not NONE. */
    void checkLValSimple(Node expr) {
        checkLValSimple(expr, Bind.NONE);
    }

    void checkLValSimple(Node expr, Bind bind) {
        boolean isBind = bind != Bind.NONE;
        if (expr instanceof Identifier) {
            Identifier id = (Identifier) expr;
            if (strict && ("eval".equals(id.name) || "arguments".equals(id.name))) {
                raise(id.start, (isBind ? "Binding " : "Assigning to ") + id.name + " in strict mode");
            }
            if (isBind) {
                if (bind == Bind.LEXICAL && "let".equals(id.name)) {
                    raise(id.start, "let is disallowed as a lexically bound name");
                }
                if (bind != Bind.OUTSIDE) {
                    declareName(id.name, bind, id.start);
                }
            }
        } else if (expr instanceof ChainExpression) {
            raise(expr.start, "Optional chaining cannot appear in left-hand side");
        } else if (expr instanceof MemberExpression) {
            if (isBind) {
                raise(expr.start, "Binding member expression");
            }
        } else if (isBind) {
            raise(expr.start, "Binding " + expr.getClass().getSimpleName());
        }
    }

    void checkLValPattern(Node expr) {
        checkLValPattern(expr, Bind.NONE, false);
    }

    void checkLValPattern(Node expr, Bind bind) {
        checkLValPattern(expr, bind, false);
    }

    void checkLValPattern(Node expr, Bind bind, boolean fromForOf) {
        if (expr instanceof ObjectPattern) {
            for (Node prop : ((ObjectPattern) expr).properties) {
                checkLValInnerPattern(prop, bind, fromForOf);
            }
        } else if (expr instanceof ArrayPattern) {
            for (Node e : ((ArrayPattern) expr).elements) {
                if (e != null) {
                    checkLValInnerPattern(e, bind, fromForOf);
                }
            }
        } else if (expr instanceof Identifier) {
            Identifier id = (Identifier) expr;
            if (fromForOf && bind == Bind.VAR) {
                if (strict && ("eval".equals(id.name) || "arguments".equals(id.name))) {
                    raise(id.start, "Binding " + id.name + " in strict mode");
                }
                declareName(id.name, Bind.VAR, id.start, true);
            } else {
                checkLValSimple(expr, bind);
            }
        } else {
            checkLValSimple(expr, bind);
            if (bind == Bind.NONE && !(expr instanceof MemberExpression)) {
                raise(expr.start, "Invalid left-hand side in assignment");
            }
        }
    }

    void checkLValInnerPattern(Node expr, Bind bind, boolean fromForOf) {
        if (expr instanceof Property) {
            checkLValInnerPattern(((Property) expr).value, bind, fromForOf);
        } else if (expr instanceof AssignmentPattern) {
            checkLValPattern(((AssignmentPattern) expr).left, bind, fromForOf);
        } else if (expr instanceof RestElement) {
            checkLValPattern(((RestElement) expr).argument, bind, fromForOf);
        } else {
            checkLValPattern(expr, bind, fromForOf);
        }
    }

    // ============================================================ classes

    /** Class decorators parsed just before {@code class}, attached by {@link #parseClass}. */
    List<Node> pendingDecorators;

    /** DecoratorList: {@code @} followed by an identifier reference with property accesses. */
    List<Node> parseDecorators() {
        ArrayList<Node> out = new ArrayList<>();
        while (type() == T.AT) {
            next();
            SP p = sp();
            Node e;
            if (type() == T.LPAREN) {
                next();
                e = parseExpression();
                expect(T.RPAREN);
            } else {
                if (type() != T.NAME) {
                    throw unexpected();
                }
                e = parseIdentReference();
                while (type() == T.DOT) {
                    next();
                    e = fin(new MemberExpression(e, parsePropertyAccessName(), false, false), p);
                }
                if (eat(T.LPAREN)) {
                    ArrayList<Node> args = new ArrayList<>();
                    for (Node arg : parseExprList(T.RPAREN, true, false, null)) {
                        args.add(arg);
                    }
                    e = fin(new CallExpression(e, args, false), p);
                }
            }
            out.add(e);
        }
        return out;
    }

    ClassNode parseClass(boolean isStatement, boolean optionalId) {
        List<Node> classDecorators = pendingDecorators != null
                ? pendingDecorators : Collections.emptyList();
        pendingDecorators = null;
        SP p = sp();
        next(); // class
        boolean oldStrict = strict;
        strict = true;
        Identifier id = null;
        if (type() == T.NAME && !isKw("extends")) {
            id = parseBindingIdent();
            String n = id.name;
            if ("let".equals(n) || "static".equals(n) || "yield".equals(n)
                    || "implements".equals(n) || "interface".equals(n)
                    || "package".equals(n) || "private".equals(n)
                    || "protected".equals(n) || "public".equals(n)) {
                raise(id.start, "Unexpected strict mode reserved word '" + n + "'");
            }
            if ("eval".equals(n) || "arguments".equals(n)) {
                raise(id.start, "Unexpected eval or arguments in strict mode");
            }
            if (isStatement) {
                declareName(n, Bind.LEXICAL, id.start);
            }
        } else if (isStatement && !optionalId) {
            throw unexpected();
        }
        Node superClass = null;
        if (eatKw("extends")) {
            superClass = parseExprSubscripts(null, false);
            if (isBareArrow(superClass)) {
                raise(superClass.start, "Unexpected arrow function in class heritage");
            }
        }
        PrivateNameScope pn = new PrivateNameScope(privateNames);
        privateNames = pn;
        expect(T.LBRACE);
        ArrayList<Node> body = new ArrayList<>();
        FunctionNode ctor = null;
        while (!eat(T.RBRACE)) {
            if (eat(T.SEMI)) {
                continue;
            }
            int decStart = lex.start;
            List<Node> decs = type() == T.AT ? parseDecorators() : Collections.emptyList();
            Node el = parseClassElement(superClass != null);
            if (el instanceof MethodDefinition
                    && ((MethodDefinition) el).kind == MethodKind.CONSTRUCTOR) {
                if (ctor != null) {
                    raise(el.start, "A class may only have one constructor");
                }
                ctor = ((MethodDefinition) el).value;
            }
            if (!decs.isEmpty()) {
                if (el instanceof MethodDefinition
                        && ((MethodDefinition) el).kind != MethodKind.CONSTRUCTOR) {
                    ((MethodDefinition) el).decorators = decs;
                } else if (el instanceof PropertyDefinition) {
                    ((PropertyDefinition) el).decorators = decs;
                } else {
                    raise(decStart, "Decorators are not valid here");
                }
            }
            body.add(el);
        }
        strict = oldStrict;
        // validate private names
        privateNames = pn.parent;
        for (PrivateIdentifier u : pn.used) {
            if (!pn.declared.containsKey(u.name)) {
                PrivateNameScope parent = pn.parent;
                if (parent != null) {
                    parent.used.add(u);
                } else if (!options.privateNames.contains(u.name)) {
                    raise(u.start, "Private field '#" + u.name
                            + "' must be declared in an enclosing class");
                }
            }
        }
        ClassNode cls = fin(new ClassNode(id, superClass, body), p);
        cls.constructor = ctor;
        cls.decorators = classDecorators;
        cls.isDeclaration = isStatement;
        cls.srcStart = p.start;
        cls.srcEnd = lex.prevEnd;
        return cls;
    }

    private Node parseClassElement(boolean derived) {
        SP p = sp();
        boolean isStatic = false;
        boolean isAsync = false;
        boolean isGenerator = false;
        MethodKind kind = MethodKind.METHOD;
        String keyName = null;
        boolean isAccessor = false;

        if (isContextual("static")) {
            LexState save = lex.save();
            next();
            if (type() == T.LBRACE) {
                return parseStaticBlock(p);
            }
            if (type() == T.ASSIGN || type() == T.SEMI || type() == T.RBRACE
                    || type() == T.LPAREN) {
                lex.restore(save);
                keyName = "static";
            } else {
                isStatic = true;
            }
        }
        SP mp = sp();
        if (keyName == null && isContextual("async")) {
            LexState save = lex.save();
            next();
            if ((type() == T.NAME || type() == T.STRING || type() == T.NUM
                    || type() == T.BIGINT || type() == T.LBRACKET
                    || type() == T.PRIVATE_NAME || type() == T.STAR) && !lex.nlBefore) {
                isAsync = true;
            } else {
                lex.restore(save);
                keyName = "async";
            }
        }
        if (keyName == null && type() == T.STAR) {
            next();
            isGenerator = true;
        }
        if (keyName == null && !isAsync && !isGenerator
                && (isContextual("get") || isContextual("set"))) {
            LexState save = lex.save();
            String which = (String) lex.value;
            next();
            if (type() == T.NAME || type() == T.STRING || type() == T.NUM
                    || type() == T.BIGINT || type() == T.LBRACKET
                    || type() == T.PRIVATE_NAME) {
                kind = "get".equals(which) ? MethodKind.GET : MethodKind.SET;
            } else {
                lex.restore(save);
            }
        }
        if (keyName == null && !isAsync && !isGenerator && kind == MethodKind.METHOD
                && isContextual("accessor")) {
            LexState save = lex.save();
            next();
            if ((type() == T.NAME || type() == T.STRING || type() == T.NUM
                    || type() == T.BIGINT || type() == T.LBRACKET
                    || type() == T.PRIVATE_NAME) && !lex.nlBefore) {
                isAccessor = true;
            } else {
                lex.restore(save);
            }
        }
        KeyInfo keyInfo = parsePropertyName(true);
        Node key = keyInfo.key;
        String keyStr;
        if (keyInfo.computed) {
            keyStr = null;
        } else if (key instanceof Identifier) {
            keyStr = ((Identifier) key).name;
        } else if (key instanceof StringLiteral) {
            keyStr = ((StringLiteral) key).value;
        } else {
            keyStr = null;
        }
        if (key instanceof PrivateIdentifier
                && "constructor".equals(((PrivateIdentifier) key).name)) {
            raise(key.start, "Classes may not have a private field named '#constructor'");
        }

        if (type() == T.LPAREN && !isAccessor) {
            // method
            boolean isCtor = !isStatic && "constructor".equals(keyStr);
            if (isCtor) {
                if (kind != MethodKind.METHOD) {
                    raise(key.start, "Class constructor may not be an accessor");
                }
                if (isGenerator) {
                    raise(key.start, "Class constructor may not be a generator");
                }
                if (isAsync) {
                    raise(key.start, "Class constructor may not be an async method");
                }
                kind = MethodKind.CONSTRUCTOR;
            }
            if (isStatic && "prototype".equals(keyStr) && !(key instanceof PrivateIdentifier)) {
                raise(key.start, "Classes may not have a static property named 'prototype'");
            }
            FunctionKind fk;
            if (kind == MethodKind.CONSTRUCTOR) {
                fk = derived ? FunctionKind.DERIVED_CONSTRUCTOR : FunctionKind.CLASS_CONSTRUCTOR;
            } else if (kind == MethodKind.GET) {
                fk = FunctionKind.GETTER;
            } else if (kind == MethodKind.SET) {
                fk = FunctionKind.SETTER;
            } else {
                fk = FunctionKind.METHOD;
            }
            FunctionNode fn = parseMethod(isGenerator, isAsync, fk, isStatic, mp);
            if (kind == MethodKind.GET && !fn.params.isEmpty()) {
                raise(fn.start, "Getter must not have any formal parameters");
            }
            if (kind == MethodKind.SET
                    && (fn.params.size() != 1 || fn.params.get(0) instanceof RestElement)) {
                raise(fn.start, "Setter must have exactly one formal parameter");
            }
            if (key instanceof PrivateIdentifier) {
                declarePrivate((PrivateIdentifier) key,
                        kind == MethodKind.GET ? "get" : kind == MethodKind.SET ? "set" : "method",
                        isStatic);
            }
            return fin(new MethodDefinition(key, fn, kind, isStatic, keyInfo.computed), p);
        }
        // field
        if (isGenerator || isAsync || kind != MethodKind.METHOD) {
            throw unexpected();
        }
        if ("constructor".equals(keyStr) && !(key instanceof PrivateIdentifier)) {
            raise(key.start, "Classes may not have a field named 'constructor'");
        }
        if (isStatic && "prototype".equals(keyStr) && !(key instanceof PrivateIdentifier)) {
            raise(key.start, "Classes may not have a static property named 'prototype'");
        }
        if (key instanceof PrivateIdentifier) {
            declarePrivate((PrivateIdentifier) key, isAccessor ? "accessor" : "field", isStatic);
        }
        Node value = null;
        FunctionNode initFn = null;
        if (eat(T.ASSIGN)) {
            SP ip = sp();
            int oldYield = yieldPos;
            int oldAwait = awaitPos;
            int oldAwaitIdent = awaitIdentPos;
            yieldPos = -1;
            awaitPos = -1;
            awaitIdentPos = -1;
            enterScope(SCOPE_FUNCTION | SCOPE_SUPER | SCOPE_CLASS_FIELD_INIT);
            currentScope().inClassFieldInit = true;
            ArrayList<Label> oldLabels = withFreshLabels();
            value = parseMaybeAssign(false);
            labels = oldLabels;
            exitScope();
            yieldPos = oldYield;
            awaitPos = oldAwait;
            awaitIdentPos = oldAwaitIdent;
            initFn = new FunctionNode(null, new ArrayList<>(), value,
                    FunctionKind.CLASS_FIELD_INIT, false, false);
            initFn.isExpressionBody = true;
            initFn.strict = true;
            initFn.isStatic = isStatic;
            fin(initFn, ip);
            initFn.srcStart = ip.start;
            initFn.srcEnd = lex.prevEnd;
        }
        if (!eat(T.SEMI)) {
            if (type() != T.RBRACE && !lex.nlBefore) {
                throw unexpected();
            }
        }
        PropertyDefinition pd = fin(new PropertyDefinition(key, value, isStatic, keyInfo.computed), p);
        pd.initializer = initFn;
        pd.isAccessor = isAccessor;
        return pd;
    }

    private void declarePrivate(PrivateIdentifier key, String kind, boolean isStatic) {
        PrivateNameScope pn = privateNames;
        String existing = pn.declared.get(key.name);
        String k = (isStatic ? "s" : "i") + kind;
        if (existing != null) {
            boolean ok = (existing.equals(isStatic ? "sget" : "iget") && "set".equals(kind))
                    || (existing.equals(isStatic ? "sset" : "iset") && "get".equals(kind));
            if (!ok) {
                raise(key.start, "Identifier '#" + key.name + "' has already been declared");
            }
            pn.declared.put(key.name, (isStatic ? "s" : "i") + "getset");
        } else {
            pn.declared.put(key.name, k);
        }
    }

    private Node parseStaticBlock(SP p) {
        // current token is '{'
        ArrayList<Label> oldLabels = withFreshLabels();
        int oldYield = yieldPos;
        int oldAwait = awaitPos;
        int oldAwaitIdent = awaitIdentPos;
        yieldPos = -1;
        awaitPos = -1;
        awaitIdentPos = -1;
        enterScope(SCOPE_CLASS_STATIC_BLOCK | SCOPE_SUPER);
        SP bp = sp();
        expect(T.LBRACE);
        List<Node> body = parseBlockBody(true, false, null);
        exitScope();
        labels = oldLabels;
        yieldPos = oldYield;
        awaitPos = oldAwait;
        awaitIdentPos = oldAwaitIdent;
        StaticBlock sb = fin(new StaticBlock(body), p);
        FunctionNode fn = new FunctionNode(null, new ArrayList<>(),
                fin(new BlockStatement(body), bp), FunctionKind.STATIC_BLOCK, false, false);
        fn.strict = true;
        fn.isStatic = true;
        fin(fn, bp);
        fn.srcStart = bp.start;
        fn.srcEnd = lex.prevEnd;
        sb.function = fn;
        return sb;
    }
}
