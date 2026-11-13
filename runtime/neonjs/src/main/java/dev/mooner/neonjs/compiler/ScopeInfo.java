/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Uninitialized;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Runtime descriptor of a declarative environment created for a {@link Scope}. */
public final class ScopeInfo {
    public static final Companion Companion = new Companion();

    public static final int F_TDZ = 1;
    public static final int F_CONST = 2;
    /** Named function expression binding: assignments are ignored in sloppy mode. */
    public static final int F_CALLEE = 4;
    public static final int F_LEXICAL = 8;
    public static final int F_PSEUDO = 16;
    public static final int F_FUNCTION = 32;
    public static final int F_CATCH = 64;
    public static final int F_PRIVATE = 128;

    public final String[] names;
    public final int[] flags;
    public final ScopeKind kind;
    public final int size;
    private final HashMap<String, Integer> index;

    /** True when sloppy direct eval may add bindings to environments of this scope. */
    public boolean evalVarTarget;
    public boolean isFunctionScope;

    public ScopeInfo(String[] names, int[] flags, ScopeKind kind) {
        this.names = names;
        this.flags = flags;
        this.kind = kind;
        this.size = names.length;
        if (names.length > 6) {
            HashMap<String, Integer> map = new HashMap<>();
            for (int i = 0; i < names.length; i++) {
                map.put(names[i], i);
            }
            this.index = map;
        } else {
            this.index = null;
        }
    }

    public int lookup(String name) {
        if (index != null) {
            Integer i = index.get(name);
            return i == null ? -1 : i;
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Initial slot values: lexical bindings start uninitialized. */
    public Object[] newSlots() {
        Object[] slots = new Object[size];
        for (int i = 0; i < size; i++) {
            slots[i] = (flags[i] & F_TDZ) != 0 ? Uninitialized.INSTANCE : Undefined.INSTANCE;
        }
        return slots;
    }

    public String[] getNames() {
        return names;
    }

    public int[] getFlags() {
        return flags;
    }

    public ScopeKind getKind() {
        return kind;
    }

    public int getSize() {
        return size;
    }

    public boolean isEvalVarTarget() {
        return evalVarTarget;
    }

    public boolean isFunctionScope() {
        return isFunctionScope;
    }

    public static final class Companion {
        public ScopeInfo from(Scope scope) {
            return ScopeInfo.from(scope);
        }
    }

    public static ScopeInfo from(Scope scope) {
        List<Binding> bindings = new ArrayList<>();
        for (Binding binding : scope.bindings.values()) {
            if (binding.inEnv()) {
                bindings.add(binding);
            }
        }
        bindings.sort(Comparator.comparingInt(binding -> binding.slot));
        String[] names = new String[bindings.size()];
        int[] flags = new int[bindings.size()];
        for (int i = 0; i < bindings.size(); i++) {
            Binding binding = bindings.get(i);
            names[i] = binding.name;
            int f = 0;
            if (binding.needsTdz()) {
                f |= F_TDZ;
            }
            if (binding.kind.isConst()) {
                f |= F_CONST;
            }
            if (binding.kind == BKind.CALLEE) {
                f |= F_CALLEE;
            }
            if (binding.kind.isLexical()) {
                f |= F_LEXICAL;
            }
            if (binding.kind == BKind.THIS || binding.kind == BKind.NEW_TARGET
                    || binding.kind == BKind.HOME || binding.kind == BKind.FN) {
                f |= F_PSEUDO;
            }
            if (binding.kind == BKind.FUNCTION) {
                f |= F_FUNCTION;
            }
            if (binding.kind == BKind.CATCH) {
                f |= F_CATCH;
            }
            if (binding.kind == BKind.PRIVATE) {
                f |= F_PRIVATE;
            }
            flags[i] = f;
        }
        ScopeInfo info = new ScopeInfo(names, flags, scope.kind);
        info.evalVarTarget = scope.evalVarTarget;
        info.isFunctionScope = scope.kind == ScopeKind.FUNCTION || scope.kind == ScopeKind.BODY
                || scope.kind == ScopeKind.EVAL;
        return info;
    }
}
