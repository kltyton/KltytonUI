/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins.temporal;

/** Rounding mode. */
enum RMode {
    CEIL("ceil"),
    FLOOR("floor"),
    EXPAND("expand"),
    TRUNC("trunc"),
    HALF_CEIL("halfCeil"),
    HALF_FLOOR("halfFloor"),
    HALF_EXPAND("halfExpand"),
    HALF_TRUNC("halfTrunc"),
    HALF_EVEN("halfEven");

    public static final RMode[] ALL = values();

    public final String id;

    RMode(String id) {
        this.id = id;
    }

    public RMode negate() {
        switch (this) {
            case CEIL:
                return FLOOR;
            case FLOOR:
                return CEIL;
            case HALF_CEIL:
                return HALF_FLOOR;
            case HALF_FLOOR:
                return HALF_CEIL;
            default:
                return this;
        }
    }

    /** GetUnsignedRoundingMode. */
    public URM unsigned(boolean negative) {
        switch (this) {
            case CEIL:
                return negative ? URM.ZERO : URM.INFINITY;
            case FLOOR:
                return negative ? URM.INFINITY : URM.ZERO;
            case EXPAND:
                return URM.INFINITY;
            case TRUNC:
                return URM.ZERO;
            case HALF_CEIL:
                return negative ? URM.HALF_ZERO : URM.HALF_INFINITY;
            case HALF_FLOOR:
                return negative ? URM.HALF_INFINITY : URM.HALF_ZERO;
            case HALF_EXPAND:
                return URM.HALF_INFINITY;
            case HALF_TRUNC:
                return URM.HALF_ZERO;
            case HALF_EVEN:
                return URM.HALF_EVEN;
            default:
                throw new AssertionError(this);
        }
    }
}
