package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.CallFeedback;
import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.compiler.DeclInfo;
import dev.mooner.neonjs.compiler.Op;
import dev.mooner.neonjs.compiler.RegExpSite;
import dev.mooner.neonjs.compiler.ScopeInfo;
import dev.mooner.neonjs.compiler.TemplateSite;
import dev.mooner.neonjs.jit.CompiledCode;
import dev.mooner.neonjs.jit.Inlining;
import dev.mooner.neonjs.jit.Jit;
import dev.mooner.neonjs.runtime.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import static dev.mooner.neonjs.runtime.Values.EMPTY_ARGS;

/** Stack-bytecode interpreter; derived from NeonJS and adapted to Java. */
public final class Interpreter {
    public static final Object SUSPENDED = new Object();
    public static final Object TAIL = new Object();

    private Interpreter() {
    }

    public static Object tailCall(Frame frame, Object value, Object receiver, Object[] arguments) {
        if (value instanceof JSClosure closure
                && (closure.code.flags & (CodeBlock.CLASS_CTOR | CodeBlock.GENERATOR | CodeBlock.ASYNC)) == 0) {
            frame.setTailFn(closure);
            frame.setTailThis(receiver);
            frame.setTailArgs(arguments);
            return TAIL;
        }
        if (value instanceof JSObject function && (function.special & JSObject.CALLABLE) != 0) {
            return function.call(receiver, arguments);
        }
        throw Rt.notCallable(value, frame.code, frame.pc);
    }

    public static Object callClosure(JSClosure fn0, Object thisArg0, Object[] args0) {
        JSClosure fn = fn0;
        Object thisArg = thisArg0;
        Object[] args = args0;
        while (true) {
            CodeBlock code = fn.code;
            int flags = code.flags;
            if ((flags & CodeBlock.CLASS_CTOR) != 0) {
                throw fn.realm.typeError("Class constructor " + fn.debugName() + " cannot be invoked without 'new'");
            }
            Realm realm = fn.realm;
            CompiledCode compiled;
            if ((flags & (CodeBlock.GENERATOR | CodeBlock.ASYNC)) != 0) compiled = null;
            else {
                Object installed = code.compiled;
                compiled = installed != null && code.childrenQueued ? (CompiledCode) installed : Jit.prepare(code, realm.agent);
            }
            Object thisValue;
            if ((flags & CodeBlock.ARROW) != 0) thisValue = fn.lexicalThis;
            else if ((flags & CodeBlock.STRICT) != 0) thisValue = thisArg;
            else if (thisArg == Undefined.INSTANCE || thisArg == Null.INSTANCE || thisArg == null) {
                thisValue = realm.getGlobalEnv().thisValue;
            } else if (thisArg instanceof JSObject) thisValue = thisArg;
            else if ((flags & (CodeBlock.USES_THIS | CodeBlock.HAS_EVAL)) != 0) thisValue = Ops.toObject(realm, thisArg);
            else thisValue = thisArg;
            Frame activation = new Frame(fn, code, realm, thisValue, args, Undefined.INSTANCE, fn.env, compiled);
            if ((flags & (CodeBlock.GENERATOR | CodeBlock.ASYNC)) != 0) return Generators.start(activation);
            Agent agent = realm.agent;
            Realm previousRealm = agent.currentRealm;
            Frame previousTop = agent.topFrame;
            if (++agent.depth > agent.maxDepth) {
                agent.depth--;
                throw JSException.rangeError("Maximum call stack size exceeded");
            }
            if (--agent.callBudget < 0) {
                agent.callBudget = 1024;
                boolean checked = false;
                try {
                    agent.checkInterrupt();
                    checked = true;
                } finally {
                    if (!checked) agent.depth--;
                }
            }
            agent.currentRealm = realm;
            activation.parent = previousTop;
            agent.topFrame = activation;
            Object result;
            try {
                if (compiled == null) result = Interpreter.run(activation);
                else {
                    boolean returned = false;
                    try {
                        result = compiled.run(activation);
                        returned = true;
                    } catch (JSException error) {
                        Rt.attachStack(error, activation, activation.pc);
                        throw error;
                    } finally {
                        if (!returned && activation.inlineFn != null) {
                            activation.inlineFn = null;
                            agent.depth--;
                        }
                    }
                }
            } finally {
                agent.depth--;
                agent.topFrame = previousTop;
                agent.currentRealm = previousRealm;
            }
            if (result != TAIL) return result;
            fn = Objects.requireNonNull(activation.getTailFn());
            thisArg = activation.getTailThis();
            args = Objects.requireNonNull(activation.getTailArgs());
        }
    }

    public static Object invokeClosure(JSClosure fn0, Object thisArg0, Object[] args0) {
        JSClosure fn = fn0;
        Object thisArg = thisArg0;
        Object[] args = args0;
        while (true) {
            CodeBlock code = fn.code;
            int flags = code.flags;
            if ((flags & CodeBlock.CLASS_CTOR) != 0) {
                throw fn.realm.typeError("Class constructor " + fn.debugName() + " cannot be invoked without 'new'");
            }
            Realm realm = fn.realm;
            CompiledCode compiled;
            if ((flags & (CodeBlock.GENERATOR | CodeBlock.ASYNC)) != 0) compiled = null;
            else {
                Object installed = code.compiled;
                compiled = installed != null && code.childrenQueued ? (CompiledCode) installed : Jit.prepare(code, realm.agent);
            }
            Object thisValue;
            if ((flags & CodeBlock.ARROW) != 0) thisValue = fn.lexicalThis;
            else if ((flags & CodeBlock.STRICT) != 0) thisValue = thisArg;
            else if (thisArg == Undefined.INSTANCE || thisArg == Null.INSTANCE || thisArg == null) {
                thisValue = realm.getGlobalEnv().thisValue;
            } else if (thisArg instanceof JSObject) thisValue = thisArg;
            else if ((flags & (CodeBlock.USES_THIS | CodeBlock.HAS_EVAL)) != 0) thisValue = Ops.toObject(realm, thisArg);
            else thisValue = thisArg;
            Frame activation = new Frame(fn, code, realm, thisValue, args, Undefined.INSTANCE, fn.env, compiled);
            if ((flags & (CodeBlock.GENERATOR | CodeBlock.ASYNC)) != 0) return Generators.start(activation);
            Agent agent = realm.agent;
            Realm previousRealm = agent.currentRealm;
            Frame previousTop = agent.topFrame;
            if (++agent.depth > agent.maxDepth) {
                agent.depth--;
                throw JSException.rangeError("Maximum call stack size exceeded");
            }
            if (--agent.callBudget < 0) {
                agent.callBudget = 1024;
                boolean checked = false;
                try {
                    agent.checkInterrupt();
                    checked = true;
                } finally {
                    if (!checked) agent.depth--;
                }
            }
            agent.currentRealm = realm;
            activation.parent = previousTop;
            agent.topFrame = activation;
            Object result;
            try {
                if (compiled == null) result = Interpreter.run(activation);
                else {
                    boolean returned = false;
                    try {
                        result = compiled.run(activation);
                        returned = true;
                    } catch (JSException error) {
                        Rt.attachStack(error, activation, activation.pc);
                        throw error;
                    } finally {
                        if (!returned && activation.inlineFn != null) {
                            activation.inlineFn = null;
                            agent.depth--;
                        }
                    }
                }
            } finally {
                agent.depth--;
                agent.topFrame = previousTop;
                agent.currentRealm = previousRealm;
            }
            if (result != TAIL) return result;
            fn = Objects.requireNonNull(activation.getTailFn());
            thisArg = activation.getTailThis();
            args = Objects.requireNonNull(activation.getTailArgs());
        }
    }

    public static Object constructClosure(JSClosure function, Object[] arguments, JSObject newTarget) {
        CodeBlock code = function.code;
        Realm realm = function.realm;
        if ((code.flags & CodeBlock.DERIVED) != 0) {
            if ((code.flags & CodeBlock.DEFAULT_CTOR) != 0) {
                JSObject parent = function.getPrototypeOf();
                if (!Ops.isConstructor(parent)) {
                    throw JSException.typeError("Super constructor " + Ops.describe(parent) + " of anonymous class is not a constructor");
                }
                Object result = parent.construct(arguments, newTarget);
                Rt.initializeInstanceElements((JSObject) result, function);
                return result;
            }
            Jit.prepare(code, realm.agent);
            CompiledCode compiled = (CompiledCode) code.compiled;
            Frame frame = new Frame(function, code, realm, Uninitialized.INSTANCE, arguments, newTarget, function.env, compiled);
            DerivedResult result = (DerivedResult) execute(frame, compiled);
            if (result.value instanceof JSObject) return result.value;
            if (result.value != Undefined.INSTANCE) throw JSException.typeError("Derived constructors may only return object or undefined");
            if (result.thisValue == Uninitialized.INSTANCE) throw tdzError("this");
            return result.thisValue;
        }
        Jit.prepare(code, realm.agent);
        JSObject prototype = Ops.getPrototypeFromConstructor(newTarget, Realm::getObjectPrototype);
        JSObject object = new JSObject(prototype);
        if ((code.flags & CodeBlock.CLASS_CTOR) != 0) Rt.initializeInstanceElements(object, function);
        if ((code.flags & CodeBlock.DEFAULT_CTOR) != 0) return object;
        CompiledCode compiled = (CompiledCode) code.compiled;
        Frame frame = new Frame(function, code, realm, object, arguments, newTarget, function.env, compiled);
        Object result = execute(frame, compiled);
        if (result == TAIL) result = callClosure(Objects.requireNonNull(frame.getTailFn()), frame.getTailThis(),
                Objects.requireNonNull(frame.getTailArgs()));
        return result instanceof JSObject ? result : object;
    }

    public static Object execute(Frame frame) {
        return execute(frame, frame.isCompiled() ? (CompiledCode) frame.code.compiled : null);
    }

    public static Object execute(Frame frame, CompiledCode compiled) {
        return runFrame(frame, compiled);
    }

    public static Object runFrame(Frame frame, CompiledCode compiled) {
        Agent agent = frame.realm.agent;
        Realm previousRealm = agent.currentRealm;
        Frame previousTop = agent.topFrame;
        if (++agent.depth > agent.maxDepth) {
            agent.depth--;
            throw JSException.rangeError("Maximum call stack size exceeded");
        }
        if (--agent.callBudget < 0) {
            agent.callBudget = 1024;
            boolean checked = false;
            try {
                agent.checkInterrupt();
                checked = true;
            } finally {
                if (!checked) agent.depth--;
            }
        }
        agent.currentRealm = frame.realm;
        frame.parent = previousTop;
        agent.topFrame = frame;
        try {
            if (compiled == null) return run(frame);
            boolean returned = false;
            try {
                Object result = compiled.run(frame);
                returned = true;
                return result;
            } catch (JSException error) {
                Rt.attachStack(error, frame, frame.pc);
                throw error;
            } finally {
                if (!returned && frame.inlineFn != null) {
                    frame.inlineFn = null;
                    agent.depth--;
                }
            }
        } finally {
            agent.depth--;
            agent.topFrame = previousTop;
            agent.currentRealm = previousRealm;
        }
    }

    private static int findHandler(CodeBlock code, int pc) {
        for (int i = 0; i < code.handlers.length; i += 4) {
            if (pc >= code.handlers[i] && pc < code.handlers[i + 1]) return i;
        }
        return -1;
    }

    private static void recordCall(CodeBlock code, int pc, CodeBlock callee) {
        CallFeedback[] feedback = code.callFeedback;
        if (feedback == null) {
            feedback = new CallFeedback[code.code.length];
            code.callFeedback = feedback;
        }
        CallFeedback entry = feedback[pc];
        if (entry == null) {
            entry = new CallFeedback();
            feedback[pc] = entry;
        }
        entry.record(callee);
    }

    private static void backEdge(Agent agent, CodeBlock code) {
        agent.checkInterrupt();
        if (code.compiled == null) code.invocationCount += 64;
    }

    public static Object run(Frame frame) {
        CodeBlock block = frame.code;
        int[] code = block.code;
        Object[] constants = block.constants;
        Object[] slots = frame.slots;
        Realm realm = frame.realm;
        boolean recordCalls = Inlining.ENABLED && realm.agent.config.executionMode == Jit.MODE_ADAPTIVE;
        boolean strict = (block.flags & CodeBlock.STRICT) != 0;
        int base = block.numRegs;
        int sp = frame.sp;
        int pc = frame.pc;
        int opStart = pc;
        int budget = 4096;
        Object pendingThrow = null;
        if (frame.getResumeMode() == Frame.MODE_THROW && frame.getSuspendKind() == Frame.SUSPEND_AWAIT) {
            frame.setResumeMode(Frame.MODE_NEXT);
            pendingThrow = slots[--sp];
            opStart = frame.getAwaitPc();
        }
        while (true) {
            try {
                if (pendingThrow != null) {
                    Object thrown = pendingThrow;
                    pendingThrow = null;
                    throw new JSException(thrown);
                }
                while (true) {
                    opStart = pc;
                    int opcode = code[pc++];
                    switch (opcode) {
                        case Op.NOP -> { }
                        case Op.PUSH_UNDEF -> slots[sp++] = Undefined.INSTANCE;
                        case Op.PUSH_NULL -> slots[sp++] = Null.INSTANCE;
                        case Op.PUSH_TRUE -> slots[sp++] = true;
                        case Op.PUSH_FALSE -> slots[sp++] = false;
                        case Op.PUSH_CONST -> slots[sp++] = constants[code[pc++]];
                        case Op.PUSH_INT -> slots[sp++] = Ops.num(code[pc++]);
                        case Op.POP -> slots[--sp] = null;
                        case Op.DUP -> { slots[sp] = slots[sp - 1]; sp++; }
                        case Op.DUP2 -> { slots[sp] = slots[sp - 2]; slots[sp + 1] = slots[sp - 1]; sp += 2; }
                        case Op.SWAP -> { Object value = slots[sp - 1]; slots[sp - 1] = slots[sp - 2]; slots[sp - 2] = value; }
                        case Op.LOAD_REG -> slots[sp++] = slots[code[pc++]];
                        case Op.STORE_REG -> { slots[code[pc++]] = slots[--sp]; slots[sp] = null; }
                        case Op.LOAD_REG_TDZ -> {
                            Object value = slots[code[pc++]];
                            int name = code[pc++];
                            if (value == Uninitialized.INSTANCE) throw tdzError((String) constants[name]);
                            slots[sp++] = value;
                        }
                        case Op.CHECK_REG_TDZ -> {
                            Object value = slots[code[pc++]];
                            int name = code[pc++];
                            if (value == Uninitialized.INSTANCE) throw tdzError((String) constants[name]);
                        }
                        case Op.PUSH_SCOPE -> frame.env = new DeclEnv(frame.env, (ScopeInfo) constants[code[pc++]]);
                        case Op.POP_SCOPE -> frame.env = Objects.requireNonNull(frame.env).parent;
                        case Op.COPY_SCOPE -> frame.env = ((DeclEnv) frame.env).copy();
                        case Op.LOAD_ENV -> {
                            Env environment = frame.env;
                            int hops = code[pc++];
                            while (hops-- > 0) environment = Objects.requireNonNull(environment).parent;
                            slots[sp++] = ((DeclEnv) environment).slots[code[pc++]];
                        }
                        case Op.STORE_ENV -> {
                            Env environment = frame.env;
                            int hops = code[pc++];
                            while (hops-- > 0) environment = Objects.requireNonNull(environment).parent;
                            ((DeclEnv) environment).slots[code[pc++]] = slots[--sp];
                            slots[sp] = null;
                        }
                        case Op.LOAD_IMPORT -> {
                            Env environment = frame.env;
                            int hops = code[pc++];
                            while (hops-- > 0) environment = Objects.requireNonNull(environment).parent;
                            slots[sp++] = Modules.deref(((DeclEnv) environment).slots[code[pc++]]);
                        }
                        case Op.LOAD_ENV_TDZ -> {
                            Env environment = frame.env;
                            int hops = code[pc++];
                            while (hops-- > 0) environment = Objects.requireNonNull(environment).parent;
                            Object value = ((DeclEnv) environment).slots[code[pc++]];
                            int name = code[pc++];
                            if (value == Uninitialized.INSTANCE) throw tdzError((String) constants[name]);
                            slots[sp++] = value;
                        }
                        case Op.LOAD_GLOBAL -> slots[sp++] = GlobalCache.load(realm, (GlobalSite) constants[code[pc++]], false);
                        case Op.LOAD_THIS -> slots[sp++] = frame.thisValue;
                        case Op.LOAD_FUNCTION -> slots[sp++] = frame.fn == null ? Undefined.INSTANCE : frame.fn;
                        case Op.LOAD_ARG -> {
                            int index = code[pc++];
                            slots[sp++] = index < frame.args.length ? frame.args[index] : Undefined.INSTANCE;
                        }
                        case Op.THROW -> throw new JSException(slots[--sp]);
                        case Op.GET_PROP -> {
                            Object value = slots[sp - 1];
                            PropSite site = (PropSite) constants[code[pc++]];
                            slots[sp - 1] = value instanceof JSObject object ? PropCache.get(object, site)
                                    : Rt.getPrimitiveProp(realm, value, site.key);
                        }
                        case Op.PUT_PROP -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            Object receiver = slots[sp - 1];
                            PropSite site = (PropSite) constants[code[pc++]];
                            if (receiver instanceof JSObject object) {
                                if (!PropCache.put(object, site, value) && (strict || block.strictAt(opStart))) throw Rt.readOnlyError(site.key, receiver);
                            } else Rt.putPrimitive(realm, receiver, site.key, value, strict || block.strictAt(opStart));
                            slots[sp - 1] = value;
                        }
                        case Op.GET_ELEM -> {
                            Object key = slots[--sp]; slots[sp] = null;
                            slots[sp - 1] = Rt.getElem(realm, slots[sp - 1], key);
                        }
                        case Op.PUT_ELEM -> {
                            Object value = slots[--sp]; Object key = slots[--sp];
                            slots[sp] = null; slots[sp + 1] = null;
                            Rt.putElem(realm, slots[sp - 1], key, value, strict || block.strictAt(opStart));
                            slots[sp - 1] = value;
                        }
                        case Op.STORE_GLOBAL -> {
                            GlobalCache.store(realm, (GlobalSite) constants[code[pc++]], slots[--sp], strict || block.strictAt(opStart));
                            slots[sp] = null;
                        }
                        case Op.SHL -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.shl(slots[sp - 1], b); }
                        case Op.SAR -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.sar(slots[sp - 1], b); }
                        case Op.SHR -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.shr(slots[sp - 1], b); }
                        case Op.BAND -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.bitAnd(slots[sp - 1], b); }
                        case Op.BOR -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.bitOr(slots[sp - 1], b); }
                        case Op.BXOR -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.bitXor(slots[sp - 1], b); }
                        case Op.ADD -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x + y : Ops.add(a, b);
                        }
                        case Op.SUB -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x - y : Ops.sub(a, b);
                        }
                        case Op.MUL -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x * y : Ops.mul(a, b);
                        }
                        case Op.DIV -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x / y : Ops.div(a, b);
                        }
                        case Op.SEQ -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = Ops.strictEquals(slots[sp - 1], b); }
                        case Op.SNE -> { Object b = slots[--sp]; slots[sp] = null; slots[sp - 1] = !Ops.strictEquals(slots[sp - 1], b); }
                        case Op.LT -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x < y : Ops.lt(a, b);
                        }
                        case Op.GT -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x > y : Ops.gt(a, b);
                        }
                        case Op.LE -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x <= y : Ops.le(a, b);
                        }
                        case Op.GE -> {
                            Object b = slots[--sp]; slots[sp] = null; Object a = slots[sp - 1];
                            slots[sp - 1] = a instanceof Double x && b instanceof Double y ? x >= y : Ops.ge(a, b);
                        }
                        case Op.NOT -> slots[sp - 1] = !Ops.toBoolean(slots[sp - 1]);
                        case Op.TO_NUMERIC -> { Object value = slots[sp - 1]; if (!(value instanceof Double)) slots[sp - 1] = Ops.toNumeric(value); }
                        case Op.INC -> { Object value = slots[sp - 1]; slots[sp - 1] = value instanceof Double number ? number + 1 : Ops.inc(value); }
                        case Op.DEC -> { Object value = slots[sp - 1]; slots[sp - 1] = value instanceof Double number ? number - 1 : Ops.dec(value); }
                        case Op.JUMP -> {
                            int target = code[pc];
                            if (target < pc && --budget <= 0) { budget = 4096; backEdge(realm.agent, block); }
                            pc = target;
                        }
                        case Op.JUMP_IF_TRUE -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            if (value instanceof Boolean bool ? bool : Ops.toBoolean(value)) {
                                int target = code[pc];
                                if (target < pc && --budget <= 0) { budget = 4096; backEdge(realm.agent, block); }
                                pc = target;
                            } else pc++;
                        }
                        case Op.JUMP_IF_FALSE -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            if (!(value instanceof Boolean bool ? bool : Ops.toBoolean(value))) {
                                int target = code[pc];
                                if (target < pc && --budget <= 0) { budget = 4096; backEdge(realm.agent, block); }
                                pc = target;
                            } else pc++;
                        }
                        case Op.JUMP_IF_NULLISH -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            if (value == Undefined.INSTANCE || value == Null.INSTANCE) pc = code[pc]; else pc++;
                        }
                        case Op.JUMP_IF_NOT_NULLISH -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            if (value != Undefined.INSTANCE && value != Null.INSTANCE) pc = code[pc]; else pc++;
                        }
                        case Op.JUMP_IF_UNDEFINED -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            if (value == Undefined.INSTANCE) pc = code[pc]; else pc++;
                        }
                        case Op.JUMP_IF_NOT_UNDEFINED -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            if (value != Undefined.INSTANCE) pc = code[pc]; else pc++;
                        }
                        case Op.JUMP_TABLE -> {
                            int register = code[pc++]; int count = code[pc++]; int index = ((Double) slots[register]).intValue();
                            pc = index >= 0 && index < count ? code[pc + index] : code[pc + count];
                        }
                        case Op.RETURN -> { Object value = slots[--sp]; frame.sp = sp; return value; }
                        case Op.CALL -> {
                            int count = code[pc++]; Object[] arguments = count == 0 ? EMPTY_ARGS : new Object[count];
                            for (int i = count - 1; i >= 0; i--) { arguments[i] = slots[--sp]; slots[sp] = null; }
                            Object receiver = slots[--sp]; slots[sp] = null;
                            Object value = slots[sp - 1]; frame.pc = opStart;
                            if (recordCalls && value instanceof JSClosure closure) recordCall(block, opStart, closure.code);
                            if (!(value instanceof JSObject function) || (function.special & JSObject.CALLABLE) == 0) throw Rt.notCallable(value, block, opStart);
                            slots[sp - 1] = function.call(receiver, arguments);
                        }
                        case Op.TAIL_CALL -> {
                            int count = code[pc++]; Object[] arguments = count == 0 ? EMPTY_ARGS : new Object[count];
                            for (int i = count - 1; i >= 0; i--) { arguments[i] = slots[--sp]; slots[sp] = null; }
                            Object receiver = slots[--sp]; slots[sp] = null;
                            frame.pc = opStart;
                            Object result = tailCall(frame, slots[sp - 1], receiver, arguments);
                            if (result == TAIL) return result;
                            slots[sp - 1] = result;
                        }
                        case Op.NEW -> {
                            int count = code[pc++]; Object[] arguments = count == 0 ? EMPTY_ARGS : new Object[count];
                            for (int i = count - 1; i >= 0; i--) { arguments[i] = slots[--sp]; slots[sp] = null; }
                            Object value = slots[sp - 1]; frame.pc = opStart;
                            if (!(value instanceof JSObject function) || (function.special & JSObject.CONSTRUCTOR) == 0) {
                                throw JSException.typeError(Rt.calleeText(block, opStart, value) + " is not a constructor");
                            }
                            slots[sp - 1] = function.construct(arguments, function);
                        }
                        case Op.NEW_OBJECT -> slots[sp++] = new JSObject(realm.getObjectPrototype());
                        case Op.NEW_OBJECT_LITERAL -> {
                            ObjectLiteralSite site = (ObjectLiteralSite) constants[code[pc++]];
                            int count = code[pc++]; sp -= count;
                            Object[] values = Arrays.copyOfRange(slots, sp, sp + count);
                            Arrays.fill(slots, sp, sp + count, null);
                            slots[sp++] = site.create(realm.getObjectPrototype(), values);
                        }
                        case Op.NEW_ARRAY -> slots[sp++] = new JSArray(realm.getArrayPrototype());
                        case Op.ARRAY_PUSH -> { Object value = slots[--sp]; slots[sp] = null; ((JSArray) slots[sp - 1]).pushInit(value); }
                        case Op.DEFINE_FIELD -> {
                            Object value = slots[--sp]; slots[sp] = null;
                            ((JSObject) slots[sp - 1]).createDataPropertyOrThrow(Objects.requireNonNull(constants[code[pc++]]), value);
                        }
                        case Op.MAKE_CLOSURE -> slots[sp++] = Rt.makeClosureIn(frame, (CodeBlock) constants[code[pc++]]);
                        case Op.ITER_STEP -> {
                            IteratorRecord iterator = (IteratorRecord) slots[code[pc++]]; int target = code[pc++];
                            Object value = Iteration.stepValue(iterator);
                            if (value == NotFound.INSTANCE) {
                                pc = target;
                                if (--budget <= 0) { budget = 4096; backEdge(realm.agent, block); }
                            } else slots[sp++] = value;
                        }
                        case Op.FOR_IN_NEXT -> {
                            ForInIterator iterator = (ForInIterator) slots[code[pc++]]; int target = code[pc++];
                            Object key = iterator.next();
                            if (key == null) pc = target; else slots[sp++] = key;
                            if (--budget <= 0) { budget = 4096; backEdge(realm.agent, block); }
                        }
                        case Op.GENERATOR_INIT -> {
                            frame.pc = pc; frame.sp = sp; frame.setSuspendKind(Frame.SUSPEND_INITIAL); return SUSPENDED;
                        }
                        case Op.YIELD -> {
                            frame.setSuspendValue(slots[--sp]); slots[sp] = null;
                            frame.pc = pc; frame.sp = sp; frame.setSuspendKind(Frame.SUSPEND_YIELD); return SUSPENDED;
                        }
                        case Op.YIELD_RAW -> {
                            frame.setSuspendValue(slots[--sp]); slots[sp] = null;
                            frame.pc = pc; frame.sp = sp; frame.setSuspendKind(Frame.SUSPEND_YIELD_RAW); return SUSPENDED;
                        }
                        case Op.AWAIT, Op.AWAIT_IGNORE, Op.AWAIT_CATCH -> {
                            if (opcode == Op.AWAIT_CATCH) pc++;
                            Object value = slots[--sp]; slots[sp] = null; frame.pc = opStart;
                            Object promise = opcode == Op.AWAIT_IGNORE ? Rt.promiseResolveOrNull(realm, value) : Rt.promiseResolveForAwait(realm, value);
                            frame.setSuspendValue(promise); frame.pc = pc; frame.sp = sp;
                            frame.setSuspendKind(Frame.SUSPEND_AWAIT); frame.setAwaitPc(opStart); return SUSPENDED;
                        }
                        default -> {
                            frame.sp = sp; frame.pc = pc;
                            slowOp(frame, opcode, opStart);
                            if (frame.getTailPending()) return TAIL;
                            sp = frame.sp; pc = frame.pc;
                        }
                    }
                }
            } catch (Exception | StackOverflowError | OutOfMemoryError error) {
                int handler = findHandler(block, opStart);
                if (handler < 0) {
                    if (error instanceof JSException guest) Rt.attachStack(guest, frame, opStart);
                    frame.sp = sp;
                    return Interpreter.<RuntimeException, Object>rethrow(error);
                }
                Object value = Rt.catchValue(error, realm, frame, opStart);
                int depth = block.handlers[handler + 3];
                Arrays.fill(slots, base + depth, slots.length, null);
                sp = base + depth; slots[sp++] = value; pc = block.handlers[handler + 2];
            }
        }
    }

    private static void slowOp(Frame f, int op, int opStart) {
        CodeBlock cb = f.code;
        int[] code = cb.code;
        Object[] k = cb.constants;
        Object[] s = f.slots;
        Realm realm = f.realm;
        boolean strict = (cb.flags & CodeBlock.STRICT) != 0 || cb.strictAt(opStart);
        int sp = f.sp;
        int pc = opStart + 1;
        switch (op) {
            case Op.DUP3 -> {
                s[sp] = s[sp - 3]; s[sp + 1] = s[sp - 2]; s[sp + 2] = s[sp - 1]; sp += 3;
            }
            case Op.ROT3 -> {
                Object c = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = s[sp - 3]; s[sp - 3] = c;
            }
            case Op.ROT4 -> {
                Object d = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = s[sp - 3];
                s[sp - 3] = s[sp - 4]; s[sp - 4] = d;
            }
            case Op.GET_ENV -> s[sp++] = f.env;
            case Op.SET_ENV -> { f.env = (Env) s[--sp]; s[sp] = null; }
            case Op.PUSH_WITH -> { f.env = new ObjectEnv(f.env, (JSObject) s[--sp], true); s[sp] = null; }
            case Op.CHECK_ENV_TDZ -> {
                Env e = f.env;
                int h = code[pc++];
                while (h-- > 0) e = Objects.requireNonNull(e).parent;
                Object v = ((DeclEnv) e).slots[code[pc++]];
                int nk = code[pc++];
                if (v == Uninitialized.INSTANCE) throw tdzError((String) k[nk]);
            }
            case Op.LOAD_NAME -> s[sp++] = Names.load(f.env, (String) k[code[pc++]], strict, false);
            case Op.LOAD_NAME_TYPEOF -> s[sp++] = Names.load(f.env, (String) k[code[pc++]], strict, true);
            case Op.LOAD_NAME_CALL -> {
                Names.CallTarget target = Names.loadForCall(f.env, (String) k[code[pc++]], strict);
                s[sp++] = target.value(); s[sp++] = target.receiver();
            }
            case Op.STORE_NAME -> {
                Names.store(f.env, (String) k[code[pc++]], s[--sp], strict, realm.getGlobalEnv()); s[sp] = null;
            }
            case Op.STORE_NAME_VAR -> {
                Rt.storeAnnexBVar(f.env, (String) k[code[pc++]], s[--sp], realm); s[sp] = null;
            }
            case Op.DELETE_NAME -> s[sp++] = Names.delete(f.env, (String) k[code[pc++]]);
            case Op.INIT_NAME -> { Names.initialize(f.env, (String) k[code[pc++]], s[--sp]); s[sp] = null; }
            case Op.LOAD_GLOBAL_TYPEOF -> s[sp++] = GlobalCache.load(realm, (GlobalSite) k[code[pc++]], true);
            case Op.INIT_GLOBAL_LEX -> {
                Rt.initGlobalLexical(realm, (String) k[code[pc++]], s[--sp]); s[sp] = null;
            }
            case Op.LOAD_NEW_TARGET -> s[sp++] = f.newTarget;
            case Op.LOAD_HOME -> {
                JSObject home = f.fn == null ? null : f.fn.homeObject;
                if (home == null) home = f.getHomeObject();
                s[sp++] = home == null ? Undefined.INSTANCE : home;
            }
            case Op.CREATE_ARGUMENTS -> {
                boolean mapped = code[pc++] == 1;
                s[sp++] = mapped
                        ? Rt.createMappedArguments(Objects.requireNonNull(f.fn), f.args,
                            f.env instanceof DeclEnv env ? env : null, Objects.requireNonNull(cb.mappedSlots))
                        : Rt.createUnmappedArguments(realm, f.args);
            }
            case Op.CREATE_REST -> {
                int start = code[pc++];
                JSArray array = new JSArray(realm.getArrayPrototype());
                for (int i = start; i < f.args.length; i++) array.pushInit(f.args[i]);
                s[sp++] = array;
            }
            case Op.INIT_THIS_REG -> {
                int r = code[pc++];
                if (s[r] != Uninitialized.INSTANCE) throw JSException.referenceError("Super constructor may only be called once");
                s[r] = s[--sp]; s[sp] = null; f.thisValue = s[r];
            }
            case Op.INIT_THIS_ENV -> {
                Env e = f.env;
                int h = code[pc++];
                while (h-- > 0) e = Objects.requireNonNull(e).parent;
                int slot = code[pc++];
                DeclEnv de = (DeclEnv) e;
                if (de.slots[slot] != Uninitialized.INSTANCE) throw JSException.referenceError("Super constructor may only be called once");
                de.slots[slot] = s[--sp]; s[sp] = null;
            }
            case Op.INIT_THIS_NAME -> { Rt.initThisDynamic(f.env, s[--sp]); s[sp] = null; }
            case Op.THROW_ERROR -> {
                int kind = code[pc++];
                String message = (String) k[code[pc++]];
                throw switch (kind) {
                    case 1 -> JSException.referenceError(message);
                    case 2 -> JSException.syntaxError(message);
                    case 3 -> JSException.rangeError(message);
                    default -> JSException.typeError(message);
                };
            }
            case Op.DELETE_PROP -> s[sp - 1] = Rt.deleteProp(realm, s[sp - 1], Objects.requireNonNull(k[code[pc++]]), strict);
            case Op.DELETE_ELEM -> {
                Object key = s[--sp]; s[sp] = null;
                Object object = s[sp - 1];
                s[sp - 1] = Rt.deleteProp(realm, object, Ops.toPropertyKey(Rt.keyForBase(object, key)), strict);
            }
            case Op.TO_PROPERTY_KEY -> s[sp - 1] = Ops.toPropertyKey(s[sp - 1]);
            case Op.TO_KEY_FOR_BASE -> s[sp - 1] = Rt.keyForBase(s[sp - 2], s[sp - 1]);
            case Op.GET_SUPER -> {
                Object base = s[--sp], key = s[--sp]; s[sp] = null; s[sp + 1] = null;
                s[sp - 1] = Rt.getSuper(realm, s[sp - 1], Ops.toPropertyKey(key), base);
            }
            case Op.PUT_SUPER -> {
                Object value = s[--sp], base = s[--sp], key = s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null;
                Rt.putSuper(realm, s[sp - 1], Ops.toPropertyKey(key), base, value, strict);
                s[sp - 1] = value;
            }
            case Op.GET_SUPER_BASE -> s[sp - 1] = Rt.superBase(s[sp - 1]);
            case Op.GET_PRIVATE -> {
                PrivateName name = (PrivateName) s[--sp]; s[sp] = null;
                s[sp - 1] = Rt.privateGet(s[sp - 1], name);
            }
            case Op.PUT_PRIVATE -> {
                Object value = s[--sp]; PrivateName name = (PrivateName) s[--sp];
                s[sp] = null; s[sp + 1] = null;
                Rt.privateSet(s[sp - 1], name, value); s[sp - 1] = value;
            }
            case Op.HAS_PRIVATE -> {
                Object object = s[--sp]; s[sp] = null;
                s[sp - 1] = Rt.privateIn((PrivateName) s[sp - 1], object);
            }
            case Op.MOD -> { Object b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.mod(s[sp - 1], b); }
            case Op.EXP -> { Object b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.exp(s[sp - 1], b); }
            case Op.EQ -> { Object b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.looseEquals(s[sp - 1], b); }
            case Op.NE -> { Object b = s[--sp]; s[sp] = null; s[sp - 1] = !Ops.looseEquals(s[sp - 1], b); }
            case Op.INSTANCEOF -> { Object b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.instanceOf(s[sp - 1], b); }
            case Op.IN -> { Object b = s[--sp]; s[sp] = null; s[sp - 1] = Ops.hasPropertyOp(s[sp - 1], b); }
            case Op.NEG -> s[sp - 1] = Ops.neg(s[sp - 1]);
            case Op.TO_NUMBER -> { if (!(s[sp - 1] instanceof Double)) s[sp - 1] = Ops.toNumber(s[sp - 1]); }
            case Op.BNOT -> s[sp - 1] = Ops.bitNot(s[sp - 1]);
            case Op.TYPEOF -> s[sp - 1] = Ops.typeOf(s[sp - 1]);
            case Op.TO_STRING -> { if (!(s[sp - 1] instanceof CharSequence)) s[sp - 1] = Ops.toString(s[sp - 1]); }
            case Op.CONCAT -> {
                Object b = s[--sp]; s[sp] = null;
                s[sp - 1] = Rope.concat((CharSequence) s[sp - 1], (CharSequence) b);
            }
            case Op.TO_OBJECT -> s[sp - 1] = Ops.toObject(realm, s[sp - 1]);
            case Op.REQUIRE_COERCIBLE -> {
                Object value = s[sp - 1];
                if (value == Undefined.INSTANCE || value == Null.INSTANCE) {
                    throw JSException.typeError("Cannot destructure '" + Ops.toDisplayString(value) + "' as it is " + Ops.toDisplayString(value) + ".");
                }
            }
            case Op.CHECK_OBJECT -> {
                int message = code[pc++];
                if (!(s[sp - 1] instanceof JSObject)) throw JSException.typeError((String) k[message]);
            }
            case Op.CALL_SPREAD -> {
                JSArray array = (JSArray) s[--sp]; Object receiver = s[--sp];
                s[sp] = null; s[sp + 1] = null;
                Object value = s[sp - 1]; f.pc = opStart;
                Object[] args = Rt.arrayToArgs(array);
                if (!(value instanceof JSObject function) || (function.special & JSObject.CALLABLE) == 0) throw Rt.notCallable(value, cb, opStart);
                s[sp - 1] = function.call(receiver, args);
            }
            case Op.NEW_SPREAD -> {
                JSArray array = (JSArray) s[--sp]; s[sp] = null;
                Object value = s[sp - 1]; f.pc = opStart;
                if (!(value instanceof JSObject function) || (function.special & JSObject.CONSTRUCTOR) == 0) {
                    throw JSException.typeError(Rt.calleeText(cb, opStart, value) + " is not a constructor");
                }
                s[sp - 1] = function.construct(Rt.arrayToArgs(array), function);
            }
            case Op.CALL_EVAL -> {
                int argc = code[pc++], flags = code[pc++];
                Object[] args = argc == 0 ? EMPTY_ARGS : new Object[argc];
                for (int i = argc - 1; i >= 0; i--) { args[i] = s[--sp]; s[sp] = null; }
                Object receiver = s[--sp]; s[sp] = null;
                Object value = s[sp - 1]; f.pc = opStart;
                if (value == realm.getEvalFunction()) s[sp - 1] = Rt.directEval(f, args, (flags & 1) != 0);
                else if ((flags & 2) != 0) {
                    Object result = tailCall(f, value, receiver, args);
                    if (result == TAIL) f.setTailPending(true); else s[sp - 1] = result;
                } else if (value instanceof JSObject function && function.isCallable()) s[sp - 1] = function.call(receiver, args);
                else throw Rt.notCallable(value, cb, opStart);
            }
            case Op.CALL_EVAL_SPREAD -> {
                int flags = code[pc++]; JSArray array = (JSArray) s[--sp]; Object receiver = s[--sp];
                s[sp] = null; s[sp + 1] = null;
                Object value = s[sp - 1]; Object[] args = Rt.arrayToArgs(array); f.pc = opStart;
                if (value == realm.getEvalFunction()) s[sp - 1] = Rt.directEval(f, args, (flags & 1) != 0);
                else if (value instanceof JSObject function && function.isCallable()) s[sp - 1] = function.call(receiver, args);
                else throw Rt.notCallable(value, cb, opStart);
            }
            case Op.SUPER_CALL -> {
                int argc = code[pc++]; Object[] args = argc == 0 ? EMPTY_ARGS : new Object[argc];
                for (int i = argc - 1; i >= 0; i--) { args[i] = s[--sp]; s[sp] = null; }
                Object newTarget = s[--sp]; s[sp] = null; f.pc = opStart;
                s[sp - 1] = Rt.superCall(s[sp - 1], args, newTarget);
            }
            case Op.SUPER_CALL_SPREAD -> {
                JSArray array = (JSArray) s[--sp]; Object newTarget = s[--sp];
                s[sp] = null; s[sp + 1] = null; f.pc = opStart;
                s[sp - 1] = Rt.superCall(s[sp - 1], Rt.arrayToArgs(array), newTarget);
            }
            case Op.GET_PROTO_OF -> {
                JSObject prototype = ((JSObject) s[sp - 1]).getPrototypeOf();
                s[sp - 1] = prototype == null ? Null.INSTANCE : prototype;
            }
            case Op.INIT_INSTANCE -> {
                JSClosure function = (JSClosure) s[--sp]; s[sp] = null;
                Rt.initializeInstanceElements((JSObject) s[sp - 1], function);
            }
            case Op.ARRAY_HOLE -> ((JSArray) s[sp - 1]).pushHoleInit();
            case Op.ARRAY_SPREAD -> {
                Object value = s[--sp]; s[sp] = null; Rt.arraySpread(realm, (JSArray) s[sp - 1], value);
            }
            case Op.DEFINE_FIELD_ELEM -> {
                Object value = s[--sp], key = s[--sp]; s[sp] = null; s[sp + 1] = null;
                ((JSObject) s[sp - 1]).createDataPropertyOrThrow(Objects.requireNonNull(key), value);
            }
            case Op.DEFINE_GETTER, Op.DEFINE_SETTER, Op.DEFINE_METHOD -> {
                boolean enumerable = code[pc++] == 1; JSFunction function = (JSFunction) s[--sp]; Object key = s[--sp];
                s[sp] = null; s[sp + 1] = null;
                Rt.defineMethod((JSObject) s[sp - 1], Objects.requireNonNull(key), function, op, enumerable);
            }
            case Op.COPY_DATA_PROPS -> {
                Object source = s[--sp]; s[sp] = null; Rt.copyDataProperties((JSObject) s[sp - 1], source, null);
            }
            case Op.COPY_DATA_PROPS_EXCL -> {
                int n = code[pc++]; HashSet<Object> excluded = new HashSet<>();
                for (int i = 0; i < n; i++) { excluded.add(Objects.requireNonNull(s[--sp])); s[sp] = null; }
                Object source = s[--sp]; s[sp] = null;
                Rt.copyDataProperties((JSObject) s[sp - 1], source, excluded);
            }
            default -> { rareOp(f, op, opStart); return; }
        }
        f.sp = sp;
        f.pc = pc;
    }

    private static void rareOp(Frame f, int op, int opStart) {
        CodeBlock cb = f.code;
        int[] code = cb.code;
        Object[] k = cb.constants;
        Object[] s = f.slots;
        Realm realm = f.realm;
        boolean strict = (cb.flags & CodeBlock.STRICT) != 0 || cb.strictAt(opStart);
        int sp = f.sp;
        int pc = opStart + 1;
        switch (op) {
            case Op.SET_PROTO -> {
                Object value = s[--sp]; s[sp] = null;
                if (value instanceof JSObject || value == Null.INSTANCE) {
                    ((JSObject) s[sp - 1]).setPrototypeOf(value instanceof JSObject object ? object : null);
                }
            }
            case Op.SET_FUNCTION_NAME -> {
                int prefix = code[pc++];
                Rt.setFunctionName((JSObject) s[sp - 1], Objects.requireNonNull(s[sp - 2]), prefix >= 0 ? (String) k[prefix] : null);
            }
            case Op.MAKE_METHOD -> {
                CodeBlock template = (CodeBlock) k[code[pc++]];
                JSObject home = (JSObject) s[code[pc++]];
                s[sp++] = Rt.makeClosure(realm, template, f.env, home);
            }
            case Op.MAKE_CLASS -> {
                CodeBlock template = (CodeBlock) k[code[pc++]];
                int flags = code[pc++];
                Object name = null, heritage = NotFound.INSTANCE;
                if ((flags & 2) != 0) { name = s[--sp]; s[sp] = null; }
                if ((flags & 1) != 0) { heritage = s[--sp]; s[sp] = null; }
                Object[] pair = Rt.makeClass(realm, template, f.env, heritage, name);
                s[sp++] = pair[0]; s[sp++] = pair[1];
            }
            case Op.NEW_PRIVATE_NAME -> s[sp++] = new PrivateName((String) k[code[pc++]]);
            case Op.ADD_FIELD -> {
                Object init = s[--sp], key = s[--sp]; JSClosure constructor = (JSClosure) s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null;
                if (constructor.fields == null) constructor.fields = new ArrayList<>();
                constructor.fields.add(new FieldRecord(Objects.requireNonNull(key), init));
            }
            case Op.ADD_PRIVATE_METHOD -> {
                int kind = code[pc++]; Object function = s[--sp]; PrivateName name = (PrivateName) s[--sp];
                JSClosure constructor = (JSClosure) s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null;
                Rt.addPrivateMethod(constructor, name, function, kind);
            }
            case Op.STATIC_PRIVATE_METHOD -> {
                int kind = code[pc++]; Object function = s[--sp]; PrivateName name = (PrivateName) s[--sp];
                JSObject constructor = (JSObject) s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null;
                Rt.staticPrivateMethod(constructor, name, function, kind);
            }
            case Op.RUN_FIELD -> {
                Object function = s[--sp], key = s[--sp]; JSObject object = (JSObject) s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null; f.pc = opStart;
                Rt.defineField(object, new FieldRecord(Objects.requireNonNull(key), function));
            }
            case Op.GET_ITERATOR -> s[sp - 1] = Iteration.getIterator(realm, s[sp - 1], false);
            case Op.GET_ASYNC_ITERATOR -> s[sp - 1] = Iteration.getIterator(realm, s[sp - 1], true);
            case Op.ITER_STEP_U -> {
                IteratorRecord record = (IteratorRecord) s[code[pc++]];
                if (record.done) s[sp++] = Undefined.INSTANCE;
                else {
                    Object value = Iteration.stepValue(record);
                    s[sp++] = value == NotFound.INSTANCE ? Undefined.INSTANCE : value;
                }
            }
            case Op.ITER_REST -> s[sp++] = Iteration.rest(realm, (IteratorRecord) s[code[pc++]]);
            case Op.ITER_CLOSE -> Iteration.closeNormal((IteratorRecord) s[code[pc++]]);
            case Op.ITER_CLOSE_THROW -> Iteration.closeOnThrow((IteratorRecord) s[code[pc++]]);
            case Op.ITER_NEXT_CALL -> s[sp++] = Iteration.callNext((IteratorRecord) s[code[pc++]], Undefined.INSTANCE);
            case Op.ITER_RESULT_STEP -> {
                IteratorRecord record = (IteratorRecord) s[code[pc++]];
                int target = code[pc++]; Object result = s[--sp]; s[sp] = null;
                if (!(result instanceof JSObject object)) {
                    record.done = true;
                    throw JSException.typeError("Iterator result " + Ops.toDisplayString(result) + " is not an object");
                }
                if (Iteration.complete(record, object)) { record.done = true; pc = target; }
                else s[sp++] = Iteration.value(record, object);
            }
            case Op.ASYNC_ITER_CLOSE -> {
                IteratorRecord record = (IteratorRecord) s[code[pc++]];
                int mode = code[pc++], target = code[pc++];
                Object result = Iteration.asyncCloseCall(record, mode == 1);
                if (result == NotFound.INSTANCE) pc = target; else s[sp++] = result;
            }
            case Op.FOR_IN_START -> s[sp - 1] = new ForInIterator(Ops.toObject(realm, s[sp - 1]));
            case Op.RESUME_DISPATCH -> {
                int target = code[pc++];
                if (f.getResumeMode() == Frame.MODE_THROW) {
                    f.setResumeMode(Frame.MODE_NEXT);
                    Object value = s[--sp]; s[sp] = null; throw new JSException(value);
                } else if (f.getResumeMode() == Frame.MODE_RETURN) {
                    f.setResumeMode(Frame.MODE_NEXT); pc = target;
                }
            }
            case Op.RESUME_MODE -> { s[sp++] = Ops.num(f.getResumeMode()); f.setResumeMode(Frame.MODE_NEXT); }
            case Op.YSTAR -> {
                IteratorRecord record = (IteratorRecord) s[code[pc++]];
                int modeReg = code[pc++], target = code[pc++];
                Object result = Iteration.yieldStarStep(record, s[sp - 1], ((Double) s[modeReg]).intValue());
                if (result instanceof Iteration.YStarDone done) {
                    s[modeReg] = Ops.num(done.isReturn ? 2 : 0); s[sp - 1] = done.value; pc = target;
                } else s[sp - 1] = result;
            }
            case Op.YSTAR_ASYNC_CALL -> {
                IteratorRecord record = (IteratorRecord) s[code[pc++]];
                int modeReg = code[pc++], target = code[pc++];
                Object result = Iteration.yieldStarAsyncCall(record, s[sp - 1], ((Double) s[modeReg]).intValue());
                if (result instanceof Iteration.YStarDone done) {
                    s[modeReg] = Ops.num(done.isReturn ? 2 : 3); s[sp - 1] = done.value; pc = target;
                } else s[sp - 1] = result;
            }
            case Op.YSTAR_ASYNC_RESULT -> {
                IteratorRecord record = (IteratorRecord) s[code[pc++]];
                int modeReg = code[pc++], target = code[pc++];
                Object result = s[sp - 1]; int mode = ((Double) s[modeReg]).intValue();
                if (!(result instanceof JSObject object)) throw JSException.typeError("Iterator result " + Ops.toDisplayString(result) + " is not an object");
                if (Iteration.complete(record, object)) {
                    s[sp - 1] = Iteration.value(record, object); s[modeReg] = Ops.num(mode == 2 ? 2 : 0); pc = target;
                } else s[sp - 1] = Iteration.value(record, object);
            }
            case Op.DEBUGGER -> realm.agent.onDebugger(f);
            case Op.TEMPLATE_OBJECT -> s[sp++] = Rt.templateObject(realm, (TemplateSite) k[code[pc++]]);
            case Op.NEW_REGEXP -> s[sp++] = Rt.newRegExp(realm, (RegExpSite) k[code[pc++]]);
            case Op.IMPORT_META -> s[sp++] = Rt.importMeta(f);
            case Op.CLASS_DEF_NEW -> {
                Object prototype = s[--sp]; s[sp] = null;
                s[sp - 1] = Decorators.newClassDef(s[sp - 1], prototype);
            }
            case Op.CLASS_ELEMENT -> {
                int flags = code[pc++]; Object function = s[--sp], key = s[--sp], decorators = s[--sp], definition = s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null; s[sp + 3] = null;
                Decorators.addElement(definition, decorators, key, function, flags);
            }
            case Op.CLASS_FINISH -> { Object definition = s[--sp]; s[sp] = null; Decorators.finish(definition); }
            case Op.CLASS_DECORATE -> {
                Object decorators = s[--sp]; s[sp] = null;
                s[sp - 1] = Decorators.decorateClass(s[sp - 1], decorators);
            }
            case Op.CLASS_STATIC_INIT -> { Object definition = s[--sp]; s[sp] = null; Decorators.staticInit(definition); }
            case Op.DYNAMIC_IMPORT_PHASE -> {
                String phase = (String) k[code[pc++]]; Object options = s[--sp]; s[sp] = null;
                s[sp - 1] = Modules.dynamicImport(f, s[sp - 1], options, phase);
            }
            case Op.DYNAMIC_IMPORT -> {
                Object options = s[--sp]; s[sp] = null; s[sp - 1] = Rt.dynamicImport(f, s[sp - 1], options);
            }
            case Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> {
                DeclInfo info = (DeclInfo) k[code[pc++]];
                int count = code[pc++]; Object[] functions = new Object[count];
                for (int i = count - 1; i >= 0; i--) { functions[i] = s[--sp]; s[sp] = null; }
                f.pc = opStart;
                if (op == Op.DECLARE_GLOBALS) Rt.declareGlobals(realm, info, functions);
                else Rt.declareEval(f, info, functions);
            }
            case Op.DERIVED_RETURN -> {
                Object receiver = s[--sp]; s[sp] = null; s[sp - 1] = new DerivedResult(s[sp - 1], receiver);
            }
            case Op.RESOLVE_NAME -> {
                String name = (String) k[code[pc++]]; NameRef reference = Names.resolve(f.env, name);
                s[sp++] = reference == null ? new NameRef(null, name, -1, strict) : reference;
            }
            case Op.GET_REF -> {
                NameRef reference = (NameRef) s[sp - 1];
                s[sp - 1] = Names.getValue(reference.env == null ? null : reference, reference.name, strict, false);
            }
            case Op.PUT_REF -> {
                Object value = s[--sp]; s[sp] = null; NameRef reference = (NameRef) s[sp - 1];
                Names.putValue(reference.env == null ? null : reference, reference.name, value, strict, realm.getGlobalEnv());
                s[sp - 1] = value;
            }
            case Op.ADD_DISPOSABLE -> {
                int hint = code[pc++]; Object capability = s[--sp], value = s[--sp]; s[sp] = null; s[sp + 1] = null;
                Disposal.add(realm, capability, value, hint);
            }
            case Op.DISPOSE_NEW -> s[sp++] = Disposal.newCapability();
            case Op.DISPOSE_SYNC -> {
                Object value = s[--sp], kind = s[--sp], capability = s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null; Disposal.disposeSync(realm, capability, kind, value);
            }
            case Op.DISPOSE_BEGIN -> {
                Object value = s[--sp], kind = s[--sp]; s[sp] = null; s[sp + 1] = null;
                s[sp - 1] = Disposal.begin(realm, s[sp - 1], kind, value);
            }
            case Op.DISPOSE_STEP -> {
                Object disposal = s[sp - 1]; boolean more = Disposal.step(disposal);
                s[sp - 1] = more ? Disposal.awaitValue(disposal) : Undefined.INSTANCE; s[sp++] = more;
            }
            case Op.DISPOSE_AWAITED -> {
                Object mode = s[--sp], disposal = s[--sp], result = s[--sp];
                s[sp] = null; s[sp + 1] = null; s[sp + 2] = null; Disposal.awaited(result, disposal, mode);
            }
            case Op.DISPOSE_END -> { Object disposal = s[--sp]; s[sp] = null; Disposal.end(disposal); }
            default -> throw new IllegalStateException("bad opcode " + op + " at " + opStart);
        }
        f.sp = sp;
        f.pc = pc;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable error) throws E {
        throw (E) error;
    }

    private static JSException tdzError(String name) {
        return JSException.referenceError(name.equals("this")
                ? "Must call super constructor in derived class before accessing 'this' or returning from derived constructor"
                : "Cannot access '" + name + "' before initialization");
    }
}

