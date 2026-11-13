/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import java.util.LinkedHashMap;

/**
 * A resolved date-time format ("format record"): the component values (in table order) and the ICU pattern that
 * produces them. {@code skeleton} is used for range formatting.
 */
final class DtfFormat {
    final LinkedHashMap<String, String> fields;
    final String pattern;
    final String skeleton;

    DtfFormat(LinkedHashMap<String, String> fields, String pattern, String skeleton) {
        this.fields = fields;
        this.pattern = pattern;
        this.skeleton = skeleton;
    }

    boolean hasHour() {
        return fields.containsKey("hour");
    }
}
