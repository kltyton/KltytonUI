/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSStringObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.NumberConv;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Rope;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.unicode.CharRanges;
import dev.mooner.neonjs.unicode.UnicodeTables;
import dev.mooner.neonjs.vm.Iteration;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.relIndex;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;

/** The %String% constructor and prototype, including the String iterator (ECMA-262 §22.1). */
final class StringBuiltins {
    /**
     * Simple case mappings added in Unicode 17 that the JDK's (older) Unicode data lacks: code point, uppercase,
     * lowercase. Generated from UnicodeData.txt 17.0.0 by diffing against the JDK's mappings.
     */
    private static final int[] CASE_17 = {
            0xA7CE, 0xA7CE, 0xA7CF, 0xA7CF, 0xA7CE, 0xA7CF, 0xA7D2, 0xA7D2, 0xA7D3, 0xA7D3, 0xA7D2, 0xA7D3,
            0xA7D4, 0xA7D4, 0xA7D5, 0xA7D5, 0xA7D4, 0xA7D5, 0x16EA0, 0x16EA0, 0x16EBB, 0x16EA1, 0x16EA1, 0x16EBC,
            0x16EA2, 0x16EA2, 0x16EBD, 0x16EA3, 0x16EA3, 0x16EBE, 0x16EA4, 0x16EA4, 0x16EBF, 0x16EA5, 0x16EA5, 0x16EC0,
            0x16EA6, 0x16EA6, 0x16EC1, 0x16EA7, 0x16EA7, 0x16EC2, 0x16EA8, 0x16EA8, 0x16EC3, 0x16EA9, 0x16EA9, 0x16EC4,
            0x16EAA, 0x16EAA, 0x16EC5, 0x16EAB, 0x16EAB, 0x16EC6, 0x16EAC, 0x16EAC, 0x16EC7, 0x16EAD, 0x16EAD, 0x16EC8,
            0x16EAE, 0x16EAE, 0x16EC9, 0x16EAF, 0x16EAF, 0x16ECA, 0x16EB0, 0x16EB0, 0x16ECB, 0x16EB1, 0x16EB1, 0x16ECC,
            0x16EB2, 0x16EB2, 0x16ECD, 0x16EB3, 0x16EB3, 0x16ECE, 0x16EB4, 0x16EB4, 0x16ECF, 0x16EB5, 0x16EB5, 0x16ED0,
            0x16EB6, 0x16EB6, 0x16ED1, 0x16EB7, 0x16EB7, 0x16ED2, 0x16EB8, 0x16EB8, 0x16ED3, 0x16EBB, 0x16EA0, 0x16EBB,
            0x16EBC, 0x16EA1, 0x16EBC, 0x16EBD, 0x16EA2, 0x16EBD, 0x16EBE, 0x16EA3, 0x16EBE, 0x16EBF, 0x16EA4, 0x16EBF,
            0x16EC0, 0x16EA5, 0x16EC0, 0x16EC1, 0x16EA6, 0x16EC1, 0x16EC2, 0x16EA7, 0x16EC2, 0x16EC3, 0x16EA8, 0x16EC3,
            0x16EC4, 0x16EA9, 0x16EC4, 0x16EC5, 0x16EAA, 0x16EC5, 0x16EC6, 0x16EAB, 0x16EC6, 0x16EC7, 0x16EAC, 0x16EC7,
            0x16EC8, 0x16EAD, 0x16EC8, 0x16EC9, 0x16EAE, 0x16EC9, 0x16ECA, 0x16EAF, 0x16ECA, 0x16ECB, 0x16EB0, 0x16ECB,
            0x16ECC, 0x16EB1, 0x16ECC, 0x16ECD, 0x16EB2, 0x16ECD, 0x16ECE, 0x16EB3, 0x16ECE, 0x16ECF, 0x16EB4, 0x16ECF,
            0x16ED0, 0x16EB5, 0x16ED0, 0x16ED1, 0x16EB6, 0x16ED1, 0x16ED2, 0x16EB7, 0x16ED2, 0x16ED3, 0x16EB8, 0x16ED3
    };

    private static final Map<Integer, int[]> case17 = new HashMap<>();
    private static final int[] cased = Objects.requireNonNull(UnicodeTables.binaryProperty("Cased"));
    private static final int[] caseIgnorable = Objects.requireNonNull(UnicodeTables.binaryProperty("Case_Ignorable"));
    private static final Collator collator;

    static {
        for (int i = 0; i < CASE_17.length; i += 3) {
            case17.put(CASE_17[i], new int[] {CASE_17[i + 1], CASE_17[i + 2]});
        }
        Collator value = Collator.getInstance(Locale.ROOT);
        value.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        collator = value;
    }

    private StringBuiltins() {
    }

    /** True if {@code s} may contain a character covered by {@link #CASE_17} (U+A7CE..U+A7D5, or U+16EA0..U+16ED3). */
    private static boolean mayNeedCase17(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uA7CE' && c <= '\uA7D5' || c == '\uD81A') return true;
        }
        return false;
    }

    static String toUpperCase(String s) {
        if (!mayNeedCase17(s)) return s.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int codePoint = s.codePointAt(i);
            i += Character.charCount(codePoint);
            int[] mapping = case17.get(codePoint);
            if (mapping != null) sb.appendCodePoint(mapping[0]);
            else sb.append(new String(Character.toChars(codePoint)).toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    static String toLowerCase(String s) {
        if (s.indexOf('\u03A3') < 0 && !mayNeedCase17(s)) return s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int codePoint = s.codePointAt(i);
            int count = Character.charCount(codePoint);
            int[] mapping = case17.get(codePoint);
            if (codePoint == 0x3A3) sb.append(isFinalSigma(s, i) ? '\u03C2' : '\u03C3');
            else if (mapping != null) sb.appendCodePoint(mapping[1]);
            else sb.append(new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT));
            i += count;
        }
        return sb.toString();
    }

    /** Final_Sigma: preceded by cased (+ case-ignorables) and not followed by (case-ignorables +) cased. */
    private static boolean isFinalSigma(String s, int at) {
        int i = at;
        boolean before = false;
        while (i > 0) {
            int codePoint = s.codePointBefore(i);
            i -= Character.charCount(codePoint);
            if (CharRanges.contains(caseIgnorable, codePoint)) continue;
            before = CharRanges.contains(cased, codePoint);
            break;
        }
        if (!before) return false;
        i = at + 1;
        while (i < s.length()) {
            int codePoint = s.codePointAt(i);
            i += Character.charCount(codePoint);
            if (CharRanges.contains(caseIgnorable, codePoint)) continue;
            return !CharRanges.contains(cased, codePoint);
        }
        return true;
    }

    static String thisStr(Object receiver, String method) {
        if (receiver == Undefined.INSTANCE || receiver == Null.INSTANCE) {
            throw typeErr("String.prototype." + method + " called on null or undefined");
        }
        return Ops.toString(receiver);
    }

    static String thisStringValue(Object receiver, String method) {
        if (receiver instanceof CharSequence text) return text.toString();
        if (receiver instanceof JSStringObject string) return string.value;
        throw typeErr("String.prototype." + method + " requires that 'this' be a String");
    }

    private static boolean isRegExp(Object value) {
        if (!(value instanceof JSObject object)) return false;
        Object match = object.get(JSSymbol.match, object);
        if (match != Undefined.INSTANCE) return Ops.toBoolean(match);
        return object.getClassName().equals("RegExp");
    }

    static void install(Realm realm) {
        JSStringObject proto = new JSStringObject(realm.getObjectPrototype(), "");
        realm.setStringPrototype(proto);
        var constructor = makeCtor(realm, "String", 1, proto,
                (function, receiver, arguments, newTarget) -> {
                    String s;
                    if (arguments.length == 0) {
                        s = "";
                    } else {
                        Object value = arguments[0];
                        if (newTarget == null && value instanceof JSSymbol symbol) return symbol.toString();
                        s = Ops.toString(value);
                    }
                    if (newTarget == null) return s;
                    return new JSStringObject(Ops.getPrototypeFromConstructor(newTarget,
                            targetRealm -> targetRealm.getStringPrototype()), s);
                });
        global(realm, "String", constructor);
        method(constructor, realm, "fromCharCode", 1, (function, receiver, arguments, newTarget) -> {
            StringBuilder sb = new StringBuilder(arguments.length);
            for (Object argument : arguments) sb.append((char) Ops.toUint16(argument));
            return sb.toString();
        });
        method(constructor, realm, "fromCodePoint", 1, (function, receiver, arguments, newTarget) -> {
            StringBuilder sb = new StringBuilder(arguments.length);
            for (Object argument : arguments) {
                double number = Ops.toNumber(argument);
                if (!Ops.isIntegral(number) || number < 0 || number > 0x10FFFF) {
                    throw rangeErr("Invalid code point " + Ops.toDisplayString(argument));
                }
                sb.appendCodePoint((int) number);
            }
            return sb.toString();
        });
        method(constructor, realm, "raw", 1, (function, receiver, arguments, newTarget) -> {
            JSObject cooked = Ops.toObject(Values.arg(arguments, 0));
            JSObject raw = Ops.toObject(cooked.get("raw", cooked));
            long count = Ops.lengthOfArrayLike(raw);
            StringBuilder sb = new StringBuilder();
            var agent = function.realm.agent;
            long i = 0L;
            while (i < count) {
                if ((i & 1023L) == 1023L) agent.checkInterrupt();
                if (sb.length() > 65536) agent.checkStringLength(sb.length());
                sb.append(Ops.toString(raw.get(PK.fromIndex(i), raw)));
                if (i + 1 < count && i + 1 < arguments.length) {
                    sb.append(Ops.toString(arguments[(int) (i + 1)]));
                }
                i++;
            }
            return sb.toString();
        });

        method(proto, realm, "at", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "at");
            double relative = Ops.toIntegerOrInfinity(Values.arg(arguments, 0));
            double index = relative >= 0 ? relative : s.length() + relative;
            if (index < 0 || index >= s.length()) return Undefined.INSTANCE;
            return String.valueOf(s.charAt((int) index));
        });
        method(proto, realm, "charAt", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "charAt");
            double position = Ops.toIntegerOrInfinity(Values.arg(arguments, 0));
            if (position < 0 || position >= s.length()) return "";
            return String.valueOf(s.charAt((int) position));
        });
        method(proto, realm, "charCodeAt", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "charCodeAt");
            double position = Ops.toIntegerOrInfinity(Values.arg(arguments, 0));
            if (position < 0 || position >= s.length()) return Double.NaN;
            return (double) s.charAt((int) position);
        });
        method(proto, realm, "codePointAt", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "codePointAt");
            double position = Ops.toIntegerOrInfinity(Values.arg(arguments, 0));
            if (position < 0 || position >= s.length()) return Undefined.INSTANCE;
            return (double) s.codePointAt((int) position);
        });
        method(proto, realm, "concat", 1, (function, receiver, arguments, newTarget) -> {
            CharSequence result = thisStr(receiver, "concat");
            for (Object argument : arguments) result = Rope.concat(result, Ops.toString(argument));
            return result;
        });
        method(proto, realm, "endsWith", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "endsWith");
            if (isRegExp(Values.arg(arguments, 0))) {
                throw typeErr("First argument to String.prototype.endsWith must not be a regular expression");
            }
            String search = Ops.toString(Values.arg(arguments, 0));
            int end = Values.arg(arguments, 1) == Undefined.INSTANCE
                    ? s.length() : clamp(Ops.toIntegerOrInfinity(Values.arg(arguments, 1)), s.length());
            int start = end - search.length();
            return start >= 0 && s.regionMatches(start, search, 0, search.length());
        });
        method(proto, realm, "includes", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "includes");
            if (isRegExp(Values.arg(arguments, 0))) {
                throw typeErr("First argument to String.prototype.includes must not be a regular expression");
            }
            String search = Ops.toString(Values.arg(arguments, 0));
            int start = clamp(Ops.toIntegerOrInfinity(Values.arg(arguments, 1)), s.length());
            return s.indexOf(search, start) >= 0;
        });
        method(proto, realm, "indexOf", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "indexOf");
            String search = Ops.toString(Values.arg(arguments, 0));
            int start = clamp(Ops.toIntegerOrInfinity(Values.arg(arguments, 1)), s.length());
            return (double) s.indexOf(search, start);
        });
        method(proto, realm, "isWellFormed", 0,
                (function, receiver, arguments, newTarget) -> isWellFormed(thisStr(receiver, "isWellFormed")));
        method(proto, realm, "lastIndexOf", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "lastIndexOf");
            String search = Ops.toString(Values.arg(arguments, 0));
            double numberPosition = Ops.toNumber(Values.arg(arguments, 1));
            double position = Double.isNaN(numberPosition) ? Double.POSITIVE_INFINITY : Ops.integerPart(numberPosition);
            int start = clamp(position, s.length());
            return (double) s.lastIndexOf(search, start);
        });
        method(proto, realm, "localeCompare", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "localeCompare");
            String that = Ops.toString(Values.arg(arguments, 0));
            int comparison = collator.compare(Normalizer.normalize(s, Normalizer.Form.NFC),
                    Normalizer.normalize(that, Normalizer.Form.NFC));
            return (double) (comparison < 0 ? -1 : comparison > 0 ? 1 : 0);
        });
        String[] symbolNames = {"match", "search"};
        JSSymbol[] symbolValues = {JSSymbol.match, JSSymbol.search};
        for (int index = 0; index < symbolNames.length; index++) {
            final String name = symbolNames[index];
            final JSSymbol symbol = symbolValues[index];
            method(proto, realm, name, 1, (function, receiver, arguments, newTarget) -> {
                Ops.requireObjectCoercible(receiver);
                Object regexp = Values.arg(arguments, 0);
                if (regexp instanceof JSObject regexpObject) {
                    Object match = Ops.getMethod(regexpObject, symbol);
                    if (match != Undefined.INSTANCE) return Ops.call(match, regexpObject, new Object[] {receiver});
                }
                String s = Ops.toString(receiver);
                JSObject rx = regexpCreate(function.realm, regexp, Undefined.INSTANCE);
                return Ops.invoke(rx, symbol, new Object[] {s});
            });
        }
        method(proto, realm, "matchAll", 1, (function, receiver, arguments, newTarget) -> {
            Ops.requireObjectCoercible(receiver);
            Object regexp = Values.arg(arguments, 0);
            if (regexp instanceof JSObject regexpObject) {
                if (isRegExp(regexpObject)) {
                    Object flags = regexpObject.get("flags", regexpObject);
                    Ops.requireObjectCoercible(flags);
                    if (Ops.toString(flags).indexOf('g') < 0) {
                        throw typeErr("String.prototype.matchAll called with a non-global RegExp argument");
                    }
                }
                Object matchAll = Ops.getMethod(regexpObject, JSSymbol.matchAll);
                if (matchAll != Undefined.INSTANCE) return Ops.call(matchAll, regexpObject, new Object[] {receiver});
            }
            String s = Ops.toString(receiver);
            JSObject rx = regexpCreate(function.realm, regexp, "g");
            return Ops.invoke(rx, JSSymbol.matchAll, new Object[] {s});
        });
        method(proto, realm, "normalize", 0, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "normalize");
            String formName = Values.arg(arguments, 0) == Undefined.INSTANCE
                    ? "NFC" : Ops.toString(Values.arg(arguments, 0));
            Normalizer.Form form = switch (formName) {
                case "NFC" -> Normalizer.Form.NFC;
                case "NFD" -> Normalizer.Form.NFD;
                case "NFKC" -> Normalizer.Form.NFKC;
                case "NFKD" -> Normalizer.Form.NFKD;
                default -> throw rangeErr("The normalization form should be one of NFC, NFD, NFKC, NFKD.");
            };
            return Normalizer.normalize(s, form);
        });
        String[] padNames = {"padEnd", "padStart"};
        boolean[] padAtStart = {false, true};
        for (int index = 0; index < padNames.length; index++) {
            final String name = padNames[index];
            final boolean atStart = padAtStart[index];
            method(proto, realm, name, 1, (function, receiver, arguments, newTarget) -> {
                String s = thisStr(receiver, name);
                long maxLength = Ops.toLength(Values.arg(arguments, 0));
                if (maxLength <= s.length()) return s;
                String fill = Values.arg(arguments, 1) == Undefined.INSTANCE
                        ? " " : Ops.toString(Values.arg(arguments, 1));
                if (fill.isEmpty()) return s;
                if (maxLength > Rope.MAX_LENGTH) throw rangeErr("Invalid string length");
                function.realm.agent.checkStringLength(maxLength);
                int fillLength = (int) (maxLength - s.length());
                StringBuilder sb = new StringBuilder((int) maxLength);
                if (!atStart) sb.append(s);
                while (sb.length() - (atStart ? 0 : s.length()) < fillLength) sb.append(fill);
                sb.setLength((atStart ? 0 : s.length()) + fillLength);
                if (atStart) sb.append(s);
                return sb.toString();
            });
        }
        method(proto, realm, "repeat", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "repeat");
            double count = Ops.toIntegerOrInfinity(Values.arg(arguments, 0));
            if (count < 0 || count == Double.POSITIVE_INFINITY) {
                throw rangeErr("Invalid count value: " + Ops.toDisplayString(Values.arg(arguments, 0)));
            }
            if (s.isEmpty() || count == 0.0) return "";
            if ((double) s.length() * count > Rope.MAX_LENGTH) throw rangeErr("Invalid string length");
            function.realm.agent.checkStringLength((long) ((double) s.length() * count));
            return s.repeat((int) count);
        });
        method(proto, realm, "replace", 2, (function, receiver, arguments, newTarget) -> {
            Ops.requireObjectCoercible(receiver);
            Object search = Values.arg(arguments, 0);
            Object replace = Values.arg(arguments, 1);
            if (search instanceof JSObject searchObject) {
                Object replacer = Ops.getMethod(searchObject, JSSymbol.replace);
                if (replacer != Undefined.INSTANCE) {
                    return Ops.call(replacer, searchObject, new Object[] {receiver, replace});
                }
            }
            String s = Ops.toString(receiver);
            String searchString = Ops.toString(search);
            boolean functionalReplace = Ops.isCallable(replace);
            String replaceString = functionalReplace ? "" : Ops.toString(replace);
            int position = s.indexOf(searchString);
            if (position < 0) return s;
            String replacement = functionalReplace
                    ? Ops.toString(((JSObject) replace).call(Undefined.INSTANCE,
                            new Object[] {searchString, (double) position, s}))
                    : getSubstitution(function.realm, searchString, s, position, List.of(),
                            Undefined.INSTANCE, replaceString);
            return s.substring(0, position) + replacement + s.substring(position + searchString.length());
        });
        method(proto, realm, "replaceAll", 2, (function, receiver, arguments, newTarget) -> {
            Ops.requireObjectCoercible(receiver);
            Object search = Values.arg(arguments, 0);
            Object replace = Values.arg(arguments, 1);
            if (search instanceof JSObject searchObject) {
                if (isRegExp(searchObject)) {
                    Object flags = searchObject.get("flags", searchObject);
                    Ops.requireObjectCoercible(flags);
                    if (Ops.toString(flags).indexOf('g') < 0) {
                        throw typeErr("replaceAll must be called with a global RegExp");
                    }
                }
                Object replacer = Ops.getMethod(searchObject, JSSymbol.replace);
                if (replacer != Undefined.INSTANCE) {
                    return Ops.call(replacer, searchObject, new Object[] {receiver, replace});
                }
            }
            String s = Ops.toString(receiver);
            String searchString = Ops.toString(search);
            boolean functionalReplace = Ops.isCallable(replace);
            String replaceString = functionalReplace ? "" : Ops.toString(replace);
            int advance = Math.max(1, searchString.length());
            ArrayList<Integer> positions = new ArrayList<>();
            int position = s.indexOf(searchString, 0);
            while (position >= 0) {
                positions.add(position);
                position = position + advance > s.length() ? -1 : s.indexOf(searchString, position + advance);
            }
            int end = 0;
            StringBuilder sb = new StringBuilder();
            for (int matchPosition : positions) {
                sb.append(s, end, matchPosition);
                String replacement = functionalReplace
                        ? Ops.toString(((JSObject) replace).call(Undefined.INSTANCE,
                                new Object[] {searchString, (double) matchPosition, s}))
                        : getSubstitution(function.realm, searchString, s, matchPosition, List.of(),
                                Undefined.INSTANCE, replaceString);
                sb.append(replacement);
                end = matchPosition + searchString.length();
            }
            if (end < s.length()) sb.append(s, end, s.length());
            return sb.toString();
        });
        method(proto, realm, "slice", 2, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "slice");
            long length = s.length();
            long from = relIndex(Values.arg(arguments, 0), length, 0);
            long to = relIndex(Values.arg(arguments, 1), length, length);
            if (from >= to) return "";
            return s.substring((int) from, (int) to);
        });
        method(proto, realm, "split", 2, (function, receiver, arguments, newTarget) -> {
            Ops.requireObjectCoercible(receiver);
            Object separator = Values.arg(arguments, 0);
            Object limit = Values.arg(arguments, 1);
            if (separator instanceof JSObject separatorObject) {
                Object split = Ops.getMethod(separatorObject, JSSymbol.split);
                if (split != Undefined.INSTANCE) {
                    return Ops.call(split, separatorObject, new Object[] {receiver, limit});
                }
            }
            String s = Ops.toString(receiver);
            long limitValue = limit == Undefined.INSTANCE ? 4294967295L : Ops.toUint32(limit);
            String separatorString = Ops.toString(separator);
            ArrayList<Object> out = new ArrayList<>();
            if (limitValue == 0L) return Builtins.arrayOf(function.realm, out);
            if (separator == Undefined.INSTANCE) return Builtins.arrayOf(function.realm, List.<Object>of(s));
            if (s.isEmpty()) {
                if (!separatorString.isEmpty()) out.add(s);
                return Builtins.arrayOf(function.realm, out);
            }
            if (separatorString.isEmpty()) {
                for (int index = 0; index < s.length(); index++) {
                    out.add(String.valueOf(s.charAt(index)));
                    if ((long) out.size() >= limitValue) break;
                }
                return Builtins.arrayOf(function.realm, out);
            }
            int start = 0;
            int position = s.indexOf(separatorString, 0);
            while (position >= 0) {
                out.add(s.substring(start, position));
                if ((long) out.size() >= limitValue) return Builtins.arrayOf(function.realm, out);
                start = position + separatorString.length();
                position = s.indexOf(separatorString, start);
            }
            out.add(s.substring(start));
            return Builtins.arrayOf(function.realm, out);
        });
        method(proto, realm, "startsWith", 1, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "startsWith");
            if (isRegExp(Values.arg(arguments, 0))) {
                throw typeErr("First argument to String.prototype.startsWith must not be a regular expression");
            }
            String search = Ops.toString(Values.arg(arguments, 0));
            int start = clamp(Ops.toIntegerOrInfinity(Values.arg(arguments, 1)), s.length());
            return start + search.length() <= s.length() && s.regionMatches(start, search, 0, search.length());
        });
        method(proto, realm, "substring", 2, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "substring");
            int start = clamp(Ops.toIntegerOrInfinity(Values.arg(arguments, 0)), s.length());
            int end = Values.arg(arguments, 1) == Undefined.INSTANCE
                    ? s.length() : clamp(Ops.toIntegerOrInfinity(Values.arg(arguments, 1)), s.length());
            return s.substring(Math.min(start, end), Math.max(start, end));
        });
        method(proto, realm, "substr", 2, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "substr");
            int size = s.length();
            double start = Ops.toIntegerOrInfinity(Values.arg(arguments, 0));
            start = start == Double.NEGATIVE_INFINITY ? 0.0
                    : start < 0 ? Math.max(size + start, 0.0) : Math.min(start, (double) size);
            double length = Values.arg(arguments, 1) == Undefined.INSTANCE
                    ? (double) size : Ops.toIntegerOrInfinity(Values.arg(arguments, 1));
            double end = Math.min(start + length, (double) size);
            if (start >= end) return "";
            return s.substring((int) start, (int) end);
        });
        method(proto, realm, "toLocaleLowerCase", 0,
                (function, receiver, arguments, newTarget) -> toLowerCase(thisStr(receiver, "toLocaleLowerCase")));
        method(proto, realm, "toLocaleUpperCase", 0,
                (function, receiver, arguments, newTarget) -> toUpperCase(thisStr(receiver, "toLocaleUpperCase")));
        method(proto, realm, "toLowerCase", 0,
                (function, receiver, arguments, newTarget) -> toLowerCase(thisStr(receiver, "toLowerCase")));
        method(proto, realm, "toString", 0,
                (function, receiver, arguments, newTarget) -> thisStringValue(receiver, "toString"));
        method(proto, realm, "toUpperCase", 0,
                (function, receiver, arguments, newTarget) -> toUpperCase(thisStr(receiver, "toUpperCase")));
        method(proto, realm, "toWellFormed", 0, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "toWellFormed");
            StringBuilder sb = new StringBuilder(s.length());
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (Character.isSurrogate(c)) sb.append('\uFFFD');
                else sb.append(c);
                i++;
            }
            return sb.toString();
        });
        method(proto, realm, "trim", 0,
                (function, receiver, arguments, newTarget) -> NumberConv.trim(thisStr(receiver, "trim")));
        var trimStart = method(proto, realm, "trimStart", 0, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "trimStart");
            int start = 0;
            while (start < s.length() && NumberConv.isJSWhitespace(s.charAt(start))) start++;
            return s.substring(start);
        });
        var trimEnd = method(proto, realm, "trimEnd", 0, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, "trimEnd");
            int end = s.length();
            while (end > 0 && NumberConv.isJSWhitespace(s.charAt(end - 1))) end--;
            return s.substring(0, end);
        });
        proto.defineOwn("trimLeft", trimStart, Attr.WC);
        proto.defineOwn("trimRight", trimEnd, Attr.WC);
        method(proto, realm, "valueOf", 0,
                (function, receiver, arguments, newTarget) -> thisStringValue(receiver, "valueOf"));

        // Annex B HTML methods
        html(proto, realm, "anchor", "a", "name");
        html(proto, realm, "big", "big", null);
        html(proto, realm, "blink", "blink", null);
        html(proto, realm, "bold", "b", null);
        html(proto, realm, "fixed", "tt", null);
        html(proto, realm, "fontcolor", "font", "color");
        html(proto, realm, "fontsize", "font", "size");
        html(proto, realm, "italics", "i", null);
        html(proto, realm, "link", "a", "href");
        html(proto, realm, "small", "small", null);
        html(proto, realm, "strike", "strike", null);
        html(proto, realm, "sub", "sub", null);
        html(proto, realm, "sup", "sup", null);

        // iterator
        JSObject stringIteratorPrototype = new JSObject(realm.getIteratorPrototype());
        realm.getIntrinsics().put("%StringIteratorPrototype%", stringIteratorPrototype);
        method(stringIteratorPrototype, realm, "next", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof StringIteratorObject iterator)) {
                throw typeErr("next method called on incompatible receiver");
            }
            String s = iterator.s;
            if (s == null || iterator.pos >= s.length()) {
                iterator.s = null;
                return Iteration.createIterResult(function.realm, Undefined.INSTANCE, true);
            }
            int codePoint = s.codePointAt(iterator.pos);
            int count = Character.charCount(codePoint);
            String result = s.substring(iterator.pos, iterator.pos + count);
            iterator.pos += count;
            return Iteration.createIterResult(function.realm, result, false);
        });
        value(stringIteratorPrototype, JSSymbol.toStringTag, "String Iterator", Attr.CONFIGURABLE);
        method(proto, realm, JSSymbol.iterator, 0,
                (function, receiver, arguments, newTarget) ->
                        new StringIteratorObject(stringIteratorPrototype, thisStr(receiver, "[Symbol.iterator]")));
    }

    private static void html(JSObject proto, Realm realm, String name, String tag, String attribute) {
        method(proto, realm, name, attribute != null ? 1 : 0, (function, receiver, arguments, newTarget) -> {
            String s = thisStr(receiver, name);
            StringBuilder sb = new StringBuilder("<").append(tag);
            if (attribute != null) {
                String value = Ops.toString(Values.arg(arguments, 0)).replace("\"", "&quot;");
                sb.append(' ').append(attribute).append("=\"").append(value).append('"');
            }
            return sb.append('>').append(s).append("</").append(tag).append('>').toString();
        });
    }

    static int clamp(double value, int length) {
        return value < 0 ? 0 : value > length ? length : (int) value;
    }

    static boolean isWellFormed(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false;
                i += 2;
                continue;
            }
            if (Character.isLowSurrogate(c)) return false;
            i++;
        }
        return true;
    }

    static JSObject regexpCreate(Realm realm, Object pattern, Object flags) {
        JSObject constructor = realm.intrinsics.get("%RegExp%");
        if (constructor == null) throw typeErr("RegExp is not supported");
        return (JSObject) constructor.construct(new Object[] {pattern, flags}, constructor);
    }

    /** GetSubstitution(matched, str, position, captures, namedCaptures, replacementTemplate). */
    static String getSubstitution(Realm realm, String matched, String string, int position,
                                  List<Object> captures, Object namedCaptures, String template) {
        StringBuilder sb = new StringBuilder();
        int captureCount = captures.size();
        int tailPosition = Math.min(position + matched.length(), string.length());
        int i = 0;
        int length = template.length();
        while (i < length) {
            char c = template.charAt(i);
            if (c != '$' || i + 1 >= length) {
                sb.append(c);
                i++;
                continue;
            }
            char d = template.charAt(i + 1);
            switch (d) {
                case '$' -> {
                    sb.append('$');
                    i += 2;
                }
                case '&' -> {
                    room(realm, sb, matched.length());
                    sb.append(matched);
                    i += 2;
                }
                case '`' -> {
                    int end = Math.min(position, string.length());
                    room(realm, sb, end);
                    sb.append(string, 0, end);
                    i += 2;
                }
                case '\'' -> {
                    if (tailPosition < string.length()) {
                        room(realm, sb, string.length() - tailPosition);
                        sb.append(string, tailPosition, string.length());
                    }
                    i += 2;
                }
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    int digits = 1;
                    int index = d - '0';
                    if (i + 2 < length && template.charAt(i + 2) >= '0' && template.charAt(i + 2) <= '9') {
                        int two = index * 10 + (template.charAt(i + 2) - '0');
                        if (two >= 1 && two <= captureCount) {
                            index = two;
                            digits = 2;
                        }
                    }
                    if (index >= 1 && index <= captureCount) {
                        Object capture = captures.get(index - 1);
                        if (capture != Undefined.INSTANCE) {
                            String text = Ops.toString(capture);
                            room(realm, sb, text.length());
                            sb.append(text);
                        }
                        i += 1 + digits;
                    } else {
                        sb.append('$');
                        i++;
                    }
                }
                case '<' -> {
                    if (namedCaptures == Undefined.INSTANCE) {
                        sb.append("$<");
                        i += 2;
                    } else {
                        int close = template.indexOf('>', i + 2);
                        if (close < 0) {
                            sb.append("$<");
                            i += 2;
                        } else {
                            String groupName = template.substring(i + 2, close);
                            Object capture = Ops.getV(realm, namedCaptures, PK.fromString(groupName));
                            if (capture != Undefined.INSTANCE) sb.append(Ops.toString(capture));
                            i = close + 1;
                        }
                    }
                }
                default -> {
                    sb.append('$');
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private static void room(Realm realm, StringBuilder sb, int extra) {
        long total = (long) sb.length() + extra;
        if (total > 65536) realm.agent.checkStringLength(total);
    }
}
