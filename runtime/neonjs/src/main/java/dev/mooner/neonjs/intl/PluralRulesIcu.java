/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.LocalizedNumberRangeFormatter;
import com.ibm.icu.number.Notation;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberRangeFormatter;
import com.ibm.icu.number.UnlocalizedNumberFormatter;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** ICU side of Intl.PluralRules (loaded on first use). */
final class PluralRulesIcu {
    private PluralRulesIcu() {
    }

    private static final List<String> ORDER = List.of("zero", "one", "two", "few", "many", "other");

    private static PluralRules rules(JSIntlPluralRules pr) {
        if (pr.icuRules instanceof PluralRules) {
            return (PluralRules) pr.icuRules;
        }
        PluralRules created = NumberFormatIcu.icuCall(() -> PluralRules.forLocale(
                ULocale.forLanguageTag(pr.dataLocale),
                "ordinal".equals(pr.type) ? PluralRules.PluralType.ORDINAL : PluralRules.PluralType.CARDINAL));
        pr.icuRules = created;
        return created;
    }

    private static UnlocalizedNumberFormatter settings(JSIntlPluralRules pr, int signum) {
        UnlocalizedNumberFormatter formatter;
        switch (pr.notation) {
            case "scientific":
                formatter = NumberFormatter.with().notation(Notation.scientific());
                break;
            case "engineering":
                formatter = NumberFormatter.with().notation(Notation.engineering());
                break;
            case "compact":
                formatter = NumberFormatter.with().notation("long".equals(pr.compactDisplay)
                        ? Notation.compactLong() : Notation.compactShort());
                break;
            default:
                formatter = NumberFormatter.with().notation(Notation.simple());
                break;
        }
        return pr.digits.applyTo(formatter, signum);
    }

    private static LocalizedNumberFormatter formatter(JSIntlPluralRules pr, int signum) {
        int index = pr.digits.signDependent() && signum < 0 ? 1 : 0;
        Object cached = pr.icuFormatters[index];
        if (cached instanceof LocalizedNumberFormatter) {
            return (LocalizedNumberFormatter) cached;
        }
        LocalizedNumberFormatter created = NumberFormatIcu.icuCall(() ->
                settings(pr, signum).locale(ULocale.forLanguageTag(pr.dataLocale)));
        pr.icuFormatters[index] = created;
        return created;
    }

    private static int sign(double value) {
        if (value < 0 || (value == 0.0 && 1.0 / value < 0)) {
            return -1;
        }
        return value > 0 ? 1 : 0;
    }

    /** ResolvePlural */
    static String select(JSIntlPluralRules pr, double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) {
            return "other";
        }
        return NumberFormatIcu.icuCall(() -> rules(pr).select(formatter(pr, sign(n)).format(n)));
    }

    /** ResolvePluralRange */
    static String selectRange(JSIntlPluralRules pr, double x, double y) {
        if (Double.isInfinite(x) || Double.isInfinite(y)) {
            // no formatted operands for non-finite values: use the category of the end
            return select(pr, y);
        }
        return NumberFormatIcu.icuCall(() -> {
            UnlocalizedNumberFormatter first = settings(pr, sign(x));
            LocalizedNumberRangeFormatter range = NumberRangeFormatter.withLocale(
                    ULocale.forLanguageTag(pr.dataLocale));
            LocalizedNumberRangeFormatter withNumbers = !pr.digits.signDependent() || sign(x) == sign(y)
                    ? range.numberFormatterBoth(first)
                    : range.numberFormatterFirst(first).numberFormatterSecond(settings(pr, sign(y)));
            return rules(pr).select(withNumbers
                    .identityFallback(NumberRangeFormatter.RangeIdentityFallback.APPROXIMATELY)
                    .formatRange(x, y));
        });
    }

    static List<Object> categories(JSIntlPluralRules pr) {
        Set<String> keywords = NumberFormatIcu.icuCall(() -> rules(pr).getKeywords());
        List<Object> result = new ArrayList<>();
        for (String keyword : ORDER) {
            if (keywords.contains(keyword)) {
                result.add(keyword);
            }
        }
        return result;
    }
}
