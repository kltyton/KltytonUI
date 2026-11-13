/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.util.List;

public class FunctionNode extends Node {
    public Identifier id;
    public final List<Node> params;
    public Node body;
    public FunctionKind kind;
    public final boolean isAsync;
    public final boolean isGenerator;

    /** Arrow function with expression body. */
    public boolean isExpressionBody = false;
    public boolean strict = false;
    public boolean hasSimpleParams = true;
    /** Function declaration (statement) vs expression. */
    public boolean isDeclaration = false;
    public boolean isStatic = false;
    /** For class constructors and methods: home object info is resolved during compilation. */
    public ClassNode classNode = null;
    /** Parser-computed facts used by the compiler. */
    public boolean usesArguments = false;
    public boolean usesThis = false;
    public boolean usesSuperProperty = false;
    public boolean usesSuperCall = false;
    public boolean usesNewTarget = false;
    public boolean hasDirectEval = false;
    /** Contains a direct eval anywhere inside (including nested arrow functions). */
    public boolean containsEval = false;
    /** Sloppy-mode functions whose name is in scope in the function body (named function expressions). */
    public String selfBinding = null;
    /** Index into the source string range used for Function.prototype.toString. */
    public int srcStart = 0;
    public int srcEnd = 0;
    public String name = "";
    /** Inferred function name used for anonymous functions (NamedEvaluation is applied at runtime). */
    public boolean hasNameProperty = true;

    public FunctionNode(Identifier id, List<Node> params, Node body, FunctionKind kind,
                        boolean isAsync, boolean isGenerator) {
        this.id = id;
        this.params = params;
        this.body = body;
        this.kind = kind;
        this.isAsync = isAsync;
        this.isGenerator = isGenerator;
    }

    public Identifier getId() {
        return id;
    }

    public void setId(Identifier id) {
        this.id = id;
    }

    public List<Node> getParams() {
        return params;
    }

    public Node getBody() {
        return body;
    }

    public void setBody(Node body) {
        this.body = body;
    }

    public FunctionKind getKind() {
        return kind;
    }

    public void setKind(FunctionKind kind) {
        this.kind = kind;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public boolean isGenerator() {
        return isGenerator;
    }

    public boolean isExpressionBody() {
        return isExpressionBody;
    }

    public void setExpressionBody(boolean expressionBody) {
        this.isExpressionBody = expressionBody;
    }

    public boolean getStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public boolean getHasSimpleParams() {
        return hasSimpleParams;
    }

    public void setHasSimpleParams(boolean hasSimpleParams) {
        this.hasSimpleParams = hasSimpleParams;
    }

    public boolean isDeclaration() {
        return isDeclaration;
    }

    public void setDeclaration(boolean declaration) {
        this.isDeclaration = declaration;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    public ClassNode getClassNode() {
        return classNode;
    }

    public void setClassNode(ClassNode classNode) {
        this.classNode = classNode;
    }

    public boolean getUsesArguments() {
        return usesArguments;
    }

    public void setUsesArguments(boolean usesArguments) {
        this.usesArguments = usesArguments;
    }

    public boolean getUsesThis() {
        return usesThis;
    }

    public void setUsesThis(boolean usesThis) {
        this.usesThis = usesThis;
    }

    public boolean getUsesSuperProperty() {
        return usesSuperProperty;
    }

    public void setUsesSuperProperty(boolean usesSuperProperty) {
        this.usesSuperProperty = usesSuperProperty;
    }

    public boolean getUsesSuperCall() {
        return usesSuperCall;
    }

    public void setUsesSuperCall(boolean usesSuperCall) {
        this.usesSuperCall = usesSuperCall;
    }

    public boolean getUsesNewTarget() {
        return usesNewTarget;
    }

    public void setUsesNewTarget(boolean usesNewTarget) {
        this.usesNewTarget = usesNewTarget;
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

    public String getSelfBinding() {
        return selfBinding;
    }

    public void setSelfBinding(String selfBinding) {
        this.selfBinding = selfBinding;
    }

    public int getSrcStart() {
        return srcStart;
    }

    public void setSrcStart(int srcStart) {
        this.srcStart = srcStart;
    }

    public int getSrcEnd() {
        return srcEnd;
    }

    public void setSrcEnd(int srcEnd) {
        this.srcEnd = srcEnd;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean getHasNameProperty() {
        return hasNameProperty;
    }

    public void setHasNameProperty(boolean hasNameProperty) {
        this.hasNameProperty = hasNameProperty;
    }
}
