/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import com.ibm.icu.impl.ICUData;
import com.ibm.icu.impl.ICUResourceBundle;
import com.ibm.icu.text.ConstrainedFieldPosition;
import com.ibm.icu.text.FormattedValue;
import com.ibm.icu.text.ListFormatter;
import com.ibm.icu.util.ULocale;
import com.ibm.icu.util.UResourceBundle;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** PartitionDurationFormatPattern and its ICU-backed helpers. */
final class DurationFormatPattern {
    private DurationFormatPattern() {
    }

    private static String[] digitalPatterns(String dataLocale) {
        try {
            ICUResourceBundle bundle = (ICUResourceBundle) UResourceBundle.getBundleInstance(
                    ICUData.ICU_UNIT_BASE_NAME, ULocale.forLanguageTag(dataLocale));
            return new String[]{bundle.getStringWithFallback("durationUnits/hm"),
                    bundle.getStringWithFallback("durationUnits/ms")};
        } catch (Exception invalid) {
            return new String[]{"h:mm", "m:ss"};
        }
    }

    private static String trimChars(String value, String chars) {
        int start = 0;
        int end = value.length();
        while (start < end && chars.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** [hoursMinutesSeparator, minutesSecondsSeparator, twoDigitHours ("true"/"false")] */
    private static String[] separators(JSIntlDurationFormat df) {
        if (df.separators != null) {
            return df.separators;
        }
        String[] patterns = digitalPatterns(df.dataLocale);
        String hm = patterns[0];
        String ms = patterns[1];
        String hmSeparator = trimChars(hm, "hHm");
        if (hmSeparator.isEmpty()) {
            hmSeparator = ":";
        }
        String msSeparator = trimChars(ms, "ms");
        if (msSeparator.isEmpty()) {
            msSeparator = ":";
        }
        String[] result = {hmSeparator, msSeparator,
                hm.startsWith("hh") || hm.startsWith("HH") ? "true" : "false"};
        df.separators = result;
        return result;
    }

    static boolean twoDigitHours(JSIntlDurationFormat df) {
        return "true".equals(separators(df)[2]);
    }

    private static ListFormatter listFormatter(JSIntlDurationFormat df) {
        if (df.listFormatter instanceof ListFormatter) {
            return (ListFormatter) df.listFormatter;
        }
        ListFormatter.Width width;
        switch (df.style) {
            case "long":
                width = ListFormatter.Width.WIDE;
                break;
            case "narrow":
                width = ListFormatter.Width.NARROW;
                break;
            default:
                width = ListFormatter.Width.SHORT;
                break;
        }
        ListFormatter created = NumberFormatIcu.icuCall(() -> ListFormatter.getInstance(
                ULocale.forLanguageTag(df.dataLocale), ListFormatter.Type.UNITS, width));
        df.listFormatter = created;
        return created;
    }

    /** The exact value of {@code unit} plus all smaller sub-second units (seconds + ms/1e3 + ...), or the plain Number. */
    private static IntlMV fractionalValue(DurationRecord duration, int unit) {
        boolean zero = true;
        for (int k = unit + 1; k <= 9; k++) {
            if (duration.values[k] != 0.0) {
                zero = false;
            }
        }
        if (zero) {
            return IntlMV.of(duration.values[unit]);
        }
        BigDecimal sum = new BigDecimal(duration.values[unit]);
        for (int k = unit + 1; k <= 9; k++) {
            sum = sum.add(new BigDecimal(duration.values[k]).movePointLeft(3 * (k - unit)));
        }
        if (sum.signum() == 0) {
            return duration.sign() < 0 ? IntlMV.NegZero : IntlMV.of(BigDecimal.ZERO);
        }
        return IntlMV.of(sum);
    }

    private static JSIntlNumberFormat numberFormat(Realm realm, JSIntlDurationFormat df, int unit, String style,
                                                   boolean signNever, boolean fractional) {
        String key = unit + "|" + style + "|" + signNever + "|" + fractional;
        JSIntlNumberFormat cached = df.numberFormats.get(key);
        if (cached != null) {
            return cached;
        }
        JSObject options = new JSObject(null);
        options.createDataPropertyOrThrow("numberingSystem", df.numberingSystem);
        if (signNever) {
            options.createDataPropertyOrThrow("signDisplay", "never");
        }
        if (fractional) {
            Integer digits = df.fractionalDigits;
            options.createDataPropertyOrThrow("maximumFractionDigits", (double) (digits != null ? digits : 9));
            options.createDataPropertyOrThrow("minimumFractionDigits", (double) (digits != null ? digits : 0));
            options.createDataPropertyOrThrow("roundingMode", "trunc");
        }
        if ("2-digit".equals(style)) {
            options.createDataPropertyOrThrow("minimumIntegerDigits", 2.0);
        }
        if (!"numeric".equals(style) && !"2-digit".equals(style)) {
            options.createDataPropertyOrThrow("style", "unit");
            options.createDataPropertyOrThrow("unit", DurationFormatBuiltins.NF_UNITS[unit]);
            options.createDataPropertyOrThrow("unitDisplay", style);
        } else {
            options.createDataPropertyOrThrow("useGrouping", Boolean.FALSE);
        }
        JSIntlNumberFormat nf = NumberFormatBuiltins.create(realm, null, df.locale, options);
        df.numberFormats.put(key, nf);
        return nf;
    }

    private static boolean isZero(IntlMV value) {
        return value.kind == IntlMV.NEG_ZERO
                || (value.kind == IntlMV.FINITE && value.signum() == 0);
    }

    /** PartitionDurationFormatPattern */
    static List<DurationPart> partition(Realm realm, JSIntlDurationFormat df, DurationRecord duration) {
        List<ArrayList<DurationPart>> groups = new ArrayList<>();
        int lastNumeric = -1;
        boolean signPending = true;
        boolean negative = duration.sign() < 0;
        for (int i = 0; i < 10; i++) {
            IntlMV value = IntlMV.of(duration.values[i]);
            String style = df.styles[i];
            String display = df.displays[i];
            boolean done = false;
            if (i >= 6 && i <= 8 && "fractional".equals(df.styles[i + 1])) {
                value = fractionalValue(duration, i);
                done = true;
            }
            boolean displayRequired = false;
            if (i == 5 && lastNumeric == 4) {
                displayRequired = "always".equals(df.displays[6]);
                if (!displayRequired) {
                    for (int k = 6; k <= 9; k++) {
                        if (duration.values[k] != 0.0) {
                            displayRequired = true;
                            break;
                        }
                    }
                }
            }
            if (!isZero(value) || !"auto".equals(display) || displayRequired) {
                boolean signNever = false;
                if (signPending) {
                    signPending = false;
                    if (isZero(value) && negative) {
                        value = IntlMV.NegZero;
                    }
                } else {
                    signNever = true;
                }
                JSIntlNumberFormat nf = numberFormat(realm, df, i, style, signNever, done);
                boolean numeric = "numeric".equals(style) || "2-digit".equals(style);
                ArrayList<DurationPart> list;
                if (lastNumeric >= 0 && numeric) {
                    list = groups.get(groups.size() - 1);
                    String[] seps = separators(df);
                    list.add(new DurationPart("literal", lastNumeric == 4 ? seps[0] : seps[1], null));
                } else {
                    list = new ArrayList<>();
                    groups.add(list);
                }
                FormattedValue formatted = NumberFormatIcu.format(nf, value);
                for (FmtPart part : NumberFormatIcu.parts(realm, formatted, new IntlMV[]{value}, false)) {
                    list.add(new DurationPart(part.type, part.value, DurationFormatBuiltins.NF_UNITS[i]));
                }
                if (numeric) {
                    lastNumeric = i;
                }
            }
            if (done) {
                break;
            }
        }
        return listFormat(realm, df, groups);
    }

    /** ListFormatParts */
    private static List<DurationPart> listFormat(Realm realm, JSIntlDurationFormat df,
                                                 List<? extends List<DurationPart>> groups) {
        List<String> strings = new ArrayList<>(groups.size());
        for (List<DurationPart> group : groups) {
            StringBuilder builder = new StringBuilder();
            for (DurationPart part : group) {
                builder.append(part.value);
            }
            strings.add(builder.toString());
        }
        FormattedValue formatted = NumberFormatIcu.icuCall(() -> listFormatter(df).formatToValue(strings));
        String text = formatted.toString();
        realm.agent.checkStringLength(text.length());
        List<DurationPart> out = new ArrayList<>();
        ConstrainedFieldPosition position = new ConstrainedFieldPosition();
        position.constrainField(ListFormatter.Field.ELEMENT);
        int pos = 0;
        int groupIndex = 0;
        while (formatted.nextPosition(position)) {
            if (position.getStart() > pos) {
                out.add(new DurationPart("literal", text.substring(pos, position.getStart()), null));
            }
            if (groupIndex < groups.size()) {
                out.addAll(groups.get(groupIndex++));
            }
            pos = position.getLimit();
        }
        if (pos < text.length()) {
            out.add(new DurationPart("literal", text.substring(pos), null));
        }
        return out;
    }
}
