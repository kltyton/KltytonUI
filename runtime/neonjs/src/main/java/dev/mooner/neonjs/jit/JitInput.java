/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.jit;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.Source;
import java.io.DataOutputStream;
import java.security.DigestOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything the class generated for a code block depends on, and nothing else: {@link JvmCompiler}'s code generator
 * reads only these fields, so {@link #getIdentity()}, a hash of all of them, decides whether two code blocks can share
 * a class. Constants are not part of it (generated code reads them from the running frame's code block at run time).
 */
public final class JitInput {
    /** Function name part of the class name (for profilers). */
    public final String name;
    public final int[] code;
    public final int[] handlers;
    public final int numRegs;
    public final int[] paramRegs;
    /**
     * Kind of each constant ({@link #CONST_INT}, {@link #CONST_NUMBER} or {@link #CONST_OTHER}). The generated code
     * treats number constants as {@code double}s and int32 ones as {@code int}s, so code blocks may share a class only
     * if their constants have the same kinds (the values may differ).
     */
    public final byte[] constKinds;
    /**
     * Calls compiled with the callee's body inlined (Inlining), by site k: the pc of the CALL and the callee's input
     * (part of {@link #getIdentity()}). {@link #inlineTargets} are the callees themselves, for
     * {@code CodeBlock.inlineTargets}.
     */
    public final List<InlineSite> inlineSites;
    public final CodeBlock[] inlineTargets;
    /** Start pc of each statement (the generated code records the pc there). */
    public final int[] statementPcs;
    /** Debug info: source line of each statement (or -1), and the source name; null without debug info. */
    public final int[] lines;
    public final String sourceName;

    private final AtomicReference<String> identity = new AtomicReference<>();

    public JitInput(CodeBlock cb) {
        this(cb, JvmCompiler.DEBUG_INFO, true);
    }

    public JitInput(CodeBlock cb, boolean debugInfo) {
        this(cb, debugInfo, true);
    }

    public JitInput(CodeBlock cb, boolean debugInfo, boolean planInlining) {
        name = JvmCompiler.sanitize(cb.name);
        code = cb.code;
        handlers = cb.handlers;
        numRegs = cb.numRegs;
        paramRegs = cb.paramRegs;
        constKinds = new byte[cb.constants.length];
        for (int i = 0; i < constKinds.length; i++) constKinds[i] = constKind(cb.constants[i]);

        List<Inlining.Site> plan = planInlining && JvmCompiler.TYPED
                ? Inlining.INSTANCE.plan(cb) : List.of();
        List<InlineSite> sites = new ArrayList<>(plan.size());
        for (Inlining.Site site : plan) {
            sites.add(new InlineSite(site.pc(), new JitInput(site.target(), false, false)));
        }
        inlineSites = List.copyOf(sites);
        if (plan.isEmpty()) {
            inlineTargets = null;
        } else {
            inlineTargets = new CodeBlock[plan.size()];
            for (int i = 0; i < plan.size(); i++) inlineTargets[i] = plan.get(i).target();
        }

        int[] lineTable = cb.lineTable;
        statementPcs = new int[lineTable.length / 2];
        for (int i = 0; i < statementPcs.length; i++) statementPcs[i] = lineTable[2 * i];
        Source src = cb.source;
        if (debugInfo && src != null) {
            lines = new int[lineTable.length / 2];
            for (int i = 0; i < lines.length; i++) {
                int position = lineTable[2 * i + 1];
                lines[i] = position >= 0 ? src.lineCol(position).line() : -1;
            }
            sourceName = src.name;
        } else {
            lines = null;
            sourceName = null;
        }
    }

    /** A call site compiled with the callee's body inlined. */
    public record InlineSite(int pc, JitInput callee) {
    }

    /** 128 bits of the SHA-256 of all fields, in hex. */
    public String getIdentity() {
        String known = identity.get();
        if (known != null) return known;
        String computed = computeIdentity();
        return identity.compareAndSet(null, computed) ? computed : identity.get();
    }

    private String computeIdentity() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        try {
            DataOutputStream out = new DataOutputStream(new DigestOutputStream(new NullOutput(), digest));
            out.writeInt(FORMAT);
            out.writeBoolean(JvmCompiler.TYPED);
            out.writeBoolean(JvmCompiler.INT32);
            out.writeBoolean(JvmCompiler.ELEMS);
            out.writeBoolean(Inlining.ENABLED);
            out.writeInt(inlineSites.size());
            for (InlineSite site : inlineSites) {
                out.writeInt(site.pc());
                out.writeUTF(site.callee().getIdentity());
            }
            out.writeUTF(name);
            writeInts(out, code);
            writeInts(out, handlers);
            out.writeInt(numRegs);
            writeInts(out, paramRegs);
            out.writeInt(constKinds.length);
            out.write(constKinds);
            writeInts(out, statementPcs);
            writeInts(out, lines);
            out.writeUTF(sourceName == null ? "" : sourceName);
            out.flush();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            hex.append(String.format("%02x", hash[i] & 0xff));
        }
        return hex.toString();
    }

    private static void writeInts(DataOutputStream out, int[] values) throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(values.length);
        for (int value : values) out.writeInt(value);
    }

    /** Changes when the code generator changes what it emits for the same input. */
    private static final int FORMAT = 7;
    public static final byte CONST_OTHER = 0;
    public static final byte CONST_NUMBER = 1;
    /** A number that is an int32 (and not -0). */
    public static final byte CONST_INT = 2;

    public static byte constKind(Object value) {
        if (!(value instanceof Double number)) return CONST_OTHER;
        int integer = number.intValue();
        if ((double) integer == number && !(number == 0.0 && 1.0 / number < 0.0)) return CONST_INT;
        return CONST_NUMBER;
    }

    /** OutputStream.nullOutputStream needs JDK 11 / Android API 33. */
    private static final class NullOutput extends OutputStream {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
        }
    }
}
