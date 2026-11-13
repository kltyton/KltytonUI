/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import java.util.List;

/** Compiled module: init code, body code, environment layout and import/export tables. */
public final class CompiledModule {
    public final CodeBlock init;
    public final CodeBlock body;
    public final ScopeInfo scope;
    public final List<ModuleRequest> requests;
    public final List<ImportEntry> importEntries;
    public final List<ExportEntry> localExports;
    public final List<ExportEntry> indirectExports;
    public final List<ExportEntry> starExports;
    public final boolean hasTopLevelAwait;
    public final Source source;

    public CompiledModule(CodeBlock init, CodeBlock body, ScopeInfo scope,
                          List<ModuleRequest> requests, List<ImportEntry> importEntries,
                          List<ExportEntry> localExports, List<ExportEntry> indirectExports,
                          List<ExportEntry> starExports, boolean hasTopLevelAwait, Source source) {
        this.init = init;
        this.body = body;
        this.scope = scope;
        this.requests = requests;
        this.importEntries = importEntries;
        this.localExports = localExports;
        this.indirectExports = indirectExports;
        this.starExports = starExports;
        this.hasTopLevelAwait = hasTopLevelAwait;
        this.source = source;
    }

    public CodeBlock getInit() {
        return init;
    }

    public CodeBlock getBody() {
        return body;
    }

    public ScopeInfo getScope() {
        return scope;
    }

    public List<ModuleRequest> getRequests() {
        return requests;
    }

    public List<ImportEntry> getImportEntries() {
        return importEntries;
    }

    public List<ExportEntry> getLocalExports() {
        return localExports;
    }

    public List<ExportEntry> getIndirectExports() {
        return indirectExports;
    }

    public List<ExportEntry> getStarExports() {
        return starExports;
    }

    public boolean isHasTopLevelAwait() {
        return hasTopLevelAwait;
    }

    public Source getSource() {
        return source;
    }
}
