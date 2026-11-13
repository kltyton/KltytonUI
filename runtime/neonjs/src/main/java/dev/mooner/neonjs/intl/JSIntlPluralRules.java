/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

/** Intl.PluralRules instance ({@code [[InitializedPluralRules]]}). */
public final class JSIntlPluralRules extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String type = "cardinal";
    public String notation = "standard";
    public String compactDisplay;
    final DigitOptions digits = new DigitOptions();
    /** ICU PluralRules (immutable) and LocalizedNumberFormatters by sign; created on first use. */
    Object icuRules;
    final Object[] icuFormatters = new Object[2];

    JSIntlPluralRules(JSObject proto) {
        super(proto);
    }
}
