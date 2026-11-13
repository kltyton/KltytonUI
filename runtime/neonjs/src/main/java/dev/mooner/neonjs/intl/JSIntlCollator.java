/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.Collator;
import dev.mooner.neonjs.runtime.JSObject;

/** Intl.Collator instance ({@code [[InitializedCollator]]}). */
public final class JSIntlCollator extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String usage = "sort";
    public String sensitivity = "variant";
    public boolean ignorePunctuation;
    public String collation = "default";
    public boolean numeric;
    public String caseFirst = "false";
    public JSObject boundCompare;
    /** The frozen (hence thread-safe) ICU collator, created on first comparison. */
    Collator icu;

    JSIntlCollator(JSObject proto) {
        super(proto);
    }
}
