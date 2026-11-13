/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import java.util.LinkedHashMap;

/** A lexical / variable environment in the scope tree. */
public final class Scope {
    public final ScopeKind kind;
    public final Scope parent;
    public final FnInfo fn;
    public final LinkedHashMap<String, Binding> bindings = new LinkedHashMap<>();
    public boolean needsEnv;
    public boolean hasEval;
    /** Sloppy direct eval may add var bindings to this scope at runtime. */
    public boolean evalVarTarget;
    /** All bindings must be env allocated (visible to eval). */
    public boolean allCaptured;
    public int envSize;
    public ScopeInfo info;
    /** For FOR scopes: per-iteration copy needed. */
    public boolean perIteration;
    public int depth;

    public Scope(ScopeKind kind, Scope parent, FnInfo fn) {
        this.kind = kind;
        this.parent = parent;
        this.fn = fn;
    }

    public Binding declare(String name, BKind kind) {
        Binding binding = new Binding(name, kind, this);
        bindings.put(name, binding);
        return binding;
    }

    public ScopeKind getKind() {
        return kind;
    }

    public Scope getParent() {
        return parent;
    }

    public FnInfo getFn() {
        return fn;
    }

    public LinkedHashMap<String, Binding> getBindings() {
        return bindings;
    }

    public boolean isNeedsEnv() {
        return needsEnv;
    }

    public boolean isHasEval() {
        return hasEval;
    }

    public boolean isEvalVarTarget() {
        return evalVarTarget;
    }

    public boolean isAllCaptured() {
        return allCaptured;
    }

    public int getEnvSize() {
        return envSize;
    }

    public ScopeInfo getInfo() {
        return info;
    }

    public boolean isPerIteration() {
        return perIteration;
    }

    public int getDepth() {
        return depth;
    }

    @Override
    public String toString() {
        return "Scope(" + kind + " " + bindings.keySet() + ")";
    }
}
