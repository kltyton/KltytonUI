/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.compiler;

import dev.mooner.neonjs.parser.FunctionNode;
import dev.mooner.neonjs.parser.Program;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiler entry points. */
public final class Compiler {
    public static final Compiler INSTANCE = new Compiler();

    private Compiler() {
    }

    public static CodeBlock compileScript(Program prog, Source source) {
        ScopeAnalyzer an = new ScopeAnalyzer(CodeMode.SCRIPT);
        FnInfo fi = an.analyze(prog);
        return new Emitter(fi, source, an).compileProgram(prog, CodeMode.SCRIPT);
    }

    public static CodeBlock compileEval(Program prog, Source source, boolean direct) {
        CodeMode mode = direct ? CodeMode.EVAL_DIRECT : CodeMode.EVAL_INDIRECT;
        ScopeAnalyzer an = new ScopeAnalyzer(mode, prog.strict);
        FnInfo fi = an.analyze(prog);
        return new Emitter(fi, source, an).compileProgram(prog, mode);
    }

    public static CompiledModule compileModule(Program prog, Source source) {
        ScopeAnalyzer an = new ScopeAnalyzer(CodeMode.MODULE);
        FnInfo fi = an.analyze(prog);
        Scope root = (Scope) prog.scope;
        boolean tla = ModuleInfo.hasTopLevelAwait(prog);
        CodeBlock init = new Emitter(fi, source, an).compileModuleInit(prog);
        CodeBlock body = new Emitter(fi, source, an).compileModuleBody(prog, tla);
        ModuleInfo.Tables tables = ModuleInfo.requestsAndEntries(prog);
        List<ModuleRequest> requests = tables.requests();
        List<ImportEntry> imports = tables.imports();
        List<ExportEntry> exports = tables.exports();
        Map<String, ImportEntry> importedLocal = new LinkedHashMap<>();
        for (ImportEntry entry : imports) {
            importedLocal.put(entry.localName, entry);
        }
        ArrayList<ExportEntry> local = new ArrayList<>();
        ArrayList<ExportEntry> indirect = new ArrayList<>();
        ArrayList<ExportEntry> star = new ArrayList<>();
        for (ExportEntry ee : exports) {
            if (ee.request < 0) {
                ImportEntry ie = importedLocal.get(ee.localName);
                if (ie == null) {
                    local.add(ee);
                } else {
                    indirect.add(new ExportEntry(ee.exportName, ie.request, ie.importName, null));
                }
            } else if (ImportEntry.NAMESPACE.equals(ee.importName) && ee.exportName == null) {
                star.add(ee);
            } else {
                indirect.add(ee);
            }
        }
        return new CompiledModule(init, body, root.info, requests, imports, local, indirect, star, tla, source);
    }

    public static CodeBlock compileFunction(FunctionNode fn, Source source) {
        ScopeAnalyzer an = new ScopeAnalyzer(CodeMode.FUNCTION_CTOR);
        an.analyzeFunction(fn);
        FnInfo fi = ((Scope) fn.scope).fn;
        return new Emitter(fi, source, an).compileFunction(fn, "anonymous", false);
    }
}
