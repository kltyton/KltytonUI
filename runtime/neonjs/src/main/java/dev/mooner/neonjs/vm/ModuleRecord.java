/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.ExportEntry;
import dev.mooner.neonjs.compiler.ImportEntry;
import dev.mooner.neonjs.compiler.ModuleRequest;
import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Cyclic (source text or synthetic) module record. */
public class ModuleRecord {
    public enum Status {
        NEW, UNLINKED, LINKING, LINKED, EVALUATING, EVALUATING_ASYNC, EVALUATED
    }

    public static final Object AMBIGUOUS = new Object();

    public final Realm realm;
    public final String key;

    public Status status = Status.UNLINKED;
    public DeclEnv env;
    public ModuleNamespace namespace;
    public ModuleNamespace deferredNamespace;
    public JSObject moduleSource;
    public Object evaluationError;
    public boolean hasError;
    public int dfsIndex = -1;
    public int dfsAncestorIndex = -1;
    public ModuleRecord cycleRoot;
    public boolean hasTLA;
    public boolean asyncEvaluation;
    public long asyncEvaluationOrder;
    public PromiseCapability topLevelCapability;
    public final ArrayList<ModuleRecord> asyncParentModules = new ArrayList<>();
    public int pendingAsyncDependencies;
    public JSObject importMeta;

    public final HashMap<Integer, ModuleRecord> loaded = new HashMap<>();

    public ModuleRecord(Realm realm, String key) {
        this.realm = realm;
        this.key = key;
    }

    public List<ModuleRequest> getRequests() {
        return Collections.emptyList();
    }

    public List<ExportEntry> getLocalExports() {
        return Collections.emptyList();
    }

    public List<ExportEntry> getIndirectExports() {
        return Collections.emptyList();
    }

    public List<ExportEntry> getStarExports() {
        return Collections.emptyList();
    }

    public ModuleRecord imported(int request) {
        ModuleRecord module = loaded.get(request);
        if (module == null) throw new IllegalStateException("module request not loaded");
        return module;
    }

    public void initializeEnvironment() {
    }

    public void executeModule(PromiseCapability capability) {
    }

    public int slotOf(String name) {
        return -1;
    }

    /** GetModuleSource: the source-phase representation of this module. JS modules have none (SyntaxError). */
    public JSObject getModuleSource() {
        throw JSException.syntaxError("Source phase import is not available for module '" + key + "'");
    }

    /** The phase of request {@code index} (null = evaluation). */
    public String phaseOf(int index) {
        List<ModuleRequest> requests = getRequests();
        if (index < 0 || index >= requests.size()) return null;
        return requests.get(index).phase;
    }

    public List<String> getExportedNames() {
        return getExportedNames(new HashSet<>());
    }

    public List<String> getExportedNames(Set<ModuleRecord> exportStarSet) {
        if (!exportStarSet.add(this)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (ExportEntry entry : getLocalExports()) {
            names.add(entry.exportName);
        }
        for (ExportEntry entry : getIndirectExports()) {
            names.add(entry.exportName);
        }
        for (ExportEntry entry : getStarExports()) {
            ModuleRecord module = imported(entry.request);
            for (String name : module.getExportedNames(exportStarSet)) {
                if (!"default".equals(name) && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** ResolveExport: ResolvedBinding, {@link #AMBIGUOUS} or null. */
    public Object resolveExport(String name) {
        return resolveExport(name, new ArrayList<>());
    }

    public Object resolveExport(String name, List<ResolveSetEntry> resolveSet) {
        for (ResolveSetEntry entry : resolveSet) {
            if (entry.module() == this && Objects.equals(entry.name(), name)) return null;
        }
        resolveSet.add(new ResolveSetEntry(this, name));
        for (ExportEntry entry : getLocalExports()) {
            if (Objects.equals(entry.exportName, name)) {
                return new ResolvedBinding(this, entry.localName);
            }
        }
        for (ExportEntry entry : getIndirectExports()) {
            if (Objects.equals(entry.exportName, name)) {
                ModuleRecord module = imported(entry.request);
                if (ImportEntry.NAMESPACE.equals(entry.importName)) {
                    return new ResolvedBinding(module, null);
                }
                if (ImportEntry.DEFERRED_NAMESPACE.equals(entry.importName)) {
                    return new ResolvedBinding(module, null, true);
                }
                if (ImportEntry.MODULE_SOURCE.equals(entry.importName)) {
                    return new ResolvedBinding(module, ImportEntry.MODULE_SOURCE);
                }
                return module.resolveExport(entry.importName, resolveSet);
            }
        }
        if ("default".equals(name)) return null;
        ResolvedBinding star = null;
        for (ExportEntry entry : getStarExports()) {
            ModuleRecord module = imported(entry.request);
            Object resolved = module.resolveExport(name, resolveSet);
            if (resolved == AMBIGUOUS) return AMBIGUOUS;
            if (resolved != null) {
                ResolvedBinding binding = (ResolvedBinding) resolved;
                if (star == null) {
                    star = binding;
                } else if (!binding.sameAs(star)) {
                    return AMBIGUOUS;
                }
            }
        }
        return star;
    }

    /** Domain replacement for the original Kotlin {@code Pair<ModuleRecord, String>} resolve-set entry. */
    public record ResolveSetEntry(ModuleRecord module, String name) {
    }
}
