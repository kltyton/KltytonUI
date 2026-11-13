/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.vm.IteratorRecord;
import java.util.List;
import java.util.function.Function;

/** Iterator helper object (%IteratorHelperPrototype% instances). */
public final class IteratorHelperObject extends JSObject {
    public final IteratorRecord underlying;
    public final Function<IteratorHelperObject, Object> step;
    public int state;
    /** Inner iterator for flatMap. */
    public IteratorRecord inner;
    public double counter;
    public Object extra;
    /** {@code [[UnderlyingIterators]]} of helpers over several iterators (Iterator.zip/zipKeyed); closed in reverse order. */
    public List<IteratorRecord> openIters;

    public IteratorHelperObject(JSObject prototype, IteratorRecord underlying,
                                Function<IteratorHelperObject, Object> step) {
        super(prototype);
        this.underlying = underlying;
        this.step = step;
    }
}
