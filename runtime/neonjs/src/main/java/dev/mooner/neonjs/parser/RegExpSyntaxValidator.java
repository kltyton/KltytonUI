/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.parser;

import dev.mooner.neonjs.regexp.RegExpEngine;
import java.util.function.BiFunction;

/** Early-error validation of regular expression literals. Returns an error message or null. */
public final class RegExpSyntaxValidator {
    public static final RegExpSyntaxValidator INSTANCE = new RegExpSyntaxValidator();

    /** Optional override of the default validator (the regular expression compiler). */
    public static BiFunction<String, String, String> validator;

    private RegExpSyntaxValidator() {
    }

    public static BiFunction<String, String, String> getValidator() {
        return validator;
    }

    public static void setValidator(BiFunction<String, String, String> value) {
        validator = value;
    }

    public static String validate(String pattern, String flags) {
        BiFunction<String, String, String> current = validator;
        if (current != null) {
            return current.apply(pattern, flags);
        }
        return RegExpEngine.validate(pattern, flags);
    }
}
