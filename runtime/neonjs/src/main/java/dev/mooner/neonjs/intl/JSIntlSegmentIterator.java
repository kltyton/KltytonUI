/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.BreakIterator;
import dev.mooner.neonjs.runtime.JSObject;

/** %SegmentIterator% instance ({@code [[IteratingSegmenter]]}, {@code [[IteratedString]]}, {@code [[IteratedStringNextSegmentCodeUnitIndex]]}). */
public final class JSIntlSegmentIterator extends JSObject {
    public final JSIntlSegmenter segmenter;
    public final String string;
    public int nextIndex;
    BreakIterator bi;

    JSIntlSegmentIterator(JSObject proto, JSIntlSegmenter segmenter, String string) {
        super(proto);
        this.segmenter = segmenter;
        this.string = string;
    }
}
