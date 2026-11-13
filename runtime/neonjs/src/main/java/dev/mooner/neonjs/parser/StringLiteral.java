/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

public class StringLiteral extends Node {
    public final String value;
    /** Raw source text including quotes, used for directive detection. */
    public String raw = "";
    public boolean hasLegacyOctal = false;

    public StringLiteral(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }

    public boolean getHasLegacyOctal() {
        return hasLegacyOctal;
    }

    public void setHasLegacyOctal(boolean hasLegacyOctal) {
        this.hasLegacyOctal = hasLegacyOctal;
    }
}
