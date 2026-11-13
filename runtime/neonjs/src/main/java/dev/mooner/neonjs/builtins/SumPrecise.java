/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.NotFound;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.vm.Iteration;
import dev.mooner.neonjs.vm.IteratorRecord;

import java.math.BigDecimal;

/** Math.sumPrecise using exact BigDecimal accumulation. */
final class SumPrecise {
    private SumPrecise() {
    }

    static double sum(Realm realm, Object items) {
        Ops.requireObjectCoercible(items);
        IteratorRecord rec = Iteration.getIterator(realm, items, false);
        BigDecimal acc = BigDecimal.ZERO;
        boolean posInf = false;
        boolean negInf = false;
        boolean nan = false;
        boolean allNegZero = true;
        long count = 0L;
        while (true) {
            Object v = Iteration.stepValue(rec);
            if (v == NotFound.INSTANCE) break;
            count++;
            if (count >= (1L << 53)) {
                Iteration.closeOnThrow(rec);
                throw BuiltinsKt.rangeErr("Too many values");
            }
            if (!(v instanceof Double number)) {
                Iteration.closeOnThrow(rec);
                throw BuiltinsKt.typeErr("Math.sumPrecise requires numbers");
            }
            if (number.isNaN()) {
                nan = true;
            } else if (number == Double.POSITIVE_INFINITY) {
                posInf = true;
            } else if (number == Double.NEGATIVE_INFINITY) {
                negInf = true;
            } else {
                if (!(number == 0.0 && 1.0 / number < 0)) allNegZero = false;
                if (number != 0.0) acc = acc.add(new BigDecimal(number));
            }
        }
        if (nan || (posInf && negInf)) return Double.NaN;
        if (posInf) return Double.POSITIVE_INFINITY;
        if (negInf) return Double.NEGATIVE_INFINITY;
        if (acc.signum() == 0) return allNegZero ? -0.0 : 0.0;
        return acc.doubleValue();
    }
}
