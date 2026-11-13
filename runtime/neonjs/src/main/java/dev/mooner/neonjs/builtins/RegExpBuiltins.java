/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.compiler.RegExpSite;
import dev.mooner.neonjs.regexp.RegExpEngine;
import dev.mooner.neonjs.regexp.RegExpMatcher;
import dev.mooner.neonjs.regexp.RegExpProgram;
import dev.mooner.neonjs.regexp.RegExpStackOverflowError;
import dev.mooner.neonjs.regexp.RegExpSyntaxError;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.JSArray;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.JSSymbol;
import dev.mooner.neonjs.runtime.NativeFunction;
import dev.mooner.neonjs.runtime.NativeImpl;
import dev.mooner.neonjs.runtime.Null;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PK;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.ProxyObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Values;
import dev.mooner.neonjs.unicode.UnicodeTables;
import dev.mooner.neonjs.vm.Iteration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Supplier;

import static dev.mooner.neonjs.builtins.BuiltinsKt.accessor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.getter;
import static dev.mooner.neonjs.builtins.BuiltinsKt.global;
import static dev.mooner.neonjs.builtins.BuiltinsKt.makeCtor;
import static dev.mooner.neonjs.builtins.BuiltinsKt.method;
import static dev.mooner.neonjs.builtins.BuiltinsKt.typeErr;
import static dev.mooner.neonjs.builtins.BuiltinsKt.value;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_DOTALL;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_GLOBAL;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_IGNORECASE;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_INDICES;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_MULTILINE;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_STICKY;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_UNICODE;
import static dev.mooner.neonjs.regexp.RegExpProgram.FLAG_UNICODE_SETS;

/** The %RegExp% constructor and prototype (ECMA-262 §22.2 plus the legacy static properties). */
final class RegExpBuiltins {
    private static final String PROTO = "%RegExp.prototype%";
    private static final String EXEC = "%RegExp.prototype.exec%";
    private static final String STATICS = "%RegExpLegacyStatics%";

    private RegExpBuiltins() {
    }

    static void install(Realm realm) {
        JSObject proto = new JSObject(realm.getObjectPrototype());
        realm.getIntrinsics().put(PROTO, proto);
        NativeFunction ctor = makeCtor(realm, "RegExp", 2, proto,
                (function, receiver, arguments, newTarget) -> construct(function, arguments, newTarget));
        realm.getIntrinsics().put("%RegExp%", ctor);
        global(realm, "RegExp", ctor);
        getter(ctor, realm, JSSymbol.species, (function, receiver, arguments, newTarget) -> receiver);
        method(ctor, realm, "escape", 1, (function, receiver, arguments, newTarget) -> {
            Object argument = Values.arg(arguments, 0);
            if (!(argument instanceof CharSequence text)) {
                throw typeErr("RegExp.escape requires a string argument");
            }
            return escape(text.toString());
        });

        NativeFunction exec = method(proto, realm, "exec", 1, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof JSRegExp regexp)) {
                throw typeErr("RegExp.prototype.exec called on incompatible receiver " + Ops.describe(receiver));
            }
            return builtinExec(function.realm, regexp, Ops.toString(Values.arg(arguments, 0)));
        });
        realm.getIntrinsics().put(EXEC, exec);
        getter(proto, realm, "dotAll",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_DOTALL, "dotAll"));
        getter(proto, realm, "flags", (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof JSObject regexp)) {
                throw typeErr("RegExp.prototype.flags getter called on non-object " + Ops.describe(receiver));
            }
            StringBuilder sb = new StringBuilder();
            if (Ops.toBoolean(regexp.get("hasIndices", regexp))) sb.append('d');
            if (Ops.toBoolean(regexp.get("global", regexp))) sb.append('g');
            if (Ops.toBoolean(regexp.get("ignoreCase", regexp))) sb.append('i');
            if (Ops.toBoolean(regexp.get("multiline", regexp))) sb.append('m');
            if (Ops.toBoolean(regexp.get("dotAll", regexp))) sb.append('s');
            if (Ops.toBoolean(regexp.get("unicode", regexp))) sb.append('u');
            if (Ops.toBoolean(regexp.get("unicodeSets", regexp))) sb.append('v');
            if (Ops.toBoolean(regexp.get("sticky", regexp))) sb.append('y');
            return sb.toString();
        });
        getter(proto, realm, "global",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_GLOBAL, "global"));
        getter(proto, realm, "hasIndices",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_INDICES, "hasIndices"));
        getter(proto, realm, "ignoreCase",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_IGNORECASE, "ignoreCase"));
        method(proto, realm, JSSymbol.match, 1,
                (function, receiver, arguments, newTarget) -> symbolMatch(function.realm, receiver, Values.arg(arguments, 0)));
        method(proto, realm, JSSymbol.matchAll, 1,
                (function, receiver, arguments, newTarget) -> symbolMatchAll(function.realm, receiver, Values.arg(arguments, 0)));
        getter(proto, realm, "multiline",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_MULTILINE, "multiline"));
        method(proto, realm, JSSymbol.replace, 2, (function, receiver, arguments, newTarget) ->
                symbolReplace(function.realm, receiver, Values.arg(arguments, 0), Values.arg(arguments, 1)));
        method(proto, realm, JSSymbol.search, 1,
                (function, receiver, arguments, newTarget) -> symbolSearch(function.realm, receiver, Values.arg(arguments, 0)));
        getter(proto, realm, "source", (function, receiver, arguments, newTarget) -> {
            if (receiver instanceof JSRegExp regexp) return escapePattern(regexp.originalSource);
            if (!(receiver instanceof JSObject)) {
                throw typeErr("RegExp.prototype.source getter called on non-object " + Ops.describe(receiver));
            }
            if (receiver == function.realm.intrinsic(PROTO)) return "(?:)";
            throw typeErr("RegExp.prototype.source getter called on non-RegExp object");
        });
        method(proto, realm, JSSymbol.split, 2, (function, receiver, arguments, newTarget) ->
                symbolSplit(function.realm, receiver, Values.arg(arguments, 0), Values.arg(arguments, 1)));
        getter(proto, realm, "sticky",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_STICKY, "sticky"));
        method(proto, realm, "test", 1, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof JSObject regexp)) {
                throw typeErr("RegExp.prototype.test called on non-object " + Ops.describe(receiver));
            }
            String string = Ops.toString(Values.arg(arguments, 0));
            if (regexp instanceof JSRegExp jsRegExp
                    && regexp.get("exec", regexp) == function.realm.intrinsics.get(EXEC)) {
                return builtinExec(function.realm, jsRegExp, string, false) != Null.INSTANCE;
            }
            return regExpExec(function.realm, regexp, string) != Null.INSTANCE;
        });
        method(proto, realm, "toString", 0, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof JSObject regexp)) {
                throw typeErr("RegExp.prototype.toString called on non-object " + Ops.describe(receiver));
            }
            String pattern = Ops.toString(regexp.get("source", regexp));
            String flags = Ops.toString(regexp.get("flags", regexp));
            return "/" + pattern + "/" + flags;
        });
        getter(proto, realm, "unicode",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_UNICODE, "unicode"));
        getter(proto, realm, "unicodeSets",
                (function, receiver, arguments, newTarget) -> flagGetter(function, receiver, FLAG_UNICODE_SETS, "unicodeSets"));

        // Annex B
        method(proto, realm, "compile", 2, (function, receiver, arguments, newTarget) -> {
            if (!(receiver instanceof JSRegExp regexp)) {
                throw typeErr("RegExp.prototype.compile called on incompatible receiver " + Ops.describe(receiver));
            }
            if (regexp.realm != function.realm) {
                throw typeErr("RegExp.prototype.compile called on a RegExp of another realm");
            }
            if (!regexp.legacyFeatures) {
                throw typeErr("RegExp.prototype.compile called on a RegExp subclass instance");
            }
            Object pattern = Values.arg(arguments, 0);
            Object flags = Values.arg(arguments, 1);
            if (pattern instanceof JSRegExp patternRegExp) {
                if (flags != Undefined.INSTANCE) {
                    throw typeErr("Cannot supply flags when constructing one RegExp from another");
                }
                return initialize(regexp, patternRegExp.originalSource, patternRegExp.originalFlags);
            }
            return initialize(regexp, pattern, flags);
        });

        // %RegExpStringIteratorPrototype%
        JSObject rsip = new JSObject(realm.getIteratorPrototype());
        realm.getIntrinsics().put("%RegExpStringIteratorPrototype%", rsip);
        method(rsip, realm, "next", 0, (function, receiver, arguments, newTarget) -> iteratorNext(function.realm, receiver));
        value(rsip, JSSymbol.toStringTag, "RegExp String Iterator", Attr.CONFIGURABLE);

        installLegacyStatics(realm, ctor);
        realm.agent.regexpFactory = RegExpBuiltins::literal;
    }

    // ------------------------------------------------------------------ legacy static properties

    private static LegacyRegExpStatics statics(Realm realm) {
        return (LegacyRegExpStatics) realm.intrinsicsAny.computeIfAbsent(STATICS, key -> new LegacyRegExpStatics());
    }

    /**
     * The statics to update after a successful match of {@code r} while {@code realm} is the current realm, or null:
     * after InvalidateLegacyRegExpStaticProperties for a RegExp of this realm without legacy features; untouched for a
     * RegExp of another realm.
     */
    private static LegacyRegExpStatics legacyTarget(Realm realm, JSRegExp r) {
        if (r.realm != realm) return null;
        LegacyRegExpStatics statics = statics(realm);
        if (!r.legacyFeatures) {
            statics.invalidate();
            return null;
        }
        return statics;
    }

    /** The receiver check of Get/SetLegacyRegExpStaticProperty: this must be the accessor realm's %RegExp%. */
    private static LegacyRegExpStatics staticsFor(NativeFunction function, Object receiver, String name) {
        if (receiver != function.realm.intrinsics.get("%RegExp%")) {
            throw typeErr("RegExp." + name + " accessor called on incompatible receiver " + Ops.describe(receiver));
        }
        return statics(function.realm);
    }

    private static String emptySlot(String name) {
        throw typeErr("RegExp." + name + " is unavailable after a match of a RegExp subclass instance");
    }

    /**
     * Getter of a legacy static named by a punctuator ("$&amp;", "$+", "$`", "$'"): such a name is no IdentifierName,
     * so the source text uses a computed property name to remain valid NativeFunction syntax.
     */
    private static final class PunctuatorGetter extends NativeFunction {
        private final String key;

        PunctuatorGetter(Realm realm, String key, NativeImpl impl) {
            super(realm, key, 0, impl, false, realm.getFunctionPrototype(), "get");
            this.key = key;
        }

        @Override
        public String sourceText() {
            return "function get [\"" + key + "\"]() { [native code] }";
        }
    }

    private static void legacyGetter(Realm realm, JSObject ctor, String name, NativeImpl impl) {
        NativeFunction getter;
        if (name.length() == 2 && name.charAt(0) == '$' && !Character.isLetterOrDigit(name.charAt(1))) {
            getter = new PunctuatorGetter(realm, name, impl);
        } else {
            getter = new NativeFunction(realm, name, 0, impl, false, realm.getFunctionPrototype(), "get");
        }
        ctor.defineAccessor(name, getter, Undefined.INSTANCE, Attr.CONFIGURABLE);
    }

    private static void installLegacyStatics(Realm realm, JSObject ctor) {
        for (String name : new String[] {"input", "$_"}) {
            accessor(ctor, realm, name, (function, receiver, arguments, newTarget) -> {
                LegacyRegExpStatics statics = staticsFor(function, receiver, name);
                return statics.input != null ? statics.input : emptySlot(name);
            }, (function, receiver, arguments, newTarget) -> {
                LegacyRegExpStatics statics = staticsFor(function, receiver, name);
                statics.input = Ops.toString(Values.arg(arguments, 0));
                return Undefined.INSTANCE;
            });
        }
        String[] matchNames = {"lastMatch", "$&", "lastParen", "$+"};
        int[] matchSlots = {0, 0, 10, 10};
        for (int index = 0; index < matchNames.length; index++) {
            final String name = matchNames[index];
            final int slot = matchSlots[index];
            legacyGetter(realm, ctor, name, (function, receiver, arguments, newTarget) -> {
                String value = staticsFor(function, receiver, name).slot(slot);
                return value != null ? value : emptySlot(name);
            });
        }
        String[] leftNames = {"leftContext", "$`"};
        for (String name : leftNames) {
            legacyGetter(realm, ctor, name, (function, receiver, arguments, newTarget) -> {
                String value = staticsFor(function, receiver, name).leftContext();
                return value != null ? value : emptySlot(name);
            });
        }
        String[] rightNames = {"rightContext", "$'"};
        for (String name : rightNames) {
            legacyGetter(realm, ctor, name, (function, receiver, arguments, newTarget) -> {
                String value = staticsFor(function, receiver, name).rightContext();
                return value != null ? value : emptySlot(name);
            });
        }
        for (int k = 1; k <= 9; k++) {
            final int slot = k;
            final String name = "$" + k;
            getter(ctor, realm, name, (function, receiver, arguments, newTarget) -> {
                String value = staticsFor(function, receiver, name).slot(slot);
                return value != null ? value : emptySlot(name);
            });
        }
    }

    // ------------------------------------------------------------------ construction

    private static boolean isRegExp(Object value) {
        if (!(value instanceof JSObject object)) return false;
        Object match = object.get(JSSymbol.match, object);
        if (match != Undefined.INSTANCE) return Ops.toBoolean(match);
        return object instanceof JSRegExp;
    }

    private static Object construct(NativeFunction function, Object[] arguments, JSObject newTargetArgument) {
        Object pattern = Values.arg(arguments, 0);
        Object flags = Values.arg(arguments, 1);
        boolean patternIsRegExp = isRegExp(pattern);
        JSObject newTarget;
        if (newTargetArgument == null) {
            newTarget = function;
            if (patternIsRegExp && flags == Undefined.INSTANCE) {
                Object constructor = ((JSObject) pattern).get("constructor", pattern);
                if (constructor == newTarget) return pattern;
            }
        } else {
            newTarget = newTargetArgument;
        }
        Object source;
        Object flagString;
        if (pattern instanceof JSRegExp patternRegExp) {
            source = patternRegExp.originalSource;
            flagString = flags == Undefined.INSTANCE ? patternRegExp.originalFlags : flags;
        } else if (patternIsRegExp) {
            JSObject patternObject = (JSObject) pattern;
            source = patternObject.get("source", patternObject);
            flagString = flags == Undefined.INSTANCE ? patternObject.get("flags", patternObject) : flags;
        } else {
            source = pattern;
            flagString = flags;
        }
        JSRegExp object = new JSRegExp(Ops.getPrototypeFromConstructor(newTarget, realm -> realm.intrinsic(PROTO)));
        object.realm = function.realm;
        object.legacyFeatures = newTarget == function;
        object.defineOwn("lastIndex", Undefined.INSTANCE, Attr.WRITABLE);
        return initialize(object, source, flagString);
    }

    /** RegExpInitialize. */
    private static JSRegExp initialize(JSRegExp object, Object pattern, Object flags) {
        String source = pattern == Undefined.INSTANCE ? "" : Ops.toString(pattern);
        String flagString = flags == Undefined.INSTANCE ? "" : Ops.toString(flags);
        int mask = RegExpProgram.parseFlags(flagString);
        if (mask < 0) {
            throw JSException.syntaxError("Invalid flags supplied to RegExp constructor '" + flagString + "'");
        }
        RegExpProgram program;
        try {
            program = RegExpEngine.compile(source, mask);
        } catch (RegExpSyntaxError error) {
            throw JSException.syntaxError("Invalid regular expression: /" + source + "/" + flagString + ": "
                    + error.getMessage());
        }
        object.originalSource = source;
        object.originalFlags = flagString;
        object.program = program;
        object.setOrThrow("lastIndex", 0.0);
        return object;
    }

    /** Creates the object for a regular expression literal (the program is compiled once per site). */
    private static JSObject literal(Realm realm, RegExpSite site) {
        RegExpProgram program = site.compiled instanceof RegExpProgram compiled ? compiled : null;
        if (program == null) {
            try {
                program = RegExpEngine.compile(site.pattern, site.flags);
            } catch (RegExpSyntaxError error) {
                throw JSException.syntaxError("Invalid regular expression: /" + site.pattern + "/" + site.flags
                        + ": " + error.getMessage());
            }
            site.compiled = program;
        }
        JSRegExp object = new JSRegExp(realm.intrinsic(PROTO));
        object.realm = realm;
        object.legacyFeatures = true;
        object.defineOwn("lastIndex", 0.0, Attr.WRITABLE);
        object.originalSource = site.pattern;
        object.originalFlags = site.flags;
        object.program = program;
        return object;
    }

    private static Object flagGetter(NativeFunction function, Object receiver, int flag, String name) {
        if (receiver instanceof JSRegExp regexp) {
            RegExpProgram program = regexp.program;
            return program != null && (program.flags & flag) != 0;
        }
        if (!(receiver instanceof JSObject)) {
            throw typeErr("RegExp.prototype." + name + " getter called on non-object " + Ops.describe(receiver));
        }
        if (receiver == function.realm.intrinsic(PROTO)) return Undefined.INSTANCE;
        throw typeErr("RegExp.prototype." + name + " getter called on non-RegExp object");
    }

    // ------------------------------------------------------------------ exec

    private static RegExpProgram programOf(JSRegExp regexp) {
        RegExpProgram program = regexp.program;
        if (program == null) throw typeErr("RegExp object is not initialized");
        return program;
    }

    private static RegExpMatcher newMatcher(Realm realm, RegExpProgram program, String string) {
        return program.matcher(string, () -> realm.agent.checkInterrupt());
    }

    private static <T> T guardStack(Supplier<T> block) {
        try {
            return block.get();
        } catch (RegExpStackOverflowError error) {
            throw JSException.rangeError("Maximum call stack size exceeded (regular expression too complex)");
        }
    }

    /** RegExpBuiltinExec(R, S). */
    static Object builtinExec(Realm realm, JSRegExp regexp, String string) {
        return builtinExec(realm, regexp, string, true);
    }

    /** RegExpBuiltinExec(R, S); when {@code !wantResult} returns {@code true} instead of the match array on success. */
    static Object builtinExec(Realm realm, JSRegExp regexp, String string, boolean wantResult) {
        int length = string.length();
        long lastIndex = Ops.toLength(regexp.get("lastIndex", regexp));
        RegExpProgram program = programOf(regexp);
        int flags = program.flags;
        boolean global = (flags & FLAG_GLOBAL) != 0;
        boolean sticky = (flags & FLAG_STICKY) != 0;
        if (!global && !sticky) lastIndex = 0;
        if (lastIndex > length) {
            regexp.setOrThrow("lastIndex", 0.0);
            return Null.INSTANCE;
        }
        RegExpMatcher matcher = newMatcher(realm, program, string);
        int startIndex = (int) lastIndex;
        if (!guardStack(() -> matcher.match(startIndex))) {
            if (global || sticky) regexp.setOrThrow("lastIndex", 0.0);
            return Null.INSTANCE;
        }
        int matchStart = Math.max(matcher.start(0), startIndex);
        int matchEnd = matcher.end(0);
        if (global || sticky) regexp.setOrThrow("lastIndex", (double) matchEnd);
        LegacyRegExpStatics legacy = legacyTarget(realm, regexp);
        if (legacy != null) legacy.update(string, matchStart, matchEnd, matcher, program.captureCount - 1);
        return wantResult ? buildResult(realm, program, string, matcher, matchStart, matchEnd) : true;
    }

    /** True if {@code object}'s "exec" is the intrinsic %RegExp.prototype.exec% data property (no side effects). */
    private static boolean hasBuiltinExec(Realm realm, JSObject object) {
        JSObject exec = realm.intrinsics.get(EXEC);
        if (exec == null) return false;
        JSObject current = object;
        while (current != null) {
            if (current instanceof ProxyObject) return false;
            PropertyDescriptor descriptor = current.getOwnProperty("exec");
            if (descriptor != null) return descriptor.isData() && descriptor.value == exec;
            current = current.getPrototypeOf();
        }
        return false;
    }

    /** Captures of the current match of {@code matcher} as code unit positions (start, end pairs; -1 = undefined). */
    private static int[] capturePositions(RegExpMatcher matcher, int count, int matchStart) {
        int[] positions = new int[2 * count];
        for (int i = 0; i < count; i++) {
            positions[2 * i] = matcher.start(i);
            positions[2 * i + 1] = matcher.end(i);
        }
        positions[0] = matchStart;
        return positions;
    }

    private static JSArray buildResult(Realm realm, RegExpProgram program, String string, RegExpMatcher matcher,
                                       int matchStart, int matchEnd) {
        int count = program.captureCount - 1;
        Object[] values = new Object[count + 1];
        values[0] = string.substring(matchStart, matchEnd);
        for (int i = 1; i <= count; i++) {
            int captureStart = matcher.start(i);
            int captureEnd = matcher.end(i);
            values[i] = captureStart < 0 || captureEnd < 0
                    ? Undefined.INSTANCE : string.substring(captureStart, captureEnd);
        }
        JSArray array = JSArray.of(realm.getArrayPrototype(), values);
        array.defineOwn("index", (double) matchStart, Attr.ALL);
        array.defineOwn("input", string, Attr.ALL);
        String[] names = program.groupNames;
        Object groups = Undefined.INSTANCE;
        // groupNames used for the indices array (null = no group property)
        String[] indexNames = null;
        if (names != null) {
            JSObject groupObject = new JSObject(null);
            groups = groupObject;
            indexNames = new String[count + 1];
            HashSet<String> matched = null;
            for (int i = 1; i <= count; i++) {
                String name = names[i];
                if (name == null) continue;
                if (matched != null && matched.contains(name)) continue;
                Object value = values[i];
                if (value != Undefined.INSTANCE) {
                    if (matched == null) matched = new HashSet<>();
                    matched.add(name);
                }
                groupObject.createDataPropertyOrThrow(PK.fromString(name), value);
                indexNames[i] = name;
            }
        }
        array.defineOwn("groups", groups, Attr.ALL);
        if ((program.flags & FLAG_INDICES) != 0) {
            Object[] pairs = new Object[count + 1];
            pairs[0] = pair(realm, matchStart, matchEnd);
            for (int i = 1; i <= count; i++) {
                int captureStart = matcher.start(i);
                int captureEnd = matcher.end(i);
                pairs[i] = captureStart < 0 || captureEnd < 0
                        ? Undefined.INSTANCE : pair(realm, captureStart, captureEnd);
            }
            JSArray indices = JSArray.of(realm.getArrayPrototype(), pairs);
            Object indexGroups = Undefined.INSTANCE;
            if (indexNames != null) {
                JSObject groupObject = new JSObject(null);
                indexGroups = groupObject;
                for (int i = 1; i <= count; i++) {
                    String name = indexNames[i];
                    if (name == null) continue;
                    groupObject.createDataPropertyOrThrow(PK.fromString(name), pairs[i]);
                }
            }
            indices.defineOwn("groups", indexGroups, Attr.ALL);
            array.defineOwn("indices", indices, Attr.ALL);
        }
        return array;
    }

    private static JSArray pair(Realm realm, int start, int end) {
        return JSArray.of(realm.getArrayPrototype(), new Object[] {(double) start, (double) end});
    }

    /** RegExpExec(R, S). */
    static Object regExpExec(Realm realm, JSObject regexp, String string) {
        final Object exec = regexp.get("exec", regexp);
        if (exec == realm.intrinsics.get(EXEC) && regexp instanceof JSRegExp jsRegExp) {
            return builtinExec(realm, jsRegExp, string);
        }
        if (Ops.isCallable(exec)) {
            Object result = ((JSObject) exec).call(regexp, new Object[] {string});
            if (!(result instanceof JSObject) && result != Null.INSTANCE) {
                throw typeErr("exec result must be an object or null");
            }
            return result;
        }
        if (!(regexp instanceof JSRegExp jsRegExp)) {
            throw typeErr("RegExp exec method called on incompatible receiver " + Ops.describe(regexp));
        }
        return builtinExec(realm, jsRegExp, string);
    }

    /** AdvanceStringIndex(S, index, unicode). */
    private static long advance(String string, long index, boolean unicode) {
        if (!unicode || index + 1 >= string.length()) return index + 1;
        int i = (int) index;
        return Character.isHighSurrogate(string.charAt(i)) && Character.isLowSurrogate(string.charAt(i + 1))
                ? index + 2 : index + 1;
    }

    private static JSObject thisObject(Object receiver, String name) {
        if (receiver instanceof JSObject object) return object;
        throw typeErr("RegExp.prototype[" + name + "] called on non-object " + Ops.describe(receiver));
    }

    private static boolean hasUnicodeFlag(String flags) {
        return flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0;
    }

    // ------------------------------------------------------------------ Symbol methods

    private static Object symbolMatch(Realm realm, Object receiver, Object stringArgument) {
        JSObject regexp = thisObject(receiver, "Symbol.match");
        String string = Ops.toString(stringArgument);
        String flags = Ops.toString(regexp.get("flags", regexp));
        if (flags.indexOf('g') < 0) return regExpExec(realm, regexp, string);
        boolean fullUnicode = hasUnicodeFlag(flags);
        regexp.setOrThrow("lastIndex", 0.0);
        ArrayList<Object> out = new ArrayList<>();
        if (regexp instanceof JSRegExp jsRegExp
                && (programOf(jsRegExp).flags & FLAG_GLOBAL) != 0
                && hasBuiltinExec(realm, jsRegExp)) {
            // no user code can run during the loop: the intermediate lastIndex values are unobservable
            RegExpProgram program = programOf(jsRegExp);
            RegExpMatcher matcher = newMatcher(realm, program, string);
            long lastIndex = 0L;
            LegacyRegExpStatics legacy = null;
            while (lastIndex <= string.length()) {
                int matchIndex = (int) lastIndex;
                if (!guardStack(() -> matcher.match(matchIndex))) break;
                int start = Math.max(matcher.start(0), matchIndex);
                int end = matcher.end(0);
                out.add(string.substring(start, end));
                if (out.size() == 1) legacy = legacyTarget(realm, jsRegExp);
                if (legacy != null) legacy.update(string, start, end, matcher, program.captureCount - 1);
                lastIndex = end == start ? advance(string, end, fullUnicode) : end;
            }
            regexp.setOrThrow("lastIndex", 0.0);
            return out.isEmpty() ? Null.INSTANCE : Builtins.arrayOf(realm, out);
        }
        int iterations = 0;
        while (true) {
            if ((++iterations & 1023) == 0) realm.agent.checkInterrupt();
            Object result = regExpExec(realm, regexp, string);
            if (result == Null.INSTANCE) return out.isEmpty() ? Null.INSTANCE : Builtins.arrayOf(realm, out);
            JSObject resultObject = (JSObject) result;
            String matchString = Ops.toString(resultObject.get(0, resultObject));
            out.add(matchString);
            if (matchString.isEmpty()) {
                long thisIndex = Ops.toLength(regexp.get("lastIndex", regexp));
                regexp.setOrThrow("lastIndex", (double) advance(string, thisIndex, fullUnicode));
            }
        }
    }

    private static Object symbolMatchAll(Realm realm, Object receiver, Object stringArgument) {
        JSObject regexp = thisObject(receiver, "Symbol.matchAll");
        String string = Ops.toString(stringArgument);
        JSObject constructor = Ops.speciesConstructor(regexp, realm.intrinsic("%RegExp%"));
        String flags = Ops.toString(regexp.get("flags", regexp));
        JSObject matcher = (JSObject) Ops.construct(constructor, new Object[] {regexp, flags});
        long lastIndex = Ops.toLength(regexp.get("lastIndex", regexp));
        matcher.setOrThrow("lastIndex", (double) lastIndex);
        return new RegExpStringIterator(realm.intrinsic("%RegExpStringIteratorPrototype%"), matcher, string,
                flags.indexOf('g') >= 0, hasUnicodeFlag(flags));
    }

    private static Object iteratorNext(Realm realm, Object receiver) {
        if (!(receiver instanceof RegExpStringIterator iterator)) {
            throw typeErr("RegExp String Iterator next called on incompatible receiver " + Ops.describe(receiver));
        }
        if (iterator.done) return Iteration.createIterResult(realm, Undefined.INSTANCE, true);
        Object match = regExpExec(realm, iterator.matcher, iterator.string);
        if (match == Null.INSTANCE) {
            iterator.done = true;
            return Iteration.createIterResult(realm, Undefined.INSTANCE, true);
        }
        if (!iterator.global) {
            iterator.done = true;
            return Iteration.createIterResult(realm, match, false);
        }
        JSObject matchObject = (JSObject) match;
        String matchString = Ops.toString(matchObject.get(0, matchObject));
        if (matchString.isEmpty()) {
            JSObject matcher = iterator.matcher;
            long thisIndex = Ops.toLength(matcher.get("lastIndex", matcher));
            matcher.setOrThrow("lastIndex", (double) advance(iterator.string, thisIndex, iterator.fullUnicode));
        }
        return Iteration.createIterResult(realm, match, false);
    }

    private static Object symbolReplace(Realm realm, Object receiver, Object stringArgument, Object replaceValue) {
        JSObject regexp = thisObject(receiver, "Symbol.replace");
        String string = Ops.toString(stringArgument);
        int lengthS = string.length();
        boolean functionalReplace = Ops.isCallable(replaceValue);
        String replaceString = functionalReplace ? "" : Ops.toString(replaceValue);
        String flags = Ops.toString(regexp.get("flags", regexp));
        boolean global = flags.indexOf('g') >= 0;
        boolean fullUnicode = false;
        if (global) {
            fullUnicode = hasUnicodeFlag(flags);
            regexp.setOrThrow("lastIndex", 0.0);
        }
        if (global && regexp instanceof JSRegExp jsRegExp
                && (programOf(jsRegExp).flags & FLAG_GLOBAL) != 0
                && hasBuiltinExec(realm, jsRegExp)) {
            return fastReplaceAll(realm, jsRegExp, string, fullUnicode,
                    functionalReplace ? (JSObject) replaceValue : null, replaceString);
        }
        ArrayList<JSObject> results = new ArrayList<>();
        int iterations = 0;
        while (true) {
            if ((++iterations & 1023) == 0) realm.agent.checkInterrupt();
            Object result = regExpExec(realm, regexp, string);
            if (result == Null.INSTANCE) break;
            JSObject resultObject = (JSObject) result;
            results.add(resultObject);
            if (!global) break;
            String matchString = Ops.toString(resultObject.get(0, resultObject));
            if (matchString.isEmpty()) {
                long thisIndex = Ops.toLength(regexp.get("lastIndex", regexp));
                regexp.setOrThrow("lastIndex", (double) advance(string, thisIndex, fullUnicode));
            }
        }
        StringBuilder accumulator = new StringBuilder();
        int nextSourcePosition = 0;
        for (JSObject result : results) {
            long resultLength = Ops.lengthOfArrayLike(result);
            long captureCount = Math.max(resultLength - 1, 0L);
            String matched = Ops.toString(result.get(0, result));
            int matchLength = matched.length();
            double positionValue = Ops.toIntegerOrInfinity(result.get("index", result));
            if (positionValue < 0.0) positionValue = 0.0;
            else if (positionValue > (double) lengthS) positionValue = lengthS;
            int position = (int) positionValue;
            ArrayList<Object> captures = new ArrayList<>();
            long captureIndex = 1L;
            while (captureIndex <= captureCount) {
                // the result comes from a user-defined exec, so its length is arbitrary
                if ((captureIndex & 1023L) == 1023L) realm.agent.checkInterrupt();
                Object capture = result.get(PK.fromIndex(captureIndex), result);
                if (capture != Undefined.INSTANCE) capture = Ops.toString(capture);
                captures.add(capture);
                captureIndex++;
            }
            Object namedCaptures = result.get("groups", result);
            String replacement;
            if (functionalReplace) {
                ArrayList<Object> replacerArguments = new ArrayList<>(captures.size() + 4);
                replacerArguments.add(matched);
                replacerArguments.addAll(captures);
                replacerArguments.add((double) position);
                replacerArguments.add(string);
                if (namedCaptures != Undefined.INSTANCE) replacerArguments.add(namedCaptures);
                replacement = Ops.toString(((JSObject) replaceValue).call(Undefined.INSTANCE,
                        replacerArguments.toArray()));
            } else {
                if (namedCaptures != Undefined.INSTANCE) namedCaptures = Ops.toObject(namedCaptures);
                replacement = StringBuiltins.getSubstitution(realm, matched, string, position, captures,
                        namedCaptures, replaceString);
            }
            if (position >= nextSourcePosition) {
                long total = (long) accumulator.length() + (position - nextSourcePosition) + replacement.length();
                if (total > 65536) realm.agent.checkStringLength(total);
                accumulator.append(string, nextSourcePosition, position).append(replacement);
                nextSourcePosition = position + matchLength;
            }
        }
        if (nextSourcePosition >= lengthS) return accumulator.toString();
        return accumulator.append(string, nextSourcePosition, lengthS).toString();
    }

    /**
     * Global {@code RegExp.prototype[Symbol.replace]} for a regexp using the builtin exec: same observable behaviour
     * as the generic algorithm, without materializing the intermediate match objects.
     */
    private static String fastReplaceAll(Realm realm, JSRegExp regexp, String string, boolean fullUnicode,
                                         JSObject function, String template) {
        RegExpProgram program = programOf(regexp);
        int count = program.captureCount;
        RegExpMatcher matcher = newMatcher(realm, program, string);
        ArrayList<int[]> matches = new ArrayList<>();
        long lastIndex = 0L;
        while (lastIndex <= string.length()) {
            int matchIndex = (int) lastIndex;
            if (!guardStack(() -> matcher.match(matchIndex))) break;
            int start = Math.max(matcher.start(0), matchIndex);
            int end = matcher.end(0);
            matches.add(capturePositions(matcher, count, start));
            lastIndex = end == start ? advance(string, end, fullUnicode) : end;
        }
        if (!matches.isEmpty()) {
            LegacyRegExpStatics legacy = legacyTarget(realm, regexp);
            if (legacy != null) legacy.update(string, matches.get(matches.size() - 1));
        }
        regexp.setOrThrow("lastIndex", 0.0);
        String[] names = program.groupNames;
        boolean simpleTemplate = function == null && template.indexOf('$') < 0;
        StringBuilder accumulator = new StringBuilder();
        int nextSourcePosition = 0;
        for (int[] capturesPositions : matches) {
            int position = capturesPositions[0];
            String matched = string.substring(position, capturesPositions[1]);
            String replacement;
            if (simpleTemplate) {
                replacement = template;
            } else {
                ArrayList<Object> captures = new ArrayList<>(count);
                for (int i = 1; i < count; i++) {
                    int captureStart = capturesPositions[2 * i];
                    int captureEnd = capturesPositions[2 * i + 1];
                    captures.add(captureStart < 0 || captureEnd < 0
                            ? Undefined.INSTANCE : string.substring(captureStart, captureEnd));
                }
                Object groups = Undefined.INSTANCE;
                if (names != null) {
                    JSObject groupObject = new JSObject(null);
                    HashSet<String> matchedNames = null;
                    for (int i = 1; i < count; i++) {
                        String name = names[i];
                        if (name == null) continue;
                        if (matchedNames != null && matchedNames.contains(name)) continue;
                        Object capture = captures.get(i - 1);
                        if (capture != Undefined.INSTANCE) {
                            if (matchedNames == null) matchedNames = new HashSet<>();
                            matchedNames.add(name);
                        }
                        groupObject.createDataPropertyOrThrow(PK.fromString(name), capture);
                    }
                    groups = groupObject;
                }
                if (function != null) {
                    ArrayList<Object> arguments = new ArrayList<>(count + 3);
                    arguments.add(matched);
                    arguments.addAll(captures);
                    arguments.add((double) position);
                    arguments.add(string);
                    if (groups != Undefined.INSTANCE) arguments.add(groups);
                    replacement = Ops.toString(function.call(Undefined.INSTANCE, arguments.toArray()));
                } else {
                    replacement = StringBuiltins.getSubstitution(realm, matched, string, position, captures,
                            groups, template);
                }
            }
            if (position >= nextSourcePosition) {
                long total = (long) accumulator.length() + (position - nextSourcePosition) + replacement.length();
                if (total > 65536) realm.agent.checkStringLength(total);
                accumulator.append(string, nextSourcePosition, position).append(replacement);
                nextSourcePosition = position + matched.length();
            }
        }
        if (nextSourcePosition < string.length()) accumulator.append(string, nextSourcePosition, string.length());
        return accumulator.toString();
    }

    private static Object symbolSearch(Realm realm, Object receiver, Object stringArgument) {
        JSObject regexp = thisObject(receiver, "Symbol.search");
        String string = Ops.toString(stringArgument);
        Object previousLastIndex = regexp.get("lastIndex", regexp);
        if (!Ops.sameValue(previousLastIndex, 0.0)) regexp.setOrThrow("lastIndex", 0.0);
        Object result = regExpExec(realm, regexp, string);
        Object currentLastIndex = regexp.get("lastIndex", regexp);
        if (!Ops.sameValue(currentLastIndex, previousLastIndex)) {
            regexp.setOrThrow("lastIndex", previousLastIndex);
        }
        if (result == Null.INSTANCE) return -1.0;
        JSObject resultObject = (JSObject) result;
        return resultObject.get("index", resultObject);
    }

    private static Object symbolSplit(Realm realm, Object receiver, Object stringArgument, Object limit) {
        JSObject regexp = thisObject(receiver, "Symbol.split");
        String string = Ops.toString(stringArgument);
        JSObject constructor = Ops.speciesConstructor(regexp, realm.intrinsic("%RegExp%"));
        String flags = Ops.toString(regexp.get("flags", regexp));
        boolean unicodeMatching = hasUnicodeFlag(flags);
        String newFlags = flags.indexOf('y') >= 0 ? flags : flags + "y";
        JSObject splitter = (JSObject) Ops.construct(constructor, new Object[] {regexp, newFlags});
        ArrayList<Object> out = new ArrayList<>();
        long limitValue = limit == Undefined.INSTANCE ? 4294967295L : Ops.toUint32(limit);
        if (limitValue == 0L) return Builtins.arrayOf(realm, out);
        int size = string.length();
        if (size == 0) {
            Object result = regExpExec(realm, splitter, string);
            if (result != Null.INSTANCE) return Builtins.arrayOf(realm, out);
            out.add(string);
            return Builtins.arrayOf(realm, out);
        }
        if (constructor == realm.intrinsic("%RegExp%") && splitter instanceof JSRegExp jsSplitter
                && hasBuiltinExec(realm, jsSplitter)) {
            // the splitter is not reachable from user code: search directly instead of trying each position
            return fastSplit(realm, jsSplitter, string, limitValue, unicodeMatching);
        }
        int p = 0;
        long q = 0L;
        int iterations = 0;
        while (q < size) {
            if ((++iterations & 1023) == 0) realm.agent.checkInterrupt();
            splitter.setOrThrow("lastIndex", (double) q);
            Object result = regExpExec(realm, splitter, string);
            if (result == Null.INSTANCE) {
                q = advance(string, q, unicodeMatching);
                continue;
            }
            JSObject resultObject = (JSObject) result;
            long end = Math.min(Ops.toLength(splitter.get("lastIndex", splitter)), (long) size);
            if (end == (long) p) {
                q = advance(string, q, unicodeMatching);
                continue;
            }
            out.add(string.substring(p, (int) q));
            if ((long) out.size() == limitValue) return Builtins.arrayOf(realm, out);
            p = (int) end;
            long captureCount = Math.max(Ops.lengthOfArrayLike(resultObject) - 1, 0L);
            long captureIndex = 1L;
            while (captureIndex <= captureCount) {
                out.add(resultObject.get(PK.fromIndex(captureIndex), resultObject));
                if ((long) out.size() == limitValue) return Builtins.arrayOf(realm, out);
                captureIndex++;
            }
            q = p;
        }
        out.add(string.substring(p, size));
        return Builtins.arrayOf(realm, out);
    }

    private static JSArray fastSplit(Realm realm, JSRegExp splitter, String string, long limit,
                                     boolean unicodeMatching) {
        RegExpProgram program = programOf(splitter);
        RegExpMatcher matcher = newMatcher(realm, program, string);
        int size = string.length();
        ArrayList<Object> out = new ArrayList<>();
        int p = 0;
        int q = 0;
        LegacyRegExpStatics legacy = null;
        boolean matched = false;
        while (q < size) {
            int searchIndex = q;
            if (!guardStack(() -> matcher.search(searchIndex))) break;
            int matchStart = Math.max(matcher.start(0), q);
            if (matchStart >= size) break;
            if (!matched) {
                matched = true;
                legacy = legacyTarget(realm, splitter);
            }
            if (legacy != null) legacy.update(string, matchStart, matcher.end(0), matcher, program.captureCount - 1);
            int end = Math.min(matcher.end(0), size);
            if (end == p) {
                q = (int) advance(string, matchStart, unicodeMatching);
                continue;
            }
            out.add(string.substring(p, matchStart));
            if ((long) out.size() == limit) return Builtins.arrayOf(realm, out);
            p = end;
            for (int i = 1; i < program.captureCount; i++) {
                int captureStart = matcher.start(i);
                int captureEnd = matcher.end(i);
                out.add(captureStart < 0 || captureEnd < 0
                        ? Undefined.INSTANCE : string.substring(captureStart, captureEnd));
                if ((long) out.size() == limit) return Builtins.arrayOf(realm, out);
            }
            q = p;
        }
        out.add(string.substring(p, size));
        return Builtins.arrayOf(realm, out);
    }

    // ------------------------------------------------------------------ source / escape

    /** EscapeRegExpPattern: escapes '/' and line terminators so that {@code /source/flags} parses as an equivalent literal. */
    static String escapePattern(String source) {
        if (source.isEmpty()) return "(?:)";
        boolean needs = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' || c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
                needs = true;
                break;
            }
        }
        if (!needs) return source;
        StringBuilder sb = new StringBuilder(source.length() + 8);
        boolean inClass = false;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                char d = source.charAt(i + 1);
                sb.append('\\');
                switch (d) {
                    case '\n' -> sb.append('n');
                    case '\r' -> sb.append('r');
                    case '\u2028' -> sb.append("u2028");
                    case '\u2029' -> sb.append("u2029");
                    default -> sb.append(d);
                }
                i += 2;
                continue;
            }
            switch (c) {
                case '/' -> {
                    if (inClass) sb.append(c);
                    else sb.append("\\/");
                }
                case '[' -> {
                    inClass = true;
                    sb.append(c);
                }
                case ']' -> {
                    inClass = false;
                    sb.append(c);
                }
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    /** RegExp.escape(S). */
    private static String escape(String string) {
        StringBuilder sb = new StringBuilder(string.length() * 2);
        int i = 0;
        while (i < string.length()) {
            int codePoint = string.codePointAt(i);
            int count = Character.charCount(codePoint);
            if (sb.length() == 0 && (codePoint >= '0' && codePoint <= '9'
                    || codePoint >= 'a' && codePoint <= 'z'
                    || codePoint >= 'A' && codePoint <= 'Z')) {
                sb.append("\\x").append(Integer.toHexString(codePoint));
            } else {
                encodeForEscape(codePoint, sb);
            }
            i += count;
        }
        return sb.toString();
    }

    private static final String SYNTAX_CHARS = "^$" + "\\" + ".*+?()[]{}|/";
    private static final String OTHER_PUNCTUATORS = ",-=<>#&!%:;@~'`\"";

    private static void encodeForEscape(int codePoint, StringBuilder sb) {
        if (codePoint < 128 && SYNTAX_CHARS.indexOf((char) codePoint) >= 0) {
            sb.append('\\').append((char) codePoint);
            return;
        }
        switch (codePoint) {
            case 9 -> {
                sb.append("\\t");
                return;
            }
            case 10 -> {
                sb.append("\\n");
                return;
            }
            case 11 -> {
                sb.append("\\v");
                return;
            }
            case 12 -> {
                sb.append("\\f");
                return;
            }
            case 13 -> {
                sb.append("\\r");
                return;
            }
            default -> {
            }
        }
        boolean isWhite = codePoint == 0xFEFF || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint == 0xA0 || codePoint == 0x20
                || (codePoint > 127 && UnicodeTables.isSpaceSeparator(codePoint));
        if ((codePoint < 128 && OTHER_PUNCTUATORS.indexOf((char) codePoint) >= 0)
                || isWhite || codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            if (codePoint <= 0xFF) {
                sb.append("\\x");
                if (codePoint < 16) sb.append('0');
                sb.append(Integer.toHexString(codePoint));
                return;
            }
            for (char unit : Character.toChars(codePoint)) {
                sb.append("\\u");
                String hex = Integer.toHexString(unit);
                for (int padding = hex.length(); padding < 4; padding++) sb.append('0');
                sb.append(hex);
            }
            return;
        }
        sb.appendCodePoint(codePoint);
    }
}
