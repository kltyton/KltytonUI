/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.text.ConstrainedFieldPosition;
import com.ibm.icu.text.DisplayContext;
import com.ibm.icu.text.FormattedValue;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.RelativeDateTimeFormatter;
import com.ibm.icu.util.ULocale;

import java.util.ArrayList;
import java.util.List;

/** ICU side of Intl.RelativeTimeFormat (loaded on first use). */
final class RelativeTimeFormatIcu {
    private RelativeTimeFormatIcu() {
    }

    /** SingularRelativeTimeUnit */
    private static String singularUnit(String unit) {
        switch (unit) {
            case "seconds":
                return "second";
            case "minutes":
                return "minute";
            case "hours":
                return "hour";
            case "days":
                return "day";
            case "weeks":
                return "week";
            case "months":
                return "month";
            case "quarters":
                return "quarter";
            case "years":
                return "year";
            case "second":
            case "minute":
            case "hour":
            case "day":
            case "week":
            case "month":
            case "quarter":
            case "year":
                return unit;
            default:
                throw Dsl.rangeErr("Invalid unit argument for format() '" + unit + "'");
        }
    }

    private static RelativeDateTimeFormatter.RelativeDateTimeUnit icuUnit(String unit) {
        switch (unit) {
            case "second":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND;
            case "minute":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE;
            case "hour":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR;
            case "day":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY;
            case "week":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK;
            case "month":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH;
            case "quarter":
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.QUARTER;
            default:
                return RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR;
        }
    }

    private static RelativeDateTimeFormatter formatter(JSIntlRelativeTimeFormat rtf) {
        if (rtf.icu instanceof RelativeDateTimeFormatter) {
            return (RelativeDateTimeFormatter) rtf.icu;
        }
        RelativeDateTimeFormatter created = NumberFormatIcu.icuCall(() -> {
            ULocale locale = NumberFormatIcu.icuLocale(rtf.dataLocale, rtf.numberingSystem);
            NumberFormat numberFormat = NumberFormat.getInstance(locale);
            numberFormat.setMinimumFractionDigits(0);
            numberFormat.setMaximumFractionDigits(3);
            numberFormat.setRoundingMode(com.ibm.icu.math.BigDecimal.ROUND_HALF_UP);
            numberFormat.setGroupingUsed(true);
            RelativeDateTimeFormatter.Style style;
            switch (rtf.style) {
                case "short":
                    style = RelativeDateTimeFormatter.Style.SHORT;
                    break;
                case "narrow":
                    style = RelativeDateTimeFormatter.Style.NARROW;
                    break;
                default:
                    style = RelativeDateTimeFormatter.Style.LONG;
                    break;
            }
            return RelativeDateTimeFormatter.getInstance(locale, numberFormat, style,
                    DisplayContext.CAPITALIZATION_NONE);
        });
        rtf.icu = created;
        return created;
    }

    private static JSIntlNumberFormat numberFormat(dev.mooner.neonjs.runtime.Realm realm,
                                                   JSIntlRelativeTimeFormat rtf) {
        if (rtf.numberFormat != null) {
            return rtf.numberFormat;
        }
        dev.mooner.neonjs.runtime.JSObject options = new dev.mooner.neonjs.runtime.JSObject(null);
        options.createDataPropertyOrThrow("numberingSystem", rtf.numberingSystem);
        JSIntlNumberFormat nf = NumberFormatBuiltins.create(realm, null, rtf.locale, options);
        rtf.numberFormat = nf;
        return nf;
    }

    /**
     * PartitionRelativeTimePattern: ICU picks the pattern (and plural form); the number inside it is formatted by
     * {@code [[NumberFormat]]}. Number parts carry the unit.
     */
    static List<FmtPart> partition(dev.mooner.neonjs.runtime.Realm realm, JSIntlRelativeTimeFormat rtf,
                                   double value, String unit0) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw Dsl.rangeErr("Invalid value: " + NumberConvShortest.toShortest(value));
        }
        String unit = singularUnit(unit0);
        RelativeDateTimeFormatter formatter = formatter(rtf);
        FormattedValue formatted = NumberFormatIcu.icuCall(() -> "auto".equals(rtf.numeric)
                ? formatter.formatToValue(value, icuUnit(unit))
                : formatter.formatNumericToValue(value, icuUnit(unit)));
        String text = formatted.toString();
        ConstrainedFieldPosition position = new ConstrainedFieldPosition();
        position.constrainField(RelativeDateTimeFormatter.Field.NUMERIC);
        if (!formatted.nextPosition(position)) {
            return List.of(new FmtPart("literal", text, null));
        }
        List<FmtPart> out = new ArrayList<>();
        if (position.getStart() > 0) {
            out.add(new FmtPart("literal", text.substring(0, position.getStart()), null));
        }
        IntlMV number = IntlMV.of(Math.abs(value));
        JSIntlNumberFormat nf = numberFormat(realm, rtf);
        for (FmtPart part : NumberFormatIcu.parts(realm, NumberFormatIcu.format(nf, number),
                new IntlMV[]{number}, false)) {
            out.add(new FmtPart(part.type, part.value, unit));
        }
        if (position.getLimit() < text.length()) {
            out.add(new FmtPart("literal", text.substring(position.getLimit()), null));
        }
        return out;
    }

    static dev.mooner.neonjs.runtime.JSArray formatToParts(dev.mooner.neonjs.runtime.Realm realm,
                                                          JSIntlRelativeTimeFormat rtf,
                                                          double value, String unit0) {
        List<Object> list = new ArrayList<>();
        for (FmtPart part : partition(realm, rtf, value, unit0)) {
            dev.mooner.neonjs.runtime.JSObject object = Dsl.partObject(realm, part.type, part.value);
            if (part.source != null) {
                object.createDataPropertyOrThrow("unit", part.source);
            }
            list.add(object);
        }
        return Dsl.jsArray(realm, list);
    }
}
