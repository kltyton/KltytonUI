/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A module request: specifier plus import attributes and import phase. {@code phase} is null for
 * ordinary evaluation-phase imports, {@link #PHASE_DEFER} for {@code import defer}, and
 * {@link #PHASE_SOURCE} for {@code import source}. Attributes use ordered key/value entries, the
 * Java counterpart of the original Kotlin pair list.
 */
public final class ModuleRequest {
    public static final String PHASE_DEFER = "defer";
    public static final String PHASE_SOURCE = "source";

    public final String specifier;
    public final List<Map.Entry<String, String>> attributes;
    public final String phase;

    public ModuleRequest(String specifier, List<Map.Entry<String, String>> attributes, String phase) {
        this.specifier = specifier;
        this.attributes = attributes;
        this.phase = phase;
    }

    public ModuleRequest(String specifier, List<Map.Entry<String, String>> attributes) {
        this(specifier, attributes, null);
    }

    public String getSpecifier() {
        return specifier;
    }

    public List<Map.Entry<String, String>> getAttributes() {
        return attributes;
    }

    public String getPhase() {
        return phase;
    }

    public String getType() {
        for (Map.Entry<String, String> attribute : attributes) {
            if ("type".equals(attribute.getKey())) {
                return attribute.getValue();
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ModuleRequest request)) {
            return false;
        }
        return Objects.equals(request.specifier, specifier)
                && Objects.equals(request.phase, phase)
                && sortedByKey(request.attributes).equals(sortedByKey(attributes));
    }

    @Override
    public int hashCode() {
        return specifier.hashCode();
    }

    private static List<Map.Entry<String, String>> sortedByKey(List<Map.Entry<String, String>> attributes) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>(attributes);
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        return sorted;
    }
}
