/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/**
 * ExportEntry. {@code request} is -1 for local exports; {@code importName} is null for local
 * exports, {@link ImportEntry#NAMESPACE} for {@code export * as ns} and star exports
 * ({@code exportName} null).
 */
public final class ExportEntry {
    public final String exportName;
    public final int request;
    public final String importName;
    public final String localName;

    public ExportEntry(String exportName, int request, String importName, String localName) {
        this.exportName = exportName;
        this.request = request;
        this.importName = importName;
        this.localName = localName;
    }

    public String getExportName() {
        return exportName;
    }

    public int getRequest() {
        return request;
    }

    public String getImportName() {
        return importName;
    }

    public String getLocalName() {
        return localName;
    }
}
