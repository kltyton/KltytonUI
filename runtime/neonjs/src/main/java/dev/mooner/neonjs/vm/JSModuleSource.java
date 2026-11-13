/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.runtime.JSObject;

/** A source-phase module representation ({@code import source}): an instance of %AbstractModuleSource%. */
public final class JSModuleSource extends JSObject {
    public final String moduleSourceClassName;

    public JSModuleSource(JSObject proto, String moduleSourceClassName) {
        super(proto);
        this.moduleSourceClassName = moduleSourceClassName;
    }
}
