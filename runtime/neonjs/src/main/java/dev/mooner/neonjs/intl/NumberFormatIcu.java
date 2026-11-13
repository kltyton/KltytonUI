/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.number.FormattedNumber;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.LocalizedNumberRangeFormatter;
import com.ibm.icu.number.Notation;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberRangeFormatter;
import com.ibm.icu.number.Scale;
import com.ibm.icu.number.UnlocalizedNumberFormatter;
import com.ibm.icu.text.ConstrainedFieldPosition;
import com.ibm.icu.text.FormattedValue;
import com.ibm.icu.text.NumberingSystem;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.MeasureUnit;
import com.ibm.icu.util.NoUnit;
import com.ibm.icu.util.ULocale;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.TerminationException;

import java.text.Format;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Builds ICU number formatters for Intl.NumberFormat objects and converts ICU output to parts. */
final class NumberFormatIcu {
    private NumberFormatIcu() {
    }

    /** The default numbering system of an (available) locale. */
    static String defaultNumberingSystem(String dataLocale) {
        try {
            NumberingSystem system = NumberingSystem.getInstance(ULocale.forLanguageTag(dataLocale));
            return SupportedValues.isSupportedNumberingSystem(system.getName()) ? system.getName() : "latn";
        } catch (Exception invalid) {
            return "latn";
        }
    }

    static ULocale icuLocale(String dataLocale, String nu) {
        return ULocale.forLanguageTag(dataLocale + "-u-nu-" + nu);
    }

    /** CurrencyDigits(currency) */
    static int currencyDigits(String code) {
        try {
            return Currency.getInstance(code).getDefaultFractionDigits();
        } catch (Exception invalid) {
            return 2;
        }
    }

    static MeasureUnit unitOf(String unit) {
        int index = unit.indexOf("-per-");
        if (index < 0) {
            return MeasureUnit.forIdentifier(unit);
        }
        return MeasureUnit.forIdentifier(unit.substring(0, index))
                .product(MeasureUnit.forIdentifier(unit.substring(index + 5)).reciprocal());
    }

    /** The unlocalized formatter for {@code nf} and values of sign {@code signum} (sign matters only for halfCeil/halfFloor). */
    static UnlocalizedNumberFormatter settings(JSIntlNumberFormat nf, int signum) {
        UnlocalizedNumberFormatter formatter = NumberFormatter.with();
        switch (nf.style) {
            case "percent":
                formatter = formatter.unit(NoUnit.PERCENT).scale(Scale.powerOfTen(2));
                break;
            case "currency":
                formatter = formatter.unit(Currency.getInstance(nf.currency)).unitWidth(currencyUnitWidth(nf.currencyDisplay));
                break;
            case "unit":
                formatter = formatter.unit(unitOf(nf.unit)).unitWidth(unitWidth(nf.unitDisplay));
                break;
            default:
                break;
        }
        switch (nf.notation) {
            case "scientific":
                formatter = formatter.notation(Notation.scientific());
                break;
            case "engineering":
                formatter = formatter.notation(Notation.engineering());
                break;
            case "compact":
                formatter = formatter.notation("long".equals(nf.compactDisplay)
                        ? Notation.compactLong() : Notation.compactShort());
                break;
            default:
                formatter = formatter.notation(Notation.simple());
                break;
        }
        formatter = nf.digits.applyTo(formatter, signum);
        if (Boolean.FALSE.equals(nf.useGrouping)) {
            formatter = formatter.grouping(NumberFormatter.GroupingStrategy.OFF);
        } else if ("min2".equals(nf.useGrouping)) {
            formatter = formatter.grouping(NumberFormatter.GroupingStrategy.MIN2);
        } else if ("always".equals(nf.useGrouping)) {
            formatter = formatter.grouping(NumberFormatter.GroupingStrategy.ON_ALIGNED);
        } else {
            formatter = formatter.grouping(NumberFormatter.GroupingStrategy.AUTO);
        }
        formatter = formatter.sign(signDisplay(nf.signDisplay,
                "currency".equals(nf.style) && "accounting".equals(nf.currencySign)));
        return formatter;
    }

    private static NumberFormatter.UnitWidth currencyUnitWidth(String display) {
        if (display == null) {
            return NumberFormatter.UnitWidth.SHORT;
        }
        switch (display) {
            case "code":
                return NumberFormatter.UnitWidth.ISO_CODE;
            case "narrowSymbol":
                return NumberFormatter.UnitWidth.NARROW;
            case "name":
                return NumberFormatter.UnitWidth.FULL_NAME;
            default:
                return NumberFormatter.UnitWidth.SHORT;
        }
    }

    private static NumberFormatter.UnitWidth unitWidth(String display) {
        if (display == null) {
            return NumberFormatter.UnitWidth.SHORT;
        }
        switch (display) {
            case "narrow":
                return NumberFormatter.UnitWidth.NARROW;
            case "long":
                return NumberFormatter.UnitWidth.FULL_NAME;
            default:
                return NumberFormatter.UnitWidth.SHORT;
        }
    }

    private static NumberFormatter.SignDisplay signDisplay(String display, boolean accounting) {
        switch (display) {
            case "never":
                return NumberFormatter.SignDisplay.NEVER;
            case "always":
                return accounting ? NumberFormatter.SignDisplay.ACCOUNTING_ALWAYS
                        : NumberFormatter.SignDisplay.ALWAYS;
            case "exceptZero":
                return accounting ? NumberFormatter.SignDisplay.ACCOUNTING_EXCEPT_ZERO
                        : NumberFormatter.SignDisplay.EXCEPT_ZERO;
            case "negative":
                return accounting ? NumberFormatter.SignDisplay.ACCOUNTING_NEGATIVE
                        : NumberFormatter.SignDisplay.NEGATIVE;
            default:
                return accounting ? NumberFormatter.SignDisplay.ACCOUNTING
                        : NumberFormatter.SignDisplay.AUTO;
        }
    }

    static LocalizedNumberFormatter formatter(JSIntlNumberFormat nf, int signum) {
        boolean dependent = nf.digits.signDependent();
        int index = dependent && signum < 0 ? 1 : 0;
        Object cached = nf.icuFormatters[index];
        if (cached instanceof LocalizedNumberFormatter) {
            return (LocalizedNumberFormatter) cached;
        }
        LocalizedNumberFormatter created = icuCall(() ->
                settings(nf, signum).locale(icuLocale(nf.dataLocale, nf.numberingSystem)));
        nf.icuFormatters[index] = created;
        return created;
    }

    static FormattedNumber format(JSIntlNumberFormat nf, IntlMV value) {
        return icuCall(() -> formatter(nf, value.signum()).format(value.toIcu()));
    }

    static FormattedValue formatRange(JSIntlNumberFormat nf, IntlMV x, IntlMV y) {
        return icuCall(() -> {
            UnlocalizedNumberFormatter first = settings(nf, x.signum());
            boolean sameMode = !nf.digits.signDependent() || (x.signum() < 0) == (y.signum() < 0);
            LocalizedNumberRangeFormatter base = NumberRangeFormatter.withLocale(
                    icuLocale(nf.dataLocale, nf.numberingSystem));
            LocalizedNumberRangeFormatter withNumbers = sameMode
                    ? base.numberFormatterBoth(first)
                    : base.numberFormatterFirst(first).numberFormatterSecond(settings(nf, y.signum()));
            return withNumbers
                    .identityFallback(NumberRangeFormatter.RangeIdentityFallback.APPROXIMATELY)
                    .formatRange(x.toIcu(), y.toIcu());
        });
    }

    /** Runs an ICU call, converting ICU exceptions (whose messages and classes must not reach JS) to RangeErrors. */
    static <T> T icuCall(Supplier<T> block) {
        try {
            return block.get();
        } catch (JSException e) {
            throw e;
        } catch (TerminationException e) {
            throw e;
        } catch (RuntimeException invalid) {
            throw JSException.rangeError("Unable to format the number");
        }
    }

    // ------------------------------------------------------------------ parts

    /** Maps an ICU number field to its ECMA-402 part type ({@code text} decides plus/minus). */
    private static String partType(Format.Field field, String text, IntlMV value) {
        if (field == com.ibm.icu.text.NumberFormat.Field.INTEGER) {
            if (value == null) {
                return "integer";
            }
            if (value.isNaN()) {
                return "nan";
            }
            if (!value.isFinite()) {
                return "infinity";
            }
            return "integer";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.FRACTION) {
            return "fraction";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.DECIMAL_SEPARATOR) {
            return "decimal";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.GROUPING_SEPARATOR) {
            return "group";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.CURRENCY) {
            return "currency";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.PERCENT) {
            return "percentSign";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.PERMILLE) {
            return "unknown";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.SIGN) {
            return text.indexOf('+') >= 0 || text.indexOf('\uFF0B') >= 0 ? "plusSign" : "minusSign";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.EXPONENT_SYMBOL) {
            return "exponentSeparator";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.EXPONENT_SIGN) {
            return text.indexOf('+') >= 0 ? "exponentPlusSign" : "exponentMinusSign";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.EXPONENT) {
            return "exponentInteger";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.COMPACT) {
            return "compact";
        }
        if (field == com.ibm.icu.text.NumberFormat.Field.MEASURE_UNIT) {
            return "unit";
        }
        return "literal";
    }

    static List<FmtPart> parts(Realm realm, FormattedValue value, IntlMV[] values, boolean range) {
        return parts(realm, value, values, range, false, null);
    }

    static List<FmtPart> parts(Realm realm, FormattedValue value, IntlMV[] values, boolean range,
                               boolean unitStyle) {
        return parts(realm, value, values, range, unitStyle, null);
    }

    /**
     * Converts ICU field positions to non-overlapping parts. Inner fields (e.g. a group inside an integer) win over
     * outer ones; for ranges {@code values} gives the start/end values and the range spans give each part's source.
     */
    static List<FmtPart> parts(Realm realm, FormattedValue value, IntlMV[] values, boolean range,
                               boolean unitStyle, String approximationSign) {
        String text = value.toString();
        int length = text.length();
        realm.agent.checkStringLength(length);
        String[] types = new String[length];
        int[] depth = new int[length];
        int[] source = new int[length];
        for (int i = 0; i < length; i++) {
            depth[i] = -1;
            source[i] = -1;
        }
        ConstrainedFieldPosition position = new ConstrainedFieldPosition();
        List<int[]> spans = new ArrayList<>();
        List<FieldRange> fields = new ArrayList<>();
        long count = 0;
        while (value.nextPosition(position)) {
            Dsl.tick(realm, count++);
            Format.Field field = position.getField();
            if (field instanceof NumberRangeFormatter.SpanField) {
                spans.add(new int[]{position.getStart(), position.getLimit(),
                        ((Number) position.getFieldValue()).intValue()});
            } else {
                fields.add(new FieldRange(field, position.getStart(), position.getLimit()));
            }
        }
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1]; i++) {
                source[i] = span[2];
            }
        }
        for (FieldRange fieldRange : fields) {
            int start = fieldRange.start;
            int limit = fieldRange.limit;
            int len = limit - start;
            String fieldText = text.substring(start, limit);
            int side = range && start < length && source[start] == 1 ? 1 : 0;
            IntlMV fieldValue = side < values.length ? values[side] : null;
            if (fieldValue == null && values.length > 0) {
                fieldValue = values[0];
            }
            String type = partType(fieldRange.field, fieldText, fieldValue);
            if (unitStyle && "percentSign".equals(type)) {
                type = "unit";
            }
            for (int i = start; i < limit; i++) {
                // smaller spans are nested inside larger ones
                if (types[i] == null || depth[i] > len) {
                    types[i] = type;
                    depth[i] = len;
                }
            }
            if (range && approximationSign != null && !approximationSign.isEmpty()
                    && (fieldRange.field == com.ibm.icu.text.NumberFormat.Field.SIGN
                    || ("literal".equals(type) && fieldText.equals(approximationSign)))) {
                int offset = fieldText.indexOf(approximationSign);
                if (offset >= 0) {
                    for (int i = start + offset; i < start + offset + approximationSign.length(); i++) {
                        types[i] = "approximatelySign";
                        depth[i] = Math.min(depth[i], len);
                    }
                }
            }
        }
        List<FmtPart> out = new ArrayList<>();
        int i = 0;
        while (i < length) {
            String type = types[i] == null ? "literal" : types[i];
            int src = source[i];
            int j = i + 1;
            while (j < length && (types[j] == null ? "literal" : types[j]).equals(type) && source[j] == src) {
                j++;
            }
            String sourceName = null;
            if (range) {
                if (src == 0) {
                    sourceName = "startRange";
                } else if (src == 1) {
                    sourceName = "endRange";
                } else {
                    sourceName = "shared";
                }
            }
            out.add(new FmtPart(type, text.substring(i, j), sourceName));
            i = j;
        }
        return out;
    }

    private static final class FieldRange {
        final Format.Field field;
        final int start;
        final int limit;

        FieldRange(Format.Field field, int start, int limit) {
            this.field = field;
            this.start = start;
            this.limit = limit;
        }
    }

    static JSArray partsArray(Realm realm, List<FmtPart> parts, String unit) {
        List<Object> list = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            Dsl.tick(realm, index);
            FmtPart part = parts.get(index);
            JSObject object = Dsl.partObject(realm, part.type, part.value);
            if (part.source != null) {
                object.createDataPropertyOrThrow("source", part.source);
            }
            if (unit != null) {
                object.createDataPropertyOrThrow("unit", unit);
            }
            list.add(object);
        }
        return Dsl.jsArray(realm, list);
    }

    static JSArray partsArray(Realm realm, List<FmtPart> parts) {
        return partsArray(realm, parts, null);
    }
}

