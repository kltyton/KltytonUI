package dev.mooner.neonjs.runtime;

/** JavaScript error constructors; derived from NeonJS and adapted to Java. */
public enum ErrorKind {
    ERROR("Error"), EVAL("EvalError"), RANGE("RangeError"), REFERENCE("ReferenceError"),
    SYNTAX("SyntaxError"), TYPE("TypeError"), URI("URIError"), AGGREGATE("AggregateError"), SUPPRESSED("SuppressedError");

    private final String jsName;

    ErrorKind(String jsName) {
        this.jsName = jsName;
    }

    public String getJsName() {
        return jsName;
    }
}
