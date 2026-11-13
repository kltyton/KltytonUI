/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

/** Intl.DisplayNames instance ({@code [[InitializedDisplayNames]]}). */
public final class JSIntlDisplayNames extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String style = "long";
    public String type = "";
    public String fallback = "code";
    public String languageDisplay;
    /** ICU display-name provider for this object's locale/style/type (created on first use). */
    Object icu;

    JSIntlDisplayNames(JSObject proto) {
        super(proto);
    }
}
