/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** One declared name in a scope, with its register or environment-slot allocation. */
public final class Binding {
    public final String name;
    public BKind kind;
    public final Scope scope;
    public boolean captured;
    public int reg = -1;
    public int slot = -1;
    /** Source position after which the binding is initialized (TDZ elision); -1 = always check. */
    public int declEnd = -1;
    /** For block-level function declarations in sloppy mode: the Annex B var binding to update. */
    public Object annexBVar;
    /** For derived constructors, the {@code this} binding starts uninitialized. */
    public boolean tdz;
    /** Binding is used as an implicit arguments object holder. */
    public boolean isArgumentsObject;
    /** For private names: the private-name kind and static flag. */
    public String privateKind;
    public boolean privateStatic;

    public Binding(String name, BKind kind, Scope scope) {
        this.name = name;
        this.kind = kind;
        this.scope = scope;
    }

    public boolean inEnv() {
        return slot >= 0;
    }

    public boolean getInEnv() {
        return inEnv();
    }

    public boolean needsTdz() {
        return kind.isLexical() || tdz;
    }

    public boolean getNeedsTdz() {
        return needsTdz();
    }

    public String getName() {
        return name;
    }

    public BKind getKind() {
        return kind;
    }

    public Scope getScope() {
        return scope;
    }

    public boolean isCaptured() {
        return captured;
    }

    public int getReg() {
        return reg;
    }

    public int getSlot() {
        return slot;
    }

    public int getDeclEnd() {
        return declEnd;
    }

    public Object getAnnexBVar() {
        return annexBVar;
    }

    public boolean isTdz() {
        return tdz;
    }

    public boolean isArgumentsObject() {
        return isArgumentsObject;
    }

    public String getPrivateKind() {
        return privateKind;
    }

    public boolean isPrivateStatic() {
        return privateStatic;
    }

    @Override
    public String toString() {
        return name + ":" + kind + (inEnv() ? "@env" + slot : "@r" + reg);
    }
}
