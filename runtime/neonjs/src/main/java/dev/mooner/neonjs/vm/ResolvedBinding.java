/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import java.util.Objects;

/**
 * Resolution result of ResolveExport. {@code bindingName} null = the module's namespace ({@code deferred}: its
 * deferred namespace); {@link dev.mooner.neonjs.compiler.ImportEntry#MODULE_SOURCE} = the module's source object.
 */
public final class ResolvedBinding {
    public final ModuleRecord module;
    public final String bindingName;
    public final boolean deferred;

    public ResolvedBinding(ModuleRecord module, String bindingName) {
        this(module, bindingName, false);
    }

    public ResolvedBinding(ModuleRecord module, String bindingName, boolean deferred) {
        this.module = module;
        this.bindingName = bindingName;
        this.deferred = deferred;
    }

    public boolean sameAs(ResolvedBinding other) {
        return module == other.module
                && Objects.equals(bindingName, other.bindingName)
                && deferred == other.deferred;
    }
}
