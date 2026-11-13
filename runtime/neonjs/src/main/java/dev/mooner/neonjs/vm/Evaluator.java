package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Compiler;
import dev.mooner.neonjs.compiler.DeclInfo;
import dev.mooner.neonjs.compiler.ScopeInfo;
import dev.mooner.neonjs.compiler.ScopeKind;
import dev.mooner.neonjs.compiler.Source;
import dev.mooner.neonjs.jit.Jit;
import dev.mooner.neonjs.parser.FunctionNode;
import dev.mooner.neonjs.parser.JSSyntaxError;
import dev.mooner.neonjs.parser.ParseOptions;
import dev.mooner.neonjs.parser.Parser;
import dev.mooner.neonjs.parser.Program;
import dev.mooner.neonjs.runtime.Attr;
import dev.mooner.neonjs.runtime.DeclEnv;
import dev.mooner.neonjs.runtime.Env;
import dev.mooner.neonjs.runtime.ErrorKind;
import dev.mooner.neonjs.runtime.GlobalBinding;
import dev.mooner.neonjs.runtime.GlobalEnv;
import dev.mooner.neonjs.runtime.JSException;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.PropertyDescriptor;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import dev.mooner.neonjs.runtime.Uninitialized;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;
import static dev.mooner.neonjs.runtime.Values.arg;

/** Script, eval and declaration instantiation; derived from NeonJS and adapted to Java. */
public final class Evaluator {
    private Evaluator() {
    }

    public static JSException syntaxError(Realm realm, JSSyntaxError error) {
        return new JSException(realm.newError(ErrorKind.SYNTAX, error.getRawMessage()));
    }

    public static CodeBlock compileScript(Realm realm, String source, String name) {
        Program program;
        try {
            program = Parser.parse(source, new ParseOptions(false, false, false, false, false, false,
                    true, false, Set.of(), name, false, realm.agent.config.annexB));
        } catch (JSSyntaxError error) {
            throw syntaxError(realm, error);
        }
        return Compiler.compileScript(program, new Source(name, source));
    }

    public static Object evaluateScript(Realm realm, String source, String name) {
        return runScript(realm, compileScript(realm, source, name));
    }

    public static Object runScript(Realm realm, CodeBlock code) {
        if (realm.agent.config.executionMode == Jit.MODE_COMPILED) Jit.prepare(code, realm.agent);
        GlobalEnv global = realm.getGlobalEnv();
        return Interpreter.execute(new Frame(null, code, realm, global.thisValue, EMPTY_ARGS, Undefined.INSTANCE, global));
    }

    private static void checkCodeGen(Realm realm) {
        if (!realm.agent.config.allowCodeGeneration) {
            throw new JSException(realm.newError(ErrorKind.EVAL, "Code generation from strings is disallowed in this context"));
        }
    }

    public static Object indirectEval(Realm realm, Object value) {
        if (!(value instanceof CharSequence)) return value;
        checkCodeGen(realm);
        String text = value.toString();
        Program program;
        try {
            program = Parser.parse(text, new ParseOptions(false, false, false, false, false, false,
                    true, false, Set.of(), "eval", false, true));
        } catch (JSSyntaxError error) {
            throw syntaxError(realm, error);
        }
        CodeBlock code = Compiler.compileEval(program, new Source("eval", text), false);
        GlobalEnv global = realm.getGlobalEnv();
        return Interpreter.execute(new Frame(null, code, realm, global.thisValue, EMPTY_ARGS, Undefined.INSTANCE, global));
    }

    public static Object directEval(Frame caller, Object[] arguments, boolean strictCaller) {
        Object value = arg(arguments, 0);
        if (!(value instanceof CharSequence)) return value;
        Realm realm = caller.realm;
        checkCodeGen(realm);
        String text = value.toString();
        int context = caller.code.evalCtx;
        ParseOptions options = new ParseOptions(false, strictCaller, false,
                (context & CodeBlock.CTX_NEW_TARGET) != 0, (context & CodeBlock.CTX_SUPER_PROP) != 0,
                (context & CodeBlock.CTX_SUPER_CALL) != 0, (context & CodeBlock.CTX_FIELD_INIT) == 0,
                (context & CodeBlock.CTX_FIELD_INIT) != 0,
                caller.code.privateNames == null ? Set.of() : caller.code.privateNames, "eval", false, true);
        Program program;
        try {
            program = Parser.parse(text, options);
        } catch (JSSyntaxError error) {
            throw syntaxError(realm, error);
        }
        CodeBlock code = Compiler.compileEval(program, new Source("eval", text), true);
        code.evalCtx = context;
        code.privateNames = caller.code.privateNames;
        Frame frame = new Frame(caller.fn, code, realm, caller.thisValue, caller.args, caller.newTarget, caller.env);
        frame.setHomeObject(caller.fn != null && caller.fn.homeObject != null ? caller.fn.homeObject : caller.getHomeObject());
        return Interpreter.execute(frame);
    }

    private static boolean canDeclareGlobalFunction(JSObject global, String name) {
        PropertyDescriptor existing = global.getOwnProperty(name);
        return existing == null ? global.isExtensible()
                : existing.configurable || existing.isData() && existing.writable && existing.enumerable;
    }

    private static boolean canDeclareGlobalVar(JSObject global, String name) {
        return global.hasOwnProperty(name) || global.isExtensible();
    }

    private static void createGlobalFunctionBinding(GlobalEnv environment, String name, Object function, boolean deletable) {
        JSObject global = environment.global;
        PropertyDescriptor existing = global.getOwnProperty(name);
        PropertyDescriptor descriptor = existing == null || existing.configurable
                ? PropertyDescriptor.data(function, Attr.WRITABLE | Attr.ENUMERABLE | (deletable ? Attr.CONFIGURABLE : 0))
                : new PropertyDescriptor().value(function);
        global.definePropertyOrThrow(name, descriptor);
        if (!global.set(name, function, global)) throw JSException.typeError("Cannot assign to read only property '" + name + "'");
        environment.varNames.add(name);
    }

    private static void createGlobalVarBinding(GlobalEnv environment, String name, boolean deletable) {
        JSObject global = environment.global;
        boolean exists = global.hasOwnProperty(name);
        boolean extensible = global.isExtensible();
        if (!exists && extensible) {
            global.definePropertyOrThrow(name, PropertyDescriptor.data(Undefined.INSTANCE,
                    Attr.WRITABLE | Attr.ENUMERABLE | (deletable ? Attr.CONFIGURABLE : 0)));
        }
        environment.varNames.add(name);
    }

    private static boolean hasRestrictedGlobalProperty(JSObject global, String name) {
        PropertyDescriptor descriptor = global.getOwnProperty(name);
        return descriptor != null && !descriptor.configurable;
    }

    public static void globalDeclarationInstantiation(Realm realm, DeclInfo info, Object[] functions) {
        GlobalEnv environment = realm.getGlobalEnv();
        JSObject global = environment.global;
        String[] lexical = info.getLexNames();
        String[] variables = info.getVarNames();
        String[] functionNames = info.getFunctionNames();
        for (String name : lexical) {
            if (environment.lexical.containsKey(name) || hasRestrictedGlobalProperty(global, name)) {
                throw JSException.syntaxError("Identifier '" + name + "' has already been declared");
            }
        }
        for (String name : variables) {
            if (environment.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '" + name + "' has already been declared");
        }
        for (String name : functionNames) {
            if (environment.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '" + name + "' has already been declared");
        }
        for (int i = functionNames.length - 1; i >= 0; i--) {
            if (!canDeclareGlobalFunction(global, functionNames[i])) throw JSException.typeError("Cannot declare global function '" + functionNames[i] + "'");
        }
        HashSet<String> declaredFunctions = new HashSet<>(Arrays.asList(functionNames));
        for (String name : variables) {
            if (!declaredFunctions.contains(name) && !canDeclareGlobalVar(global, name)) {
                throw JSException.typeError("Cannot declare global variable '" + name + "'");
            }
        }
        for (int i = 0; i < lexical.length; i++) {
            environment.declareLexical(lexical[i], new GlobalBinding(Uninitialized.INSTANCE, !info.getLexConst()[i]));
        }
        for (int i = 0; i < functionNames.length; i++) createGlobalFunctionBinding(environment, functionNames[i], functions[i], false);
        for (String name : variables) {
            if (declaredFunctions.contains(name)) continue;
            if (containsName(info.getAnnexBNames(), name)
                    && (environment.lexical.containsKey(name) || !canDeclareGlobalVar(global, name))) continue;
            createGlobalVarBinding(environment, name, false);
        }
    }

    private static Env varEnvOf(Env environment) {
        for (Env current = environment; current != null; current = current.parent) {
            if (current instanceof DeclEnv declared && declared.info.evalVarTarget) return declared;
            if (current instanceof GlobalEnv) return current;
        }
        return null;
    }

    public static void evalDeclarationInstantiation(Frame frame, DeclInfo info, Object[] functions) {
        Realm realm = frame.realm;
        Env lexical = frame.env;
        Env variable = varEnvOf(lexical);
        if (variable == null) variable = realm.getGlobalEnv();
        String[] functionNames = info.getFunctionNames();
        String[] variables = info.getVarNames();
        String[] annexB = info.getAnnexBNames();
        LinkedHashSet<String> allNames = new LinkedHashSet<>(Arrays.asList(functionNames));
        allNames.addAll(Arrays.asList(variables));
        if (!info.isStrict()) {
            if (variable instanceof GlobalEnv global) {
                for (String name : allNames) {
                    if (global.lexical.containsKey(name)) throw JSException.syntaxError("Identifier '" + name + "' has already been declared");
                }
            }
            for (Env current = lexical; current != null; current = current.parent) {
                if (current instanceof DeclEnv declared && !(declared == frame.env && declared.info.kind == ScopeKind.EVAL)) {
                    for (String name : allNames) {
                        int index = declared.info.lookup(name);
                        if (index < 0) continue;
                        int flags = declared.info.flags[index];
                        if ((flags & ScopeInfo.F_CATCH) != 0) continue;
                        if (declared == variable && (flags & ScopeInfo.F_LEXICAL) == 0) continue;
                        if (declared != variable || (flags & ScopeInfo.F_LEXICAL) != 0) {
                            if (containsName(annexB, name) && !containsName(functionNames, name) && !containsName(variables, name)) continue;
                            throw JSException.syntaxError("Identifier '" + name + "' has already been declared");
                        }
                    }
                }
                if (current == variable) break;
            }
        }
        if (variable instanceof GlobalEnv global) {
            for (int i = functionNames.length - 1; i >= 0; i--) {
                if (!canDeclareGlobalFunction(global.global, functionNames[i])) throw JSException.typeError("Cannot declare global function '" + functionNames[i] + "'");
            }
            for (String name : variables) {
                if (containsName(functionNames, name) || containsName(annexB, name)) continue;
                if (!canDeclareGlobalVar(global.global, name)) throw JSException.typeError("Cannot declare global variable '" + name + "'");
            }
            for (int i = 0; i < functionNames.length; i++) createGlobalFunctionBinding(global, functionNames[i], functions[i], true);
            for (String name : variables) {
                if (containsName(functionNames, name)) continue;
                if (containsName(annexB, name) && (global.lexical.containsKey(name) || !canDeclareGlobalVar(global.global, name))) continue;
                createGlobalVarBinding(global, name, true);
            }
            return;
        }
        DeclEnv declared = (DeclEnv) variable;
        for (int i = 0; i < functionNames.length; i++) setOrCreateVar(declared, functionNames[i], functions[i]);
        for (String name : variables) {
            if (containsName(functionNames, name) || declared.info.lookup(name) >= 0) continue;
            if (declared.extension == null) declared.extension = new LinkedHashMap<>();
            if (!declared.extension.containsKey(name)) declared.extension.put(name, Undefined.INSTANCE);
        }
    }

    private static void setOrCreateVar(DeclEnv environment, String name, Object value) {
        int index = environment.info.lookup(name);
        if (index >= 0) environment.slots[index] = value;
        else {
            if (environment.extension == null) environment.extension = new LinkedHashMap<>();
            environment.extension.put(name, value);
        }
    }

    private static boolean containsName(String[] names, String name) {
        for (String candidate : names) if (candidate.equals(name)) return true;
        return false;
    }

    public static JSObject createDynamicFunction(Realm realm, JSObject newTarget, String kind, Object[] arguments) {
        checkCodeGen(realm);
        StringBuilder parameters = new StringBuilder();
        for (int i = 0; i < arguments.length - 1; i++) {
            if (i > 0) parameters.append(',');
            parameters.append(Ops.toString(arguments[i]));
        }
        String body = arguments.length > 0 ? Ops.toString(arguments[arguments.length - 1]) : "";
        boolean async = kind.equals("async") || kind.equals("asyncGenerator");
        boolean generator = kind.equals("generator") || kind.equals("asyncGenerator");
        Parser.DynamicFunction parsed;
        try {
            parsed = Parser.parseDynamicFunction(parameters.toString(), body, async, generator);
        } catch (JSSyntaxError error) {
            throw syntaxError(realm, error);
        }
        FunctionNode node = parsed.function();
        String text = parsed.source();
        Source source = new Source("anonymous", text);
        node.srcStart = 0;
        node.srcEnd = text.length();
        CodeBlock code = Compiler.compileFunction(node, source);
        code.srcStart = 0;
        code.srcEnd = text.length();
        Jit.prefetch(code, realm.agent);
        JSObject prototype = newTarget == null ? functionPrototype(realm, kind)
                : Ops.getPrototypeFromConstructor(newTarget, targetRealm -> functionPrototype(targetRealm, kind));
        JSClosure function = Rt.makeClosure(realm, code, realm.getGlobalEnv(), null);
        function.changeProto(prototype);
        return function;
    }

    private static JSObject functionPrototype(Realm realm, String kind) {
        return switch (kind) {
            case "generator" -> realm.getGeneratorFunctionPrototype();
            case "async" -> realm.getAsyncFunctionPrototype();
            case "asyncGenerator" -> realm.getAsyncGeneratorFunctionPrototype();
            default -> realm.getFunctionPrototype();
        };
    }
}
