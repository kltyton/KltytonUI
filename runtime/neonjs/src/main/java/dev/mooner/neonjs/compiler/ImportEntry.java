/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

/**
 * ImportEntry: {@code importName} is {@link #NAMESPACE} for namespace imports,
 * {@link #DEFERRED_NAMESPACE} for {@code import defer * as ns}, and {@link #MODULE_SOURCE} for
 * {@code import source x}.
 */
public final class ImportEntry {
    public static final String NAMESPACE = "\uD800*";
    public static final String DEFERRED_NAMESPACE = "\uD800defer";
    public static final String MODULE_SOURCE = "\uD800source";

    public final int request;
    public final String importName;
    public final String localName;

    public ImportEntry(int request, String importName, String localName) {
        this.request = request;
        this.importName = importName;
        this.localName = localName;
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
