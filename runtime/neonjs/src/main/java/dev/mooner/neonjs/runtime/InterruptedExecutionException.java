package dev.mooner.neonjs.runtime;

/** Requested execution interruption; derived from NeonJS and adapted to Java. */
public final class InterruptedExecutionException extends TerminationException {
    public InterruptedExecutionException(String message) {
        super(message);
    }
}
