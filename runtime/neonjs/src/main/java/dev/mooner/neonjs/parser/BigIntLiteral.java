/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import java.math.BigInteger;

public class BigIntLiteral extends Node {
    public final BigInteger value;

    public BigIntLiteral(BigInteger value) {
        this.value = value;
    }

    public BigInteger getValue() {
        return value;
    }
}
