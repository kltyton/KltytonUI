/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

import java.util.HashMap;

/** Intl.DurationFormat instance ({@code [[InitializedDurationFormat]]}). */
public final class JSIntlDurationFormat extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String numberingSystem = "latn";
    public String style = "short";
    /** Style and display of each unit of {@link DurationFormatBuiltins#UNITS} ("fractional" for fractional sub-seconds). */
    final String[] styles = new String[10];
    final String[] displays = new String[10];
    public Integer fractionalDigits;
    /** Number formats used by format(), by option key (per object; never shared). */
    final HashMap<String, JSIntlNumberFormat> numberFormats = new HashMap<>();
    /** ICU ListFormatter (immutable) and digital separators; computed on first use. */
    Object listFormatter;
    String[] separators;

    JSIntlDurationFormat(JSObject proto) {
        super(proto);
    }
}
