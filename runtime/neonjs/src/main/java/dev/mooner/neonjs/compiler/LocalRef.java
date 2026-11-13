/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/** A reference resolved to a binding, with whether TDZ checking is required at the use site. */
public final class LocalRef extends Resolved {
    public final Binding binding;
    public final boolean checkTdz;

    public LocalRef(Binding binding, boolean checkTdz) {
        this.binding = binding;
        this.checkTdz = checkTdz;
    }

    public Binding getBinding() {
        return binding;
    }

    public boolean isCheckTdz() {
        return checkTdz;
    }
}
