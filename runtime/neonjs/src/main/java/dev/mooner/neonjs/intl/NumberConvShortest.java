/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.NumberConv;

/** Shortest round-trip decimal string of a double, via the engine's Number::toString. */
final class NumberConvShortest {
    private NumberConvShortest() {
    }

    static String toShortest(double value) {
        return NumberConv.toString(value);
    }
}
