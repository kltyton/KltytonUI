/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Op;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

/**
 * Translates NeonJS stack bytecode into a JVM class implementing {@link CompiledCode}. The JS operand stack maps onto
 * the JVM operand stack, registers onto JVM locals, and exception handler ranges onto JVM exception tables; complex
 * instructions call {@link JitRt}. Generator and async functions are not compiled (they stay interpreted).
 */
public final class JvmCompiler {
    private static final System.Logger LOGGER = System.getLogger(JvmCompiler.class.getName());
    public static final JvmCompiler INSTANCE = new JvmCompiler();

    private static final AtomicLong counter = new AtomicLong();

    /** Classes generated and defined. */
    public static final AtomicLong compiledCount = new AtomicLong();
    /** Code blocks given code compiled earlier for another block with the same identity. */
    public static final AtomicLong sharedCount = new AtomicLong();
    public static final AtomicLong failedCount = new AtomicLong();
    public static final ConcurrentHashMap<String, AtomicLong> failureReasons = new ConcurrentHashMap<>();

    private static final String FRAME = "dev/mooner/neonjs/vm/Frame";
    private static final String RT = "dev/mooner/neonjs/jit/JitRt";
    private static final String OBJ = "Ljava/lang/Object;";
    private static final String FRAME_D = "Ldev/mooner/neonjs/vm/Frame;";
    private static final String AGENT = "dev/mooner/neonjs/runtime/Agent";
    private static final String CLOSURE = "dev/mooner/neonjs/vm/JSClosure";
    private static final String CLOSURE_D = "Ldev/mooner/neonjs/vm/JSClosure;";
    private static final String CODEBLOCK = "dev/mooner/neonjs/compiler/CodeBlock";
    private static final String CODEBLOCK_D = "Ldev/mooner/neonjs/compiler/CodeBlock;";
    private static final String REALM_D = "Ldev/mooner/neonjs/runtime/Realm;";
    private static final String AGENT_D = "Ldev/mooner/neonjs/runtime/Agent;";
    private static final String CONSTS_D = "[Ljava/lang/Object;";

    private static final int L_FRAME = 1;
    private static final int L_CONSTS = 2;
    private static final int L_AGENT = 3;
    private static final int L_BUDGET = 4;
    private static final int L_REGS = 5;

    /** Most instructions after a GET_ELEM compiled twice (Gen.planSplit). */
    private static final int MAX_SPLIT = 6;

    /** Binary name of the class {@link CodeDefiners#isUsable} defines to test a definer. */
    public static final String PROBE_CLASS = "dev.mooner.neonjs.jit.JSProbe";

    /** Whether generated classes carry the source file name and line numbers (JVM debug info). */
    public static final boolean DEBUG_INFO = JvmCodeDefiner.VISIBLE_CLASSES
            || System.getProperty("neonjs.jit.debugInfo") != null;

    /** Whether generated code keeps numbers in unboxed {@code double} locals. */
    public static final boolean TYPED = !"false".equals(System.getProperty("neonjs.jit.typed"));

    /** Whether unboxed code also keeps int32 values in {@code int} locals. */
    public static final boolean INT32 = !"false".equals(System.getProperty("neonjs.jit.int32"));

    /** Whether unboxed code stores numbers into typed arrays without boxing them. */
    public static final boolean ELEMS = !"false".equals(System.getProperty("neonjs.jit.elem"));

    /** Debugging: {@code -Dneonjs.jit.dump=DIR} writes every generated class there. */
    private static final String DUMP_DIR = System.getProperty("neonjs.jit.dump");

    /** Calls compiled with the callee's body inlined (Inlining). */
    public static final AtomicLong inlinedCalls = new AtomicLong();

    /** Blocks compiled without their inlined calls, by reason. */
    public static final ConcurrentHashMap<String, AtomicLong> notInlinedReasons = new ConcurrentHashMap<>();

    /** Blocks compiled without unboxed values, by reason. */
    public static final ConcurrentHashMap<String, AtomicLong> untypedReasons = new ConcurrentHashMap<>();

    private JvmCompiler() {
        // before the first generated class (including the definer probe) is linked: see ArtCha
        ArtCha.INSTANCE.linkCompiledCodeImplementations();
    }

    private CompiledCode fail(String reason) {
        failedCount.incrementAndGet();
        failureReasons.computeIfAbsent(shorten(reason, 120), key -> new AtomicLong()).incrementAndGet();
        return null;
    }

    public boolean canCompile(CodeBlock cb) {
        return !cb.isGenerator() && !cb.isAsync() && (cb.flags & CodeBlock.NO_JIT) == 0;
    }

    /** Compiles {@code cb} into a class defined by the default definer. */
    public CompiledCode compile(CodeBlock cb) {
        return compile(cb, CodeDefiners.getDefault());
    }

    /** Compiles {@code cb} into a class defined by {@code definer}, or reuses equal code. */
    public CompiledCode compile(CodeBlock cb, CodeDefiner definer) {
        return compileAll(List.of(cb), definer).get(0);
    }

    /**
     * Compiles {@code blocks} together: blocks with the same identity get one class, classes compiled before come from
     * the definer's {@link CodeCache}, and the new classes are defined with one {@link CodeDefiner#defineAll} call.
     */
    public List<CompiledCode> compileAll(List<CodeBlock> blocks, CodeDefiner definer) {
        CompiledCode[] out = new CompiledCode[blocks.size()];
        boolean usable = CodeDefiners.isUsable(definer);
        // visible classes live in the engine's class loader under counter names: never shared
        CodeCache cache = JvmCodeDefiner.VISIBLE_CLASSES ? null : CodeCache.of(definer);
        // identity -> input and the blocks wanting it, in order
        LinkedHashMap<String, JitInput> inputs = new LinkedHashMap<>();
        HashMap<String, ArrayList<Integer>> wanting = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CodeBlock cb = blocks.get(i);
            // a block's code is never replaced. Jit.prepare reads `compiled` before `jitTask`, so a thread that read
            // it just before the code was installed can queue the block again once the task is gone; the installed
            // code reads the callees it inlines from the block (inlineTargets), set once with it
            Object installed = cb.compiled;
            if (installed != null) {
                out[i] = (CompiledCode) installed;
                continue;
            }
            if (!canCompile(cb)) continue;
            if (!usable) {
                fail("no usable code definer on this platform");
                continue;
            }
            try {
                JitInput input = new JitInput(cb);
                // read by the generated code through its frame's code block: set before the code is installed
                cb.inlineTargets = input.inlineTargets;
                String id = cache == null ? "#" + i : input.getIdentity();
                CompiledCode known = cache == null ? null : cache.get(id);
                if (known != null) {
                    out[i] = known;
                    sharedCount.incrementAndGet();
                    continue;
                }
                inputs.putIfAbsent(id, input);
                wanting.computeIfAbsent(id, key -> new ArrayList<>(1)).add(i);
            } catch (RuntimeException error) {
                failure(error);
            }
        }
        if (inputs.isEmpty()) return Arrays.asList(out);
        // generate
        ArrayList<String> ids = new ArrayList<>(inputs.size());
        ArrayList<GeneratedClass> classes = new ArrayList<>(inputs.size());
        for (Map.Entry<String, JitInput> entry : inputs.entrySet()) {
            String id = entry.getKey();
            try {
                GeneratedClass generated = generate(entry.getValue(), definer.getClassFileVersion());
                ids.add(id);
                classes.add(generated);
            } catch (RuntimeException error) {
                for (int i = 0; i < wanting.get(id).size(); i++) failure(error);
            }
        }
        // define (one batch; if the definer rejects it, class by class so one bad class fails alone)
        ClassLoader loader = JvmCompiler.class.getClassLoader();
        List<Class<?>> defined;
        try {
            defined = definer.defineAll(classes, loader);
            if (defined.size() != classes.size()) {
                throw new IllegalStateException("defineAll returned " + defined.size()
                        + " classes for " + classes.size());
            }
        } catch (Exception | ClassFormatError | VerifyError error) {
            if (classes.size() == 1) {
                for (int i = 0; i < wanting.get(ids.get(0)).size(); i++) failure(error);
                defined = new ArrayList<>(1);
                defined.add(null);
            } else {
                defined = new ArrayList<>(classes.size());
                for (int k = 0; k < classes.size(); k++) {
                    GeneratedClass generated = classes.get(k);
                    try {
                        defined.add(definer.define(generated.name, generated.bytes, loader));
                    } catch (Exception | ClassFormatError | VerifyError inner) {
                        for (int i = 0; i < wanting.get(ids.get(k)).size(); i++) failure(inner);
                        defined.add(null);
                    }
                }
            }
        }
        for (int k = 0; k < defined.size(); k++) {
            Class<?> type = defined.get(k);
            if (type == null) continue;
            String id = ids.get(k);
            CompiledCode code;
            try {
                code = (CompiledCode) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException error) {
                for (int i = 0; i < wanting.get(id).size(); i++) failure(error);
                continue;
            }
            compiledCount.incrementAndGet();
            CompiledCode shared = cache == null ? code : cache.putIfAbsent(id, code);
            List<Integer> wanted = wanting.get(id);
            for (int n = 0; n < wanted.size(); n++) {
                out[wanted.get(n)] = shared;
                if (n > 0) sharedCount.incrementAndGet();
            }
        }
        return Arrays.asList(out);
    }

    private void failure(Throwable error) {
        if (error instanceof JitBailout bailout) {
            fail(bailout.getMessage() == null ? "bailout" : bailout.getMessage());
        } else if (error instanceof MethodTooLargeException) {
            fail("method too large");
        } else {
            if (System.getProperty("neonjs.jit.debug") != null) error.printStackTrace();
            fail(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static String shorten(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static final class CW extends ClassWriter {
        CW() {
            super(COMPUTE_FRAMES | COMPUTE_MAXS);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    /** A trivial {@link CompiledCode} class (its run returns null) for testing a definer. */
    public byte[] probeClass(int version) {
        CW cw = new CW();
        cw.visit(version, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PROBE_CLASS.replace('.', '/'),
                null, "java/lang/Object", new String[] {"dev/mooner/neonjs/jit/CompiledCode"});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "run", "(" + FRAME_D + ")" + OBJ, null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates the class for {@code input} with class file {@code version}; returns its binary name and bytes. The
     * name ends with the input's identity: equal names mean equal class files, in every run.
     */
    public GeneratedClass generate(JitInput input) {
        return generate(input, V17);
    }

    public GeneratedClass generate(JitInput input, int version) {
        String suffix = JvmCodeDefiner.VISIBLE_CLASSES
                ? Long.toString(counter.incrementAndGet()) : input.getIdentity();
        String name = "dev/mooner/neonjs/jit/JS$" + input.name + "$" + suffix;
        try {
            byte[] bytes = classBytes(input, name, version, TYPED, true);
            dump(name, bytes);
            return new GeneratedClass(name.replace('/', '.'), bytes);
        } catch (RuntimeException error) {
            // inlined bodies make code larger, unboxed code too, and the type analysis gives up on some shapes: such
            // blocks get the code without inlined calls, or the Object code, as before them (deterministic, so equal
            // inputs still give equal classes)
            if (!TYPED || !(error instanceof MethodTooLargeException) && !(error instanceof JitBailout)) throw error;
            try {
                if (input.inlineSites.isEmpty()) throw error;
                notInlinedReasons.computeIfAbsent(reason(error), key -> new AtomicLong()).incrementAndGet();
                byte[] bytes = classBytes(input, name, version, true, false);
                dump(name, bytes);
                return new GeneratedClass(name.replace('/', '.'), bytes);
            } catch (RuntimeException inner) {
                if (!(inner instanceof MethodTooLargeException) && !(inner instanceof JitBailout)) throw inner;
                untypedReasons.computeIfAbsent(reason(inner), key -> new AtomicLong()).incrementAndGet();
                byte[] bytes = classBytes(input, name, version, false, false);
                dump(name, bytes);
                return new GeneratedClass(name.replace('/', '.'), bytes);
            }
        }
    }

    private static void dump(String name, byte[] bytes) {
        String dir = DUMP_DIR;
        if (dir == null) return;
        try {
            int slash = name.lastIndexOf('/');
            java.nio.file.Files.write(new File(dir, name.substring(slash + 1) + ".class").toPath(), bytes);
        } catch (IOException | SecurityException error) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not write generated class " + name + " to " + dir, error);
        }
    }

    private static String reason(Throwable error) {
        return error instanceof JitBailout
                ? shorten(error.getMessage() == null ? "bailout" : error.getMessage(), 120)
                : "method too large";
    }

    static String sanitize(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') builder.append(c);
        }
        return builder.length() == 0 ? "anon"
                : builder.length() <= 40 ? builder.toString() : builder.substring(0, 40);
    }

    private byte[] classBytes(JitInput input, String name, int version, boolean typed, boolean inline) {
        CW cw = new CW();
        cw.visit(version, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null,
                "java/lang/Object", new String[] {"dev/mooner/neonjs/jit/CompiledCode"});
        if (input.sourceName != null) cw.visitSource(input.sourceName, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "run", "(" + FRAME_D + ")" + OBJ, null, null);
        mv.visitCode();
        new Gen(input, mv, typed, null, null, inline).emit();
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** What the body of a call compiled inlined reads instead of its own frame's state. */
    private static final class InlineCtx {
        final int constsLocal;
        final int envLocal;
        final int resultLocal;
        final byte resultKind;
        final Label exit;

        InlineCtx(int constsLocal, int envLocal, int resultLocal, byte resultKind, Label exit) {
            this.constsLocal = constsLocal;
            this.envLocal = envLocal;
            this.resultLocal = resultLocal;
            this.resultKind = resultKind;
            this.exit = exit;
        }
    }

    private record Trampoline(Label label, byte[] kinds, int target) {
    }

    private static final class Gen {
        final JitInput cb;
        final MethodVisitor mv;
        final boolean typed;
        final Gen outer;
        final int[] code;
        final HashMap<Integer, Label> labels = new HashMap<>();
        final HashMap<Integer, Label> handlerLabels = new HashMap<>();
        /** Kinds of the operand stack and of the registers (JitTypes): which values are unboxed. */
        final TypeAnalysis ta;
        /** Set while this generator emits a body inlined at a call. */
        InlineCtx inl;
        /** CALL pc -> inline site k (the outermost function only: inlined bodies do not inline further). */
        final Map<Integer, Integer> inlineAt;
        /** How each register is held. */
        final byte[] storage;
        /** JVM local of each register kept as a double or an int (-1: an Object local, reg). */
        final int[] slots;
        int nextTemp;
        /** Kinds of the values on the JVM operand stack at the current point of the generated code. */
        final KindStack st = new KindStack();
        final boolean[] stmtStart;
        final int[] stmtLine;
        final ArrayList<Trampoline> trampolines = new ArrayList<>();
        final int regBase;

        Gen(JitInput cb, MethodVisitor mv, boolean typed, Gen outer, byte[] argKinds, boolean inlining) {
            this.cb = cb;
            this.mv = mv;
            this.typed = typed;
            this.outer = outer;
            this.code = cb.code;
            this.ta = typed ? new TypeAnalysis(cb, JvmCompiler.INT32, JvmCompiler.ELEMS, argKinds).run() : null;
            Map<Integer, Integer> inlineAt = new HashMap<>();
            if (inlining && outer == null && ta != null) {
                for (int k = 0; k < cb.inlineSites.size(); k++) {
                    inlineAt.put(cb.inlineSites.get(k).pc(), k);
                }
            }
            this.inlineAt = inlineAt;
            this.storage = ta != null ? ta.regStorage : new byte[cb.numRegs];
            this.slots = new int[cb.numRegs];
            Arrays.fill(slots, -1);
            this.stmtStart = new boolean[code.length];
            for (int pc : cb.statementPcs) if (pc < stmtStart.length) stmtStart[pc] = true;
            this.stmtLine = new int[code.length];
            Arrays.fill(stmtLine, -1);
            if (cb.lines != null) {
                for (int k = 0; k < cb.statementPcs.length; k++) {
                    int pc = cb.statementPcs[k];
                    if (pc < stmtLine.length) stmtLine[pc] = cb.lines[k];
                }
            }
            if (outer == null) {
                int n = L_REGS + cb.numRegs;
                for (int r = 0; r < cb.numRegs; r++) {
                    if (storage[r] == JT.NUM) {
                        slots[r] = n;
                        n += 2;
                    } else if (storage[r] == JT.INT) {
                        slots[r] = n;
                        n += 1;
                    }
                }
                nextTemp = n;
                regBase = L_REGS;
            } else {
                nextTemp = -1;
                regBase = outer.alloc(cb.numRegs);
                for (int r = 0; r < cb.numRegs; r++) {
                    if (storage[r] == JT.NUM) slots[r] = outer.alloc(2);
                    else if (storage[r] == JT.INT) slots[r] = outer.alloc(1);
                }
            }
        }

        Label label(int pc) {
            return labels.computeIfAbsent(pc, key -> new Label());
        }

        /** {@code n} new JVM locals, from the outermost generator (the method's). */
        int alloc(int n) {
            if (outer != null) return outer.alloc(n);
            int value = nextTemp;
            nextTemp += n;
            return value;
        }

        int temp() {
            return alloc(1);
        }

        int tempD() {
            return alloc(2);
        }

        int reg(int r) {
            return regBase + r;
        }

        void rt(String name, String desc) {
            mv.visitMethodInsn(INVOKESTATIC, RT, name, desc, false);
        }

        void iconst(int value) {
            if (value >= -1 && value <= 5) {
                mv.visitInsn(ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                mv.visitIntInsn(SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        void frame() {
            mv.visitVarInsn(ALOAD, L_FRAME);
        }

        void consts() {
            mv.visitVarInsn(ALOAD, inl == null ? L_CONSTS : inl.constsLocal);
        }

        String pcField() {
            return inl != null ? "inlinePc" : "pc";
        }

        void constant(int k) {
            consts();
            iconst(k);
            mv.visitInsn(AALOAD);
        }

        void getstatic(String owner, String field, String desc) {
            mv.visitFieldInsn(GETSTATIC, owner, field, desc);
        }

        void pushUndefined() {
            getstatic("dev/mooner/neonjs/runtime/Undefined", "INSTANCE",
                    "Ldev/mooner/neonjs/runtime/Undefined;");
        }

        /** Value on the stack is {@code Interpreter.TAIL}: return it so the caller performs the tail call. */
        void returnIfTail() {
            mv.visitInsn(DUP);
            getstatic("dev/mooner/neonjs/vm/Interpreter", "TAIL", OBJ);
            Label cont = new Label();
            mv.visitJumpInsn(IF_ACMPNE, cont);
            mv.visitInsn(ARETURN);
            mv.visitLabel(cont);
        }

        void packArray(int n) {
            int[] temps = new int[n];
            for (int i = 0; i < n; i++) temps[i] = temp();
            for (int i = n - 1; i >= 0; i--) mv.visitVarInsn(ASTORE, temps[i]);
            iconst(n);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < n; i++) {
                mv.visitInsn(DUP);
                iconst(i);
                mv.visitVarInsn(ALOAD, temps[i]);
                mv.visitInsn(AASTORE);
            }
        }

        void interruptCheck() {
            Label ok = new Label();
            mv.visitIincInsn(L_BUDGET, -1);
            mv.visitVarInsn(ILOAD, L_BUDGET);
            mv.visitJumpInsn(IFGT, ok);
            mv.visitVarInsn(ALOAD, L_AGENT);
            mv.visitMethodInsn(INVOKEVIRTUAL, "dev/mooner/neonjs/runtime/Agent", "checkInterrupt", "()V", false);
            iconst(4096);
            mv.visitVarInsn(ISTORE, L_BUDGET);
            mv.visitLabel(ok);
        }

        void emit() {
            collectJumpLabels();
            int[] handlers = cb.handlers;
            for (int i = 0; i < handlers.length; i += 4) {
                int target = handlers[i + 2];
                // a handler is reachable only if some instruction it protects is: no code is generated otherwise
                if (ta == null || ta.reachable(target)) {
                    Label start = label(handlers[i]);
                    Label end = label(handlers[i + 1]);
                    Label handler = handlerLabels.computeIfAbsent(target, key -> new Label());
                    mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                }
            }
            // prologue
            frame();
            mv.visitFieldInsn(GETFIELD, FRAME, "code", "Ldev/mooner/neonjs/compiler/CodeBlock;");
            mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/compiler/CodeBlock", "constants", CONSTS_D);
            mv.visitVarInsn(ASTORE, L_CONSTS);
            frame();
            mv.visitFieldInsn(GETFIELD, FRAME, "realm", "Ldev/mooner/neonjs/runtime/Realm;");
            mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/Realm", "agent", AGENT_D);
            mv.visitVarInsn(ASTORE, L_AGENT);
            iconst(4096);
            mv.visitVarInsn(ISTORE, L_BUDGET);
            for (int r = 0; r < cb.numRegs; r++) {
                pushUndefined();
                mv.visitVarInsn(ASTORE, reg(r));
                // never read before a number is stored (TypeAnalysis); initialized for the verifier
                if (storage[r] == JT.NUM) {
                    mv.visitInsn(DCONST_0);
                    mv.visitVarInsn(DSTORE, slots[r]);
                } else if (storage[r] == JT.INT) {
                    mv.visitInsn(ICONST_0);
                    mv.visitVarInsn(ISTORE, slots[r]);
                }
            }
            if (cb.paramRegs != null) {
                for (int index = 0; index < cb.paramRegs.length; index++) {
                    int r = cb.paramRegs[index];
                    // a parameter register kept unboxed is never read before a number is stored in it
                    if (r >= 0 && storage[r] == JT.ANY) {
                        frame();
                        iconst(index);
                        rt("arg", "(" + FRAME_D + "I)" + OBJ);
                        mv.visitVarInsn(ASTORE, reg(r));
                    }
                }
            }
            emitBody();
            // falling off the end is impossible: emitter always ends with RETURN
            pushUndefined();
            mv.visitInsn(ARETURN);
            emitTrampolines();
        }

        void emitTrampolines() {
            for (Trampoline trampoline : trampolines) {
                mv.visitLabel(trampoline.label());
                st.set(trampoline.kinds());
                convertTo(ta.stackIn[trampoline.target()]);
                mv.visitJumpInsn(GOTO, label(trampoline.target()));
            }
            trampolines.clear();
        }

        /** The labels of jump targets (before any code is emitted). */
        void collectJumpLabels() {
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc];
                int len = Op.INSTANCE.length(code, pc);
                if (op == Op.JUMP_TABLE) {
                    int n = code[pc + 2];
                    for (int i = 0; i <= n; i++) label(code[pc + 3 + i]);
                } else if (Op.INSTANCE.isJump(op)) {
                    label(code[pc + len - 1]);
                }
                pc += len;
            }
        }

        /** The instructions, in order: the code of a function, or of a body inlined at a call. */
        void emitBody() {
            int pc = 0;
            int lastLine = -1;
            int[] stmts = cb.statementPcs;
            int[] lines = cb.lines;
            int si = 0;
            int curLine = -1;
            while (pc < code.length) {
                Label pcLabel = labels.get(pc);
                if (pcLabel != null) mv.visitLabel(pcLabel);
                boolean newStatement = false;
                while (si < stmts.length && stmts[si] <= pc) {
                    if (lines != null) curLine = lines[si];
                    si++;
                    newStatement = true;
                }
                if (ta != null && !ta.reachable(pc)) {
                    pc += Op.INSTANCE.length(code, pc);
                    continue;
                }
                Label handlerLabel = handlerLabels.get(pc);
                if (handlerLabel != null) {
                    // exception entry: [Throwable] -> [value]
                    mv.visitLabel(handlerLabel);
                    frame();
                    rt("catchValue", "(Ljava/lang/Throwable;" + FRAME_D + ")" + OBJ);
                }
                if (ta != null) st.set(ta.stackIn[pc]);
                if (newStatement) {
                    // frame.pc = pc at each statement, so errors raised between calls point at the right statement
                    frame();
                    iconst(pc);
                    mv.visitFieldInsn(PUTFIELD, FRAME, pcField(), "I");
                }
                if (curLine >= 0 && curLine != lastLine) {
                    Label lineLabel = new Label();
                    mv.visitLabel(lineLabel);
                    mv.visitLineNumber(curLine, lineLabel);
                    lastLine = curLine;
                }
                int op = code[pc];
                Split split = ta != null && op == Op.GET_ELEM ? planSplit(pc, ta) : null;
                if (split != null) {
                    emitSplit(pc, split, ta);
                    // both copies stored the pc at the line table entries inside
                    while (si < stmts.length && stmts[si] < split.end) {
                        if (lines != null) curLine = lines[si];
                        si++;
                    }
                    pc = split.end;
                    continue;
                }
                Integer site = op == Op.CALL ? inlineAt.get(pc) : null;
                if (site != null) {
                    int end = emitInlineCall(pc, site, ta);
                    if (end >= 0) {
                        while (si < stmts.length && stmts[si] < end) {
                            if (lines != null) curLine = lines[si];
                            si++;
                        }
                        pc = end;
                        continue;
                    }
                }
                if (ta == null) emitGeneric(pc);
                else emitOp(pc, ta);
                int next = pc + Op.INSTANCE.length(code, pc);
                // the fall-through edge: box what the next instruction expects boxed
                if (ta != null && fallsThrough(op) && ta.reachable(next)) convertTo(ta.stackIn[next]);
                pc = next;
            }
            Label endLabel = labels.get(pc);
            if (endLabel != null) mv.visitLabel(endLabel);
        }

        /** A GET_ELEM compiled twice: the instructions after it up to end, and whether its fast case gives an int. */
        private static final class Split {
            final int end;
            final boolean intResult;

            Split(int end, boolean intResult) {
                this.end = end;
                this.intResult = intResult;
            }
        }

        private static final class SplitSim {
            final int end;
            final boolean bitwiseUse;

            SplitSim(int end, boolean bitwiseUse) {
                this.end = end;
                this.bitwiseUse = bitwiseUse;
            }
        }

        Split planSplit(int pc0, TypeAnalysis ta) {
            if (!ta.elems) return null;
            byte[] in0 = ta.stackIn[pc0];
            if (in0.length < 2 || !JT.isNum(in0[in0.length - 1])) return null;
            SplitSim asDouble = simulateSplit(pc0, JT.NUM, ta);
            if (asDouble == null) return null;
            if (ta.ints && asDouble.bitwiseUse) {
                SplitSim asInt = simulateSplit(pc0, JT.INT, ta);
                return asInt == null ? null : new Split(asInt.end, true);
            }
            return new Split(asDouble.end, false);
        }

        SplitSim simulateSplit(int pc0, byte elem, TypeAnalysis ta) {
            int first = pc0 + Op.INSTANCE.length(code, pc0);
            byte[] in0 = ta.stackIn[pc0];
            byte[] fast = in0.clone();
            fast[fast.length - 1] = elem;
            return simulateRegion(first, fast, false, ta);
        }

        /**
         * The end of the instructions from {@code first} worth compiling twice, the fast copy starting with the kinds
         * {@code fastIn}, or null when there is none.
         */
        SplitSim simulateRegion(int first, byte[] fastIn, boolean allowPop, TypeAnalysis ta) {
            int pc = first;
            if (!ta.reachable(pc)) return null;
            KindStack staticKinds = new KindStack();
            staticKinds.set(ta.stackIn[pc]);
            KindStack fast = new KindStack();
            fast.set(fastIn);
            if (fast.size != staticKinds.size) return null;
            int elemAt = fast.size - 1; // stack index of the element until it is used
            boolean bitwiseUse = false;
            int end = -1;
            int n = 0;
            while (n < MAX_SPLIT && pc < code.length) {
                if (!ta.reachable(pc) || labels.containsKey(pc) || handlerLabels.containsKey(pc)) break;
                if (!staticKinds.matches(ta.stackIn[pc])) return null;
                int op = code[pc];
                int a = Op.operands[op] >= 1 ? code[pc + 1] : 0;
                byte regKind = op == Op.LOAD_REG ? ta.regKind(pc, a) : JT.ANY;
                int before = fast.size;
                if (!ta.stackStep(op, a, fast, regKind)) break;
                ta.stackStep(op, a, staticKinds, regKind);
                if (elemAt >= 0 && elemAt >= before - stackPops(op)) {
                    if (op == Op.POP && !allowPop) return null; // discarded: nothing to gain
                    bitwiseUse = JT.isBitwise(op) || op == Op.BNOT;
                    elemAt = -1;
                }
                pc += Op.INSTANCE.length(code, pc);
                n++;
                if (elemAt < 0) {
                    if (fast.matches(staticKinds.toArray())) {
                        end = pc;
                        break;
                    }
                    if (convertible(fast, staticKinds)) end = pc;
                }
            }
            if (end < 0 || !ta.reachable(end)) return null;
            return new SplitSim(end, bitwiseUse);
        }

        /** Values consumed by the instructions {@link TypeAnalysis#stackStep} handles. */
        int stackPops(int op) {
            switch (op) {
                case Op.PUSH_INT:
                case Op.PUSH_CONST:
                case Op.LOAD_REG:
                    return 0;
                case Op.POP:
                case Op.NEG:
                case Op.BNOT:
                case Op.INC:
                case Op.DEC:
                case Op.TO_NUMERIC:
                case Op.TO_NUMBER:
                    return 1;
                case Op.PUT_ELEM:
                    return 3;
                default:
                    return 2;
            }
        }

        /** Whether {@link #convertTo} can turn the kinds {@code from} into {@code to}. */
        boolean convertible(KindStack from, KindStack to) {
            if (from.size != to.size) return false;
            for (int i = 0; i < from.size; i++) {
                byte f = from.get(i);
                byte t = to.get(i);
                if (f != t && t != JT.ANY && !(f == JT.INT && t == JT.NUM)) return false;
            }
            return true;
        }

        /**
         * The GET_ELEM at {@code pc0} and the instructions after it up to {@code split.end}, compiled twice.
         */
        void emitSplit(int pc0, Split split, TypeAnalysis ta) {
            // (obj key -- value): the key stays unboxed, and both are kept for the slow case
            byte[] wanted = st.toArray();
            wanted[wanted.length - 2] = JT.ANY;
            convertTo(wanted);
            byte key = st.pop();
            st.pop();
            int tKey = storeTemp(key);
            int tObj = temp();
            mv.visitVarInsn(ASTORE, tObj);
            byte[] below = st.toArray();
            String k = key == JT.INT ? "I" : "D";
            Label slowGet = new Label();
            Label miss = new Label();
            Label merge = new Label();
            mv.visitVarInsn(ALOAD, tObj);
            mv.visitTypeInsn(INSTANCEOF, "dev/mooner/neonjs/builtins/JSTypedArray");
            mv.visitJumpInsn(IFEQ, slowGet);
            mv.visitVarInsn(ALOAD, tObj);
            loadTemp(key, tKey);
            mv.visitVarInsn(ALOAD, L_AGENT);
            if (split.intResult) rt("elemInt32" + k, "(" + OBJ + k + AGENT_D + ")I");
            else rt("elemNum" + k, "(" + OBJ + k + AGENT_D + ")D");
            mv.visitVarInsn(ALOAD, L_AGENT);
            mv.visitFieldInsn(GETFIELD, AGENT, "elemMiss", "Z");
            mv.visitJumpInsn(IFNE, miss);
            // fast case
            int first = pc0 + Op.INSTANCE.length(code, pc0);
            st.set(below);
            st.push(split.intResult ? JT.INT : JT.NUM);
            emitRegionFast(first, split.end, ta);
            mv.visitJumpInsn(GOTO, merge);
            // slow case: from a miss (drop the helper's 0, clear the flag), or from another receiver
            mv.visitLabel(miss);
            mv.visitInsn(split.intResult ? POP : POP2);
            mv.visitVarInsn(ALOAD, L_AGENT);
            mv.visitInsn(ICONST_0);
            mv.visitFieldInsn(PUTFIELD, AGENT, "elemMiss", "Z");
            mv.visitLabel(slowGet);
            mv.visitVarInsn(ALOAD, tObj);
            loadTemp(key, tKey);
            frame();
            if (key == JT.INT) rt("getElemI", "(" + OBJ + "I" + FRAME_D + ")" + OBJ);
            else rt("getElemD", "(" + OBJ + "D" + FRAME_D + ")" + OBJ);
            st.set(below);
            st.push(JT.ANY);
            if (!st.matches(ta.stackIn[first])) throw new JitBailout("split at " + pc0 + ": stack kinds differ");
            emitRegionSlow(first, split.end, ta);
            mv.visitLabel(merge);
        }

        /** The fast copy of the instructions {@code [first, end)}. */
        void emitRegionFast(int first, int end, TypeAnalysis ta) {
            int pc = first;
            while (pc < end) {
                storePc(pc);
                emitOp(pc, ta);
                pc += Op.INSTANCE.length(code, pc);
            }
            convertTo(ta.stackIn[end]);
        }

        /** The slow copy of the instructions {@code [first, end)}: as the main loop compiles them. */
        void emitRegionSlow(int first, int end, TypeAnalysis ta) {
            int pc = first;
            while (pc < end) {
                st.set(ta.stackIn[pc]);
                storePc(pc);
                emitOp(pc, ta);
                int next = pc + Op.INSTANCE.length(code, pc);
                convertTo(ta.stackIn[next]);
                pc = next;
            }
        }

        int emitInlineCall(int pc, int k, TypeAnalysis ta) {
            int argc = code[pc + 1];
            int n = argc + 2;
            if (st.size < n || st.get(st.size - n) != JT.ANY) return -1;
            byte[] kinds = new byte[n];
            for (int i = 0; i < n; i++) kinds[i] = st.get(st.size - n + i);
            byte[] argKinds = new byte[argc];
            for (int i = 0; i < argc; i++) {
                byte kk = kinds[2 + i];
                argKinds[i] = JT.isNum(kk) ? kk : JT.ANY;
            }
            Gen callee;
            try {
                callee = new Gen(cb.inlineSites.get(k).callee(), mv, true, this, argKinds, false);
            } catch (JitBailout error) {
                return -1;
            }
            byte r = callee.resultKind();
            int first = pc + Op.INSTANCE.length(code, pc);
            // (fn this args... -- result)
            int[] temps = new int[n];
            for (int i = n - 1; i >= 0; i--) temps[i] = storeTemp(kinds[i]);
            for (int i = 0; i < n; i++) st.pop();
            byte[] below = st.toArray();
            int end = first;
            if (r != JT.ANY) {
                byte[] fast = Arrays.copyOf(below, below.length + 1);
                fast[below.length] = r;
                SplitSim sim = simulateRegion(first, fast, true, ta);
                if (sim != null) end = sim.end;
            }
            Label slow = new Label();
            Label merge = new Label();
            int fn = temps[0];
            // the guard
            mv.visitVarInsn(ALOAD, fn);
            mv.visitTypeInsn(INSTANCEOF, CLOSURE);
            mv.visitJumpInsn(IFEQ, slow);
            mv.visitVarInsn(ALOAD, fn);
            mv.visitTypeInsn(CHECKCAST, CLOSURE);
            mv.visitFieldInsn(GETFIELD, CLOSURE, "code", CODEBLOCK_D);
            frame();
            mv.visitFieldInsn(GETFIELD, FRAME, "code", CODEBLOCK_D);
            mv.visitFieldInsn(GETFIELD, CODEBLOCK, "inlineTargets", "[" + CODEBLOCK_D);
            iconst(k);
            mv.visitInsn(AALOAD);
            mv.visitJumpInsn(IF_ACMPNE, slow);
            mv.visitVarInsn(ALOAD, fn);
            mv.visitTypeInsn(CHECKCAST, CLOSURE);
            mv.visitFieldInsn(GETFIELD, CLOSURE, "realm", REALM_D);
            frame();
            mv.visitFieldInsn(GETFIELD, FRAME, "realm", REALM_D);
            mv.visitJumpInsn(IF_ACMPNE, slow);
            // the body
            frame();
            mv.visitVarInsn(ALOAD, fn);
            mv.visitTypeInsn(CHECKCAST, CLOSURE);
            iconst(pc);
            rt("inlineEnter", "(" + FRAME_D + CLOSURE_D + "I)V");
            int constsLocal = temp();
            mv.visitVarInsn(ALOAD, fn);
            mv.visitTypeInsn(CHECKCAST, CLOSURE);
            mv.visitFieldInsn(GETFIELD, CLOSURE, "code", CODEBLOCK_D);
            mv.visitFieldInsn(GETFIELD, CODEBLOCK, "constants", CONSTS_D);
            mv.visitVarInsn(ASTORE, constsLocal);
            int envLocal = temp();
            mv.visitVarInsn(ALOAD, fn);
            mv.visitTypeInsn(CHECKCAST, CLOSURE);
            mv.visitFieldInsn(GETFIELD, CLOSURE, "env", "Ldev/mooner/neonjs/runtime/Env;");
            mv.visitVarInsn(ASTORE, envLocal);
            int resultLocal = r == JT.NUM ? tempD() : temp();
            Label exit = new Label();
            callee.inl = new InlineCtx(constsLocal, envLocal, resultLocal, r, exit);
            int[] argTemps = new int[argc];
            byte[] argKindValues = new byte[argc];
            for (int i = 0; i < argc; i++) {
                argTemps[i] = temps[2 + i];
                argKindValues[i] = kinds[2 + i];
            }
            callee.emitInlined(argTemps, argKindValues);
            mv.visitLabel(exit);
            frame();
            rt("inlineExit", "(" + FRAME_D + ")V");
            st.set(below);
            loadTemp(r, resultLocal);
            st.push(r);
            emitRegionFast(first, end, ta);
            mv.visitJumpInsn(GOTO, merge);
            // the ordinary call
            mv.visitLabel(slow);
            st.set(below);
            for (int i = 0; i < n; i++) {
                loadTemp(kinds[i], temps[i]);
                box(kinds[i]);
                st.push(JT.ANY);
            }
            emitGeneric(pc);
            st.set(below);
            st.push(JT.ANY);
            if (!st.matches(ta.stackIn[first])) {
                throw new JitBailout("inlined call at " + pc + ": stack kinds differ");
            }
            emitRegionSlow(first, end, ta);
            mv.visitLabel(merge);
            inlinedCalls.incrementAndGet();
            return end;
        }

        /** The kind of the values this (inlined) function returns: INT or NUM when every return is one, else ANY. */
        byte resultKind() {
            TypeAnalysis ta = this.ta;
            byte result = -1;
            int pc = 0;
            while (pc < code.length) {
                if (code[pc] == Op.RETURN && ta.reachable(pc)) {
                    byte[] stack = ta.stackIn[pc];
                    byte kind = stack.length == 0 ? JT.ANY : stack[stack.length - 1];
                    result = result < 0 ? kind : JT.join(result, kind);
                }
                pc += Op.INSTANCE.length(code, pc);
            }
            return JT.isNum(result) ? result : JT.ANY;
        }

        /** This function's body, inlined: registers start undefined, {@code argTemps} go to the parameters. */
        void emitInlined(int[] argTemps, byte[] argKinds) {
            collectJumpLabels();
            for (int r = 0; r < cb.numRegs; r++) {
                pushUndefined();
                mv.visitVarInsn(ASTORE, reg(r));
                if (storage[r] == JT.NUM) {
                    mv.visitInsn(DCONST_0);
                    mv.visitVarInsn(DSTORE, slots[r]);
                } else if (storage[r] == JT.INT) {
                    mv.visitInsn(ICONST_0);
                    mv.visitVarInsn(ISTORE, slots[r]);
                }
            }
            if (cb.paramRegs != null) {
                for (int i = 0; i < cb.paramRegs.length; i++) {
                    if (i < argTemps.length) {
                        int r = cb.paramRegs[i];
                        loadTemp(argKinds[i], argTemps[i]);
                        storeReg(r, argKinds[i]);
                    }
                }
            }
            emitBody();
            emitTrampolines();
        }

        /** RETURN in an inlined body: the value to the result local (in the call's result kind), then to the exit. */
        void emitInlineReturn() {
            InlineCtx inl = this.inl;
            byte kind = st.pop();
            convert(kind, inl.resultKind);
            if (inl.resultKind == JT.NUM) mv.visitVarInsn(DSTORE, inl.resultLocal);
            else if (inl.resultKind == JT.INT) mv.visitVarInsn(ISTORE, inl.resultLocal);
            else mv.visitVarInsn(ASTORE, inl.resultLocal);
            while (st.size > 0) popValue(st.pop());
            mv.visitJumpInsn(GOTO, inl.exit);
        }

        /** In a split, what the main loop does at a line table entry: frame.pc = pc (and the JVM line number). */
        void storePc(int pc) {
            if (!stmtStart[pc]) return;
            frame();
            iconst(pc);
            mv.visitFieldInsn(PUTFIELD, FRAME, pcField(), "I");
            if (stmtLine[pc] >= 0) {
                Label line = new Label();
                mv.visitLabel(line);
                mv.visitLineNumber(stmtLine[pc], line);
            }
        }

        boolean fallsThrough(int op) {
            return op != Op.JUMP && op != Op.JUMP_TABLE && op != Op.RETURN
                    && op != Op.THROW && op != Op.THROW_ERROR;
        }

        // ------------------------------------------------------------------ unboxed values

        void box(byte kind) {
            if (kind == JT.NUM) {
                mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(D)" + OBJ, false);
            } else if (kind == JT.INT) {
                mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)" + OBJ, false);
            } else if (kind == JT.BOOL) {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            }
        }

        void convert(byte from, byte to) {
            if (from == to) return;
            if (to == JT.ANY) {
                box(from);
                return;
            }
            if (from == JT.INT && to == JT.NUM) {
                mv.visitInsn(I2D);
                return;
            }
            throw new JitBailout("cannot narrow a stack value");
        }

        void popValue(byte kind) {
            mv.visitInsn(kind == JT.NUM ? POP2 : POP);
        }

        void toDouble(byte kind) {
            convert(kind, JT.NUM);
        }

        void toInt32(byte kind) {
            if (kind == JT.NUM) rt("toInt32D", "(D)I");
        }

        void unboxNum() {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
        }

        int storeTemp(byte kind) {
            if (kind == JT.NUM) {
                int slot = tempD();
                mv.visitVarInsn(DSTORE, slot);
                return slot;
            }
            int slot = temp();
            if (kind == JT.BOOL || kind == JT.INT) mv.visitVarInsn(ISTORE, slot);
            else mv.visitVarInsn(ASTORE, slot);
            return slot;
        }

        void loadTemp(byte kind, int slot) {
            if (kind == JT.NUM) mv.visitVarInsn(DLOAD, slot);
            else if (kind == JT.BOOL || kind == JT.INT) mv.visitVarInsn(ILOAD, slot);
            else mv.visitVarInsn(ALOAD, slot);
        }

        void convertTo(byte[] want) {
            if (want.length != st.size) throw new JitBailout("operand stack depth differs");
            int low = -1;
            for (int k = 0; k < st.size; k++) {
                if (st.get(k) != want[k]) {
                    if (want[k] != JT.ANY && !(want[k] == JT.NUM && st.get(k) == JT.INT)) {
                        throw new JitBailout("cannot narrow a stack value");
                    }
                    if (low < 0) low = k;
                }
            }
            if (low < 0) return;
            if (low == st.size - 1) {
                convert(st.get(low), want[low]);
                st.setKind(low, want[low]);
                return;
            }
            byte[] kinds = new byte[st.size - low];
            for (int i = 0; i < kinds.length; i++) kinds[i] = st.get(low + i);
            int[] temps = new int[kinds.length];
            for (int k = kinds.length - 1; k >= 0; k--) temps[k] = storeTemp(kinds[k]);
            for (int k = 0; k < kinds.length; k++) {
                loadTemp(kinds[k], temps[k]);
                convert(kinds[k], want[low + k]);
                st.setKind(low + k, want[low + k]);
            }
        }

        void widenInts(int n) {
            byte[] want = st.toArray();
            for (int i = want.length - n; i < want.length; i++) {
                if (want[i] == JT.INT) want[i] = JT.NUM;
            }
            convertTo(want);
        }

        void boxAll() {
            convertTo(new byte[st.size]);
        }

        void permute(int n, int[] order) {
            byte[] kinds = new byte[n];
            for (int i = 0; i < n; i++) kinds[i] = st.get(st.size - n + i);
            int[] temps = new int[n];
            for (int k = n - 1; k >= 0; k--) temps[k] = storeTemp(kinds[k]);
            for (int k : order) loadTemp(kinds[k], temps[k]);
        }

        void compareDoubles(int op) {
            int cmp;
            int jump;
            switch (op) {
                case Op.LT:
                    cmp = DCMPG;
                    jump = IFLT;
                    break;
                case Op.LE:
                    cmp = DCMPG;
                    jump = IFLE;
                    break;
                case Op.GT:
                    cmp = DCMPL;
                    jump = IFGT;
                    break;
                case Op.GE:
                    cmp = DCMPL;
                    jump = IFGE;
                    break;
                case Op.EQ:
                case Op.SEQ:
                    cmp = DCMPL;
                    jump = IFEQ;
                    break;
                default:
                    cmp = DCMPL;
                    jump = IFNE;
                    break;
            }
            mv.visitInsn(cmp);
            Label yes = new Label();
            Label done = new Label();
            mv.visitJumpInsn(jump, yes);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, done);
            mv.visitLabel(yes);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(done);
        }

        void branch(int jump, int target, TypeAnalysis ta) {
            byte[] want = ta.stackIn[target];
            if (st.matches(want)) {
                mv.visitJumpInsn(jump, label(target));
            } else {
                Label trampoline = new Label();
                trampolines.add(new Trampoline(trampoline, st.toArray(), target));
                mv.visitJumpInsn(jump, trampoline);
            }
        }

        void emitOp(int pc, TypeAnalysis ta) {
            int op = code[pc];
            int a = Op.operands[op] >= 1 ? code[pc + 1] : 0;
            switch (op) {
                case Op.PUSH_INT:
                    if (ta.intKind == JT.INT) {
                        iconst(a);
                    } else if (a == 0) {
                        mv.visitInsn(DCONST_0);
                    } else if (a == 1) {
                        mv.visitInsn(DCONST_1);
                    } else {
                        mv.visitLdcInsn((double) a);
                    }
                    st.push(ta.intKind);
                    return;
                case Op.PUSH_CONST: {
                    byte kind = ta.constKind(a);
                    if (JT.isNum(kind)) {
                        constant(a);
                        unboxNum();
                        // an int32 (its kind is part of the class identity)
                        if (kind == JT.INT) rt("exactInt", "(D)I");
                        st.push(kind);
                        return;
                    }
                    break;
                }
                case Op.PUSH_TRUE:
                case Op.PUSH_FALSE:
                    mv.visitInsn(op == Op.PUSH_TRUE ? ICONST_1 : ICONST_0);
                    st.push(JT.BOOL);
                    return;
                case Op.LOAD_REG: {
                    byte kind = ta.regKind(pc, a);
                    if (storage[a] == JT.INT) {
                        if (kind != JT.INT) throw new JitBailout("int register loaded as kind " + kind);
                        mv.visitVarInsn(ILOAD, slots[a]);
                    } else if (storage[a] == JT.NUM) {
                        if (!JT.isNum(kind)) throw new JitBailout("double register loaded as kind " + kind);
                        mv.visitVarInsn(DLOAD, slots[a]);
                        if (kind == JT.INT) rt("exactInt", "(D)I");
                    } else {
                        mv.visitVarInsn(ALOAD, reg(a));
                        if (JT.isNum(kind)) {
                            unboxNum();
                            if (kind == JT.INT) rt("exactInt", "(D)I");
                        }
                    }
                    st.push(JT.isNum(kind) ? kind : JT.ANY);
                    return;
                }
                case Op.STORE_REG:
                    storeReg(a, st.pop());
                    return;
                case Op.RETURN:
                    if (inl != null) {
                        emitInlineReturn();
                        return;
                    }
                    break;
                case Op.POP:
                    popValue(st.pop());
                    return;
                case Op.DUP: {
                    byte kind = st.peek(0);
                    mv.visitInsn(kind == JT.NUM ? DUP2 : DUP);
                    st.push(kind);
                    return;
                }
                case Op.DUP2:
                case Op.DUP3:
                case Op.SWAP:
                case Op.ROT3:
                case Op.ROT4: {
                    int n;
                    int[] order;
                    if (op == Op.DUP2) {
                        n = 2;
                        order = TypeAnalysis.PERM_DUP2;
                    } else if (op == Op.DUP3) {
                        n = 3;
                        order = TypeAnalysis.PERM_DUP3;
                    } else if (op == Op.SWAP) {
                        n = 2;
                        order = TypeAnalysis.PERM_SWAP;
                    } else if (op == Op.ROT3) {
                        n = 3;
                        order = TypeAnalysis.PERM_ROT3;
                    } else {
                        n = 4;
                        order = TypeAnalysis.PERM_ROT4;
                    }
                    if (st.allAny(n)) emitGeneric(pc);
                    else permute(n, order);
                    st.permute(n, order);
                    return;
                }
                case Op.ADD:
                case Op.SUB:
                case Op.MUL:
                case Op.DIV:
                case Op.MOD:
                case Op.EXP:
                case Op.BAND:
                case Op.BOR:
                case Op.BXOR:
                case Op.SHL:
                case Op.SAR:
                case Op.SHR:
                case Op.LT:
                case Op.GT:
                case Op.LE:
                case Op.GE:
                case Op.EQ:
                case Op.NE:
                case Op.SEQ:
                case Op.SNE: {
                    int cls = JT.binaryClass(op, st.peek(1), st.peek(0));
                    if (cls != JT.GENERIC) {
                        byte result = JT.binaryResult(op, cls, ta.intKind);
                        emitBinary(op, cls, result);
                        st.pop();
                        st.pop();
                        st.push(result);
                        return;
                    }
                    break;
                }
                case Op.NEG:
                case Op.BNOT:
                case Op.INC:
                case Op.DEC:
                case Op.TO_NUMERIC: {
                    byte kind = st.peek(0);
                    if (JT.isNum(kind)) {
                        byte result = ta.unaryResult(op, kind);
                        if (op == Op.NEG) {
                            toDouble(kind);
                            mv.visitInsn(DNEG);
                        } else if (op == Op.BNOT) {
                            if (result == JT.INT) {
                                toInt32(kind);
                                mv.visitInsn(ICONST_M1);
                                mv.visitInsn(IXOR);
                            } else {
                                rt("bnotD", "(D)D");
                            }
                        } else if (op == Op.INC) {
                            toDouble(kind);
                            mv.visitInsn(DCONST_1);
                            mv.visitInsn(DADD);
                        } else if (op == Op.DEC) {
                            toDouble(kind);
                            mv.visitInsn(DCONST_1);
                            mv.visitInsn(DSUB);
                        }
                        st.pop();
                        st.push(result);
                        return;
                    }
                    break;
                }
                case Op.TO_NUMBER: {
                    byte kind = st.pop();
                    byte result = ta.toNumberResult(kind);
                    if (kind == JT.NUM || kind == JT.INT) {
                        // unchanged
                    } else if (kind == JT.BOOL) {
                        if (result == JT.NUM) mv.visitInsn(I2D);
                    } else {
                        rt("toNumberD", "(" + OBJ + ")D");
                    }
                    st.push(result);
                    return;
                }
                case Op.NOT:
                    truth(st.pop(), true);
                    mv.visitInsn(ICONST_1);
                    mv.visitInsn(IXOR);
                    st.push(JT.BOOL);
                    return;
                case Op.JUMP:
                    if (a <= pc) interruptCheck();
                    convertTo(ta.stackIn[a]);
                    mv.visitJumpInsn(GOTO, label(a));
                    return;
                case Op.JUMP_IF_TRUE:
                case Op.JUMP_IF_FALSE:
                    if (a <= pc) interruptCheck();
                    truth(st.pop(), false);
                    branch(op == Op.JUMP_IF_TRUE ? IFNE : IFEQ, a, ta);
                    return;
                case Op.GET_ELEM: {
                    byte key = st.peek(0);
                    if (JT.isNum(key)) {
                        byte[] want = st.toArray();
                        want[want.length - 2] = JT.ANY;
                        convertTo(want);
                        frame();
                        if (key == JT.INT) rt("getElemI", "(" + OBJ + "I" + FRAME_D + ")" + OBJ);
                        else rt("getElemD", "(" + OBJ + "D" + FRAME_D + ")" + OBJ);
                        st.pop();
                        st.pop();
                        st.push(JT.ANY);
                        return;
                    }
                    break;
                }
                case Op.PUT_ELEM: {
                    byte key = st.peek(1);
                    byte stored = ta.putElemResult(key, st.peek(0));
                    if (JT.isNum(stored)) {
                        byte[] want = st.toArray();
                        want[want.length - 3] = JT.ANY;
                        convertTo(want);
                        frame();
                        String k = key == JT.INT ? "I" : "D";
                        String v = stored == JT.INT ? "I" : "D";
                        rt("setElem" + k + v, "(" + OBJ + k + v + FRAME_D + ")" + v);
                        st.pop();
                        st.pop();
                        st.pop();
                        st.push(stored);
                        return;
                    }
                    if (JT.isNum(key)) {
                        boolean vNum = key == JT.NUM && st.peek(0) == JT.NUM;
                        byte[] want = st.toArray();
                        want[want.length - 3] = JT.ANY;
                        if (!vNum) want[want.length - 1] = JT.ANY;
                        convertTo(want);
                        frame();
                        if (key == JT.INT) rt("putElemIA", "(" + OBJ + "I" + OBJ + FRAME_D + ")" + OBJ);
                        else if (vNum) rt("putElemDD", "(" + OBJ + "DD" + FRAME_D + ")" + OBJ);
                        else rt("putElemDA", "(" + OBJ + "D" + OBJ + FRAME_D + ")" + OBJ);
                        st.pop();
                        st.pop();
                        st.pop();
                        st.push(JT.ANY);
                        return;
                    }
                    break;
                }
                default:
                    break;
            }
            // everything else: the Object code; its results are Objects
            int pops = JT.pops(op, a);
            if (pops >= 0) {
                // it consumes only the top values: box those, the ones below may stay unboxed
                if (pops > st.size) throw new JitBailout("operand stack underflow at " + pc);
                byte[] want = st.toArray();
                for (int i = want.length - pops; i < want.length; i++) want[i] = JT.ANY;
                convertTo(want);
                int pushes = pops + effectOf(op, pc);
                emitGeneric(pc);
                for (int i = 0; i < pops; i++) st.pop();
                for (int i = 0; i < pushes; i++) st.push(JT.ANY);
                return;
            }
            boxAll();
            int depth = st.size + effectOf(op, pc);
            emitGeneric(pc);
            st.setAllAny(depth);
        }

        /** The truth value of the value of {@code kind} on the stack, as an int that is 0 for false. */
        void truth(byte kind, boolean normalized) {
            if (kind == JT.BOOL) return;
            if (kind == JT.INT) {
                if (normalized) {
                    // (x | -x) >>> 31: 1 for every int but 0
                    mv.visitInsn(DUP);
                    mv.visitInsn(INEG);
                    mv.visitInsn(IOR);
                    iconst(31);
                    mv.visitInsn(IUSHR);
                }
                return;
            }
            if (kind == JT.NUM) rt("truthyD", "(D)Z");
            else rt("truthy", "(" + OBJ + ")Z");
        }

        /**
         * A binary operator with at least one number operand ({@code cls} NN, NA or AN) giving {@code result};
         * booleans are boxed first.
         */
        void emitBinary(int op, int cls, byte result) {
            if (st.peek(1) == JT.BOOL || st.peek(0) == JT.BOOL) {
                byte[] want = st.toArray();
                if (want[want.length - 2] == JT.BOOL) want[want.length - 2] = JT.ANY;
                if (want[want.length - 1] == JT.BOOL) want[want.length - 1] = JT.ANY;
                convertTo(want);
            }
            byte left = st.peek(1);
            byte right = st.peek(0);
            if (JT.isBitwise(op) && cls == JT.NN && ta.ints) {
                toInt32(right);
                if (left == JT.NUM) {
                    int t = temp();
                    mv.visitVarInsn(ISTORE, t);
                    toInt32(left);
                    mv.visitVarInsn(ILOAD, t);
                }
                // the JVM masks shift counts to 5 bits, as JS does
                if (op == Op.BAND) {
                    mv.visitInsn(IAND);
                } else if (op == Op.BOR) {
                    mv.visitInsn(IOR);
                } else if (op == Op.BXOR) {
                    mv.visitInsn(IXOR);
                } else if (op == Op.SHL) {
                    mv.visitInsn(ISHL);
                } else if (op == Op.SAR) {
                    mv.visitInsn(ISHR);
                } else {
                    // >>>: the uint32 as a double
                    mv.visitInsn(IUSHR);
                    mv.visitInsn(I2L);
                    mv.visitLdcInsn(0xFFFFFFFFL);
                    mv.visitInsn(LAND);
                    mv.visitInsn(L2D);
                }
                return;
            }
            if (JT.isCompare(op) && left == JT.INT && right == JT.INT) {
                compareInts(op);
                return;
            }
            widenInts(2);
            String base;
            switch (op) {
                case Op.ADD: base = "add"; break;
                case Op.SUB: base = "sub"; break;
                case Op.MUL: base = "mul"; break;
                case Op.DIV: base = "div"; break;
                case Op.MOD: base = "mod"; break;
                case Op.EXP: base = "exp"; break;
                case Op.BAND: base = "band"; break;
                case Op.BOR: base = "bor"; break;
                case Op.BXOR: base = "bxor"; break;
                case Op.SHL: base = "shl"; break;
                case Op.SAR: base = "sar"; break;
                case Op.SHR: base = "shr"; break;
                case Op.LT: base = "lt"; break;
                case Op.GT: base = "gt"; break;
                case Op.LE: base = "le"; break;
                case Op.GE: base = "ge"; break;
                case Op.EQ: base = "eq"; break;
                case Op.NE: base = "ne"; break;
                case Op.SEQ: base = "seq"; break;
                default: base = "sne"; break;
            }
            // the helpers of the bitwise operators (but >>>) return the int32
            boolean intResult = JT.isBitwise(op) && op != Op.SHR;
            String ret = JT.isCompare(op) ? "Z" : op == Op.ADD && cls != JT.NN ? OBJ : intResult ? "I" : "D";
            if (cls == JT.NN) {
                if (op == Op.ADD) {
                    mv.visitInsn(DADD);
                } else if (op == Op.SUB) {
                    mv.visitInsn(DSUB);
                } else if (op == Op.MUL) {
                    mv.visitInsn(DMUL);
                } else if (op == Op.DIV) {
                    mv.visitInsn(DDIV);
                } else if (op == Op.MOD) {
                    mv.visitInsn(DREM); // fmod: the sign of the dividend, as JS %
                } else if (op == Op.EXP) {
                    rt("powDD", "(DD)D"); // not Math.pow: 1 ** Infinity is NaN in JS
                } else if (JT.isCompare(op)) {
                    compareDoubles(op);
                } else {
                    rt(base + "DD", "(DD)D");
                }
            } else if (cls == JT.NA) {
                if (JT.isCompare(op)) compareMixed(op, base, true);
                else rt(base + "DA", "(D" + OBJ + ")" + ret);
            } else {
                if (JT.isCompare(op)) compareMixed(op, base, false);
                else rt(base + "AD", "(" + OBJ + "D)" + ret);
            }
            if (intResult && cls != JT.NN && result == JT.NUM) mv.visitInsn(I2D);
        }

        /** Stores the value of {@code kind} on the stack in register {@code a} (STORE_REG). */
        void storeReg(int a, byte kind) {
            // a value of a kind the register is not held as is never loaded from it (TypeAnalysis): a dead store
            if (storage[a] == JT.INT) {
                if (kind == JT.INT) mv.visitVarInsn(ISTORE, slots[a]);
                else popValue(kind);
            } else if (storage[a] == JT.NUM) {
                if (JT.isNum(kind)) {
                    toDouble(kind);
                    mv.visitVarInsn(DSTORE, slots[a]);
                } else {
                    popValue(kind);
                }
            } else {
                box(kind);
                mv.visitVarInsn(ASTORE, reg(a));
            }
        }

        /** Pushes 0 or 1 for the comparison of the two ints on the stack. */
        void compareInts(int op) {
            int jump;
            if (op == Op.LT) jump = IF_ICMPLT;
            else if (op == Op.LE) jump = IF_ICMPLE;
            else if (op == Op.GT) jump = IF_ICMPGT;
            else if (op == Op.GE) jump = IF_ICMPGE;
            else if (op == Op.EQ || op == Op.SEQ) jump = IF_ICMPEQ;
            else jump = IF_ICMPNE;
            Label yes = new Label();
            Label done = new Label();
            mv.visitJumpInsn(jump, yes);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, done);
            mv.visitLabel(yes);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(done);
        }

        /** A comparison of a number with a value of unknown type. */
        void compareMixed(int op, String base, boolean numLeft) {
            Label slow = new Label();
            Label done = new Label();
            if (numLeft) {
                // number, value
                mv.visitInsn(DUP);
                mv.visitTypeInsn(INSTANCEOF, "java/lang/Double");
                mv.visitJumpInsn(IFEQ, slow);
                unboxNum();
                compareDoubles(op);
                mv.visitJumpInsn(GOTO, done);
                mv.visitLabel(slow);
                rt(base + "DA", "(D" + OBJ + ")Z");
            } else {
                // value, number
                int t = tempD();
                mv.visitVarInsn(DSTORE, t);
                mv.visitInsn(DUP);
                mv.visitTypeInsn(INSTANCEOF, "java/lang/Double");
                mv.visitJumpInsn(IFEQ, slow);
                unboxNum();
                mv.visitVarInsn(DLOAD, t);
                compareDoubles(op);
                mv.visitJumpInsn(GOTO, done);
                mv.visitLabel(slow);
                mv.visitVarInsn(DLOAD, t);
                rt(base + "AD", "(" + OBJ + "D)Z");
            }
            mv.visitLabel(done);
        }

        /** Stack effect of {@code op} at {@code pc} (as TypeAnalysis computes it). */
        int effectOf(int op, int pc) {
            int e = Op.effects[op];
            if (e != Op.VAR) return e;
            int a = code[pc + 1];
            int b = Op.operands[op] >= 2 ? code[pc + 2] : 0;
            switch (op) {
                case Op.CALL:
                case Op.TAIL_CALL:
                case Op.CALL_EVAL:
                case Op.SUPER_CALL:
                case Op.COPY_DATA_PROPS_EXCL:
                    return -(a + 1);
                case Op.NEW:
                    return -a;
                case Op.NEW_OBJECT_LITERAL:
                    return 1 - b;
                case Op.MAKE_CLASS:
                    return 2 - ((b & 1) != 0 ? 1 : 0) - ((b & 2) != 0 ? 1 : 0);
                case Op.DECLARE_GLOBALS:
                case Op.DECLARE_EVAL:
                    return -b;
                default:
                    throw new JitBailout("no stack effect for " + Op.names[op]);
            }
        }

        void emitGeneric(int pc) {
            int op = code[pc];
            int a = Op.operands[op] >= 1 ? code[pc + 1] : 0;
            int b = Op.operands[op] >= 2 ? code[pc + 2] : 0;
            int c = Op.operands[op] >= 3 ? code[pc + 3] : 0;
            switch (op) {
                case Op.NOP:
                    break;
                case Op.PUSH_UNDEF:
                    pushUndefined();
                    break;
                case Op.PUSH_NULL:
                    getstatic("dev/mooner/neonjs/runtime/Null", "INSTANCE",
                            "Ldev/mooner/neonjs/runtime/Null;");
                    break;
                case Op.PUSH_TRUE:
                    getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
                    break;
                case Op.PUSH_FALSE:
                    getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
                    break;
                case Op.PUSH_CONST:
                    constant(a);
                    break;
                case Op.PUSH_INT:
                    iconst(a);
                    mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)" + OBJ, false);
                    break;
                case Op.POP:
                    mv.visitInsn(POP);
                    break;
                case Op.DUP:
                    mv.visitInsn(DUP);
                    break;
                case Op.DUP2:
                    mv.visitInsn(DUP2);
                    break;
                case Op.DUP3: {
                    int t3 = temp();
                    int t2 = temp();
                    int t1 = temp();
                    mv.visitVarInsn(ASTORE, t3);
                    mv.visitVarInsn(ASTORE, t2);
                    mv.visitVarInsn(ASTORE, t1);
                    for (int t : new int[] {t1, t2, t3, t1, t2, t3}) mv.visitVarInsn(ALOAD, t);
                    break;
                }
                case Op.SWAP:
                    mv.visitInsn(SWAP);
                    break;
                case Op.ROT3:
                    mv.visitInsn(DUP_X2);
                    mv.visitInsn(POP);
                    break;
                case Op.ROT4: {
                    int t4 = temp();
                    int t3 = temp();
                    int t2 = temp();
                    int t1 = temp();
                    mv.visitVarInsn(ASTORE, t4);
                    mv.visitVarInsn(ASTORE, t3);
                    mv.visitVarInsn(ASTORE, t2);
                    mv.visitVarInsn(ASTORE, t1);
                    for (int t : new int[] {t4, t1, t2, t3}) mv.visitVarInsn(ALOAD, t);
                    break;
                }
                case Op.LOAD_REG:
                    mv.visitVarInsn(ALOAD, reg(a));
                    break;
                case Op.STORE_REG:
                    mv.visitVarInsn(ASTORE, reg(a));
                    break;
                case Op.LOAD_REG_TDZ:
                case Op.CHECK_REG_TDZ:
                    mv.visitVarInsn(ALOAD, reg(a));
                    iconst(b);
                    consts();
                    rt("tdz", "(" + OBJ + "I" + CONSTS_D + ")" + OBJ);
                    if (op == Op.CHECK_REG_TDZ) mv.visitInsn(POP);
                    break;
                case Op.PUSH_SCOPE:
                    frame();
                    consts();
                    iconst(a);
                    rt("pushScope", "(" + FRAME_D + CONSTS_D + "I)V");
                    break;
                case Op.POP_SCOPE:
                    frame();
                    rt("popScope", "(" + FRAME_D + ")V");
                    break;
                case Op.COPY_SCOPE:
                    frame();
                    rt("copyScope", "(" + FRAME_D + ")V");
                    break;
                case Op.GET_ENV:
                    frame();
                    mv.visitFieldInsn(GETFIELD, FRAME, "env", "Ldev/mooner/neonjs/runtime/Env;");
                    break;
                case Op.SET_ENV:
                    frame();
                    rt("setEnv", "(" + OBJ + FRAME_D + ")V");
                    break;
                case Op.PUSH_WITH:
                    frame();
                    rt("pushWith", "(" + OBJ + FRAME_D + ")V");
                    break;
                case Op.LOAD_ENV:
                    if (inl != null) {
                        mv.visitVarInsn(ALOAD, inl.envLocal);
                        iconst(a);
                        iconst(b);
                        rt("loadEnvE", "(" + OBJ + "II)" + OBJ);
                    } else {
                        frame();
                        iconst(a);
                        iconst(b);
                        rt("loadEnv", "(" + FRAME_D + "II)" + OBJ);
                    }
                    break;
                case Op.STORE_ENV:
                    if (inl != null) {
                        mv.visitVarInsn(ALOAD, inl.envLocal);
                        iconst(a);
                        iconst(b);
                        rt("storeEnvE", "(" + OBJ + OBJ + "II)V");
                    } else {
                        frame();
                        iconst(a);
                        iconst(b);
                        rt("storeEnv", "(" + OBJ + FRAME_D + "II)V");
                    }
                    break;
                case Op.LOAD_IMPORT:
                    frame();
                    iconst(a);
                    iconst(b);
                    rt("loadEnv", "(" + FRAME_D + "II)" + OBJ);
                    mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/vm/Modules", "deref",
                            "(" + OBJ + ")" + OBJ, false);
                    break;
                case Op.LOAD_ENV_TDZ:
                case Op.CHECK_ENV_TDZ:
                    if (inl != null) {
                        mv.visitVarInsn(ALOAD, inl.envLocal);
                        iconst(a);
                        iconst(b);
                        iconst(c);
                        consts();
                        rt("loadEnvTdzE", "(" + OBJ + "III" + CONSTS_D + ")" + OBJ);
                    } else {
                        frame();
                        iconst(a);
                        iconst(b);
                        iconst(c);
                        consts();
                        rt("loadEnvTdz", "(" + FRAME_D + "III" + CONSTS_D + ")" + OBJ);
                    }
                    if (op == Op.CHECK_ENV_TDZ) mv.visitInsn(POP);
                    break;
                case Op.LOAD_NAME:
                    frame();
                    constant(a);
                    rt("loadName", "(" + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.LOAD_NAME_TYPEOF:
                    frame();
                    constant(a);
                    rt("loadNameTypeof", "(" + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.LOAD_NAME_CALL:
                    frame();
                    constant(a);
                    rt("loadNameCall", "(" + FRAME_D + OBJ + ")" + CONSTS_D);
                    mv.visitInsn(DUP);
                    iconst(0);
                    mv.visitInsn(AALOAD);
                    mv.visitInsn(SWAP);
                    iconst(1);
                    mv.visitInsn(AALOAD);
                    break;
                case Op.STORE_NAME:
                    frame();
                    constant(a);
                    rt("storeName", "(" + OBJ + FRAME_D + OBJ + ")V");
                    break;
                case Op.STORE_NAME_VAR:
                    frame();
                    constant(a);
                    rt("storeNameVar", "(" + OBJ + FRAME_D + OBJ + ")V");
                    break;
                case Op.DELETE_NAME:
                    frame();
                    constant(a);
                    rt("deleteName", "(" + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.INIT_NAME:
                    frame();
                    constant(a);
                    rt("initName", "(" + OBJ + FRAME_D + OBJ + ")V");
                    break;
                case Op.LOAD_GLOBAL:
                    frame();
                    constant(a);
                    rt("loadGlobal", "(" + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.LOAD_GLOBAL_TYPEOF:
                    frame();
                    constant(a);
                    rt("loadGlobalTypeof", "(" + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.STORE_GLOBAL:
                    frame();
                    constant(a);
                    rt("storeGlobal", "(" + OBJ + FRAME_D + OBJ + ")V");
                    break;
                case Op.INIT_GLOBAL_LEX:
                    frame();
                    constant(a);
                    rt("initGlobalLex", "(" + OBJ + FRAME_D + OBJ + ")V");
                    break;
                case Op.RESOLVE_NAME:
                    frame();
                    constant(a);
                    rt("resolveName", "(" + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.GET_REF:
                    frame();
                    rt("getRef", "(" + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.PUT_REF:
                    frame();
                    rt("putRef", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.LOAD_THIS:
                    frame();
                    mv.visitFieldInsn(GETFIELD, FRAME, "thisValue", OBJ);
                    break;
                case Op.LOAD_FUNCTION:
                    frame();
                    rt("loadFunction", "(" + FRAME_D + ")" + OBJ);
                    break;
                case Op.LOAD_NEW_TARGET:
                    frame();
                    mv.visitFieldInsn(GETFIELD, FRAME, "newTarget", OBJ);
                    break;
                case Op.LOAD_HOME:
                    frame();
                    rt("loadHome", "(" + FRAME_D + ")" + OBJ);
                    break;
                case Op.CREATE_ARGUMENTS:
                    frame();
                    iconst(a);
                    rt("createArguments", "(" + FRAME_D + "I)" + OBJ);
                    break;
                case Op.CREATE_REST:
                    frame();
                    iconst(a);
                    rt("createRest", "(" + FRAME_D + "I)" + OBJ);
                    break;
                case Op.LOAD_ARG:
                    frame();
                    iconst(a);
                    rt("arg", "(" + FRAME_D + "I)" + OBJ);
                    break;
                case Op.INIT_THIS_REG:
                    mv.visitVarInsn(ALOAD, reg(a));
                    frame();
                    rt("initThisReg", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    mv.visitVarInsn(ASTORE, reg(a));
                    break;
                case Op.INIT_THIS_ENV:
                    frame();
                    iconst(a);
                    iconst(b);
                    rt("initThisEnv", "(" + OBJ + FRAME_D + "II)V");
                    break;
                case Op.INIT_THIS_NAME:
                    frame();
                    rt("initThisName", "(" + OBJ + FRAME_D + ")V");
                    break;
                case Op.THROW:
                    rt("throwValue", "(" + OBJ + ")Ljava/lang/Throwable;");
                    mv.visitInsn(ATHROW);
                    break;
                case Op.THROW_ERROR:
                    iconst(a);
                    iconst(b);
                    consts();
                    rt("throwError", "(II" + CONSTS_D + ")Ljava/lang/Throwable;");
                    mv.visitInsn(ATHROW);
                    break;
                case Op.GET_PROP:
                    constant(a);
                    frame();
                    rt("getProp", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.PUT_PROP:
                    constant(a);
                    frame();
                    rt("putProp", "(" + OBJ + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.GET_ELEM:
                    frame();
                    rt("getElem", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.PUT_ELEM:
                    frame();
                    rt("putElem", "(" + OBJ + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.DELETE_PROP:
                    constant(a);
                    frame();
                    rt("deleteProp", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.DELETE_ELEM:
                    frame();
                    rt("deleteElem", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.TO_PROPERTY_KEY:
                    rt("toPropertyKey", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.TO_KEY_FOR_BASE:
                    // (obj key -- obj key')
                    mv.visitInsn(DUP2);
                    rt("keyForBase", "(" + OBJ + OBJ + ")" + OBJ);
                    mv.visitInsn(SWAP);
                    mv.visitInsn(POP);
                    break;
                case Op.GET_SUPER:
                    frame();
                    rt("getSuper", "(" + OBJ + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.PUT_SUPER:
                    frame();
                    rt("putSuper", "(" + OBJ + OBJ + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.GET_SUPER_BASE:
                    rt("superBase", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.GET_PRIVATE:
                    rt("getPrivate", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.PUT_PRIVATE:
                    rt("putPrivate", "(" + OBJ + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.HAS_PRIVATE:
                    rt("hasPrivate", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.ADD:
                    rt("add", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SUB:
                    rt("sub", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.MUL:
                    rt("mul", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.DIV:
                    rt("div", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.MOD:
                    rt("mod", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.EXP:
                    rt("exp", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SHL:
                    rt("shl", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SAR:
                    rt("sar", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SHR:
                    rt("shr", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.BAND:
                    rt("band", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.BOR:
                    rt("bor", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.BXOR:
                    rt("bxor", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.EQ:
                    rt("eq", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.NE:
                    rt("ne", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SEQ:
                    rt("seq", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SNE:
                    rt("sne", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.LT:
                    rt("lt", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.GT:
                    rt("gt", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.LE:
                    rt("le", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.GE:
                    rt("ge", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.INSTANCEOF:
                    rt("instanceOf", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.IN:
                    rt("inOp", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.NEG:
                    rt("neg", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.TO_NUMBER:
                    rt("toNumber", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.NOT:
                    rt("not", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.BNOT:
                    rt("bnot", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.TYPEOF:
                    rt("typeOf", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.TO_NUMERIC:
                    rt("toNumeric", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.INC:
                    rt("inc", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.DEC:
                    rt("dec", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.TO_STRING:
                    rt("toStr", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.CONCAT:
                    rt("concat", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.TO_OBJECT:
                    frame();
                    rt("toObject", "(" + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.REQUIRE_COERCIBLE:
                    rt("requireCoercible", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.CHECK_OBJECT:
                    iconst(a);
                    consts();
                    rt("checkObject", "(" + OBJ + "I" + CONSTS_D + ")" + OBJ);
                    break;
                case Op.DERIVED_RETURN:
                    rt("derivedReturn", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.JUMP:
                    if (a <= pc) interruptCheck();
                    mv.visitJumpInsn(GOTO, label(a));
                    break;
                case Op.JUMP_IF_TRUE:
                case Op.JUMP_IF_FALSE:
                    if (a <= pc) interruptCheck();
                    rt("truthy", "(" + OBJ + ")Z");
                    mv.visitJumpInsn(op == Op.JUMP_IF_TRUE ? IFNE : IFEQ, label(a));
                    break;
                case Op.JUMP_IF_NULLISH:
                case Op.JUMP_IF_NOT_NULLISH:
                    rt("isNullish", "(" + OBJ + ")Z");
                    mv.visitJumpInsn(op == Op.JUMP_IF_NULLISH ? IFNE : IFEQ, label(a));
                    break;
                case Op.JUMP_IF_UNDEFINED:
                case Op.JUMP_IF_NOT_UNDEFINED:
                    rt("isUndefined", "(" + OBJ + ")Z");
                    mv.visitJumpInsn(op == Op.JUMP_IF_UNDEFINED ? IFNE : IFEQ, label(a));
                    break;
                case Op.JUMP_TABLE: {
                    int r = code[pc + 1];
                    int n = code[pc + 2];
                    mv.visitVarInsn(ALOAD, reg(r));
                    rt("toIntIndex", "(" + OBJ + ")I");
                    Label[] targets = new Label[n];
                    for (int i = 0; i < n; i++) targets[i] = label(code[pc + 3 + i]);
                    if (n == 0) {
                        mv.visitInsn(POP);
                        mv.visitJumpInsn(GOTO, label(code[pc + 3]));
                    } else {
                        mv.visitTableSwitchInsn(0, n - 1, label(code[pc + 3 + n]), targets);
                    }
                    break;
                }
                case Op.RETURN:
                    mv.visitInsn(ARETURN);
                    break;
                case Op.CALL: {
                    // from an inlined body: the frame's pc stays at the inlined call (icall* record the body's pc)
                    String method = inl != null ? "icall" : "call";
                    if (a == 0) {
                        frame();
                        iconst(pc);
                        rt(method + "0", "(" + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    } else if (a == 1) {
                        frame();
                        iconst(pc);
                        rt(method + "1", "(" + OBJ + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    } else if (a == 2) {
                        frame();
                        iconst(pc);
                        rt(method + "2", "(" + OBJ + OBJ + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    } else if (a == 3) {
                        frame();
                        iconst(pc);
                        rt(method + "3", "(" + OBJ + OBJ + OBJ + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    } else if (a == 4) {
                        frame();
                        iconst(pc);
                        rt(method + "4", "(" + OBJ + OBJ + OBJ + OBJ + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    } else {
                        packArray(a);
                        frame();
                        iconst(pc);
                        rt(method, "(" + OBJ + OBJ + CONSTS_D + FRAME_D + "I)" + OBJ);
                    }
                    break;
                }
                case Op.CALL_SPREAD:
                    frame();
                    iconst(pc);
                    rt("callSpread", "(" + OBJ + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    break;
                case Op.NEW:
                    packArray(a);
                    frame();
                    iconst(pc);
                    rt(inl != null ? "iconstruct" : "construct", "(" + OBJ + CONSTS_D + FRAME_D + "I)" + OBJ);
                    break;
                case Op.NEW_SPREAD:
                    frame();
                    iconst(pc);
                    rt("newSpread", "(" + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    break;
                case Op.CALL_EVAL:
                    packArray(a);
                    frame();
                    iconst(b);
                    iconst(pc);
                    rt("callEval", "(" + OBJ + OBJ + CONSTS_D + FRAME_D + "II)" + OBJ);
                    if ((b & 2) != 0) returnIfTail();
                    break;
                case Op.TAIL_CALL:
                    packArray(a);
                    frame();
                    iconst(pc);
                    rt("tailCall", "(" + OBJ + OBJ + CONSTS_D + FRAME_D + "I)" + OBJ);
                    returnIfTail();
                    break;
                case Op.CALL_EVAL_SPREAD:
                    frame();
                    iconst(a);
                    iconst(pc);
                    rt("callEvalSpread", "(" + OBJ + OBJ + OBJ + FRAME_D + "II)" + OBJ);
                    break;
                case Op.SUPER_CALL:
                    packArray(a);
                    frame();
                    iconst(pc);
                    rt("superCall", "(" + OBJ + OBJ + CONSTS_D + FRAME_D + "I)" + OBJ);
                    break;
                case Op.SUPER_CALL_SPREAD:
                    frame();
                    iconst(pc);
                    rt("superCallSpread", "(" + OBJ + OBJ + OBJ + FRAME_D + "I)" + OBJ);
                    break;
                case Op.GET_PROTO_OF:
                    rt("getProtoOf", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.INIT_INSTANCE:
                    rt("initInstance", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.NEW_OBJECT:
                    frame();
                    rt("newObject", "(" + FRAME_D + ")" + OBJ);
                    break;
                case Op.NEW_OBJECT_LITERAL:
                    // (v1..vn -- obj): move the values into an Object[] that becomes the object's values array
                    iconst(b);
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                    for (int i = b - 1; i >= 0; i--) {
                        mv.visitInsn(DUP_X1);   // .. arr v arr
                        mv.visitInsn(SWAP);     // .. arr arr v
                        iconst(i);
                        mv.visitInsn(SWAP);     // .. arr arr i v
                        mv.visitInsn(AASTORE);  // .. arr
                    }
                    constant(a);
                    frame();
                    rt("newObjectLiteral", "([" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.NEW_ARRAY:
                    frame();
                    rt("newArray", "(" + FRAME_D + ")" + OBJ);
                    break;
                case Op.ARRAY_PUSH:
                    rt("arrayPush", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.ARRAY_HOLE:
                    rt("arrayHole", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.ARRAY_SPREAD:
                    frame();
                    rt("arraySpread", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.DEFINE_FIELD:
                    constant(a);
                    rt("defineField", "(" + OBJ + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.DEFINE_FIELD_ELEM:
                    rt("defineFieldElem", "(" + OBJ + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.DEFINE_GETTER:
                case Op.DEFINE_SETTER:
                case Op.DEFINE_METHOD:
                    iconst(op);
                    iconst(a);
                    rt("defineMethod", "(" + OBJ + OBJ + OBJ + "II)" + OBJ);
                    break;
                case Op.COPY_DATA_PROPS:
                    rt("copyDataProps", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.COPY_DATA_PROPS_EXCL:
                    packArray(a);
                    rt("copyDataPropsExcl", "(" + OBJ + OBJ + CONSTS_D + ")" + OBJ);
                    break;
                case Op.SET_PROTO:
                    rt("setProto", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.SET_FUNCTION_NAME:
                    mv.visitInsn(DUP2);
                    iconst(a);
                    consts();
                    rt("setFunctionName", "(" + OBJ + OBJ + "I" + CONSTS_D + ")V");
                    break;
                case Op.MAKE_CLOSURE:
                    frame();
                    consts();
                    iconst(a);
                    rt("makeClosure", "(" + FRAME_D + CONSTS_D + "I)" + OBJ);
                    break;
                case Op.MAKE_METHOD:
                    mv.visitVarInsn(ALOAD, reg(b));
                    frame();
                    consts();
                    iconst(a);
                    rt("makeMethod", "(" + OBJ + FRAME_D + CONSTS_D + "I)" + OBJ);
                    break;
                case Op.MAKE_CLASS: {
                    // stack: [heritage]? [name]? -> proto ctor
                    boolean hasName = (b & 2) != 0;
                    boolean hasHeritage = (b & 1) != 0;
                    int tn = temp();
                    if (hasName) {
                        mv.visitVarInsn(ASTORE, tn);
                    } else {
                        mv.visitInsn(ACONST_NULL);
                        mv.visitVarInsn(ASTORE, tn);
                    }
                    if (!hasHeritage) {
                        getstatic("dev/mooner/neonjs/runtime/NotFound", "INSTANCE",
                                "Ldev/mooner/neonjs/runtime/NotFound;");
                    }
                    mv.visitVarInsn(ALOAD, tn);
                    frame();
                    consts();
                    iconst(a);
                    rt("makeClass", "(" + OBJ + OBJ + FRAME_D + CONSTS_D + "I)" + CONSTS_D);
                    mv.visitInsn(DUP);
                    iconst(0);
                    mv.visitInsn(AALOAD);
                    mv.visitInsn(SWAP);
                    iconst(1);
                    mv.visitInsn(AALOAD);
                    break;
                }
                case Op.NEW_PRIVATE_NAME:
                    constant(a);
                    rt("newPrivateName", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.ADD_FIELD:
                    rt("addField", "(" + OBJ + OBJ + OBJ + ")V");
                    break;
                case Op.ADD_PRIVATE_METHOD:
                    iconst(a);
                    rt("addPrivateMethod", "(" + OBJ + OBJ + OBJ + "I)V");
                    break;
                case Op.STATIC_PRIVATE_METHOD:
                    iconst(a);
                    rt("staticPrivateMethod", "(" + OBJ + OBJ + OBJ + "I)V");
                    break;
                case Op.RUN_FIELD:
                    frame();
                    iconst(pc);
                    rt("runField", "(" + OBJ + OBJ + OBJ + FRAME_D + "I)V");
                    break;
                case Op.GET_ITERATOR:
                    frame();
                    rt("getIterator", "(" + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.ITER_STEP:
                    mv.visitVarInsn(ALOAD, reg(a));
                    rt("iterStep", "(" + OBJ + ")" + OBJ);
                    mv.visitInsn(DUP);
                    rt("isNotFound", "(" + OBJ + ")Z");
                    Label contIter = new Label();
                    mv.visitJumpInsn(IFEQ, contIter);
                    mv.visitInsn(POP);
                    mv.visitJumpInsn(GOTO, label(b));
                    mv.visitLabel(contIter);
                    break;
                case Op.ITER_STEP_U:
                    mv.visitVarInsn(ALOAD, reg(a));
                    rt("iterStepU", "(" + OBJ + ")" + OBJ);
                    break;
                case Op.ITER_REST:
                    mv.visitVarInsn(ALOAD, reg(a));
                    frame();
                    rt("iterRest", "(" + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.ITER_CLOSE:
                    mv.visitVarInsn(ALOAD, reg(a));
                    rt("iterClose", "(" + OBJ + ")V");
                    break;
                case Op.ITER_CLOSE_THROW:
                    mv.visitVarInsn(ALOAD, reg(a));
                    rt("iterCloseThrow", "(" + OBJ + ")V");
                    break;
                case Op.FOR_IN_START:
                    frame();
                    rt("forInStart", "(" + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.FOR_IN_NEXT:
                    mv.visitVarInsn(ALOAD, reg(a));
                    rt("forInNext", "(" + OBJ + ")" + OBJ);
                    mv.visitInsn(DUP);
                    Label contForIn = new Label();
                    mv.visitJumpInsn(IFNONNULL, contForIn);
                    mv.visitInsn(POP);
                    mv.visitJumpInsn(GOTO, label(b));
                    mv.visitLabel(contForIn);
                    break;
                case Op.DEBUGGER:
                    frame();
                    rt("debugger", "(" + FRAME_D + ")V");
                    break;
                case Op.DISPOSE_NEW:
                    rt("disposeNew", "()" + OBJ);
                    break;
                case Op.ADD_DISPOSABLE:
                    iconst(a);
                    frame();
                    rt("addDisposable", "(" + OBJ + OBJ + "I" + FRAME_D + ")V");
                    break;
                case Op.DISPOSE_SYNC:
                    frame();
                    rt("disposeSync", "(" + OBJ + OBJ + OBJ + FRAME_D + ")V");
                    break;
                case Op.TEMPLATE_OBJECT:
                    frame();
                    consts();
                    iconst(a);
                    rt("templateObject", "(" + FRAME_D + CONSTS_D + "I)" + OBJ);
                    break;
                case Op.NEW_REGEXP:
                    frame();
                    consts();
                    iconst(a);
                    rt("newRegExp", "(" + FRAME_D + CONSTS_D + "I)" + OBJ);
                    break;
                case Op.IMPORT_META:
                    frame();
                    rt("importMeta", "(" + FRAME_D + ")" + OBJ);
                    break;
                case Op.DYNAMIC_IMPORT:
                    frame();
                    rt("dynamicImport", "(" + OBJ + OBJ + FRAME_D + ")" + OBJ);
                    break;
                case Op.CLASS_DEF_NEW:
                    rt("classDefNew", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.CLASS_ELEMENT:
                    iconst(a);
                    rt("classElement", "(" + OBJ + OBJ + OBJ + OBJ + "I)V");
                    break;
                case Op.CLASS_FINISH:
                    rt("classFinish", "(" + OBJ + ")V");
                    break;
                case Op.CLASS_DECORATE:
                    rt("classDecorate", "(" + OBJ + OBJ + ")" + OBJ);
                    break;
                case Op.CLASS_STATIC_INIT:
                    rt("classStaticInit", "(" + OBJ + ")V");
                    break;
                case Op.DYNAMIC_IMPORT_PHASE:
                    frame();
                    constant(a);
                    rt("dynamicImportPhase", "(" + OBJ + OBJ + FRAME_D + OBJ + ")" + OBJ);
                    break;
                case Op.DECLARE_GLOBALS:
                    packArray(b);
                    frame();
                    consts();
                    iconst(a);
                    iconst(pc);
                    rt("declareGlobals", "(" + CONSTS_D + FRAME_D + CONSTS_D + "II)V");
                    break;
                case Op.DECLARE_EVAL:
                    packArray(b);
                    frame();
                    consts();
                    iconst(a);
                    iconst(pc);
                    rt("declareEval", "(" + CONSTS_D + FRAME_D + CONSTS_D + "II)V");
                    break;
                default:
                    throw new JitBailout("unsupported op " + Op.names[op]);
            }
        }
    }

}
