/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.BreakIterator;
import dev.mooner.neonjs.runtime.JSObject;

/** %Segments% instance ({@code [[SegmentsSegmenter]]}, {@code [[SegmentsString]]}). */
public final class JSIntlSegments extends JSObject {
    public final JSIntlSegmenter segmenter;
    public final String string;
    /** Break iterator over {@link #string}, private to this object (used by containing()). */
    BreakIterator bi;

    JSIntlSegments(JSObject proto, JSIntlSegmenter segmenter, String string) {
        super(proto);
        this.segmenter = segmenter;
        this.string = string;
    }
}
