/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

/** Intl.NumberFormat instance ({@code [[InitializedNumberFormat]]}). */
public final class JSIntlNumberFormat extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String numberingSystem = "latn";
    public String style = "decimal";
    public String currency;
    public String currencyDisplay;
    public String currencySign;
    public String unit;
    public String unitDisplay;
    final DigitOptions digits = new DigitOptions();
    public String notation = "standard";
    public String compactDisplay;
    /** "min2", "auto", "always" or false */
    public Object useGrouping = "auto";
    public String signDisplay = "auto";
    public JSObject boundFormat;
    /** ICU LocalizedNumberFormatters (immutable), by sign for halfCeil/halfFloor; created on first use. */
    final Object[] icuFormatters = new Object[2];

    JSIntlNumberFormat(JSObject proto) {
        super(proto);
    }
}
