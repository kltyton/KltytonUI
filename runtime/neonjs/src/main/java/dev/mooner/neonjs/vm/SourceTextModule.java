/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.CompiledModule;
import dev.mooner.neonjs.compiler.ExportEntry;
import dev.mooner.neonjs.compiler.ImportEntry;
import dev.mooner.neonjs.compiler.ModuleRequest;
import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import java.util.List;

/** Module compiled from JS source text. */
public final class SourceTextModule extends ModuleRecord {
    public final CompiledModule compiled;

    public SourceTextModule(Realm realm, String key, CompiledModule compiled) {
        super(realm, key);
        this.compiled = compiled;
        hasTLA = compiled.hasTopLevelAwait;
        compiled.source.owner = this;
        env = new DeclEnv(realm.getGlobalEnv(), compiled.scope);
    }

    @Override
    public List<ModuleRequest> getRequests() {
        return compiled.requests;
    }

    @Override
    public List<ExportEntry> getLocalExports() {
        return compiled.localExports;
    }

    @Override
    public List<ExportEntry> getIndirectExports() {
        return compiled.indirectExports;
    }

    @Override
    public List<ExportEntry> getStarExports() {
        return compiled.starExports;
    }

    @Override
    public int slotOf(String name) {
        return compiled.scope.lookup(name);
    }

    @Override
    public void initializeEnvironment() {
        for (ExportEntry entry : getIndirectExports()) {
            Object resolved = resolveExport(entry.exportName);
            if (resolved == null || resolved == ModuleRecord.AMBIGUOUS) {
                throw JSException.syntaxError("The requested module does not provide an export named '"
                        + entry.exportName + "'");
            }
        }
        DeclEnv environment = env;
        for (ImportEntry entry : compiled.importEntries) {
            ModuleRecord module = imported(entry.request);
            int slot = compiled.scope.lookup(entry.localName);
            if (ImportEntry.NAMESPACE.equals(entry.importName)) {
                environment.slots[slot] = Modules.namespaceOf(module);
            } else if (ImportEntry.DEFERRED_NAMESPACE.equals(entry.importName)) {
                environment.slots[slot] = Modules.namespaceOf(module, true);
            } else if (ImportEntry.MODULE_SOURCE.equals(entry.importName)) {
                environment.slots[slot] = module.getModuleSource();
            } else {
                Object resolved = module.resolveExport(entry.importName);
                if (resolved == null || resolved == ModuleRecord.AMBIGUOUS) {
                    String specifier = getRequests().get(entry.request).specifier;
                    throw JSException.syntaxError(resolved == null
                            ? "The requested module '" + specifier + "' does not provide an export named '"
                                    + entry.importName + "'"
                            : "The requested module '" + specifier
                                    + "' contains conflicting star exports for name '" + entry.importName + "'");
                }
                environment.slots[slot] = Modules.bindingValueOrRef((ResolvedBinding) resolved);
            }
        }
        // hoisted function declarations
        Frame frame = new Frame(null, compiled.init, realm, Undefined.INSTANCE, Values.EMPTY_ARGS,
                Undefined.INSTANCE, environment);
        Interpreter.execute(frame);
    }

    @Override
    public void executeModule(PromiseCapability capability) {
        Frame frame = new Frame(null, compiled.body, realm, Undefined.INSTANCE, Values.EMPTY_ARGS,
                Undefined.INSTANCE, env);
        if (capability == null) {
            Interpreter.execute(frame);
            return;
        }
        Generators.runAsyncBody(frame, realm, capability);
    }
}
