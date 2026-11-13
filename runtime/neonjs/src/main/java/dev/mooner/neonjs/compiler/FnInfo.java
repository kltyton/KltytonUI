/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.FunctionKind;
import dev.mooner.neonjs.parser.Node;

/** Scope-analysis facts and allocations for one function (or script/module/eval top level). */
public final class FnInfo {
    public final Node node;
    public final FnInfo parent;
    public Scope scope;
    public Scope varScope;
    public Scope calleeScope;
    /** Separate variable environment receiving {@code var}s of direct eval calls in parameter expressions. */
    public Scope paramEvalScope;
    public boolean isArrow;
    public boolean strict;
    public boolean isAsync;
    public boolean isGenerator;
    public FunctionKind kind = FunctionKind.NORMAL;
    public boolean hasParamExpressions;
    public boolean hasDirectEval;
    public boolean containsEval;
    public Binding thisBinding;
    public Binding newTargetBinding;
    public Binding homeBinding;
    public Binding fnBinding;
    public Binding argumentsBinding;
    public boolean usesThis;
    public boolean usesArguments;
    public boolean mappedArguments;
    public int numRegs;
    /** True for script/module/eval top-level code. */
    public boolean isTopLevel;

    public FnInfo(Node node, FnInfo parent) {
        this.node = node;
        this.parent = parent;
    }

    /** Functions that have their own this/arguments/new.target (non-arrow). */
    public boolean hasOwnThis() {
        return !isArrow;
    }

    public boolean getHasOwnThis() {
        return hasOwnThis();
    }

    public Node getNode() {
        return node;
    }

    public FnInfo getParent() {
        return parent;
    }

    public Scope getScope() {
        return scope;
    }

    public Scope getVarScope() {
        return varScope;
    }

    public Scope getCalleeScope() {
        return calleeScope;
    }

    public Scope getParamEvalScope() {
        return paramEvalScope;
    }

    public boolean isArrow() {
        return isArrow;
    }

    public boolean isStrict() {
        return strict;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public boolean isGenerator() {
        return isGenerator;
    }

    public FunctionKind getKind() {
        return kind;
    }

    public boolean isHasParamExpressions() {
        return hasParamExpressions;
    }

    public boolean isHasDirectEval() {
        return hasDirectEval;
    }

    public boolean isContainsEval() {
        return containsEval;
    }

    public Binding getThisBinding() {
        return thisBinding;
    }

    public Binding getNewTargetBinding() {
        return newTargetBinding;
    }

    public Binding getHomeBinding() {
        return homeBinding;
    }

    public Binding getFnBinding() {
        return fnBinding;
    }

    public Binding getArgumentsBinding() {
        return argumentsBinding;
    }

    public boolean isUsesThis() {
        return usesThis;
    }

    public boolean isUsesArguments() {
        return usesArguments;
    }

    public boolean isMappedArguments() {
        return mappedArguments;
    }

    public int getNumRegs() {
        return numRegs;
    }

    public boolean isTopLevel() {
        return isTopLevel;
    }
}
