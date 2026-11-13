/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Compiler;
import dev.mooner.neonjs.compiler.Source;
import dev.mooner.neonjs.jit.CodeDefiner;
import dev.mooner.neonjs.parser.JSSyntaxError;
import dev.mooner.neonjs.parser.ParseOptions;
import dev.mooner.neonjs.parser.Parser;
import dev.mooner.neonjs.parser.Program;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entry point: an engine holds shared configuration (execution mode, sandbox policy, host access)
 * and creates isolated {@link NeonContext}s. Engines and compiled scripts are thread-safe;
 * contexts are single-threaded.
 *
 * <p>Derived from NeonJS and adapted to Java; all builder settings and defaults are retained.
 */
public final class NeonEngine implements AutoCloseable {
    public final ExecutionMode executionMode;
    public final int jitThreshold;
    public final boolean backgroundCompilation;
    public final SandboxPolicy sandbox;
    public final HostAccess hostAccess;
    /** Automatically run pending Promise jobs when a top-level evaluation returns. */
    public final boolean autoRunJobs;
    public final NeonConsole console;
    /** Whether contexts get the web platform globals of {@code dev.mooner.neonjs.ext.WebGlobals}. */
    public final boolean webGlobals;
    /** Whether contexts get {@code Intl} (when the neonjs-intl module is on the class path). */
    public final boolean intl;
    /** Defines generated classes (JIT code, Java.extend adapters); null = the default definer. */
    public final CodeDefiner codeDefiner;
    /** Engine modules installed into every context, in order ({@link Builder#extension}). */
    public final List<NeonExtension> extensions;

    private volatile boolean closed;

    private NeonEngine(Builder builder) {
        this.executionMode = builder.executionMode;
        this.jitThreshold = builder.jitThreshold;
        this.backgroundCompilation = builder.backgroundCompilation;
        this.sandbox = builder.sandbox;
        this.hostAccess = builder.hostAccess;
        this.autoRunJobs = builder.autoRunJobs;
        this.console = builder.console;
        this.webGlobals = builder.webGlobals;
        this.intl = builder.intl;
        this.codeDefiner = builder.codeDefiner;
        this.extensions = Collections.unmodifiableList(new ArrayList<>(builder.extensions));
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public int getJitThreshold() {
        return jitThreshold;
    }

    public boolean getBackgroundCompilation() {
        return backgroundCompilation;
    }

    public SandboxPolicy getSandbox() {
        return sandbox;
    }

    public HostAccess getHostAccess() {
        return hostAccess;
    }

    public boolean getAutoRunJobs() {
        return autoRunJobs;
    }

    public NeonConsole getConsole() {
        return console;
    }

    public boolean getWebGlobals() {
        return webGlobals;
    }

    public boolean getIntl() {
        return intl;
    }

    public CodeDefiner getCodeDefiner() {
        return codeDefiner;
    }

    public List<NeonExtension> getExtensions() {
        return extensions;
    }

    public NeonContext newContext() {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
        return new NeonContext(this);
    }

    /** Parses and compiles {@code source} for repeated execution. Throws {@link NeonSyntaxException}. */
    public NeonScript compile(String source, String name) {
        Program program;
        try {
            ParseOptions options = new ParseOptions(false, false, false, false, false, false, true,
                    false, Collections.emptySet(), name, false, true);
            program = Parser.parse(source, options);
        } catch (JSSyntaxError error) {
            throw new NeonSyntaxException(error.getMessage(), error.getLine(), error.getColumn(), name);
        }
        CodeBlock code = Compiler.compileScript(program, new Source(name, source));
        return new NeonScript(this, code, name);
    }

    public NeonScript compile(String source) {
        return compile(source, "<script>");
    }

    @Override
    public void close() {
        closed = true;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NeonEngine create() {
        return new Builder().build();
    }

    public static final class Builder {
        public ExecutionMode executionMode = ExecutionMode.ADAPTIVE;
        public int jitThreshold = 1000;
        public boolean backgroundCompilation = true;
        public SandboxPolicy sandbox = SandboxPolicy.UNRESTRICTED;
        public HostAccess hostAccess = HostAccess.NONE;
        public boolean autoRunJobs = true;
        public NeonConsole console = NeonConsole.STDIO;
        public boolean webGlobals = false;
        public boolean intl = true;
        public CodeDefiner codeDefiner = null;
        public final ArrayList<NeonExtension> extensions = new ArrayList<>();

        public Builder executionMode(ExecutionMode mode) {
            this.executionMode = mode;
            return this;
        }

        /** Where the JS {@code console} object writes; {@code null} leaves {@code console} undefined. Default: stdout/stderr. */
        public Builder console(NeonConsole console) {
            this.console = console;
            return this;
        }

        /**
         * Installs {@code queueMicrotask}, timers, {@code atob}/{@code btoa} and UTF-8
         * {@code TextEncoder}/{@code TextDecoder} in every context. Default: off.
         */
        public Builder webGlobals(boolean enabled) {
            this.webGlobals = enabled;
            return this;
        }

        /**
         * Installs ECMA-402 {@code Intl} and locale-sensitive built-in methods when the
         * neonjs-intl module is on the class path (default).
         */
        public Builder intl(boolean enabled) {
            this.intl = enabled;
            return this;
        }

        /**
         * How classes generated at run time (JIT-compiled functions, {@code Java.extend} adapters)
         * are defined. The default is a supported {@link java.util.ServiceLoader} provider, else
         * the standard JVM definer. Without a usable definer the engine runs interpreted and
         * {@code Java.extend} is unavailable.
         */
        public Builder codeDefiner(CodeDefiner definer) {
            this.codeDefiner = definer;
            return this;
        }

        /**
         * Rhino-style optimization level: -1 = interpreter only, 0..8 = adaptive (lower = compile
         * later), 9 = compile everything eagerly.
         */
        public Builder optimizationLevel(int level) {
            if (level < 0) {
                this.executionMode = ExecutionMode.INTERPRETER;
            } else if (level >= 9) {
                this.executionMode = ExecutionMode.COMPILED;
            } else {
                this.executionMode = ExecutionMode.ADAPTIVE;
                this.jitThreshold = 1 << (2 * (9 - level));
            }
            return this;
        }

        public Builder jitThreshold(int n) {
            this.jitThreshold = n;
            return this;
        }

        /** Whether functions are compiled by background threads (default). */
        public Builder backgroundCompilation(boolean enabled) {
            this.backgroundCompilation = enabled;
            return this;
        }

        public Builder sandbox(SandboxPolicy policy) {
            this.sandbox = policy;
            return this;
        }

        public Builder hostAccess(HostAccess access) {
            this.hostAccess = access;
            return this;
        }

        public Builder autoRunJobs(boolean enabled) {
            this.autoRunJobs = enabled;
            return this;
        }

        /**
         * Installs an engine module into every context, after the built-ins, the console and the
         * web globals. Extensions are never found on the class path: an application opts in here.
         */
        public Builder extension(NeonExtension extension) {
            this.extensions.add(extension);
            return this;
        }

        public NeonEngine build() {
            return new NeonEngine(this);
        }
    }
}
