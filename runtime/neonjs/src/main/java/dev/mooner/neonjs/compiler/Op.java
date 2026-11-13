package dev.mooner.neonjs.compiler;

/** Stack bytecode instruction definitions; derived from NeonJS and adapted to Java. */
public final class Op {
    public static final Op INSTANCE = new Op();
    public static final int VAR = Integer.MIN_VALUE;
    public static final int NOP = 0;
    public static final int PUSH_UNDEF = 1;
    public static final int PUSH_NULL = 2;
    public static final int PUSH_TRUE = 3;
    public static final int PUSH_FALSE = 4;
    public static final int PUSH_CONST = 5;
    public static final int PUSH_INT = 6;
    public static final int POP = 7;
    public static final int DUP = 8;
    public static final int DUP2 = 9;
    public static final int DUP3 = 10;
    public static final int SWAP = 11;
    public static final int ROT3 = 12; // (a b c -- c a b)
    public static final int ROT4 = 13; // (a b c d -- d a b c)
    public static final int LOAD_REG = 14;
    public static final int STORE_REG = 15;
    public static final int LOAD_REG_TDZ = 16;
    public static final int CHECK_REG_TDZ = 17;
    public static final int PUSH_SCOPE = 18;
    public static final int POP_SCOPE = 19;
    public static final int COPY_SCOPE = 20;
    public static final int GET_ENV = 21;
    public static final int SET_ENV = 22;
    public static final int PUSH_WITH = 23;
    public static final int LOAD_ENV = 24;
    public static final int STORE_ENV = 25;
    public static final int LOAD_ENV_TDZ = 26;
    public static final int CHECK_ENV_TDZ = 27;
    public static final int LOAD_NAME = 28;
    public static final int LOAD_NAME_TYPEOF = 29;
    public static final int LOAD_NAME_CALL = 30;
    public static final int STORE_NAME = 31;
    public static final int STORE_NAME_VAR = 32;
    public static final int DELETE_NAME = 33;
    public static final int INIT_NAME = 34;
    public static final int LOAD_GLOBAL = 35;
    public static final int LOAD_GLOBAL_TYPEOF = 36;
    public static final int STORE_GLOBAL = 37;
    public static final int INIT_GLOBAL_LEX = 38;
    public static final int LOAD_THIS = 39;
    public static final int LOAD_FUNCTION = 40;
    public static final int LOAD_NEW_TARGET = 41;
    public static final int LOAD_HOME = 42;
    public static final int CREATE_ARGUMENTS = 43;
    public static final int CREATE_REST = 44;
    public static final int LOAD_ARG = 45;
    public static final int INIT_THIS_REG = 46;
    public static final int INIT_THIS_ENV = 47;
    public static final int INIT_THIS_NAME = 48;
    public static final int THROW = 49;
    public static final int THROW_ERROR = 50; // kind msgK; kind: 0 type, 1 reference, 2 syntax, 3 range
    public static final int GET_PROP = 51; // (obj -- v)
    public static final int PUT_PROP = 52; // (obj v -- v)
    public static final int GET_ELEM = 53; // (obj key -- v)
    public static final int PUT_ELEM = 54; // (obj key v -- v)
    public static final int DELETE_PROP = 55;
    public static final int DELETE_ELEM = 56;
    public static final int TO_PROPERTY_KEY = 57;
    public static final int TO_KEY_FOR_BASE = 58; // (obj key -- obj key') TypeError on nullish base
    public static final int GET_SUPER = 59; // (this key base -- v)
    public static final int PUT_SUPER = 60; // (this key base v -- v)
    public static final int GET_SUPER_BASE = 61; // (home -- base)
    public static final int GET_PRIVATE = 62; // (obj pn -- v)
    public static final int PUT_PRIVATE = 63; // (obj pn v -- v)
    public static final int HAS_PRIVATE = 64; // (pn obj -- bool)
    public static final int ADD = 65;
    public static final int SUB = 66;
    public static final int MUL = 67;
    public static final int DIV = 68;
    public static final int MOD = 69;
    public static final int EXP = 70;
    public static final int SHL = 71;
    public static final int SAR = 72;
    public static final int SHR = 73;
    public static final int BAND = 74;
    public static final int BOR = 75;
    public static final int BXOR = 76;
    public static final int EQ = 77;
    public static final int NE = 78;
    public static final int SEQ = 79;
    public static final int SNE = 80;
    public static final int LT = 81;
    public static final int GT = 82;
    public static final int LE = 83;
    public static final int GE = 84;
    public static final int INSTANCEOF = 85;
    public static final int IN = 86;
    public static final int NEG = 87;
    public static final int TO_NUMBER = 88;
    public static final int NOT = 89;
    public static final int BNOT = 90;
    public static final int TYPEOF = 91;
    public static final int TO_NUMERIC = 92;
    public static final int INC = 93;
    public static final int DEC = 94;
    public static final int TO_STRING = 95;
    public static final int CONCAT = 96;
    public static final int TO_OBJECT = 97;
    public static final int REQUIRE_COERCIBLE = 98;
    public static final int CHECK_OBJECT = 99;
    public static final int JUMP = 100;
    public static final int JUMP_IF_TRUE = 101;
    public static final int JUMP_IF_FALSE = 102;
    public static final int JUMP_IF_NULLISH = 103;
    public static final int JUMP_IF_NOT_NULLISH = 104;
    public static final int JUMP_IF_UNDEFINED = 105;
    public static final int JUMP_IF_NOT_UNDEFINED = 106;
    public static final int JUMP_TABLE = 107; // reg count t0..t(count-1) default
    public static final int RETURN = 108;
    public static final int CALL = 109; // argc (f this a1..an -- r)
    public static final int CALL_SPREAD = 110; // (f this arr -- r)
    public static final int NEW = 111; // argc (f a1..an -- r)
    public static final int NEW_SPREAD = 112; // (f arr -- r)
    public static final int CALL_EVAL = 113; // argc flags
    public static final int CALL_EVAL_SPREAD = 114;
    public static final int SUPER_CALL = 115; // argc (func newTarget a1..an -- r)
    public static final int SUPER_CALL_SPREAD = 116;
    public static final int GET_PROTO_OF = 117;
    public static final int INIT_INSTANCE = 118; // (obj fn -- obj)
    public static final int NEW_OBJECT = 119;
    public static final int NEW_ARRAY = 120;
    public static final int ARRAY_PUSH = 121;
    public static final int ARRAY_HOLE = 122;
    public static final int ARRAY_SPREAD = 123;
    public static final int DEFINE_FIELD = 124; // keyK (obj v -- obj)
    public static final int DEFINE_FIELD_ELEM = 125; // (obj key v -- obj)
    public static final int DEFINE_GETTER = 126; // enumerable (obj key fn -- obj)
    public static final int DEFINE_SETTER = 127;
    public static final int DEFINE_METHOD = 128;
    public static final int COPY_DATA_PROPS = 129; // (target src -- target)
    public static final int COPY_DATA_PROPS_EXCL = 130; // n (target src k1..kn -- target)
    public static final int SET_PROTO = 131; // (obj v -- obj)
    public static final int SET_FUNCTION_NAME = 132; // prefixK (key fn -- key fn)
    public static final int MAKE_CLOSURE = 133;
    public static final int MAKE_METHOD = 134; // templateK homeReg
    public static final int MAKE_CLASS = 135; // templateK flags ([heritage] [name] -- proto ctor)
    public static final int NEW_PRIVATE_NAME = 136;
    public static final int ADD_FIELD = 137; // (ctor key fn -- )
    public static final int ADD_PRIVATE_METHOD = 138; // kind (ctor pn fn -- )
    public static final int STATIC_PRIVATE_METHOD = 139;
    public static final int RUN_FIELD = 140; // (obj key fn -- )
    public static final int GET_ITERATOR = 141;
    public static final int GET_ASYNC_ITERATOR = 142;
    public static final int ITER_STEP = 143; // r t ( -- v) jumps to t when done
    public static final int ITER_STEP_U = 144; // r ( -- v|undefined)
    public static final int ITER_REST = 145; // r ( -- array)
    public static final int ITER_CLOSE = 146;
    public static final int ITER_CLOSE_THROW = 147;
    public static final int ITER_NEXT_CALL = 148; // r ( -- result)
    public static final int ITER_RESULT_STEP = 149; // r t (result -- value) jumps when done (pops)
    public static final int ASYNC_ITER_CLOSE = 150; // r mode t ( -- result) jumps when no return method
    public static final int FOR_IN_START = 151;
    public static final int FOR_IN_NEXT = 152; // r t ( -- key)
    public static final int GENERATOR_INIT = 153;
    public static final int YIELD = 154; // (v -- received)
    public static final int YIELD_RAW = 155; // (iterResult -- received)
    public static final int RESUME_DISPATCH = 156; // tReturn
    public static final int RESUME_MODE = 157;
    public static final int AWAIT = 158;
    public static final int AWAIT_IGNORE = 159;
    public static final int AWAIT_CATCH = 160; // modeReg
    public static final int YSTAR = 161; // itReg modeReg tDone
    public static final int YSTAR_ASYNC_CALL = 162;
    public static final int YSTAR_ASYNC_RESULT = 163;
    public static final int DEBUGGER = 164;
    public static final int TEMPLATE_OBJECT = 165;
    public static final int NEW_REGEXP = 166;
    public static final int IMPORT_META = 167;
    public static final int DYNAMIC_IMPORT = 168; // (spec opts -- promise)
    public static final int DECLARE_GLOBALS = 169; // infoK n (fn1..fnn -- )
    public static final int DECLARE_EVAL = 170;
    public static final int ADD_DISPOSABLE = 171; // hint (cap v -- )
    public static final int DERIVED_RETURN = 172;
    public static final int RESOLVE_NAME = 173;
    public static final int GET_REF = 174;
    public static final int PUT_REF = 175;
    public static final int LOAD_IMPORT = 176;
    public static final int DISPOSE_NEW = 177; // ( -- cap)
    public static final int DISPOSE_SYNC = 178; // (cap kind value -- ) DisposeResources, throws if abrupt
    public static final int DISPOSE_BEGIN = 179; // (cap kind value -- disposal)
    public static final int DISPOSE_STEP = 180; // (disposal -- awaitValue more)
    public static final int DISPOSE_AWAITED = 181; // (result disposal mode -- )
    public static final int DISPOSE_END = 182; // (disposal -- ) throws if abrupt
    public static final int TAIL_CALL = 183; // argc (fn this args -- v): call in tail position (frame replaced for closures)
    public static final int DYNAMIC_IMPORT_PHASE = 184; // phaseK (spec opts -- promise): import.defer() / import.source()
    public static final int CLASS_DEF_NEW = 185;
    public static final int CLASS_ELEMENT = 186;
    public static final int CLASS_FINISH = 187;
    public static final int CLASS_DECORATE = 188;
    public static final int CLASS_STATIC_INIT = 189;
    public static final int NEW_OBJECT_LITERAL = 190; // site n (v1..vn -- obj)
    public static final int COUNT = 191;

    public static final String[] names = {"NOP", "PUSH_UNDEF", "PUSH_NULL", "PUSH_TRUE", "PUSH_FALSE", "PUSH_CONST", "PUSH_INT", "POP", "DUP", "DUP2", "DUP3", "SWAP", "ROT3", "ROT4", "LOAD_REG", "STORE_REG", "LOAD_REG_TDZ", "CHECK_REG_TDZ", "PUSH_SCOPE", "POP_SCOPE", "COPY_SCOPE", "GET_ENV", "SET_ENV", "PUSH_WITH", "LOAD_ENV", "STORE_ENV", "LOAD_ENV_TDZ", "CHECK_ENV_TDZ", "LOAD_NAME", "LOAD_NAME_TYPEOF", "LOAD_NAME_CALL", "STORE_NAME", "STORE_NAME_VAR", "DELETE_NAME", "INIT_NAME", "LOAD_GLOBAL", "LOAD_GLOBAL_TYPEOF", "STORE_GLOBAL", "INIT_GLOBAL_LEX", "LOAD_THIS", "LOAD_FUNCTION", "LOAD_NEW_TARGET", "LOAD_HOME", "CREATE_ARGUMENTS", "CREATE_REST", "LOAD_ARG", "INIT_THIS_REG", "INIT_THIS_ENV", "INIT_THIS_NAME", "THROW", "THROW_ERROR", "GET_PROP", "PUT_PROP", "GET_ELEM", "PUT_ELEM", "DELETE_PROP", "DELETE_ELEM", "TO_PROPERTY_KEY", "TO_KEY_FOR_BASE", "GET_SUPER", "PUT_SUPER", "GET_SUPER_BASE", "GET_PRIVATE", "PUT_PRIVATE", "HAS_PRIVATE", "ADD", "SUB", "MUL", "DIV", "MOD", "EXP", "SHL", "SAR", "SHR", "BAND", "BOR", "BXOR", "EQ", "NE", "SEQ", "SNE", "LT", "GT", "LE", "GE", "INSTANCEOF", "IN", "NEG", "TO_NUMBER", "NOT", "BNOT", "TYPEOF", "TO_NUMERIC", "INC", "DEC", "TO_STRING", "CONCAT", "TO_OBJECT", "REQUIRE_COERCIBLE", "CHECK_OBJECT", "JUMP", "JUMP_IF_TRUE", "JUMP_IF_FALSE", "JUMP_IF_NULLISH", "JUMP_IF_NOT_NULLISH", "JUMP_IF_UNDEFINED", "JUMP_IF_NOT_UNDEFINED", "JUMP_TABLE", "RETURN", "CALL", "CALL_SPREAD", "NEW", "NEW_SPREAD", "CALL_EVAL", "CALL_EVAL_SPREAD", "SUPER_CALL", "SUPER_CALL_SPREAD", "GET_PROTO_OF", "INIT_INSTANCE", "NEW_OBJECT", "NEW_ARRAY", "ARRAY_PUSH", "ARRAY_HOLE", "ARRAY_SPREAD", "DEFINE_FIELD", "DEFINE_FIELD_ELEM", "DEFINE_GETTER", "DEFINE_SETTER", "DEFINE_METHOD", "COPY_DATA_PROPS", "COPY_DATA_PROPS_EXCL", "SET_PROTO", "SET_FUNCTION_NAME", "MAKE_CLOSURE", "MAKE_METHOD", "MAKE_CLASS", "NEW_PRIVATE_NAME", "ADD_FIELD", "ADD_PRIVATE_METHOD", "STATIC_PRIVATE_METHOD", "RUN_FIELD", "GET_ITERATOR", "GET_ASYNC_ITERATOR", "ITER_STEP", "ITER_STEP_U", "ITER_REST", "ITER_CLOSE", "ITER_CLOSE_THROW", "ITER_NEXT_CALL", "ITER_RESULT_STEP", "ASYNC_ITER_CLOSE", "FOR_IN_START", "FOR_IN_NEXT", "GENERATOR_INIT", "YIELD", "YIELD_RAW", "RESUME_DISPATCH", "RESUME_MODE", "AWAIT", "AWAIT_IGNORE", "AWAIT_CATCH", "YSTAR", "YSTAR_ASYNC_CALL", "YSTAR_ASYNC_RESULT", "DEBUGGER", "TEMPLATE_OBJECT", "NEW_REGEXP", "IMPORT_META", "DYNAMIC_IMPORT", "DECLARE_GLOBALS", "DECLARE_EVAL", "ADD_DISPOSABLE", "DERIVED_RETURN", "RESOLVE_NAME", "GET_REF", "PUT_REF", "LOAD_IMPORT", "DISPOSE_NEW", "DISPOSE_SYNC", "DISPOSE_BEGIN", "DISPOSE_STEP", "DISPOSE_AWAITED", "DISPOSE_END", "TAIL_CALL", "DYNAMIC_IMPORT_PHASE", "CLASS_DEF_NEW", "CLASS_ELEMENT", "CLASS_FINISH", "CLASS_DECORATE", "CLASS_STATIC_INIT", "NEW_OBJECT_LITERAL"};

    public static final int[] operands = {0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 1, 0, 0, 0, 0, 0, 2, 2, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 2, 0, 0, 2, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, -1, 0, 1, 0, 1, 0, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 1, 2, 2, 1, 0, 1, 1, 0, 0, 0, 2, 1, 1, 1, 1, 1, 2, 3, 0, 2, 0, 0, 0, 1, 0, 0, 0, 1, 3, 3, 3, 0, 1, 1, 0, 0, 2, 2, 1, 0, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 2};

    public static final int[] effects = {0, 1, 1, 1, 1, 1, 1, -1, 1, 2, 3, 0, 0, 0, 1, -1, 1, 0, 0, 0, 0, 1, -1, -1, 1, -1, 1, 0, 1, 1, 2, -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, 0, 0, -1, -1, -2, 0, -1, 0, 0, -2, -3, 0, -1, -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, 0, -1, VAR, -2, VAR, -1, VAR, -2, VAR, -2, 0, -1, 1, 1, -1, 0, -1, -1, -2, -2, -2, -2, -1, VAR, -1, 0, 1, 1, VAR, 1, -3, -3, -3, -3, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, -1, VAR, VAR, -2, -1, 1, 0, -1, 1, 1, -3, -2, 1, -3, -1, VAR, -1, -1, -4, -1, -1, -1, VAR};

    private Op() {
    }

    public int length(int[] code, int pc) {
        int op = code[pc];
        if (op == JUMP_TABLE) return 3 + code[pc + 2] + 1;
        return 1 + operands[op];
    }

    public boolean isJump(int op) {
        return op == JUMP || op == JUMP_IF_TRUE || op == JUMP_IF_FALSE || op == JUMP_IF_NULLISH
                || op == JUMP_IF_NOT_NULLISH || op == JUMP_IF_UNDEFINED || op == JUMP_IF_NOT_UNDEFINED
                || op == ITER_STEP || op == ITER_RESULT_STEP || op == ASYNC_ITER_CLOSE || op == FOR_IN_NEXT
                || op == RESUME_DISPATCH || op == YSTAR || op == YSTAR_ASYNC_CALL || op == YSTAR_ASYNC_RESULT;
    }
}

