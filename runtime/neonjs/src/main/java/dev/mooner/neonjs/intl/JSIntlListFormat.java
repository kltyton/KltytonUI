/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.ListFormatter;
import dev.mooner.neonjs.runtime.JSObject;

/** Intl.ListFormat instance ({@code [[InitializedListFormat]]}). */
public final class JSIntlListFormat extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String type = "conjunction";
    public String style = "long";
    /** ICU list formatter (immutable, created on first use). */
    ListFormatter icu;

    JSIntlListFormat(JSObject proto) {
        super(proto);
    }
}
