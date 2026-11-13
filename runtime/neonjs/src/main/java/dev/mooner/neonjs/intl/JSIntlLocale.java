/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

/** Intl.Locale instance ({@code [[InitializedLocale]]}). All slots are canonical strings; null means undefined. */
public final class JSIntlLocale extends JSObject {
    public String locale = "";
    public String calendar;
    public String collation;
    public String firstDayOfWeek;
    public String hourCycle;
    public String caseFirst;
    public boolean numeric;
    public String numberingSystem;

    JSIntlLocale(JSObject proto) {
        super(proto);
    }
}
