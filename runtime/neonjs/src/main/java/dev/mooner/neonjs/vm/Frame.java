package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.jit.CompiledCode;
import dev.mooner.neonjs.runtime.Env;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.Arrays;

/** Interpreter activation record; derived from NeonJS and adapted to Java. */
public final class Frame {
    public static final Object[] NO_SLOTS = new Object[0];
    public static final int SUSPEND_YIELD = 1;
    public static final int SUSPEND_AWAIT = 2;
    public static final int SUSPEND_INITIAL = 3;
    public static final int SUSPEND_YIELD_RAW = 4;
    public static final int MODE_NEXT = 0;
    public static final int MODE_THROW = 1;
    public static final int MODE_RETURN = 2;

    public final JSClosure fn;
    public final CodeBlock code;
    public final Realm realm;
    public Object thisValue;
    public final Object[] args;
    public final Object newTarget;
    public Env env;
    public final Object[] slots;
    public int sp;
    public int pc;
    public FrameExt ext;
    public Frame parent;
    public JSClosure inlineFn;
    public int inlinePc;

    public Frame(JSClosure function, CodeBlock code, Realm realm, Object receiver, Object[] arguments,
                 Object newTarget, Env environment) {
        this(function, code, realm, receiver, arguments, newTarget, environment, (CompiledCode) code.compiled);
    }

    public Frame(JSClosure function, CodeBlock code, Realm realm, Object receiver, Object[] arguments,
                 Object newTarget, Env environment, CompiledCode compiled) {
        fn = function;
        this.code = code;
        this.realm = realm;
        thisValue = receiver;
        args = arguments;
        this.newTarget = newTarget;
        env = environment;
        slots = compiled != null ? NO_SLOTS : new Object[code.numRegs + code.maxStack];
        sp = code.numRegs;
        if (compiled == null) initSlots();
    }

    private FrameExt extension() {
        FrameExt current = ext;
        if (current == null) {
            current = new FrameExt();
            ext = current;
        }
        return current;
    }

    public int getResumeMode() { return ext == null ? 0 : ext.resumeMode; }
    public void setResumeMode(int mode) { extension().resumeMode = mode; }
    public int getSuspendKind() { return ext == null ? 0 : ext.suspendKind; }
    public void setSuspendKind(int kind) { extension().suspendKind = kind; }
    public Object getSuspendValue() { return ext == null ? null : ext.suspendValue; }
    public void setSuspendValue(Object value) { extension().suspendValue = value; }
    public Object getGenerator() { return ext == null ? null : ext.generator; }
    public void setGenerator(Object generator) { extension().generator = generator; }
    public int getAwaitPc() { return ext == null ? 0 : ext.awaitPc; }
    public void setAwaitPc(int pc) { extension().awaitPc = pc; }
    public JSClosure getTailFn() { return ext == null ? null : ext.tailFn; }
    public void setTailFn(JSClosure function) { extension().tailFn = function; }
    public Object getTailThis() { return ext == null ? null : ext.tailThis; }
    public void setTailThis(Object receiver) { extension().tailThis = receiver; }
    public Object[] getTailArgs() { return ext == null ? null : ext.tailArgs; }
    public void setTailArgs(Object[] arguments) { extension().tailArgs = arguments; }
    public boolean getTailPending() { return ext != null && ext.tailPending; }
    public void setTailPending(boolean pending) { extension().tailPending = pending; }
    public JSObject getHomeObject() { return ext == null ? null : ext.homeObject; }
    public void setHomeObject(JSObject object) { extension().homeObject = object; }

    public boolean isCompiled() {
        return slots == NO_SLOTS;
    }

    private void initSlots() {
        Arrays.fill(slots, 0, code.numRegs, Undefined.INSTANCE);
        int[] registers = code.paramRegs;
        if (registers != null) {
            int count = Math.min(registers.length, args.length);
            for (int i = 0; i < count; i++) {
                int register = registers[i];
                if (register >= 0) slots[register] = args[i];
            }
        }
    }

    public static final class FrameExt {
        public int resumeMode;
        public int suspendKind;
        public Object suspendValue;
        public Object generator;
        public int awaitPc;
        public JSClosure tailFn;
        public Object tailThis;
        public Object[] tailArgs;
        public boolean tailPending;
        public JSObject homeObject;
    }
}
