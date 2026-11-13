/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public enum T {
    EOF("end of input"),
    NAME("identifier"),
    PRIVATE_NAME("private name"),
    NUM("number"),
    BIGINT("bigint"),
    STRING("string"),
    TEMPLATE("template"),
    REGEXP("regular expression"),
    LBRACE("{"),
    RBRACE("}"),
    LPAREN("("),
    RPAREN(")"),
    LBRACKET("["),
    RBRACKET("]"),
    DOT("."),
    ELLIPSIS("..."),
    SEMI(";"),
    COMMA(","),
    QUESTION("?"),
    QDOT("?."),
    COLON(":"),
    ARROW("=>"),
    AT("@"),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    EQ("=="),
    NE("!="),
    SEQ("==="),
    SNE("!=="),
    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    SLASH("/"),
    PERCENT("%"),
    STARSTAR("**"),
    INC("++"),
    DEC("--"),
    SHL("<<"),
    SAR(">>"),
    SHR(">>>"),
    AMP("&"),
    BAR("|"),
    CARET("^"),
    BANG("!"),
    TILDE("~"),
    AND("&&"),
    OR("||"),
    NULLISH("??"),
    ASSIGN("="),
    PLUS_ASSIGN("+="),
    MINUS_ASSIGN("-="),
    STAR_ASSIGN("*="),
    SLASH_ASSIGN("/="),
    PERCENT_ASSIGN("%="),
    STARSTAR_ASSIGN("**="),
    SHL_ASSIGN("<<="),
    SAR_ASSIGN(">>="),
    SHR_ASSIGN(">>>="),
    AMP_ASSIGN("&="),
    BAR_ASSIGN("|="),
    CARET_ASSIGN("^="),
    AND_ASSIGN("&&="),
    OR_ASSIGN("||="),
    NULLISH_ASSIGN("??=");

    public final String text;

    T(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public boolean isAssign() {
        return ordinal() >= ASSIGN.ordinal();
    }
}
