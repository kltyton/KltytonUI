/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class TemplateLiteral extends Node {
    public final List<TemplateElement> quasis;
    public final List<Node> expressions;

    public TemplateLiteral(List<TemplateElement> quasis, List<Node> expressions) {
        this.quasis = quasis;
        this.expressions = expressions;
    }

    public List<TemplateElement> getQuasis() {
        return quasis;
    }

    public List<Node> getExpressions() {
        return expressions;
    }
}
