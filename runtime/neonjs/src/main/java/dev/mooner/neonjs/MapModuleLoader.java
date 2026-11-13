/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory module loader (specifiers are keys; relative paths resolved against the referrer's key).
 * Derived from NeonJS and adapted to Java.
 */
public final class MapModuleLoader implements NeonModuleLoader {
    private final Map<String, String> modules;

    public MapModuleLoader(Map<String, String> modules) {
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    @Override
    public String resolve(String specifier, String referrer) {
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) {
            return specifier;
        }
        String base = referrer == null ? "" : substringBeforeLast(referrer, '/');
        List<String> parts = new ArrayList<>();
        if (!base.isEmpty()) {
            for (String segment : base.split("/", -1)) {
                parts.add(segment);
            }
        }
        for (String segment : specifier.split("/", -1)) {
            if (segment.equals(".") || segment.isEmpty()) {
                continue;
            }
            if (segment.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(segment);
            }
        }
        return String.join("/", parts);
    }

    @Override
    public String load(String key) {
        return modules.get(key);
    }

    private static String substringBeforeLast(String value, char delimiter) {
        int index = value.lastIndexOf(delimiter);
        return index == -1 ? "" : value.substring(0, index);
    }
}
