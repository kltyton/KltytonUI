/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.vm.Evaluator;
import java.math.BigInteger;

/** Global object functions (eval, URI coding, parseFloat/parseInt, ...); derived from NeonJS and adapted to Java. */
final class GlobalBuiltins {
    private GlobalBuiltins() {
    }

    static void install(Realm realm) {
        JSObject global = realm.getGlobalObject();
        global.defineOwn("globalThis", global, Attr.WC);
        global.defineOwn("Infinity", Double.POSITIVE_INFINITY, Attr.NONE);
        global.defineOwn("NaN", Double.NaN, Attr.NONE);
        global.defineOwn("undefined", Undefined.INSTANCE, Attr.NONE);
        realm.setEvalFunction(BuiltinsKt.method(global, realm, "eval", 1, (function, thisArg, args, newTarget) ->
                Evaluator.indirectEval(function.realm, Values.arg(args, 0))));
        BuiltinsKt.method(global, realm, "isFinite", 1, (function, thisArg, args, newTarget) -> {
            double number = Ops.toNumber(Values.arg(args, 0));
            return !Double.isNaN(number) && !Double.isInfinite(number);
        });
        BuiltinsKt.method(global, realm, "isNaN", 1, (function, thisArg, args, newTarget) ->
                Double.isNaN(Ops.toNumber(Values.arg(args, 0))));
        NativeFunction parseFloatFunction = BuiltinsKt.method(global, realm, "parseFloat", 1,
                (function, thisArg, args, newTarget) -> parseFloat(Ops.toString(Values.arg(args, 0))));
        NativeFunction parseIntFunction = BuiltinsKt.method(global, realm, "parseInt", 2,
                (function, thisArg, args, newTarget) ->
                        parseInt(Ops.toString(Values.arg(args, 0)), Values.arg(args, 1)));
        JSObject numberConstructor = (JSObject) global.get("Number");
        numberConstructor.defineOwn("parseFloat", parseFloatFunction, Attr.WC);
        numberConstructor.defineOwn("parseInt", parseIntFunction, Attr.WC);
        BuiltinsKt.method(global, realm, "decodeURI", 1, (function, thisArg, args, newTarget) ->
                URICoding.decode(Ops.toString(Values.arg(args, 0)), ";/?:@&=+$,#"));
        BuiltinsKt.method(global, realm, "decodeURIComponent", 1, (function, thisArg, args, newTarget) ->
                URICoding.decode(Ops.toString(Values.arg(args, 0)), ""));
        BuiltinsKt.method(global, realm, "encodeURI", 1, (function, thisArg, args, newTarget) ->
                URICoding.encode(Ops.toString(Values.arg(args, 0)), ";/?:@&=+$,-_.!~*'()#"));
        BuiltinsKt.method(global, realm, "encodeURIComponent", 1, (function, thisArg, args, newTarget) ->
                URICoding.encode(Ops.toString(Values.arg(args, 0)), "-_.!~*'()"));
        BuiltinsKt.method(global, realm, "escape", 1, (function, thisArg, args, newTarget) ->
                URICoding.escape(Ops.toString(Values.arg(args, 0))));
        BuiltinsKt.method(global, realm, "unescape", 1, (function, thisArg, args, newTarget) ->
                URICoding.unescape(Ops.toString(Values.arg(args, 0))));
    }

    static double parseFloat(String input) {
        String s = trimStart(input);
        int n = s.length();
        int i = 0;
        boolean neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        if (s.startsWith("Infinity", i)) return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int numStart = i;
        int intDigits = 0;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
            intDigits++;
        }
        int fracDigits = 0;
        int end = i;
        if (i < n && s.charAt(i) == '.') {
            int j = i + 1;
            while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                j++;
                fracDigits++;
            }
            if (intDigits > 0 || fracDigits > 0) {
                i = j;
                end = j;
            }
        }
        if (intDigits == 0 && fracDigits == 0) return Double.NaN;
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
            int ed = 0;
            while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                j++;
                ed++;
            }
            if (ed > 0) end = j;
        }
        String literal = s.substring(numStart, end);
        if (literal.startsWith(".")) literal = "0" + literal;
        literal = literal.replace(".e", ".0e").replace(".E", ".0E");
        if (literal.endsWith(".")) literal += "0";
        double value;
        try {
            value = Double.parseDouble(literal);
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
        return neg ? -value : value;
    }

    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && NumberConv.isJSWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    static double parseInt(String input, Object radixArg) {
        String s = trimStart(input);
        int sign = 1;
        if (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            if (s.charAt(0) == '-') sign = -1;
            s = s.substring(1);
        }
        int radix = Ops.toInt32(radixArg);
        boolean stripPrefix = true;
        if (radix != 0) {
            if (radix < 2 || radix > 36) return Double.NaN;
            if (radix != 16) stripPrefix = false;
        } else {
            radix = 10;
        }
        if (stripPrefix && s.length() >= 2 && s.charAt(0) == '0'
                && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
            s = s.substring(2);
            radix = 16;
        }
        int end = 0;
        while (end < s.length() && NumberConv.digitVal(s.charAt(end), radix) >= 0) end++;
        if (end == 0) return Double.NaN;
        String digits = s.substring(0, end);
        double value;
        if (radix == 10 && end > 15) {
            value = Double.parseDouble(digits);
        } else if (end <= 10) {
            value = (double) Long.parseLong(digits, radix);
        } else {
            value = new BigInteger(digits, radix).doubleValue();
        }
        return sign * value;
    }
}
