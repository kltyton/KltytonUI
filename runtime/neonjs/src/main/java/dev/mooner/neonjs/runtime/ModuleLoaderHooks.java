package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.vm.Frame;

/** Module hooks used by runtime imports; derived from NeonJS and adapted to Java. */
public interface ModuleLoaderHooks {
    JSObject importMeta(Frame frame);

    Object dynamicImport(Frame frame, Object specifier, Object options);
}
