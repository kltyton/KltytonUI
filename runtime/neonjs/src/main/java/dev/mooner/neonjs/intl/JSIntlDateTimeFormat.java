/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.intl;

import dev.mooner.neonjs.runtime.JSObject;

import java.util.HashMap;
import java.util.Map;

/** Intl.DateTimeFormat instance ({@code [[InitializedDateTimeFormat]]}). */
final class JSIntlDateTimeFormat extends JSObject {
    String locale = "";
    String dataLocale = "";
    String calendar = "gregory";
    String numberingSystem = "latn";
    String timeZone = "UTC";
    String hourCycle;
    String dateStyle;
    String timeStyle;
    JSObject boundFormat;

    /** {@code [[DateTimeFormat]]} */
    DtfFormat format;

    /** The format options record of CreateDateTimeFormat (components requested by the caller) and its hc. */
    Map<String, String> formatOptions = Map.of();
    String hc;
    String formatMatcher = "best fit";

    /** ICU objects, created on first use; owned by this instance (never shared, they are not thread-safe). */
    DtfIcu icu;

    /** Lazily computed {@code [[TemporalPlain...Format]]} records (null entry = no overlapping fields). */
    final HashMap<String, DtfFormat> temporalFormats = new HashMap<>();

    JSIntlDateTimeFormat(JSObject proto) {
        super(proto);
    }
}
