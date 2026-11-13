/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** Declaration info for global / eval declaration instantiation. */
public final class DeclInfo {
    public final String[] varNames;
    public final String[] functionNames;
    public final String[] lexNames;
    public final boolean[] lexConst;
    public final String[] annexBNames;
    public final boolean strict;

    public DeclInfo(String[] varNames, String[] functionNames, String[] lexNames,
                    boolean[] lexConst, String[] annexBNames, boolean strict) {
        this.varNames = varNames;
        this.functionNames = functionNames;
        this.lexNames = lexNames;
        this.lexConst = lexConst;
        this.annexBNames = annexBNames;
        this.strict = strict;
    }

    public String[] getVarNames() {
        return varNames;
    }

    public String[] getFunctionNames() {
        return functionNames;
    }

    public String[] getLexNames() {
        return lexNames;
    }

    public boolean[] getLexConst() {
        return lexConst;
    }

    public String[] getAnnexBNames() {
        return annexBNames;
    }

    public boolean isStrict() {
        return strict;
    }
}
