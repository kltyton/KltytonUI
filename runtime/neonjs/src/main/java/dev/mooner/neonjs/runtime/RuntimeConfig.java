package dev.mooner.neonjs.runtime;

import dev.mooner.neonjs.jit.CodeDefiner;
import java.time.ZoneId;

/** Engine configuration consulted by execution; derived from NeonJS and adapted to Java. */
public final class RuntimeConfig {
    public String defaultLocale;
    public boolean intl = true;
    public ZoneId timeZone;
    public int maxCallDepth = 3000;
    public long maxExecutionMillis;
    public long maxStatements;
    public boolean propagateInternalErrors;
    public int maxStringLength = Rope.MAX_LENGTH;
    public boolean annexB = true;
    public long maxAllocatedBytes;
    public boolean limitsPerTask;
    public boolean allowCodeGeneration = true;
    public int executionMode;
    public int jitThreshold = 1000;
    public boolean backgroundJit = true;
    public CodeDefiner codeDefiner;
}
