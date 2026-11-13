/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

/** Intl.RelativeTimeFormat instance ({@code [[InitializedRelativeTimeFormat]]}). */
public final class JSIntlRelativeTimeFormat extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String numberingSystem = "latn";
    public String style = "long";
    public String numeric = "always";
    /** ICU RelativeDateTimeFormatter (owns a DecimalFormat, so never shared); created on first use. */
    Object icu;
    /** {@code [[NumberFormat]]}: formats the number inside ICU's pattern (Intl.NumberFormat semantics, e.g. min2 grouping). */
    JSIntlNumberFormat numberFormat;

    JSIntlRelativeTimeFormat(JSObject proto) {
        super(proto);
    }
}
