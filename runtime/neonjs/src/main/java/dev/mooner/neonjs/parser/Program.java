/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class Program extends Node {
    public final List<Node> body;
    public final boolean isModule;
    public boolean strict = false;
    public boolean hasDirectEval = false;
    public boolean containsEval = false;
    public boolean usesThis = false;
    public boolean hasTopLevelAwait = false;
    public String source = "";

    public Program(List<Node> body, boolean isModule) {
        this.body = body;
        this.isModule = isModule;
    }

    public List<Node> getBody() {
        return body;
    }

    public boolean isModule() {
        return isModule;
    }

    public boolean getStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public boolean getHasDirectEval() {
        return hasDirectEval;
    }

    public void setHasDirectEval(boolean hasDirectEval) {
        this.hasDirectEval = hasDirectEval;
    }

    public boolean getContainsEval() {
        return containsEval;
    }

    public void setContainsEval(boolean containsEval) {
        this.containsEval = containsEval;
    }

    public boolean getUsesThis() {
        return usesThis;
    }

    public void setUsesThis(boolean usesThis) {
        this.usesThis = usesThis;
    }

    public boolean getHasTopLevelAwait() {
        return hasTopLevelAwait;
    }

    public void setHasTopLevelAwait(boolean hasTopLevelAwait) {
        this.hasTopLevelAwait = hasTopLevelAwait;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
