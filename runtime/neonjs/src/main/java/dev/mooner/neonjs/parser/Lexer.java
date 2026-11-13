/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.math.BigInteger;
import java.util.HashMap;

/**
 * JavaScript lexer. Ported from NeonJS Lexer.kt; tokenization order, error positions and messages
 * are kept identical.
 */
public class Lexer {
    public final String src;
    public final String sourceName;
    public final boolean isModule;

    public int pos = 0;
    public int line = 1;
    public int lineStart = 0;

    // current token
    public T type = T.EOF;
    public int start = 0;
    public int end = 0;
    public int tokLine = 1;
    public int tokCol = 0;
    public boolean nlBefore = false;
    public Object value = null;
    public boolean escaped = false;
    /** Position of the first legacy octal literal/escape or \\8 \\9 escape in the current token, -1 if none. */
    public int legacyOctalPos = -1;
    /** Template raw text / cooked value (cooked is null when the template has an invalid escape). */
    public String raw = null;
    public String cooked = null;
    public boolean templateTail = false;
    public String regexFlags = null;
    /** For templates: position of the invalid escape (for error reporting in untagged templates). */
    public int invalidEscapePos = -1;

    // previous token end, for ASI and node end positions
    public int prevEnd = 0;
    public int prevLine = 1;
    public int prevCol = 0;

    private final HashMap<String, String> names = new HashMap<>();
    private final StringBuilder sb = new StringBuilder();

    public Lexer(String src, String sourceName, boolean isModule) {
        this.src = src;
        this.sourceName = sourceName;
        this.isModule = isModule;
        // hashbang
        if (src.startsWith("#!")) {
            pos = 2;
            while (pos < src.length() && !Chars.isLineTerminator(src.charAt(pos))) {
                pos++;
            }
        }
    }

    public Lexer(String src) {
        this(src, null, false);
    }

    public Lexer(String src, String sourceName) {
        this(src, sourceName, false);
    }

    public LexState save() {
        return new LexState(pos, line, lineStart, type, start, end, tokLine, tokCol, nlBefore,
                value, escaped, legacyOctalPos, raw, cooked, templateTail, regexFlags, prevEnd,
                prevLine, prevCol, invalidEscapePos);
    }

    public void restore(LexState s) {
        pos = s.pos;
        line = s.line;
        lineStart = s.lineStart;
        type = s.type;
        start = s.start;
        end = s.end;
        tokLine = s.tokLine;
        tokCol = s.tokCol;
        nlBefore = s.nlBefore;
        value = s.value;
        escaped = s.escaped;
        legacyOctalPos = s.legacyOctalPos;
        raw = s.raw;
        cooked = s.cooked;
        templateTail = s.templateTail;
        regexFlags = s.regexFlags;
        prevEnd = s.prevEnd;
        prevLine = s.prevLine;
        prevCol = s.prevCol;
        invalidEscapePos = s.invalidEscapePos;
    }

    public JSSyntaxError error(String msg) {
        return error(msg, start);
    }

    public JSSyntaxError error(String msg, int at) {
        LineCol location = lineCol(at);
        throw new JSSyntaxError(msg, at, location.line, location.column, sourceName);
    }

    public LineCol lineCol(int at) {
        int l = 1;
        int ls = 0;
        int i = 0;
        int lim = Math.min(at, src.length());
        while (i < lim) {
            int c = src.charAt(i);
            if (c == 0x0A || c == 0x2028 || c == 0x2029) {
                l++;
                ls = i + 1;
            } else if (c == 0x0D) {
                if (i + 1 < lim && src.charAt(i + 1) == 0x0A) {
                    i++;
                }
                l++;
                ls = i + 1;
            }
            i++;
        }
        return new LineCol(l, at - ls + 1);
    }

    private int cur() {
        return pos < src.length() ? src.charAt(pos) : -1;
    }

    private int peekc() {
        return peekc(1);
    }

    private int peekc(int off) {
        return pos + off < src.length() ? src.charAt(pos + off) : -1;
    }

    private int codePointAt(int i) {
        char c = src.charAt(i);
        if (Character.isHighSurrogate(c) && i + 1 < src.length()
                && Character.isLowSurrogate(src.charAt(i + 1))) {
            return Character.toCodePoint(c, src.charAt(i + 1));
        }
        return c;
    }

    private void newline(int c) {
        // pos points at the line terminator
        if (c == 0x0D && peekc() == 0x0A) {
            pos++;
        }
        pos++;
        line++;
        lineStart = pos;
    }

    /** Skips whitespace and comments, sets nlBefore. */
    private void skipSpace() {
        boolean atStart = pos == 0
                || (src.startsWith("#!") && start == 0 && type == T.EOF && prevEnd == 0);
        boolean sawOnlyTrivia = atStart;
        while (pos < src.length()) {
            int c = src.charAt(pos);
            if (c == 0x0A || c == 0x0D || c == 0x2028 || c == 0x2029) {
                newline(c);
                nlBefore = true;
            } else if (c == 0x20 || c == 0x09) {
                pos++;
            } else if (c == '/') {
                int n = peekc();
                if (n == '/') {
                    pos += 2;
                    skipLineComment();
                } else if (n == '*') {
                    int cs = pos;
                    pos += 2;
                    boolean closed = false;
                    while (pos < src.length()) {
                        int d = src.charAt(pos);
                        if (d == '*' && peekc() == '/') {
                            pos += 2;
                            closed = true;
                            break;
                        }
                        if (Chars.isLineTerminator(d)) {
                            newline(d);
                            nlBefore = true;
                        } else {
                            pos++;
                        }
                    }
                    if (!closed) {
                        error("Unterminated comment", cs);
                    }
                } else {
                    return;
                }
            } else if (c == '<' && !isModule && src.startsWith("<!--", pos)) {
                pos += 4;
                skipLineComment();
            } else if (c == '-' && !isModule && (nlBefore || sawOnlyTrivia)
                    && src.startsWith("-->", pos)) {
                pos += 3;
                skipLineComment();
            } else if (c > 0x7F && Chars.isWhitespace(c)) {
                pos++;
            } else if (c == 0x0B || c == 0x0C) {
                pos++;
            } else {
                return;
            }
        }
    }

    private void skipLineComment() {
        while (pos < src.length() && !Chars.isLineTerminator(src.charAt(pos))) {
            pos++;
        }
    }

    /** Advances to the next token. */
    public void next() {
        prevEnd = end;
        prevLine = tokLine;
        prevCol = tokCol;
        nlBefore = false;
        skipSpace();
        start = pos;
        tokLine = line;
        tokCol = pos - lineStart;
        value = null;
        escaped = false;
        legacyOctalPos = -1;
        if (pos >= src.length()) {
            type = T.EOF;
            end = pos;
            return;
        }
        readToken();
        end = pos;
    }

    private void punct(T t, int len) {
        type = t;
        pos += len;
    }

    private void readToken() {
        int c = src.charAt(pos);
        switch (c) {
            case '(':
                punct(T.LPAREN, 1);
                break;
            case ')':
                punct(T.RPAREN, 1);
                break;
            case '{':
                punct(T.LBRACE, 1);
                break;
            case '}':
                punct(T.RBRACE, 1);
                break;
            case '[':
                punct(T.LBRACKET, 1);
                break;
            case ']':
                punct(T.RBRACKET, 1);
                break;
            case ';':
                punct(T.SEMI, 1);
                break;
            case ',':
                punct(T.COMMA, 1);
                break;
            case ':':
                punct(T.COLON, 1);
                break;
            case '~':
                punct(T.TILDE, 1);
                break;
            case '@':
                punct(T.AT, 1);
                break;
            case '.': {
                int n = peekc();
                if (n >= '0' && n <= '9') {
                    readNumber();
                } else if (n == '.' && peekc(2) == '.') {
                    punct(T.ELLIPSIS, 3);
                } else {
                    punct(T.DOT, 1);
                }
                break;
            }
            case '?': {
                int n = peekc();
                if (n == '?') {
                    if (peekc(2) == '=') {
                        punct(T.NULLISH_ASSIGN, 3);
                    } else {
                        punct(T.NULLISH, 2);
                    }
                } else if (n == '.' && !(peekc(2) >= '0' && peekc(2) <= '9')) {
                    punct(T.QDOT, 2);
                } else {
                    punct(T.QUESTION, 1);
                }
                break;
            }
            case '=': {
                int n = peekc();
                if (n == '>') {
                    punct(T.ARROW, 2);
                } else if (n == '=') {
                    if (peekc(2) == '=') {
                        punct(T.SEQ, 3);
                    } else {
                        punct(T.EQ, 2);
                    }
                } else {
                    punct(T.ASSIGN, 1);
                }
                break;
            }
            case '!':
                if (peekc() == '=') {
                    if (peekc(2) == '=') {
                        punct(T.SNE, 3);
                    } else {
                        punct(T.NE, 2);
                    }
                } else {
                    punct(T.BANG, 1);
                }
                break;
            case '+':
                switch (peekc()) {
                    case '+':
                        punct(T.INC, 2);
                        break;
                    case '=':
                        punct(T.PLUS_ASSIGN, 2);
                        break;
                    default:
                        punct(T.PLUS, 1);
                        break;
                }
                break;
            case '-':
                switch (peekc()) {
                    case '-':
                        punct(T.DEC, 2);
                        break;
                    case '=':
                        punct(T.MINUS_ASSIGN, 2);
                        break;
                    default:
                        punct(T.MINUS, 1);
                        break;
                }
                break;
            case '*':
                if (peekc() == '*') {
                    if (peekc(2) == '=') {
                        punct(T.STARSTAR_ASSIGN, 3);
                    } else {
                        punct(T.STARSTAR, 2);
                    }
                } else if (peekc() == '=') {
                    punct(T.STAR_ASSIGN, 2);
                } else {
                    punct(T.STAR, 1);
                }
                break;
            case '/':
                if (peekc() == '=') {
                    punct(T.SLASH_ASSIGN, 2);
                } else {
                    punct(T.SLASH, 1);
                }
                break;
            case '%':
                if (peekc() == '=') {
                    punct(T.PERCENT_ASSIGN, 2);
                } else {
                    punct(T.PERCENT, 1);
                }
                break;
            case '^':
                if (peekc() == '=') {
                    punct(T.CARET_ASSIGN, 2);
                } else {
                    punct(T.CARET, 1);
                }
                break;
            case '&':
                switch (peekc()) {
                    case '&':
                        if (peekc(2) == '=') {
                            punct(T.AND_ASSIGN, 3);
                        } else {
                            punct(T.AND, 2);
                        }
                        break;
                    case '=':
                        punct(T.AMP_ASSIGN, 2);
                        break;
                    default:
                        punct(T.AMP, 1);
                        break;
                }
                break;
            case '|':
                switch (peekc()) {
                    case '|':
                        if (peekc(2) == '=') {
                            punct(T.OR_ASSIGN, 3);
                        } else {
                            punct(T.OR, 2);
                        }
                        break;
                    case '=':
                        punct(T.BAR_ASSIGN, 2);
                        break;
                    default:
                        punct(T.BAR, 1);
                        break;
                }
                break;
            case '<':
                switch (peekc()) {
                    case '<':
                        if (peekc(2) == '=') {
                            punct(T.SHL_ASSIGN, 3);
                        } else {
                            punct(T.SHL, 2);
                        }
                        break;
                    case '=':
                        punct(T.LE, 2);
                        break;
                    default:
                        punct(T.LT, 1);
                        break;
                }
                break;
            case '>':
                switch (peekc()) {
                    case '>':
                        switch (peekc(2)) {
                            case '>':
                                if (peekc(3) == '=') {
                                    punct(T.SHR_ASSIGN, 4);
                                } else {
                                    punct(T.SHR, 3);
                                }
                                break;
                            case '=':
                                punct(T.SAR_ASSIGN, 3);
                                break;
                            default:
                                punct(T.SAR, 2);
                                break;
                        }
                        break;
                    case '=':
                        punct(T.GE, 2);
                        break;
                    default:
                        punct(T.GT, 1);
                        break;
                }
                break;
            case '"':
            case '\'':
                readString(c);
                break;
            case '`':
                pos++;
                readTemplate();
                break;
            case '#':
                pos++;
                if (pos < src.length()
                        && (Chars.isIdStart(codePointAt(pos)) || src.charAt(pos) == '\\')) {
                    String name = readIdentifierName();
                    type = T.PRIVATE_NAME;
                    value = name;
                } else {
                    error("Invalid or unexpected token '#'", pos - 1);
                }
                break;
            default:
                if (c >= '0' && c <= '9') {
                    readNumber();
                } else {
                    int cp = codePointAt(pos);
                    if (Chars.isIdStart(cp) || c == '\\') {
                        value = readIdentifierName();
                        type = T.NAME;
                    } else {
                        error("Invalid or unexpected token", pos);
                    }
                }
                break;
        }
    }

    private String intern(String s) {
        String existing = names.get(s);
        if (existing != null) {
            return existing;
        }
        names.put(s, s);
        return s;
    }

    /** Reads an IdentifierName starting at pos; handles unicode escapes. Sets {@link #escaped}. */
    private String readIdentifierName() {
        int simpleStart = pos;
        StringBuilder b = null;
        boolean first = true;
        while (pos < src.length()) {
            int c = src.charAt(pos);
            if (c == '\\') {
                if (b == null) {
                    b = new StringBuilder();
                }
                b.append(src, simpleStart, pos);
                int escPos = pos;
                if (peekc() != 'u') {
                    error("Invalid Unicode escape sequence", escPos);
                }
                pos += 2;
                int cp = readUnicodeEscapeBody(escPos);
                if (first ? !Chars.isIdStart(cp) : !Chars.isIdPart(cp)) {
                    error("Invalid Unicode escape sequence", escPos);
                }
                b.appendCodePoint(cp);
                escaped = true;
                simpleStart = pos;
            } else {
                int cp = codePointAt(pos);
                if (first ? !Chars.isIdStart(cp) : !Chars.isIdPart(cp)) {
                    break;
                }
                pos += Character.charCount(cp);
            }
            first = false;
        }
        String s;
        if (b == null) {
            s = src.substring(simpleStart, pos);
        } else {
            b.append(src, simpleStart, pos);
            s = b.toString();
        }
        return intern(s);
    }

    /** After "\\u" consumed. Returns code point. */
    private int readUnicodeEscapeBody(int escPos) {
        if (cur() == '{') {
            pos++;
            int v = 0;
            int digits = 0;
            while (true) {
                int c = cur();
                if (c == '}') {
                    break;
                }
                int h = Chars.hexVal(c);
                if (h < 0) {
                    error("Invalid Unicode escape sequence", escPos);
                }
                v = v * 16 + h;
                if (v > 0x10FFFF) {
                    error("Undefined Unicode code-point", escPos);
                }
                digits++;
                pos++;
            }
            if (digits == 0) {
                error("Invalid Unicode escape sequence", escPos);
            }
            pos++;
            return v;
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int h = Chars.hexVal(cur());
            if (h < 0) {
                error("Invalid Unicode escape sequence", escPos);
            }
            v = v * 16 + h;
            pos++;
        }
        return v;
    }

    private boolean isDigitIn(int c, int radix) {
        switch (radix) {
            case 2:
                return c == '0' || c == '1';
            case 8:
                return c >= '0' && c <= '7';
            case 10:
                return c >= '0' && c <= '9';
            default:
                return Chars.hexVal(c) >= 0;
        }
    }

    /** Reads digits with optional numeric separators into sb. Returns number of digits read. */
    private int readDigits(int radix, boolean allowSep) {
        int n = 0;
        boolean lastSep = false;
        while (pos < src.length()) {
            int c = src.charAt(pos);
            if (c == '_') {
                if (!allowSep) {
                    error("Numeric separators are not allowed here", pos);
                }
                if (n == 0 || lastSep) {
                    error("Numeric separators are not allowed here", pos);
                }
                lastSep = true;
                pos++;
                continue;
            }
            if (!isDigitIn(c, radix)) {
                break;
            }
            sb.append((char) c);
            lastSep = false;
            n++;
            pos++;
        }
        if (lastSep) {
            error("Numeric separators are not allowed at the end of numeric literals", pos - 1);
        }
        return n;
    }

    private void readNumber() {
        sb.setLength(0);
        int numStart = pos;
        int c = src.charAt(pos);
        if (c == '0') {
            int n = peekc() | 0x20;
            int radix;
            if (n == 'x') {
                radix = 16;
            } else if (n == 'o') {
                radix = 8;
            } else if (n == 'b') {
                radix = 2;
            } else {
                radix = 0;
            }
            if (radix != 0) {
                pos += 2;
                if (readDigits(radix, true) == 0) {
                    error("Invalid or unexpected token", numStart);
                }
                if (cur() == 'n') {
                    pos++;
                    type = T.BIGINT;
                    value = new BigInteger(sb.toString(), radix);
                } else {
                    type = T.NUM;
                    value = new BigInteger(sb.toString(), radix).doubleValue();
                }
                checkAfterNumber();
                return;
            }
            int d = peekc();
            if ((d >= '0' && d <= '9') || d == '_') {
                // legacy octal or NonOctalDecimalIntegerLiteral
                legacyOctalPos = numStart;
                pos++;
                if (cur() == '_') {
                    error("Numeric separator can not be used after leading 0", pos);
                }
                boolean octal = true;
                while (pos < src.length()) {
                    int e = src.charAt(pos);
                    if (e == '_') {
                        error("Numeric separators are not allowed here", pos);
                    }
                    if (e < '0' || e > '9') {
                        break;
                    }
                    if (e >= '8') {
                        octal = false;
                    }
                    sb.append((char) e);
                    pos++;
                }
                if (octal) {
                    if (cur() == 'n') {
                        error("Invalid BigInt literal", numStart);
                    }
                    type = T.NUM;
                    value = new BigInteger(sb.toString(), 8).doubleValue();
                    checkAfterNumber();
                    return;
                }
                // NonOctalDecimalIntegerLiteral: may have fraction/exponent
                if (cur() == 'n') {
                    error("Invalid BigInt literal", numStart);
                }
                readFractionAndExponent(numStart, false);
                return;
            }
        }
        if (c != '.') {
            readDigits(10, true);
            if (cur() == 'n') {
                pos++;
                type = T.BIGINT;
                value = new BigInteger(sb.toString());
                checkAfterNumber();
                return;
            }
        }
        readFractionAndExponent(numStart, true);
    }

    private void readFractionAndExponent(int numStart, boolean allowSep) {
        if (cur() == '.') {
            sb.append('.');
            pos++;
            if (cur() == '_') {
                error("Numeric separators are not allowed here", pos);
            }
            readDigits(10, allowSep);
        }
        int e = cur();
        if (e == 'e' || e == 'E') {
            sb.append('e');
            pos++;
            int s = cur();
            if (s == '+' || s == '-') {
                sb.append((char) s);
                pos++;
            }
            if (cur() == '_') {
                error("Numeric separators are not allowed here", pos);
            }
            if (readDigits(10, allowSep) == 0) {
                error("Invalid or unexpected token", numStart);
            }
        }
        if (cur() == 'n') {
            error("Invalid BigInt literal", numStart);
        }
        type = T.NUM;
        String s = sb.toString();
        value = s.equals(".") ? 0.0
                : Double.parseDouble(s.startsWith(".") ? "0" + s : s);
        checkAfterNumber();
    }

    private void checkAfterNumber() {
        if (pos < src.length()) {
            int cp = codePointAt(pos);
            if (Chars.isIdStart(cp) || (cp >= '0' && cp <= '9') || cp == '\\') {
                error("Invalid or unexpected token", pos);
            }
        }
    }

    private void readString(int quote) {
        pos++;
        sb.setLength(0);
        int chunk = pos;
        while (true) {
            if (pos >= src.length()) {
                error("Unterminated string constant", start);
            }
            int c = src.charAt(pos);
            if (c == quote) {
                sb.append(src, chunk, pos);
                pos++;
                break;
            }
            if (c == '\\') {
                sb.append(src, chunk, pos);
                readEscape(false);
                chunk = pos;
                continue;
            }
            if (c == 0x0A || c == 0x0D) {
                error("Unterminated string constant", start);
            }
            pos++;
        }
        type = T.STRING;
        value = sb.toString();
    }

    /**
     * Reads an escape sequence at pos (pointing at backslash) and appends the result to sb.
     * In templates, returns false for invalid escapes (and does not throw).
     */
    private boolean readEscape(boolean inTemplate) {
        int escPos = pos;
        pos++;
        if (pos >= src.length()) {
            error("Unterminated string constant", start);
        }
        int c = src.charAt(pos);
        if (c == 'n') {
            sb.append('\n');
            pos++;
        } else if (c == 't') {
            sb.append('\t');
            pos++;
        } else if (c == 'r') {
            sb.append('\r');
            pos++;
        } else if (c == 'b') {
            sb.append('\b');
            pos++;
        } else if (c == 'f') {
            sb.append('\u000C');
            pos++;
        } else if (c == 'v') {
            sb.append('\u000B');
            pos++;
        } else if (c == 0x0D) {
            pos++;
            if (cur() == 0x0A) {
                pos++;
            }
            line++;
            lineStart = pos;
        } else if (c == 0x0A || c == 0x2028 || c == 0x2029) {
            pos++;
            line++;
            lineStart = pos;
        } else if (c == 'x') {
            pos++;
            int h1 = Chars.hexVal(cur());
            int h2 = Chars.hexVal(peekc());
            if (h1 < 0 || h2 < 0) {
                if (inTemplate) {
                    return false;
                }
                error("Invalid hexadecimal escape sequence", escPos);
            }
            pos += 2;
            sb.append((char) (h1 * 16 + h2));
        } else if (c == 'u') {
            pos++;
            if (inTemplate) {
                int save = pos;
                int cp = tryReadUnicodeEscapeBody();
                if (cp < 0) {
                    pos = save;
                    return false;
                }
                sb.appendCodePoint(cp);
            } else {
                sb.appendCodePoint(readUnicodeEscapeBody(escPos));
            }
        } else if (c >= '0' && c <= '7') {
            if (c == '0' && !(peekc() >= '0' && peekc() <= '9')) {
                sb.append('\u0000');
                pos++;
            } else {
                if (inTemplate) {
                    return false;
                }
                if (legacyOctalPos < 0) {
                    legacyOctalPos = escPos;
                }
                int v = c - '0';
                pos++;
                int d1 = cur();
                if (d1 >= '0' && d1 <= '7') {
                    v = v * 8 + (d1 - '0');
                    pos++;
                    int d2 = cur();
                    if (c <= '3' && d2 >= '0' && d2 <= '7') {
                        v = v * 8 + (d2 - '0');
                        pos++;
                    }
                }
                sb.append((char) v);
            }
        } else if (c == '8' || c == '9') {
            if (inTemplate) {
                return false;
            }
            if (legacyOctalPos < 0) {
                legacyOctalPos = escPos;
            }
            sb.append((char) c);
            pos++;
        } else {
            int cp = codePointAt(pos);
            sb.appendCodePoint(cp);
            pos += Character.charCount(cp);
        }
        return true;
    }

    private int tryReadUnicodeEscapeBody() {
        if (cur() == '{') {
            pos++;
            int v = 0;
            int digits = 0;
            while (true) {
                int c = cur();
                if (c == '}') {
                    break;
                }
                int h = Chars.hexVal(c);
                if (h < 0) {
                    return -1;
                }
                v = v * 16 + h;
                if (v > 0x10FFFF) {
                    return -1;
                }
                digits++;
                pos++;
            }
            if (digits == 0) {
                return -1;
            }
            pos++;
            return v;
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int h = Chars.hexVal(cur());
            if (h < 0) {
                return -1;
            }
            v = v * 16 + h;
            pos++;
        }
        return v;
    }

    /** Reads template characters after "`" or "}" until "${" or "`". */
    private void readTemplate() {
        sb.setLength(0);
        StringBuilder rawSb = new StringBuilder();
        boolean valid = true;
        invalidEscapePos = -1;
        int chunk = pos;
        while (true) {
            if (pos >= src.length()) {
                error("Unterminated template literal", start);
            }
            int c = src.charAt(pos);
            if (c == '`') {
                appendTemplateChunk(chunk, pos, rawSb, valid);
                pos++;
                templateTail = true;
                break;
            }
            if (c == '$' && peekc() == '{') {
                appendTemplateChunk(chunk, pos, rawSb, valid);
                pos += 2;
                templateTail = false;
                break;
            }
            if (c == '\\') {
                appendTemplateChunk(chunk, pos, rawSb, valid);
                int escStart = pos;
                boolean ok = readEscape(true);
                if (!ok) {
                    if (valid) {
                        invalidEscapePos = escStart;
                    }
                    valid = false;
                    // skip the remainder of the bad escape: only the escape char itself; following chars are normal
                    pos = escStart + 2;
                }
                appendRaw(rawSb, escStart, pos);
                chunk = pos;
                continue;
            }
            if (c == 0x0D || c == 0x0A || c == 0x2028 || c == 0x2029) {
                appendTemplateChunk(chunk, pos, rawSb, valid);
                if (c == 0x0D) {
                    pos++;
                    if (cur() == 0x0A) {
                        pos++;
                    }
                    if (valid) {
                        sb.append('\n');
                    }
                    rawSb.append('\n');
                } else {
                    pos++;
                    if (valid) {
                        sb.append((char) c);
                    }
                    rawSb.append((char) c);
                }
                line++;
                lineStart = pos;
                chunk = pos;
                continue;
            }
            pos++;
        }
        type = T.TEMPLATE;
        raw = rawSb.toString();
        cooked = valid ? sb.toString() : null;
    }

    private void appendTemplateChunk(int from, int to, StringBuilder rawSb, boolean valid) {
        if (to > from) {
            if (valid) {
                sb.append(src, from, to);
            }
            rawSb.append(src, from, to);
        }
    }

    private void appendRaw(StringBuilder rawSb, int from, int to) {
        int i = from;
        while (i < to) {
            char ch = src.charAt(i);
            if (ch == '\r') {
                rawSb.append('\n');
                if (i + 1 < to && src.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                rawSb.append(ch);
            }
            i++;
        }
    }

    /** Called by the parser when it sees '}' that closes a template substitution. */
    public void rescanTemplateContinuation() {
        // current token must be RBRACE starting at start
        pos = start + 1;
        readTemplate();
        end = pos;
    }

    /** Called by the parser when a '/' or '/=' token starts a primary expression. */
    public void rescanRegExp() {
        pos = start + 1;
        boolean inClass = false;
        int bodyStart = pos;
        while (true) {
            if (pos >= src.length()) {
                error("Invalid regular expression: missing /", start);
            }
            int c = src.charAt(pos);
            if (Chars.isLineTerminator(c)) {
                error("Invalid regular expression: missing /", start);
            }
            if (c == '\\') {
                pos++;
                if (pos >= src.length() || Chars.isLineTerminator(src.charAt(pos))) {
                    error("Invalid regular expression: missing /", start);
                }
                pos++;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                break;
            }
            pos++;
        }
        String body = src.substring(bodyStart, pos);
        pos++;
        int flagsStart = pos;
        while (pos < src.length()) {
            int cp = codePointAt(pos);
            if (cp == '\\') {
                error("Invalid regular expression flags", pos);
            }
            if (!Chars.isIdPart(cp)) {
                break;
            }
            pos += Character.charCount(cp);
        }
        type = T.REGEXP;
        value = body;
        regexFlags = src.substring(flagsStart, pos);
        end = pos;
    }

    public String getSrc() {
        return src;
    }

    public String getSourceName() {
        return sourceName;
    }

    public boolean isModule() {
        return isModule;
    }

    public int getPos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getLineStart() {
        return lineStart;
    }

    public void setLineStart(int lineStart) {
        this.lineStart = lineStart;
    }

    public T getType() {
        return type;
    }

    public void setType(T type) {
        this.type = type;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
    }

    public int getTokLine() {
        return tokLine;
    }

    public void setTokLine(int tokLine) {
        this.tokLine = tokLine;
    }

    public int getTokCol() {
        return tokCol;
    }

    public void setTokCol(int tokCol) {
        this.tokCol = tokCol;
    }

    public boolean getNlBefore() {
        return nlBefore;
    }

    public void setNlBefore(boolean nlBefore) {
        this.nlBefore = nlBefore;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public boolean getEscaped() {
        return escaped;
    }

    public void setEscaped(boolean escaped) {
        this.escaped = escaped;
    }

    public int getLegacyOctalPos() {
        return legacyOctalPos;
    }

    public void setLegacyOctalPos(int legacyOctalPos) {
        this.legacyOctalPos = legacyOctalPos;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }

    public String getCooked() {
        return cooked;
    }

    public void setCooked(String cooked) {
        this.cooked = cooked;
    }

    public boolean getTemplateTail() {
        return templateTail;
    }

    public void setTemplateTail(boolean templateTail) {
        this.templateTail = templateTail;
    }

    public String getRegexFlags() {
        return regexFlags;
    }

    public void setRegexFlags(String regexFlags) {
        this.regexFlags = regexFlags;
    }

    public int getInvalidEscapePos() {
        return invalidEscapePos;
    }

    public void setInvalidEscapePos(int invalidEscapePos) {
        this.invalidEscapePos = invalidEscapePos;
    }

    public int getPrevEnd() {
        return prevEnd;
    }

    public void setPrevEnd(int prevEnd) {
        this.prevEnd = prevEnd;
    }

    public int getPrevLine() {
        return prevLine;
    }

    public void setPrevLine(int prevLine) {
        this.prevLine = prevLine;
    }

    public int getPrevCol() {
        return prevCol;
    }

    public void setPrevCol(int prevCol) {
        this.prevCol = prevCol;
    }

    /** Java representation of the Kotlin {@code Pair<Int, Int>} returned by {@link #lineCol(int)}. */
    public static final class LineCol {
        public final int line;
        public final int column;

        public LineCol(int line, int column) {
            this.line = line;
            this.column = column;
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }

        public int getFirst() {
            return line;
        }

        public int getSecond() {
            return column;
        }
    }
}
