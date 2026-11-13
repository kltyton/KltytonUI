/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Resource and capability limits applied to every context of an engine.
 *
 * <p>Limits are enforced cooperatively by the engine at loop back-edges and calls, so a script cannot block the
 * host indefinitely. Host code always runs unrestricted.
 *
 * <p>Derived from NeonJS and adapted to Java.
 */
public final class SandboxPolicy {
    /** No limits (trusted code). */
    public static final SandboxPolicy UNRESTRICTED = new Builder().build();

    /** Reasonable defaults for untrusted code: 5s, depth 1000, no eval, no Java global. */
    public static final SandboxPolicy STRICT = new Builder()
            .maxExecutionTime(5000)
            .maxCallDepth(1000)
            .allowEval(false)
            .build();

    /** Wall-clock limit for a single top-level evaluation (eval / call from host), in milliseconds. 0 = unlimited. */
    public final long maxExecutionMillis;

    /** Approximate instruction budget per top-level evaluation. 0 = unlimited. */
    public final long maxStatements;

    /** Maximum JS call depth. */
    public final int maxCallDepth;

    /** Maximum length of a JS string. */
    public final int maxStringLength;

    /** Maximum bytes the evaluating thread may allocate during one top-level evaluation (HotSpot only). 0 = unlimited. */
    public final long maxAllocatedBytes;

    /** Whether eval and the Function/GeneratorFunction/AsyncFunction constructors may compile code at runtime. */
    public final boolean allowEval;

    /** Whether the Java global (Java.type etc.) is installed. Requires HostAccess permitting class lookup. */
    public final boolean exposeJavaGlobal;

    /** Makes Math.random deterministic (seeded) when set. */
    public final Long randomSeed;

    /** When set, Date.now returns this value. */
    public final Long fixedTimeMillis;

    /** Default locale for Intl (BCP 47); null = the host's ("en-US" when Builder.deterministic is used). */
    public final String defaultLocale;

    /** Local time zone seen by Date and Temporal.Now; null = the host's (UTC when Builder.deterministic is used). */
    public final ZoneId timeZone;

    /** Most timers (setTimeout / setInterval) a context may have pending. */
    public final int maxTimers;

    /**
     * Whether the time, instruction and allocation limits apply to each task of the event loop rather than to the
     * whole runEventLoop / await / evalModule call, and waiting for the next task counts against none of them.
     */
    public final boolean limitsPerTask;

    private SandboxPolicy(Builder builder) {
        this.maxExecutionMillis = builder.maxExecutionMillis;
        this.maxStatements = builder.maxStatements;
        this.maxCallDepth = builder.maxCallDepth;
        this.maxStringLength = builder.maxStringLength;
        this.maxAllocatedBytes = builder.maxAllocatedBytes;
        this.allowEval = builder.allowEval;
        this.exposeJavaGlobal = builder.exposeJavaGlobal;
        this.randomSeed = builder.randomSeed;
        this.fixedTimeMillis = builder.fixedTimeMillis;
        this.defaultLocale = builder.defaultLocale != null
                ? builder.defaultLocale
                : (builder.randomSeed != null ? "en-US" : null);
        this.timeZone = builder.timeZone != null
                ? builder.timeZone
                : (builder.randomSeed != null ? ZoneOffset.UTC : null);
        this.maxTimers = builder.maxTimers;
        this.limitsPerTask = builder.limitsPerTask;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long getMaxExecutionMillis() {
        return maxExecutionMillis;
    }

    public long getMaxStatements() {
        return maxStatements;
    }

    public int getMaxCallDepth() {
        return maxCallDepth;
    }

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public long getMaxAllocatedBytes() {
        return maxAllocatedBytes;
    }

    public boolean getAllowEval() {
        return allowEval;
    }

    public boolean getExposeJavaGlobal() {
        return exposeJavaGlobal;
    }

    public Long getRandomSeed() {
        return randomSeed;
    }

    public Long getFixedTimeMillis() {
        return fixedTimeMillis;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public ZoneId getTimeZone() {
        return timeZone;
    }

    public int getMaxTimers() {
        return maxTimers;
    }

    public boolean getLimitsPerTask() {
        return limitsPerTask;
    }

    public static final class Builder {
        public long maxExecutionMillis = 0L;
        public long maxStatements = 0L;
        public int maxCallDepth = 3000;
        public int maxStringLength = (1 << 30) - 25;
        public long maxAllocatedBytes = 0L;
        public boolean allowEval = true;
        public boolean exposeJavaGlobal = false;
        public Long randomSeed = null;
        public Long fixedTimeMillis = null;
        public ZoneId timeZone = null;
        public String defaultLocale = null;
        public int maxTimers = 10_000;
        public boolean limitsPerTask = false;

        public Builder maxExecutionTime(long millis) {
            this.maxExecutionMillis = millis;
            return this;
        }

        public Builder maxStatements(long n) {
            this.maxStatements = n;
            return this;
        }

        public Builder maxCallDepth(int n) {
            this.maxCallDepth = n;
            return this;
        }

        public Builder maxStringLength(int n) {
            this.maxStringLength = n;
            return this;
        }

        public Builder maxAllocatedBytes(long n) {
            this.maxAllocatedBytes = n;
            return this;
        }

        public Builder allowEval(boolean b) {
            this.allowEval = b;
            return this;
        }

        public Builder exposeJavaGlobal(boolean b) {
            this.exposeJavaGlobal = b;
            return this;
        }

        public Builder deterministic(long seed, Long fixedTime) {
            this.randomSeed = seed;
            this.fixedTimeMillis = fixedTime;
            return this;
        }

        /** Local time zone for scripts (e.g. "UTC", "Asia/Seoul", "+09:00"); hides the host's zone. */
        public Builder timeZone(String id) {
            this.timeZone = ZoneId.of(id);
            return this;
        }

        /** Default locale for Intl (e.g. "en-US", "ko-KR"); hides the host's locale. */
        public Builder defaultLocale(String tag) {
            this.defaultLocale = tag;
            return this;
        }

        /** Caps the pending timers of a context (RangeError beyond); default 10,000. */
        public Builder maxTimers(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("maxTimers must be >= 0");
            }
            this.maxTimers = n;
            return this;
        }

        /** Applies the time, instruction and allocation limits to each task of the event loop (default: to each call). */
        public Builder limitsPerTask(boolean b) {
            this.limitsPerTask = b;
            return this;
        }

        public SandboxPolicy build() {
            return new SandboxPolicy(this);
        }

        public long getMaxExecutionMillis() {
            return maxExecutionMillis;
        }

        public void setMaxExecutionMillis(long maxExecutionMillis) {
            this.maxExecutionMillis = maxExecutionMillis;
        }

        public long getMaxStatements() {
            return maxStatements;
        }

        public void setMaxStatements(long maxStatements) {
            this.maxStatements = maxStatements;
        }

        public int getMaxCallDepth() {
            return maxCallDepth;
        }

        public void setMaxCallDepth(int maxCallDepth) {
            this.maxCallDepth = maxCallDepth;
        }

        public int getMaxStringLength() {
            return maxStringLength;
        }

        public void setMaxStringLength(int maxStringLength) {
            this.maxStringLength = maxStringLength;
        }

        public long getMaxAllocatedBytes() {
            return maxAllocatedBytes;
        }

        public void setMaxAllocatedBytes(long maxAllocatedBytes) {
            this.maxAllocatedBytes = maxAllocatedBytes;
        }

        public boolean getAllowEval() {
            return allowEval;
        }

        public void setAllowEval(boolean allowEval) {
            this.allowEval = allowEval;
        }

        public boolean getExposeJavaGlobal() {
            return exposeJavaGlobal;
        }

        public void setExposeJavaGlobal(boolean exposeJavaGlobal) {
            this.exposeJavaGlobal = exposeJavaGlobal;
        }

        public Long getRandomSeed() {
            return randomSeed;
        }

        public void setRandomSeed(Long randomSeed) {
            this.randomSeed = randomSeed;
        }

        public Long getFixedTimeMillis() {
            return fixedTimeMillis;
        }

        public void setFixedTimeMillis(Long fixedTimeMillis) {
            this.fixedTimeMillis = fixedTimeMillis;
        }

        public ZoneId getTimeZone() {
            return timeZone;
        }

        public void setTimeZone(ZoneId timeZone) {
            this.timeZone = timeZone;
        }

        public String getDefaultLocale() {
            return defaultLocale;
        }

        public void setDefaultLocale(String defaultLocale) {
            this.defaultLocale = defaultLocale;
        }

        public int getMaxTimers() {
            return maxTimers;
        }

        public void setMaxTimers(int maxTimers) {
            this.maxTimers = maxTimers;
        }

        public boolean getLimitsPerTask() {
            return limitsPerTask;
        }

        public void setLimitsPerTask(boolean limitsPerTask) {
            this.limitsPerTask = limitsPerTask;
        }
    }
}
