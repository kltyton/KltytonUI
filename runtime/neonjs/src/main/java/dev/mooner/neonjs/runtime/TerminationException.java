package dev.mooner.neonjs.runtime;

/** Uncatchable JavaScript execution termination; derived from NeonJS and adapted to Java. */
public class TerminationException extends RuntimeException {
    public TerminationException(String message) {
        super(message);
    }
}
