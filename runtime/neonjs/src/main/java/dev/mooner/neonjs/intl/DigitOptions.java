/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.number.IntegerWidth;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.Precision;
import com.ibm.icu.number.UnlocalizedNumberFormatter;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Undefined;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/** The digit options of SetNumberFormatDigitOptions (shared by NumberFormat and PluralRules). */
final class DigitOptions {
    int minimumIntegerDigits = 1;
    Integer minimumFractionDigits;
    Integer maximumFractionDigits;
    Integer minimumSignificantDigits;
    Integer maximumSignificantDigits;
    /** fractionDigits, significantDigits, morePrecision or lessPrecision */
    String roundingType = "fractionDigits";
    String computedRoundingPriority = "auto";
    int roundingIncrement = 1;
    String roundingMode = "halfExpand";
    String trailingZeroDisplay = "auto";

    private static final int[] INCREMENTS = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000};
    private static final String[] ROUNDING_MODES = {"ceil", "floor", "expand", "trunc", "halfCeil", "halfFloor",
            "halfExpand", "halfTrunc", "halfEven"};
    private static final String[] PRIORITIES = {"auto", "morePrecision", "lessPrecision"};
    private static final String[] TRAILING = {"auto", "stripIfInteger"};

    /** SetNumberFormatDigitOptions(intlObj, options, mnfdDefault, mxfdDefault, notation) */
    void read(JSObject options, int mnfdDefault, int mxfdDefault0, String notation) {
        int mxfdDefault = mxfdDefault0;
        int mnid = Opt.number(options, "minimumIntegerDigits", 1, 21, 1);
        Object mnfd = options.get("minimumFractionDigits", options);
        Object mxfd = options.get("maximumFractionDigits", options);
        Object mnsd = options.get("minimumSignificantDigits", options);
        Object mxsd = options.get("maximumSignificantDigits", options);
        minimumIntegerDigits = mnid;
        int increment = Opt.number(options, "roundingIncrement", 1, 5000, 1);
        if (!contains(INCREMENTS, increment)) {
            throw Dsl.rangeErr("roundingIncrement value is out of range.");
        }
        String mode = Opt.str(options, "roundingMode", ROUNDING_MODES, "halfExpand");
        String priority = Opt.str(options, "roundingPriority", PRIORITIES, "auto");
        String trailing = Opt.str(options, "trailingZeroDisplay", TRAILING, "auto");
        if (increment != 1) {
            mxfdDefault = mnfdDefault;
        }
        roundingIncrement = increment;
        roundingMode = mode;
        trailingZeroDisplay = trailing;
        boolean hasSd = mnsd != Undefined.INSTANCE || mxsd != Undefined.INSTANCE;
        boolean hasFd = mnfd != Undefined.INSTANCE || mxfd != Undefined.INSTANCE;
        boolean needSd = true;
        boolean needFd = true;
        if ("auto".equals(priority)) {
            needSd = hasSd;
            if (needSd || (!hasFd && "compact".equals(notation))) {
                needFd = false;
            }
        }
        if (needSd) {
            if (hasSd) {
                int min = Opt.defaultNumber(mnsd, 1, 21, 1);
                minimumSignificantDigits = min;
                maximumSignificantDigits = Opt.defaultNumber(mxsd, min, 21, 21);
            } else {
                minimumSignificantDigits = 1;
                maximumSignificantDigits = 21;
            }
        }
        if (needFd) {
            readFractionDigits(hasFd, mnfd, mxfd, mnfdDefault, mxfdDefault);
        }
        resolveRoundingType(needSd, needFd, priority, hasSd);
        if (increment != 1) {
            if (!"fractionDigits".equals(roundingType)) {
                throw Dsl.typeErr("roundingIncrement requires fraction digits rounding");
            }
            if (!maximumFractionDigits.equals(minimumFractionDigits)) {
                throw Dsl.rangeErr("maximumFractionDigits and minimumFractionDigits must be equal with roundingIncrement");
            }
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private void readFractionDigits(boolean hasFd, Object mnfd0, Object mxfd0, int mnfdDefault, int mxfdDefault) {
        if (hasFd) {
            Integer mnfd = Opt.defaultNumber(mnfd0, 0, 100, null);
            Integer mxfd = Opt.defaultNumber(mxfd0, 0, 100, null);
            if (mnfd == null) {
                mnfd = Math.min(mnfdDefault, mxfd);
            } else if (mxfd == null) {
                mxfd = Math.max(mxfdDefault, mnfd);
            } else if (mnfd > mxfd) {
                throw Dsl.rangeErr("minimumFractionDigits is greater than maximumFractionDigits");
            }
            minimumFractionDigits = mnfd;
            maximumFractionDigits = mxfd;
        } else {
            minimumFractionDigits = mnfdDefault;
            maximumFractionDigits = mxfdDefault;
        }
    }

    private void resolveRoundingType(boolean needSd, boolean needFd, String priority, boolean hasSd) {
        if (!needSd && !needFd) {
            minimumFractionDigits = 0;
            maximumFractionDigits = 0;
            minimumSignificantDigits = 1;
            maximumSignificantDigits = 2;
            roundingType = "morePrecision";
            computedRoundingPriority = "morePrecision";
        } else if ("morePrecision".equals(priority) || "lessPrecision".equals(priority)) {
            roundingType = priority;
            computedRoundingPriority = priority;
        } else if (hasSd) {
            roundingType = "significantDigits";
            computedRoundingPriority = "auto";
        } else {
            roundingType = "fractionDigits";
            computedRoundingPriority = "auto";
        }
    }

    /** Adds the digit options to {@code object} in resolvedOptions order (minimumIntegerDigits .. maximumSignificantDigits). */
    void addDigitsTo(JSObject object) {
        object.createDataPropertyOrThrow("minimumIntegerDigits", (double) minimumIntegerDigits);
        if (minimumFractionDigits != null) {
            object.createDataPropertyOrThrow("minimumFractionDigits", (double) minimumFractionDigits);
        }
        if (maximumFractionDigits != null) {
            object.createDataPropertyOrThrow("maximumFractionDigits", (double) maximumFractionDigits);
        }
        if (minimumSignificantDigits != null) {
            object.createDataPropertyOrThrow("minimumSignificantDigits", (double) minimumSignificantDigits);
        }
        if (maximumSignificantDigits != null) {
            object.createDataPropertyOrThrow("maximumSignificantDigits", (double) maximumSignificantDigits);
        }
    }

    /** Applies precision, integer width and rounding mode for values of sign {@code signum}. */
    UnlocalizedNumberFormatter applyTo(UnlocalizedNumberFormatter formatter0, int signum) {
        UnlocalizedNumberFormatter formatter = formatter0.integerWidth(IntegerWidth.zeroFillTo(minimumIntegerDigits));
        Precision precision;
        switch (roundingType) {
            case "significantDigits":
                precision = Precision.minMaxSignificantDigits(minimumSignificantDigits, maximumSignificantDigits);
                break;
            case "morePrecision":
            case "lessPrecision":
                precision = Precision.minMaxFraction(minimumFractionDigits, maximumFractionDigits)
                        .withSignificantDigits(minimumSignificantDigits, maximumSignificantDigits,
                                "morePrecision".equals(roundingType)
                                        ? NumberFormatter.RoundingPriority.RELAXED
                                        : NumberFormatter.RoundingPriority.STRICT);
                break;
            default:
                if (roundingIncrement != 1) {
                    precision = Precision.increment(BigDecimal.valueOf(roundingIncrement)
                            .scaleByPowerOfTen(-maximumFractionDigits));
                } else {
                    precision = Precision.minMaxFraction(minimumFractionDigits, maximumFractionDigits);
                }
                break;
        }
        if ("stripIfInteger".equals(trailingZeroDisplay)) {
            precision = precision.trailingZeroDisplay(NumberFormatter.TrailingZeroDisplay.HIDE_IF_WHOLE);
        }
        formatter = formatter.precision(precision);
        return formatter.roundingMode(icuRoundingMode(signum));
    }

    RoundingMode icuRoundingMode(int signum) {
        switch (roundingMode) {
            case "ceil":
                return RoundingMode.CEILING;
            case "floor":
                return RoundingMode.FLOOR;
            case "expand":
                return RoundingMode.UP;
            case "trunc":
                return RoundingMode.DOWN;
            case "halfCeil":
                return signum < 0 ? RoundingMode.HALF_DOWN : RoundingMode.HALF_UP;
            case "halfFloor":
                return signum < 0 ? RoundingMode.HALF_UP : RoundingMode.HALF_DOWN;
            case "halfTrunc":
                return RoundingMode.HALF_DOWN;
            case "halfEven":
                return RoundingMode.HALF_EVEN;
            default:
                return RoundingMode.HALF_UP;
        }
    }

    /** Whether the rounding mode depends on the sign of the value (halfCeil / halfFloor). */
    boolean signDependent() {
        return "halfCeil".equals(roundingMode) || "halfFloor".equals(roundingMode);
    }
}
