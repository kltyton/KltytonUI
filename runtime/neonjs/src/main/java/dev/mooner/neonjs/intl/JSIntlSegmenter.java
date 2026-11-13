/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.BreakIterator;
import dev.mooner.neonjs.runtime.JSObject;

/** Intl.Segmenter instance ({@code [[InitializedSegmenter]]}). */
public final class JSIntlSegmenter extends JSObject {
    public String locale = "";
    public String dataLocale = "";
    public String granularity = "grapheme";
    /** Prototype break iterator (never used directly; cloned for every %Segments% / %SegmentIterator%). */
    BreakIterator icu;

    JSIntlSegmenter(JSObject proto) {
        super(proto);
    }
}
