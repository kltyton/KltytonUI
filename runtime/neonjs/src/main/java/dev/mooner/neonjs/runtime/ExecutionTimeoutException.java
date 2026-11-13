package dev.mooner.neonjs.runtime;

/** Execution deadline termination; derived from NeonJS and adapted to Java. */
public final class ExecutionTimeoutException extends TerminationException {
    public ExecutionTimeoutException(String message) {
        super(message);
    }
}
