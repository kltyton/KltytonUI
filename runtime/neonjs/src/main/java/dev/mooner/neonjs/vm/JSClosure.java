package dev.mooner.neonjs.vm;

import dev.mooner.neonjs.compiler.CodeBlock;
import dev.mooner.neonjs.runtime.Env;
import dev.mooner.neonjs.runtime.JSFunction;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Realm;
import dev.mooner.neonjs.runtime.Undefined;
import java.util.ArrayList;

/** Bytecode-backed function object; derived from NeonJS and adapted to Java. */
public final class JSClosure extends JSFunction {
    public final CodeBlock code;
    public final Env env;
    public JSObject homeObject;
    public Object lexicalThis = Undefined.INSTANCE;
    public ArrayList<FieldRecord> fields;
    public ArrayList<PrivateElement> privateMethods;
    public ArrayList<JSObject> extraInitializers;

    public JSClosure(Realm realm, CodeBlock code, Env environment, JSObject prototype) {
        super(realm, prototype);
        this.code = code;
        env = environment;
        special |= CALLABLE;
        if ((code.flags & CodeBlock.CONSTRUCTOR) != 0) special |= CONSTRUCTOR;
        if ((code.flags & CodeBlock.CLASS_CTOR) != 0) special |= CLASS_CONSTRUCTOR;
    }

    @Override
    public Object call(Object receiver, Object[] arguments) {
        return Interpreter.callClosure(this, receiver, arguments);
    }

    @Override
    public Object construct(Object[] arguments, JSObject newTarget) {
        return Interpreter.constructClosure(this, arguments, newTarget);
    }

    @Override
    public String sourceText() {
        String source = code.sourceText();
        return source == null ? "function " + debugName() + "() { [native code] }" : source;
    }
}
